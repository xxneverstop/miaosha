# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 构建与运行

```bash
# 构建（输出: target/miaosha-1.0-SNAPSHOT.jar）
mvn clean package -DskipTests

# 本地运行（先导出 .env；需要 MySQL、Redis、RocketMQ NameServer/Broker）
mvn spring-boot:run

# Docker：先打包并复制 JAR 为根目录 miaosha.jar，再启动应用（端口 18091）
bash scripts/start-app.sh

# Docker Compose：仅 App + MySQL，Redis / RocketMQ 需另外准备
docker compose up -d --build

# 停止分服务脚本创建的容器（Compose 使用 docker compose down）
bash scripts/stop-all.sh
```

应用运行在 **18091** 端口。完整演示入口是分服务脚本；Compose 不包含 Redis、RocketMQ、Nginx。Windows `package.bat` 使用 `JAVA_HOME` / `MAVEN_HOME` 或 `PATH`，生成 `target/deploy/`，部署包包含 `.env` 凭证，不提交该目录。

### 配置职责

- `src/main/resources/application.yml`：随 JAR 打包的基础配置及环境变量占位；Redis / Tomcat 配置类在源码 `config/` 中。
- 根 `application.yml`：Docker 额外加载的 Tomcat / 日志覆盖配置，不重复维护 DB / Redis / MQ 连接。
- `.env.example`：无真实凭证的模板；复制为被忽略的 `.env` 后填写实际地址与密码。
- Compose / 分服务脚本读取 `.env` 并向应用注入环境变量；分服务 App 默认用 Docker 服务名和容器端口。
- Spring Boot 不自动读取 `.env`。本机 Bash 需先 `set -a; . ./.env; set +a`；本机数据库 URL 使用 `SPRING_DATASOURCE_URL`，不要把 Compose 的 `mysql:3306` 当成本机地址。配置覆盖顺序与完整命令见 [README](README.md#快速开始)。

## 技术栈

- **Spring Boot** 2.0.5，**MyBatis** 1.3.1，**Druid** 1.1.3 连接池
- **Redis** + Spring Session + Jackson JSON 序列化（Joda DateTime 支持）
- **RocketMQ** 4.3.0——两个 Producer：`DefaultMQProducer`（异步消息）+ `TransactionMQProducer`（事务消息）
- **MySQL** + MyBatis Generator 生成的 Mapper
- **Guava** 18.0（RateLimiter、CacheBuilder），**Caffeine** 2.8.0（本地缓存）
- **Joda-Time** 2.9.1，**Hibernate Validator** 5.2.4

## 核心架构：秒杀下单链路

项目是一个**高并发秒杀系统**，所有关键设计决策围绕以下链路展开：

```
用户点击「下单」
  │
  ├─ GET  /order/generateverifycode   → 生成图形验证码，验证码字符串缓存到 Redis
  ├─ POST /order/generatetoken        → 可选验证码校验 → 检查售罄标记 → 校验活动状态 → 大闸计数 -1 → 发放 UUID 令牌（Redis，30 分钟 TTL）
  └─ POST /order/createorder          → RateLimiter 限流 → 校验令牌 → 售罄检查 → 线程池提交 → 事务 MQ → 创建订单
```

### 五层防护体系（按执行顺序）

| 防护层 | 所在接口 | 作用 | Redis Key |
|--------|----------|------|-----------|
| 验证码 | `/generatetoken` | 防脚本，4 位字母数字混合图片 | `verify_code_{uid}` |
| 令牌+大闸 | `/generatetoken` | 总量控制，最多发 `库存 × 5` 个令牌 | `promo_token_...`、`promo_door_count_{promoId}` |
| RateLimiter | `/createorder` | Guava 令牌桶，当前固定每实例 5 QPS | —（JVM 本地） |
| 令牌校验 | `/createorder` | 开启大闸校验时，活动请求须携带合法令牌 | `promo_token_{promoId}_userid_{uid}_itemid_{iid}` |
| 线程池有界队列 | `/createorder` | 并发度控制（20 线程 + 100 容量），满了 AbortPolicy 拒绝 | —（JVM 本地） |

基础配置 `miaosha.verify-code=false`，其他开关及边界见 README。队列开启时 HTTP 线程仍通过 `Future.get()` 同步等待；秒杀令牌当前不会被消费，不代表一人一单。

### 库存扣减：Redis Lua 原子脚本

`ItemServiceImpl.decreaseStock` 使用预编译的 Lua 脚本（`DECREASE_STOCK_LUA`）：

```lua
local stock = redis.call('GET', KEYS[1])       -- promo_item_stock_{id}
if not stock then return -1 end                 -- 未初始化
stock = tonumber(stock)
local amount = tonumber(ARGV[1])
if stock < amount then return -2 end            -- 库存不足
local newStock = stock - amount
redis.call('SET', KEYS[1], newStock)
if newStock == 0 then redis.call('SET', KEYS[2], 'true') end  -- 售罄标记
return newStock
```

与旧 INCR 方案的关键区别：**先判再扣**——库存不足返回 -2，不碰 Redis 库存值，正常情况不需要回补。脚本通过 `DefaultRedisScript` 预编译，Spring 自动管理 EVALSHA 缓存。

### 事务型 MQ + stock_log：库存同步及已知恢复边界

`MqProducer.transactionAsyncReduceStock()` 流程：

1. 发半消息到 `stock_topic`（消费者不可见）
2. `executeLocalTransaction` → `OrderServiceImpl.createOrder()`：
   - Redis Lua 扣库存
   - DB：INSERT 订单 + UPDATE 销量 + UPDATE stock_log（status 1→2）
   - 注册 `TransactionSynchronizationManager.afterCompletion`：如果事务回滚 → `increaseStock`（3 次重试）
3. 成功 → COMMIT → Consumer 收到消息 → `itemStockDOMapper.decreaseStock`（DB 库存同步）
4. 明确返回 ROLLBACK 的事务消息不会投递给 Consumer；不能把所有异常都视为已正确回滚
5. commit 前宕机 → `checkLocalTransaction` 回查 `stock_log.status`（2=成功 COMMIT，3=失败 ROLLBACK，1=UNKNOW 继续回查）

### MQ Consumer 幂等（关键）

`MqConsumer` 用 `stockLogId`（`initStockLog` 生成的 UUID）+ Redis `setIfAbsent` 做去重。RocketMQ 是 at-least-once 语义——rebalance、Broker 崩溃重启、处理超时都会触发重投。不加幂等 = 重复扣 DB 库存。

当前去重是 `SETNX → EXPIRE → DB UPDATE`，并非同一事务：置位后宕机或 DB 失败会漏处理，TTL 到期后重投可能重扣；Consumer 还未检查扣库影响行数。订单 `@Transactional` 未声明受检业务异常回滚规则，Redis 回补也无幂等记录和自动对账。不得把当前实现写成“无条件保证最终一致”或“消费恰好一次”。`stock_log.status=2` 仅代表本地订单成功，不代表异步库存已同步。

`stock-mode=mysql` 本身已扣 DB，后续同步 / 异步分支还有再次扣减风险；库存模式不是任意可组合。当前主演示路径为 `lua + mq-async-sync=true`。序列查询已用 `FOR UPDATE`，但私有同类调用的 `REQUIRES_NEW` 不生效，不应据此误判订单号必然重复。

`miaosha.mq-async-sync=false` 会跳过 Consumer 的 DB 库存扣减，并在订单本地事务中尝试同步扣 DB；Producer / Consumer 仍会初始化，所以该开关不移除 MQ 启动依赖。

### 所有 Redis Key 汇总

| Key Pattern | 用途 | 设置位置 |
|-------------|------|----------|
| `promo_item_stock_{id}` | 缓存库存数量 | `publishPromo`（初始化）、Lua 脚本（扣减）、`increaseStock`（回补） |
| `promo_item_stock_invalid_{id}` | 售罄标记 | Lua 脚本（库存归零时） |
| `promo_door_count_{promoId}` | 大闸计数器，初始 = 库存×5 | `publishPromo`（初始化）、`generateSecondKillToken`（递减） |
| `promo_token_{promoId}_userid_{uid}_itemid_{iid}` | 秒杀令牌（30 分钟 TTL） | `generateSecondKillToken` |
| `verify_code_{uid}` | 验证码（30 分钟 TTL） | `generateverifycode` |
| `stock_log_synced_{stockLogId}` | MQ Consumer 幂等锁（1 小时 TTL） | `MqConsumer` |
| `item_validate_{id}` | 商品缓存（10 分钟 TTL） | `getItemByIdInCache` |
| `item_{id}` | 商品详情 Redis 缓存（30 分钟 TTL），Caffeine 300 秒 | `ItemController.getItem` |

### 已修复的 Bug

1. **`increaseStock` Redis key 错误**：原来用 `item_validate_{id}`（与 `decreaseStock` 用的 `promo_item_stock_{id}` 不一致）→ 修复为 `promo_item_stock_{id}`
2. **`increaseStock` 回补时清除售罄标记**：回补后调用 `redisTemplate.delete("promo_item_stock_invalid_" + itemId)`
3. **线程池有界队列**：从 `Executors.newFixedThreadPool(20)`（`LinkedBlockingQueue` 无参构造 = 无界队列）→ 改为 `ThreadPoolExecutor(20, 20, ..., new LinkedBlockingQueue<>(100), AbortPolicy)`

## 关键文件

### 核心链路（任何改动前必须理解）

| 文件 | 职责 |
|------|------|
| `controller/OrderController.java` | `/generatetoken`、`/createorder`、`/generateverifycode`——RateLimiter 和线程池初始化 |
| `service/impl/OrderServiceImpl.java` | `@Transactional createOrder`——Redis 扣库存 → DB 写入订单/销量/stock_log → TransactionSync 回滚回调 |
| `service/impl/ItemServiceImpl.java` | Lua 库存脚本、`increaseStock` 回补、`initStockLog` 流水初始化 |
| `service/impl/PromoServiceImpl.java` | `generateSecondKillToken`（令牌+大闸）、`publishPromo`（库存预热+大闸初始化） |
| `mq/MqProducer.java` | 事务 + 异步 RocketMQ Producer，`TransactionListener` 实现 |
| `mq/MqConsumer.java` | 库存同步消费者，`setIfAbsent` 幂等 |

### 支撑文件

| 文件 | 职责 |
|------|------|
| `config/RedisConfig.java` | RedisTemplate 配置，Jackson + Joda 序列化，`@EnableRedisHttpSession` |
| `config/WebServerConfiguration.java` | Tomcat keep-alive 调优（30s 超时，10000 最大请求数） |
| `error/EmBusinessError.java` | 错误枚举，含 `RATELIMIT(30003)` |
| `util/CodeUtil.java` | 图形验证码生成器（90×20，4 位字母数字） |

## Docs 文档

文档统一从 [docs/README.md](docs/README.md) 进入；`my-docs/` 是待实施方案，`backup/` 是原学习和压测记录，`reference/` 是参考材料。历史参数与简历目标不能当作当前实现。

| 文档 | 内容 |
|------|------|
| `docs/backup/第九十章-流量削峰防刷限流代码改动详解.md` | 第九章/第十章逐文件代码差异清单 |
| `docs/backup/库存不一致问题排查与修复.md` | 历史 MQ Consumer 去重改动及其背景 |
| `docs/backup/Redis库存事务回退问题分析.md` | DB 回滚与 Redis 回补的历史分析 |
| `docs/backup/线程池有界队列改造.md` | 无界队列 → 有界队列 + AbortPolicy |
| `docs/backup/Redis-Lua原子库存扣减-方案与问题分析.md` | INCR → Lua 原子脚本迁移分析 |
| `docs/backup/简历与面试-秒杀系统项目.md` | 历史简历条目与面试问答 |

## JMeter 测试方法

验证数据一致性的标准测试流程：

- 线程数 500，Ramp-Up 10s，循环 1 次
- 先浏览器登录 → 拿到 token → 拿到秒杀令牌 → 填入 JMeter 参数
- 当前 `RateLimiter.create(5)` 限制单实例准入速率；HTTP 总吞吐不等于业务成功吞吐
- 使用 JSON 业务断言，记录本轮初始库存 / 订单 / 销量；等待消息收敛后对比 Redis、MySQL 扣减和成功订单购买数量增量
- 历史 142 → 1200 QPS 尚未在当前版本复现；结构整理不等于完成可靠性改造或压测验证
