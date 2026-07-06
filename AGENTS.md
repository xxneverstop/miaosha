# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## 构建与运行

```bash
# 构建（输出: target/miaosha-1.0-SNAPSHOT.jar）
mvn clean package -DskipTests

# 本地运行（需要 MySQL、Redis、RocketMQ NameServer/Broker）
mvn spring-boot:run

# Docker：构建镜像并启动应用容器（端口 18091）
bash scripts/start-app.sh

# Docker Compose：启动全部服务（MySQL + Redis + RocketMQ + Nginx + App）
docker-compose up -d

# 停止全部服务
bash scripts/stop-all.sh
```

应用运行在 **18091** 端口。Docker 启动脚本通过环境变量注入 DB/Redis/MQ 连接信息。本地 `application.yml` 硬编码连接 `182.92.116.131`（远程虚拟机）。

## 技术栈

- **Spring Boot** 2.0.5，**MyBatis** 1.3.1，**Druid** 1.1.3 连接池
- **Redis**（Jedis 连接池，max-active=50）+ Spring Session + Jackson JSON 序列化（Joda DateTime 支持）
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
  ├─ POST /order/generatetoken        → 校验验证码 → 检查售罄标记 → 校验活动状态 → 大闸计数 -1 → 发放 UUID 令牌（Redis，5 分钟 TTL）
  └─ POST /order/createorder          → RateLimiter 限流 → 校验令牌 → 售罄检查 → 线程池提交 → 事务 MQ → 创建订单
```

### 五层防护体系（按执行顺序）

| 防护层 | 所在接口 | 作用 | Redis Key |
|--------|----------|------|-----------|
| 验证码 | `/generatetoken` | 防脚本，4 位字母数字混合图片 | `verify_code_{uid}` |
| 令牌+大闸 | `/generatetoken` | 总量控制，最多发 `库存 × 5` 个令牌 | `promo_token_...`、`promo_door_count_{promoId}` |
| RateLimiter | `/createorder` | Guava 令牌桶，N QPS 限速 | —（JVM 本地） |
| 令牌校验 | `/createorder` | 防绕过——必须携带合法令牌 | `promo_token_{promoId}_userid_{uid}_itemid_{iid}` |
| 线程池有界队列 | `/createorder` | 并发度控制（20 线程 + 100 容量），满了 AbortPolicy 拒绝 | —（JVM 本地） |

### 库存扣减：Redis Lua 原子脚本

`ItemServiceImpl.decreaseStock` 使用预编译的 Lua 脚本（`DECREASE_STOCK_LUA`）：

```lua
local stock = redis.call('GET', KEYS[1])       -- promo_item_stock_{id}
if not stock then return -1 end                 -- 未初始化
stock = tonumber(stock)
if stock < amount then return -2 end            -- 库存不足
local newStock = stock - amount
redis.call('SET', KEYS[1], newStock)
if newStock == 0 then redis.call('SET', KEYS[2], 'true') end  -- 售罄标记
return newStock
```

与旧 INCR 方案的关键区别：**先判再扣**——库存不足返回 -2，不碰 Redis 库存值，正常情况不需要回补。脚本通过 `DefaultRedisScript` 预编译，Spring 自动管理 EVALSHA 缓存。

### 事务型 MQ + stock_log 流水表保证 Redis ↔ DB 最终一致

`MqProducer.transactionAsyncReduceStock()` 流程：

1. 发半消息到 `stock_topic`（消费者不可见）
2. `executeLocalTransaction` → `OrderServiceImpl.createOrder()`：
   - Redis Lua 扣库存
   - DB：INSERT 订单 + UPDATE 销量 + UPDATE stock_log（status 1→2）
   - 注册 `TransactionSynchronizationManager.afterCompletion`：如果事务回滚 → `increaseStock`（3 次重试）
3. 成功 → COMMIT → Consumer 收到消息 → `itemStockDOMapper.decreaseStock`（DB 库存同步）
4. 失败 → ROLLBACK → Consumer 永远收不到
5. commit 前宕机 → `checkLocalTransaction` 回查 `stock_log.status`（2=成功 COMMIT，3=失败 ROLLBACK，1=UNKNOW 继续回查）

### MQ Consumer 幂等（关键）

`MqConsumer` 用 `stockLogId`（`initStockLog` 生成的 UUID）+ Redis `setIfAbsent` 做去重。RocketMQ 是 at-least-once 语义——rebalance、Broker 崩溃重启、处理超时都会触发重投。不加幂等 = 重复扣 DB 库存。

### 所有 Redis Key 汇总

| Key Pattern | 用途 | 设置位置 |
|-------------|------|----------|
| `promo_item_stock_{id}` | 缓存库存数量 | `publishPromo`（初始化）、Lua 脚本（扣减）、`increaseStock`（回补） |
| `promo_item_stock_invalid_{id}` | 售罄标记 | Lua 脚本（库存归零时） |
| `promo_door_count_{promoId}` | 大闸计数器，初始 = 库存×5 | `publishPromo`（初始化）、`generateSecondKillToken`（递减） |
| `promo_token_{promoId}_userid_{uid}_itemid_{iid}` | 秒杀令牌（5 分钟 TTL） | `generateSecondKillToken` |
| `verify_code_{uid}` | 验证码（10 分钟 TTL） | `generateverifycode` |
| `stock_log_synced_{stockLogId}` | MQ Consumer 幂等锁（1 小时 TTL） | `MqConsumer` |
| `item_validate_{id}` | 商品缓存（10 分钟 TTL） | `getItemByIdInCache` |
| `item_{id}` | 商品详情缓存（10 分钟 TTL） | `ItemController.getItem` |

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

`docs/` 目录包含学习过程中编写的详细分析文档：

| 文档 | 内容 |
|------|------|
| `第九十章-流量削峰防刷限流代码改动详解.md` | 第九章/第十章逐文件代码差异清单 |
| `库存不一致问题排查与修复.md` | JMeter 测试发现 DB 多扣 → MQ Consumer 幂等修复 |
| `Redis库存事务回退问题分析.md` | DB 事务回滚 Redis 不回补 → TransactionSync 修复 |
| `线程池有界队列改造.md` | 无界队列 → 有界队列 + AbortPolicy |
| `Redis-Lua原子库存扣减-方案与问题分析.md` | INCR → Lua 原子脚本迁移分析 |
| `简历与面试-秒杀系统项目.md` | 简历条目 + 完整面试问答 |

## JMeter 测试方法

验证数据一致性的标准测试流程：

- 线程数 500，Ramp-Up 10s，循环 1 次
- 先浏览器登录 → 拿到 token → 拿到秒杀令牌 → 填入 JMeter 参数
- `RateLimiter.create(N)` 控制实际进入的并发量
- 测试后对比 Redis `GET promo_item_stock_{id}` 和 MySQL `SELECT stock FROM item_stock`——**两边扣减数必须一致**
