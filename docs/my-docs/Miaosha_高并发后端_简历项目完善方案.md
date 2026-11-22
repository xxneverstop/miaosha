# Miaosha：高并发交易可靠性与性能工程项目

> **性质**：基于现有 Java 秒杀系统的源码审计、改造设计与简历目标文档；**不是已经实施完毕的开发记录**。  
> **代码基线**：[`xxneverstop/miaosha`](https://github.com/xxneverstop/miaosha)，`master`：`f316113c7087870e01c4b17f66a55212fee765b1`；核对日期：2026-11-17。  
> **目标岗位**：Java/通用后端、高并发交易、分布式平台后端、AI 平台的通用后端岗位。  
> **定位**：不再追求“秒杀功能大全”，重点证明**性能工程、消息可靠性、幂等、异常恢复、数据一致性和可复现验证**。

## 0. 一页结论

**最重要的工作不是换 Go 或拆微服务，而是把秒杀主链路的真实故障窗口修好，并且用实验验证。**

```text
客户端 / 压测器
   │
   ▼
令牌/限流/售罄拦截 ──────→ 拒绝（业务状态可观测）
   │
   ▼
订单请求 / 库存流水 → RocketMQ 半消息 → 订单本地事务
                                         │
                        ┌────────────────┴─────────────┐
                        ▼                              ▼
               Redis Lua 库存预扣                MySQL 订单/流水
                        │                              │
                        └────────事务结果/回查─────────┘
                                         │ commit
                                         ▼
                           Consumer / DB 事务内去重
                                         │
                                         ▼
                              MySQL 库存最终同步
                                         │
                                         ▼
                          对账 / 失败分类 / 告警与修复
```

**三条最终主线**：① MySQL 热点锁→Redis Lua 的性能取舍；② RocketMQ 至少一次投递→DB 事务内幂等及补偿；③ 故障注入与可信压测。微服务/多实例属于加强项，不是前置目标。

## 1. 项目现状：已经有什么

| 能力 | 已实现位置 | 事实边界 |
|---|---|---|
| 电商商品、活动、下单分层 | `controller/OrderController.java`、`service/impl/OrderServiceImpl.java` | Spring Boot 单体应用连接多个基础设施，不是多业务微服务 |
| MySQL/Redis/ Lua 三种库存策略 | `service/impl/ItemServiceImpl.java` 的 `decreaseStock()` | 通过 `miaosha.stock-mode` 分支切换；需要验证不同模式不会重复扣减 |
| RocketMQ 事务消息 | `mq/MqProducer.java` | 有半消息、本地事务、Broker 回查，但失败及异常分支仍有缺口 |
| Redis 端 MQ 消费去重 | `mq/MqConsumer.java` | `setIfAbsent → expire → DB update` 并非同一事务，存在消息丢处理/重扣窗口 |
| Redis 回补 | `OrderServiceImpl.createOrder()` 注册 `afterCompletion`，`ItemServiceImpl.increaseStock()` | 只在真实 DB rollback 后回补；进程崩溃或 Redis 操作间断无法自动恢复 |
| 商品多级缓存 | `controller/ItemController.java`、`service/impl/CacheServiceImpl.java` | Caffeine + Redis + MySQL；无主动跨实例本地缓存失效闭环 |
| 限流、令牌、泄洪 | `OrderController.java`、`PromoServiceImpl.java` | 当前 `RateLimiter.create(5)`，`Future.get()` 使接口仍同步等待线程池结果 |
| Docker、历史压测与面经 | `README.md`、`docker-compose.yml`、`docs/秒杀系统压测结果报告.md`、`docs/reference/面经.md` | **历史 142→1200 QPS 尚未与当前源码配置复现，不可作为已验证当前性能** |

**最值得突出**：不是“会 Redis/Lua/MQ”，而是对一次订单跨 Redis、MySQL 和 MQ 时，哪些东西能证明一致、哪些故障窗口不受事务保护有明确认识。

## 2. 当前代码缺陷：按严重性排序

| 优先级 | 真实缺陷与文件 | 为什么会影响正确性 |
|---|---|---|
| **P0** | `mq/MqConsumer.java` 先 `setIfAbsent` 再设置 TTL、扣 DB，且未检查 `decreaseStock` 影响行数 | 置位成功后宕机，重投被跳过；TTL 到期后重投可再扣；0 行更新可能仍被 ACK 成功 |
| **P0** | `OrderServiceImpl.createOrder()` 的 `@Transactional` 未声明 `rollbackFor`，而 `BusinessException extends Exception` | Spring 默认不因受检异常回滚；MQ 可能 rollback 但 DB 订单/流水已提交，Redis 回补也不会按预期触发 |
| **P0** | `MqProducer.executeLocalTransaction()` 仅捕获 `BusinessException`，正常路径直接 `COMMIT_MESSAGE` | 未清晰区分 RuntimeException、DB 提交状态未知、半消息回查；需防止未经核验的误提交 |
| **P0** | Redis 回补的 `INCRBY` 与删除售罄标记分开，缺乏按 stockLogId 的补偿去重；源码无自动对账 | 重复回补、进程崩溃、回补失败均可能造成库存差额；日志中的“定时对账兜底”目前没有对应实现 |
| **P0** | `OrderController.init()` 固定 `RateLimiter.create(5)`，压测报告显示历史 1200 QPS | 现代码、压测脚本与历史指标不一致；高请求 Throughput ≠ 成功下单吞吐 |
| **P1** | `OrderController.createOrder()` 校验秒杀令牌但不消费；`PromoServiceImpl.generateSecondKillToken()` 可以反复扣减大闸 | 同用户可能复用令牌重复下单；大闸消耗与有效唯一请求不对应 |
| **P1** | `OrderServiceImpl.generateOrderNo()` 是 `private` 同类调用，却标 `REQUIRES_NEW`，读序列再写回 | Spring AOP 事务注解不生效；并发读改写序列可能生成重复订单号 |
| **P1** | 不同 `stock-mode` / `mq-async-sync` 组合有双扣风险 | `mysql` 分支已经扣 DB；随后 `!mqAsyncSync` 又执行 DB 扣减；对照实验必须定义合法组合 |
| **P1** | `README` 指明首次 clone 缺少被 `.gitignore` 忽略的配置类 | 项目不能直接复现；技术深度再高也不能跳过可运行性 |
| **P2** | 历史依赖较旧、管理接口鉴权薄弱、本地缓存跨实例不一致 | 影响工程可靠性，但不应在 P0 之前大范围升级框架 |

补充：`sql/miaosha.sql` 的 `order_info` 目前只有主键 `id`，没有一人一单的业务唯一约束；`stock_log` 只有 1 初始、2 成功、3 回滚状态，**没有单独的消费完成状态**。当前 `ItemStockDOMapper.xml` 的 `decreaseStock` SQL 已是 `WHERE stock >= amount` 的条件更新，这是可复用的基础。

## 3. 优化完成后的目标简历写法

> **目标版本；尚未实现的补偿、幂等、自动对账、真实压测不得以“已完成”表述。**【】必须在复现实验后填写，不能直接复制历史 1200 QPS。

**项目名称：Miaosha — 高并发交易可靠性与性能优化系统**  
**技术栈**：Java、Spring Boot、MySQL、Redis、Lua、RocketMQ、Caffeine、Docker、JMeter

1. **高并发交易性能优化**：针对热点商品的 MySQL 库存行锁竞争，设计 Redis 库存预热与 Lua 原子扣减，结合多级缓存、限流及有界队列治理流量；在同环境对照压测中，业务成功吞吐由【A】提升至【B】、P99 下降【X%】，并分析瓶颈转移与拒绝请求占比。
2. **消息可靠性与分布式一致性**：基于 RocketMQ 事务消息、订单流水与数据库事务内消费幂等处理库存异步同步，针对重复投递、事务回滚及跨 Redis/MySQL 的补偿窗口，建立幂等回补与库存对账流程。
3. **故障验证与系统观测**：构建服务宕机、MQ 重投、DB 不可用、重复下单等故障注入测试，验证订单/流水/库存不变量；记录成功率、P95/P99、消息积压、恢复耗时和未收敛异常，并保存可复现测试工件。
4. **分布式部署（仅做完可加）**：在多应用实例和独立 Consumer 部署下验证全局准入、缓存可见性与消息重试机制，分析实例扩容后吞吐、锁竞争和一致性的变化。

**最终简历推荐保留 1–3 条**；若多实例部分没有明显新增证据，不要写第四条。

## 4. 工程总方案：先固定交易语义

### 4.1 明确唯一“可宣称正确”的主路径

**建议生产式演示路径**：`stock-mode=lua`、`mq-async-sync=true`、单次下单 `amount=1`、消息含 `stockLogId`、一人一单按活动维度约束（若启用）。其他 MySQL/INCR 模式只用于隔离对照测试。

```text
PENDING (库存流水已创建)
      │
      ├─ 订单本地事务失败 → FAILED / 补偿待确认
      │
      └─ 订单本地事务提交 → ORDER_COMMITTED
                                   │
                            MQ 消费 DB 库存
                                   │
                                   ▼
                            STOCK_SYNCED
```

不要把 `stock_log.status=2`（订单本地事务成功）当作“DB 库存异步同步已完成”。建议新增 `sync_status` 或独立消费流水追踪该阶段。

### 4.2 重要边界：Redis 与 MySQL 没有共享本地事务

RocketMQ 事务消息能够决定“订单本地事务是否提交后再放行消息”，**不能使 Redis Lua 与 MySQL 变成一个 ACID 事务**。即使加了回滚回调，进程仍可能在 Redis 扣成功、DB 提交前突然死亡。最小方案需留下足够证据与安全恢复规则：

- Redis 预扣应有稳定的 `reservationId = stockLogId`，可查询、去重、回补；
- DB 有 `stock_log` / 订单状态；Consumer 用 DB 原子事务进行去重；
- 对账在明确没有在途订单 / 预留后才作结论；未知状态**报警并人工核查**，不能盲目将 Redis 库存改写为 DB 库存。

如果这些故障恢复前提未实现，只能说“降低不一致风险和提供修复机制”，不能无条件说“保证最终一致”。

## 5. 精确到代码的 P0 改造

以下文件路径的共同前缀是 `src/main/java/com/imooc/miaoshaproject/`，除非单独注明。**新类/新 SQL 为建议设计，不是现有源码；示例是接口级伪代码或关键 SQL，需按 MyBatis 参数名和 Spring 版本落地后编译测试。**

### P0-1. 先补可运行环境与自动化测试

**涉及**：`pom.xml`、`README.md`、`.gitignore`、`src/main/resources/application-example.yml`（新增）、`src/test/**`（新增）、`docker-compose.yml`。

任务：

1. 解决当前忽略配置文件/配置类导致的首次 clone 不完整问题；提交无密钥的配置模板，敏感变量使用环境变量。
2. 建立可固定版本的 Docker 环境：MySQL、Redis、RocketMQ、App；启动后健康检查，明确版本及初始化脚本。**不要在 P0 顺手全面升级 Spring Boot/MySQL。**
3. `pom.xml` 配置 JUnit / Spring Test；推荐逐步引入可控集成测试环境（Testcontainers 或固定 Compose），而非仅靠启动手工测试。
4. 单独维护测试数据重置脚本，避免执行现有带 `DROP TABLE` 的 `sql/miaosha.sql` 到非隔离数据库。

**验收**：新机器 clone → 使用模板配置 → 构建 → 容器启动 → 下单基础测试通过；无真实密钥进入 Git。

### P0-2. 修复数据库事务回滚语义

**文件**：`service/impl/OrderServiceImpl.java` 的 `createOrder()`、`error/BusinessException.java`、`mq/MqProducer.java`。

**最小改造**：

```java
// OrderServiceImpl.createOrder：明确受检异常也应导致 DB 回滚
@Transactional(rollbackFor = Exception.class)
public OrderModel createOrder(... ) throws BusinessException {
    ...
}
```

同时检查所有被 `@Transactional` 标记、却可能抛出 `BusinessException` 的写路径，避免只修一个函数。或者统一使业务异常继承 RuntimeException，但这是全局语义变更，不建议与局部改造混用。

**MqProducer.executeLocalTransaction()** 应以 DB 已提交状态作为 MQ COMMIT 的判据。不要用“方法调用返回了”来代替“DB 状态确认”：

```text
调用 createOrder() 成功 → 查询并验证订单/stock_log 已提交 → COMMIT
明确事务失败并能确认 DB 未提交 → ROLLBACK
数据库超时、状态不确定 → UNKNOWN，由 checkLocalTransaction 再查
```

注意 Broker 回查时 `status=1` 只能表示“暂时未知”；要配合时间阈值、告警和异常状态处理，不应无限期处于 UNKNOWN。还需要保证“失败状态的更新”不被同一个已经回滚的事务撤销。

**新增测试**： `src/test/java/com/imooc/miaoshaproject/service/OrderTransactionIT.java`（建议）：

- Redis 已扣减后，插订单失败：DB 的订单/销量/流水状态按设计回滚；Redis 补偿最终有可观察结果。
- `BusinessException` 与 RuntimeException 分支都验证 rollback。
- Broker 查询时已提交返回 COMMIT、明确未提交返回 ROLLBACK、未知返回 UNKNOWN。

### P0-3. 将 Consumer 幂等移动到 MySQL 同一事务

**原问题代码**：`mq/MqConsumer.java` 的 `consumeMessage()` 当前先写 `stock_log_synced_*` Redis key，再 `itemStockDOMapper.decreaseStock()`，最后返回 `CONSUME_SUCCESS`。

**新建表**（新 SQL 文件建议 `sql/migrations/001_stock_consume_log.sql`，不要直接覆盖历史 seed）：

```sql
CREATE TABLE stock_consume_log (
  stock_log_id VARCHAR(64) PRIMARY KEY,
  item_id INT NOT NULL,
  amount INT NOT NULL,
  consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
```

**新建**：

```text
service/StockConsumeService.java                   # 新增接口
service/impl/StockConsumeServiceImpl.java          # 新增事务边界
dao/StockConsumeLogMapper.java                     # 新增 Mapper
src/main/resources/mapping/StockConsumeLogMapper.xml
```

**数据库事务内处理流程**：

```java
// 方案级伪代码，需按 MyBatis / SQLException 类型实现
@Transactional(rollbackFor = Exception.class)
public void consumeOnce(String stockLogId, int itemId, int amount) {
    // 1. 校验 stock_log 确实属于已提交订单，itemId/amount 必须匹配
    // 2. INSERT stock_consume_log(stock_log_id, item_id, amount)
    //    若主键重复：核对旧记录内容一致后直接返回（已处理）
    // 3. rows = itemStockDOMapper.decreaseStock(itemId, amount)
    // 4. rows != 1 → 抛异常，回滚本地事务（包括去重记录）
    // 5. （可选）更新 stock_log.sync_status = SYNCED
}
```

事务与唯一主键保证：**扣 DB 库存和写“已消费”记录一起提交或一起回滚**。Redis 只可用于快速缓存，不可单独作为最终消费成功证据。

`MqConsumer.java` 改为调用一个**独立 Spring Bean** 的 `consumeOnce()`（避免同类调用使 `@Transactional` 不生效）：

```java
// 示意：实际需要保留 stockLogId 的解析及非法数据处理
for (MessageExt msg : msgs) {
    try {
        stockConsumeService.consumeOnce(stockLogId, itemId, amount);
    } catch (TransientDataAccessException e) {
        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
    } catch (Exception e) {
        // 记录错误 + 分类告警，无法立即确认安全时不要 ACK
        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
    }
}
return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
```

实际要补充**有上限的重试与死信/人工处理**；永久性错误如消息缺字段不应无限自旋。单次回调可能包含多条消息，前几条成功而后续失败时整批重投是正常的，靠上述唯一消费记录避免重复扣减。

**验收**：同一 `stockLogId` 投递 2、10、100 次只扣 DB 一次；在去重记录插入之后、库存 SQL 之前/之后模拟异常，DB 事务均无“标记已处理但库存未变”的状态；校验 `decreaseStock` 影响行数必须是 1。

### P0-4. Redis 预扣和回补的可追踪性

**现有**：`ItemServiceImpl.decreaseStock()`、`increaseStock()`；`OrderServiceImpl.createOrder()` 的 `afterCompletion`。

**目标**：回补操作必须按 `stockLogId` 去重，并将“是否已预扣”变成可查询证据。

建议将主链 Lua 的库存预扣扩展为：

```text
DECR 库存 + 写 reservation:<stockLogId> = {itemId, amount, status=reserved}
       （在同一个 Redis Lua 脚本里原子完成）
```

Redis Lua 的思路：

```lua
-- 设计示意，不可直接粘贴替换现有脚本
-- KEYS: stock, soldout, reservation
-- ARGV: amount, reservation-value, TTL
if redis.call('EXISTS', KEYS[3]) == 1 then return 1 end -- 重复请求：先核对 reservation 内容
local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
local amount = tonumber(ARGV[1])
if stock < amount then return -2 end
redis.call('DECRBY', KEYS[1], amount)
redis.call('SET', KEYS[3], ARGV[2], 'EX', tonumber(ARGV[3]))
if stock == amount then redis.call('SET', KEYS[2], 'true') end
return 0
```

**关键前提**：`stockLogId` 需要由 `OrderController.createOrder()` 在真正预扣前创建，并一路传给 `MqProducer` 和 `ItemServiceImpl`。预留记录不能比故障回查窗口更早过期；Redis 数据丢失时仍需依赖持久化与 DB 对账重建，不能因有 Redis marker 就宣称完全可靠。

**补偿 Lua** 的设计要求：检查预留存在且确实是待补偿状态 → **原子** `INCRBY` 库存、删除售罄标记、写 `compensated:<stockLogId>`，使重复回调不重复加库存。补偿完成后记录审计。**严禁把普通手动补货和失败回补使用同一个无需标识的 `increaseStock()`。**

**必须说明的边界**：Redis 键可能过期、持久化也有故障窗口，DB 与 Redis 仍不能完全原子提交。因此 P0 阶段采用“可追踪预留 + 幂等补偿 + 安全对账 + 告警”，无法证明状态时停止自动更正；若未来要更强一致性，应讨论 DB 预留记录/Outbox/Saga 的权威账本设计，而不是继续叠加 Lua。

**建议文件**：

```text
src/main/resources/lua/reserve_stock.lua            # 新增，主链库存预留
src/main/resources/lua/compensate_stock.lua         # 新增，幂等补偿
service/impl/ItemServiceImpl.java                   # 修改预扣与回补签名
service/impl/OrderServiceImpl.java                  # 回调传 stockLogId
src/test/java/com/imooc/miaoshaproject/service/StockReservationIT.java # 新增
```

**故障验收**：重复补偿只加库存一次；服务在 Lua 后、订单 DB commit 前崩溃，可检测预留并明确进入待核查/安全恢复流程；无订单却有预留、订单成功但消息未同步均可发现。对于无法安全裁决的预留，输出 `manual_review` 记录，不自动猜测。

### P0-5. 增加对账与异常处理（不是“盲目重置库存”）

**建议新增**：

```text
service/StockReconcileService.java
service/impl/StockReconcileServiceImpl.java
scheduler/StockReconcileJob.java
service/model/StockReconcileResult.java
src/test/java/com/imooc/miaoshaproject/service/StockReconcileIT.java
sql/migrations/002_stock_sync_state.sql     # 如需增加 sync_status
```

最小功能：按 `promoId + itemId` 聚合 `订单数量/amount`、`stock_log` 成功/失败/未知、`stock_consume_log` 消费完成数、Redis 预留状态和 DB 剩余库存。将状态分成**正常、在途、可自动补偿、未知需人工介入**。

**严格约束**：只有冻结测试活动入口、消费积压清空、确认无在途事务/预留后，才能使用以下不变量核验一次活动（不考虑人工补货和取消订单的简化模型）：

```text
初始库存 - Redis 当前库存
≈ 已提交订单购买数量 + 尚未完成/待取消的 Redis 预留数量

初始库存 - MySQL 当前库存
= 已完成库存同步的购买数量

所有在途事务收敛后：
初始库存 - Redis 库存 = 初始库存 - MySQL 库存 = 本轮成功订单购买数量之和
```

若涉及订单取消、补货、多活动共享库存，需要引入额外流水，以上简化公式不能直接用。对账可以先采用**只读扫描 + 告警**；自动修复须按状态、版本、幂等键建立独立安全策略，不能 `SET redis_stock = mysql_stock` 然后继续放行流量。

**验收**：能定位并解释 `Redis > DB`、`Redis < DB`、`stock_log=1` 长期滞留、数据库消费失败、消息积压等问题，并提供可复现的故障案例。

### P0-6. 消除不同库存模式的双扣/漏扣风险

**涉及**：`ItemServiceImpl.decreaseStock()`、`OrderServiceImpl.createOrder()`、`MqConsumer.java`。

当前 `stock-mode=mysql` 在 `decreaseStock` 中已经扣 DB；`mq-async-sync=false` 还会在订单尾部再次扣 DB。需要显式定义每种合法模式的库存归属及写入位置。

**建议验收矩阵**：

| 模式 | 入口库存扣减 | Consumer 库存扣减 | 目的 |
|---|---|---|---|
| `mysql + sync` | DB 一次 | 不扣 | 原始单库对照 |
| `redis-incr + sync` | Redis 一次 + DB 一次（不同资源） | 不扣 | 仅用于历史优化对照，需保留回补语义 |
| `lua + sync` | Redis 一次 + DB 一次 | 不扣 | 对比同步 DB 压力 |
| `lua + async` | Redis 一次 | DB 事务内消费一次 | **目标主路径** |

把配置组合显式限制；不支持的组合启动时 fail-fast，而不是静默双扣。每个模式测试库存初始/结束差异相等，保证因果对照。

## 6. P1 完善：业务幂等、性能与部署

### P1-A. 一人一单 + 令牌消费

**涉及**：`PromoServiceImpl.generateSecondKillToken()`、`OrderController.createOrder()`、`OrderServiceImpl.createOrder()`、`sql/migrations/**`。

- Redis 入站：用 Lua 原子校验并消费 `promoToken`，重复请求返回已有请求状态，而不是再扣库存。
- DB 最终防线：建议新增 `promo_user_claim(promo_id, user_id, order_id)`，主键 `(promo_id,user_id)`，与创建订单放在同一个 DB 事务。**不要直接给 `order_info(promo_id,user_id)` 加唯一索引**：现有 `promo_id=0` 的普通订单及历史重复数据会引起迁移和语义问题。
- 定义“同一请求重试”与“同用户第二次购买”的不同返回：前者查询原请求结果，后者业务拒绝；不能都只返回“库存不足”。
- 验证多实例并发请求，DB 只存在一笔相应活动订单，并能正确处理失败后重试。

### P1-B. 订单号生成正确性

**涉及**：`OrderServiceImpl.generateOrderNo()`、`SequenceDOMapper.xml`。

当前 `private @Transactional(REQUIRES_NEW)` 同类调用不通过 Spring 代理，因此不要说订单号有真正新事务保护。并且“读取 sequence 当前值 → 回写 +1”存在并发竞争。

改造建议优先使用数据库原子发号或 Redis `INCR` + 日期分段，记录唯一性策略；分库分表尾号当前写死 `00`，不要宣称已实现分库分表。

验收：多线程/多实例生成 10 万个 ID，唯一且可追踪；包括重启和跨日边界。

### P1-C. 压测实验重新设计（简历高优先级）

**现有**：`docs/秒杀系统压测方案.md`、`docs/秒杀系统压测结果报告.md`；**目标新增**：

```text
benchmarks/jmeter/                       # .jmx, 测试数据及说明
benchmarks/scripts/reset-test-data.sh   # 仅针对隔离测试库
benchmarks/scripts/check-invariants.sql
benchmarks/results/<timestamp>/
    environment.md                       # 机器、CPU、JVM、网络、版本
    config-snapshot.yml                  # 去敏后的配置
    *.jtl                                # JMeter 原始结果
    server-metrics.csv                   # DB/Redis/MQ/GC/CPU/带宽等
    business-summary.json                # 业务状态码、成功/失败/拒绝
    report.md                            # 分析、瓶颈、正确性
```

**至少做两组互不混淆的压测**：

1. **容量测试**：准备足够库存、足够独立用户、明确调整 RateLimiter；比较同样约束下 MySQL、Redis INCR、Lua 三种方案。业务成功吞吐 = 成功完成业务的下单数 / 测试区间，不能拿接口总响应 QPS 替代。
2. **热点售罄/过载测试**：少库存、大量无效或重复请求，测入口拒绝率、P99、资源占用、库存不变量；高总请求 QPS 可以是“拒绝吞吐”，必须明确标注。

**必须记录**：`HTTP Throughput`、`业务成功数/成功吞吐`、`库存不足`、`限流`、`超时/系统失败`、P50/P95/P99、DB 行锁等待、连接池、Redis QPS、MQ Lag、恢复耗时。对于业务异常仍返回 HTTP 200 的接口，JMeter 必须检查 JSON 的 `status / data.errCode`，而不是只用 HTTP 状态。

**特别注意**：现有 5 QPS 限流 + 少库存环境，不能复现历史报告的“成功下单 1200 QPS”。如果无法复现历史绝对值，诚实报告新的数据和适用条件；高 QPS 却多扣/漏扣库存的结果应判不合格。

### P1-D. 多实例/Worker 验证，而不是为了微服务而微服务

**建议**：先将 Consumer 变成可独立部署的进程或启动配置；两个应用实例共享 Redis/MySQL/MQ，由 Nginx 轮询。复用现有 Java 代码而不是完整拆分用户、商品、订单、网关、注册中心。

**具体测试**：

- 每实例 Guava RateLimiter 5 QPS 不等于全局 5 QPS；是否需要 Redis 全局限流？
- Caffeine 的过期快照在不同实例是否一致；是否需要按业务配置失效广播？
- Consumer 多实例抢占/重平衡时消息是否会重复投递、业务是否仍只扣库存一次？
- 实例在请求处理中死亡，令牌、预留、消息回查和对账如何收敛？

能解释这些问题，就已具备真正的分布式系统面试素材；不必先加 Nacos、Gateway、K8s。

### P2. 安全与工程升级（有余力才做）

历史依赖、`sun.misc.BASE64Encoder`、MD5 密码、管理接口鉴权、无界潜在队列、敏感配置保护可按风险优先级处理。安全问题不能用“学习项目”完全忽略，但不建议与一致性 P0 同时做大规模版本升级，避免难以判断故障根因。

## 7. 故障注入计划（每条必须有断言）

**建议新增**：`src/test/java/.../mq/StockConsumeIT.java`、`service/OrderTransactionIT.java`、`service/StockReservationIT.java`、`service/StockReconcileIT.java`；`benchmarks/faults/` 放环境脚本和运行日志。

| 场景 | 注入点 | 必须验证的事实 |
|---|---|---|
| MQ 重投 10 次 | Consumer 相同 `stockLogId` 重入 | DB 库存只扣一次，重复可成功 ACK |
| 去重写入后 DB 异常 | `consumeOnce` 事务中途 | DB 去重记录与库存更新一起回滚，Broker 重试后可正常完成 |
| DB 库存不足 | `decreaseStock` 更新 0 行 | 不确认成功消费，错误明确记录，不能产生假成功 |
| 订单事务业务异常 | `createOrder` 在订单写入后抛 `BusinessException` | DB 回滚，MQ 状态正确，Redis 补偿按状态处理 |
| Redis 预扣后进程崩溃 | Lua 成功后、DB 事务提交前 | 预留可识别；未知状态被报警/进入安全恢复，而非默默少库存 |
| 重复补偿 | 同 `stockLogId` 连续回补 | Redis 只加回一次，不出现超额回补 |
| RocketMQ/DB 短暂不可用 | Broker 或 MySQL 重启 | 重试、积压和恢复可观测；收敛后对账成立 |
| 双实例同用户重试 | 两个 App 同时处理 | 一人一单 DB 唯一事实成立（P1） |

**每条故障测试应保存**：注入开关、具体时间点、请求 ID/stockLogId、异常日志、Redis/DB 前后快照、MQ 消费状态、断言结果及恢复耗时。

## 8. 面试导向：只沉淀 3 条核心故事链

### 故事 1：Redis 预扣快，但为什么没有天然一致性？（主线）

- **问题**：热点行锁竞争，Redis 减少 DB 争用，但 Redis 与 MySQL 并非同一事务。
- **首次方案**：Lua 预扣 + RocketMQ 事务消息 + 库存流水。
- **发现缺陷**：`@Transactional` 对受检异常默认不回滚，消费者 Redis SETNX 与 DB 扣库存分离，容易出现“已标记处理、实际没处理”。
- **改进**：事务回滚语义修复、DB 事务内消费幂等、预留与补偿、对账。
- **验证**：消息重投、进程中断、DB 异常，检查**原始数据和库存不变量**。
- **深挖**：RocketMQ 事务消息能保证什么？为什么还需要消费端幂等？Redis 丢数据怎么办？如何处理状态未知？

### 故事 2：为什么引入 Redis Lua，以及如何证明优化有效？

- **问题**：MySQL `UPDATE item_stock SET stock=stock-? WHERE stock>=?` 在单热点商品上争抢同一行锁。
- **方案**：比较 MySQL、Redis INCR、Lua；Lua 将判库存、扣减和售罄标记放进一次原子脚本。
- **实验**：相同机器、流量、库存、用户分布；区分业务成功吞吐/拒绝吞吐，记录 P99、DB 锁等待、Redis CPU 和网络。
- **边界**：Lua 是 Redis 内部原子，不代表跨 Redis/MySQL 原子；库存售罄时高 HTTP QPS 未必有业务含义。
- **深挖**：瓶颈转移到哪里？为何不优先用分布式锁？有多商品热点时怎样扩展？

### 故事 3：为什么要有限流和有界队列？

- **问题**：系统在请求尖峰时可能通过无限等待/积压使延迟失控。
- **方案**：令牌/限流/售罄快速失败/有界线程池分级拒绝。
- **取舍**：当前 `Future.get()` 依然阻塞 HTTP，请准确称为“限并发/削峰”，**不要宣称 HTTP 请求已经全部异步**。
- **实验**：无过载/过载、单实例/双实例、不同队列容量；比较成功吞吐、拒绝率、P99 和资源上限。
- **深挖**：线程池饱和时如何拒绝？多实例限流如何统一？什么时候排队比直接拒绝更差？

## 9. 开发顺序与验收门槛

| 顺序 | 具体交付 | 必须通过的门槛 |
|---|---|---|
| 0 基线 | 启动脚本、无密钥配置、快照、基础测试 | 新环境能一键启动和跑通主链 |
| 1 事务与消费 | `rollbackFor`、Producer 状态判定、`stock_consume_log` + 新 Consumer Service | 重投只扣一次；所有异常事务状态正确 |
| 2 预留/补偿/对账 | Redis reservation、幂等回补、只读对账+告警 | 未知状态不被误恢复；补偿不会多加；异常可查 |
| 3 对照压测 | `.jmx`、`.jtl`、DB/Redis/MQ 指标、库存不变量 | 当前版本可复现；数字严格区分请求与业务成功 |
| 4 业务完备（P1） | 一人一单、令牌消费、订单号原子发号 | 并发重试不重复下单；订单号不冲突 |
| 5 分布式加强（P1） | 两实例 + 独立 Consumer | 验证全局限流、重复投递、恢复收敛 |
| 6 面试材料 | 三个故事、源码定位、测试截图/日志、复盘 | 60 秒、3 分钟、10 分钟均能讲清并现场回答细节 |

**P0 终止条件**：可靠交易路径 + 合理对账 + 当前版本可信压测 + 故障测试。达到后就可以用于简历；P1 的多实例/微服务不是必须。

## 10. 学习内容：只围绕代码中的问题

- Java/Spring：AOP 代理与同类调用、事务传播、受检异常回滚、线程池/拒绝策略、JUC、Spring Data Redis、MyBatis 事务边界。
- MySQL：InnoDB 行锁、唯一约束、事务隔离、条件更新、死锁、索引及故障恢复。
- Redis：Lua 原子性、SETNX 与 TTL、AOF/数据丢失、缓存过期与一致性、分布式限流。
- MQ/分布式：RocketMQ 半消息与回查、至少一次投递、消费端幂等、重试、死信、状态机、预留/补偿、对账。
- 性能：JMeter 业务断言、成功吞吐与拒绝吞吐、P95/P99、资源瓶颈、控制变量和可复现性。

## 11. 最终简历写法审计清单

- [ ] 不把“有 RocketMQ 事务消息”说成“Redis/MySQL 强一致”。
- [ ] 不把“消息不会丢”或“消费者恰好执行一次”作为无条件承诺。
- [ ] 不把历史 142→1200 QPS 当作当前代码已复测结果。
- [ ] 不用 HTTP 200 判断下单成功；看业务状态与订单/库存事实。
- [ ] 故障注入、自动对账、消费表、一人一单没有实现前不写“已完成”。
- [ ] 任何压测数字能提供 `.jmx/.jtl`、环境快照、SQL/Redis 核对结果。
- [ ] 能回答为何仍是单体、什么时候值得拆 Worker、为什么暂不拆微服务。
- [ ] 说明在某些 Redis/DB 跨资源崩溃窗口里仍需人工核查，不造“完美最终一致”故事。

## 12. 仓库源码入口

- [README / 现有边界](https://github.com/xxneverstop/miaosha/blob/master/README.md)
- [订单事务 OrderServiceImpl](https://github.com/xxneverstop/miaosha/blob/master/src/main/java/com/imooc/miaoshaproject/service/impl/OrderServiceImpl.java)
- [MQ Producer](https://github.com/xxneverstop/miaosha/blob/master/src/main/java/com/imooc/miaoshaproject/mq/MqProducer.java)
- [MQ Consumer](https://github.com/xxneverstop/miaosha/blob/master/src/main/java/com/imooc/miaoshaproject/mq/MqConsumer.java)
- [ItemServiceImpl](https://github.com/xxneverstop/miaosha/blob/master/src/main/java/com/imooc/miaoshaproject/service/impl/ItemServiceImpl.java)
- [OrderController](https://github.com/xxneverstop/miaosha/blob/master/src/main/java/com/imooc/miaoshaproject/controller/OrderController.java)
- [Stock SQL](https://github.com/xxneverstop/miaosha/blob/master/src/main/resources/mapping/ItemStockDOMapper.xml)
- [数据库种子](https://github.com/xxneverstop/miaosha/blob/master/sql/miaosha.sql)
- [历史压测报告](https://github.com/xxneverstop/miaosha/blob/master/docs/秒杀系统压测结果报告.md)
- [项目面经](https://github.com/xxneverstop/miaosha/blob/master/docs/reference/面经.md)

**文档结束。** 本文件是改造蓝图，必须以各阶段的源码和实验结果更新简历。
