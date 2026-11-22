# Redis 库存无法随 DB 事务回滚的问题分析与解决

> 问题：DB 事务回滚后，Redis 已扣减的库存没有恢复

---

## 一、问题是否存在？

**存在。** 而且只要 DB 操作在 Redis 扣减之后失败，就会出现。

### 当前代码的步骤顺序

`OrderServiceImpl.createOrder`：

```java
@Transactional
public OrderModel createOrder(...) {
    // ① 校验（读 Redis + 读 DB，不写）

    // ② Redis 扣库存 —— 在 @Transactional 管控范围之外！
    boolean result = itemService.decreaseStock(itemId, amount);   // INCR -1
    if (!result) throw new BusinessException(EmBusinessError.STOCK_NOT_ENOUGH);

    // ③ 订单入库 —— 在 @Transactional 管控范围内
    orderDOMapper.insertSelective(orderDO);

    // ④ 增加销量 —— 在 @Transactional 管控范围内
    itemService.increaseSales(itemId, amount);

    // ⑤ 更新库存流水 status=2 —— 在 @Transactional 管控范围内
    stockLogDOMapper.updateByPrimaryKeySelective(stockLogDO);
}
```

`@Transactional` 只能回滚 ③④⑤。Redis 操作 ② 不在 Spring 事务管理范围内，**一旦执行就无法自动回滚**。

### 推导：什么情况下会触发？

```
时间线
──────
T1  ② Redis INCR -1  → 成功（stock: 330）
T2  ③ insertOrder    → 开始
T3  ④ increaseSales  → 失败！(例如 DB 连接断开、死锁、约束冲突)
T4  Spring 回滚 ③④⑤ → DB 恢复原状
T5  Redis 没有回滚    → stock 仍然是 330（永远少了 1）
```

任何能导致 ③④⑤ 中任一步失败的场景都会触发：DB 连接超时、死锁、磁盘满、唯一约束冲突、网络闪断……

### 连带问题：checkLocalTransaction 陷入死循环

当前 `MqProducer.checkLocalTransaction`：

```java
if (stockLogDO.getStatus() == 2) {
    return COMMIT_MESSAGE;      // 成功
} else if (stockLogDO.getStatus() == 1) {
    return UNKNOW;              // 还在处理中，继续回查
}
return ROLLBACK_MESSAGE;
```

如果 DB 事务回滚了，`stockLog.status` 的更新（从 1 到 2）也被回滚了，status 永远是 1。`checkLocalTransaction` 永远返回 UNKNOW，RocketMQ 最多回查 15 次，全部浪费，最终这条半消息被丢弃——但 Redis 库存早已扣掉且无法恢复。

### 和之前 Consumer 幂等问题的关系

两个问题方向相反，但都导致 Redis 与 DB 不一致：

| 问题 | 方向 | 根因 |
|------|------|------|
| Consumer 重复消费 | **DB 多扣** | MQ 重投 + Consumer 零幂等 |
| Redis 无法随事务回滚 | **Redis 多扣** | Redis 扣减在 `@Transactional` 之外 |

---

## 二、有哪些解决办法

### 方案 A：TransactionSynchronizationManager（推荐）

Spring 提供的「事务同步管理器」，在事务完成（提交或回滚）后触发回调。

```java
@Transactional
public OrderModel createOrder(...) {
    // ② Redis 扣库存
    boolean result = itemService.decreaseStock(itemId, amount);
    if (!result) throw ...;

    // ===== 新增：注册事务完成回调 =====
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronizationAdapter() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    // 事务回滚了 → Redis 库存回补
                    itemService.increaseStock(itemId, amount);
                }
            }
        });

    // ③④⑤ DB 操作...
}
```

| 优点 | 缺点 |
|------|------|
| Spring 原生，零额外依赖 | 如果进程在 ② 之后、回调注册之前崩溃，Redis 仍然丢失 |
| 与现有代码侵入小 | `afterCompletion` 里回补 Redis 失败无法重试（可以加日志告警） |
| 自动感知 commit/rollback | |

---

### 方案 B：在 executeLocalTransaction 的 catch 里回补

`MqProducer.executeLocalTransaction` 是目前已经存在的异常兜底位置：

```java
try {
    orderService.createOrder(...);
} catch (BusinessException e) {
    // 现有：更新 stockLog 状态为 3
    // 新增：回补 Redis 库存
    itemService.increaseStock(itemId, amount);
    return LocalTransactionState.ROLLBACK_MESSAGE;
}
```

| 优点 | 缺点 |
|------|------|
| 改动极小 | **只能捕获 BusinessException**，RuntimeException（NPE、DB 异常等）抓不到 |
| 与现有兜底逻辑在一起 | 如果 `createOrder` 内部已经 catch 了异常转为 BusinessException 则有效，否则漏 |

---

### 方案 C：先写 DB，成功后再扣 Redis

把 Redis 扣减移到 `@Transactional` 提交之后：

```java
@Transactional
public OrderModel createOrder(...) {
    // 校验...

    // ③④⑤ DB 操作...
    orderDOMapper.insertSelective(orderDO);
    itemService.increaseSales(itemId, amount);
    stockLogDOMapper.updateByPrimaryKeySelective(stockLogDO);

    // 事务提交后，在 MqProducer 里扣 Redis？不行——那时 DB 已经写完了
}
```

| 优点 | 缺点 |
|------|------|
| 不会出现 DB 回滚 Redis 没回滚 | **秒杀场景不可接受**：库存校验必须在扣减前，否则超卖 |
| | Redis 扣减失败时 DB 已提交，更麻烦 |

秒杀场景下库存扣减必须前置——先扣 Redis（快速过滤），再写 DB。这个方案**不适用**。

---

### 方案 D：定时对账任务

定时扫描 `stock_log` 表中 `status=1` 且创建时间超过阈值（如 5 分钟）的记录，这些记录对应「开始下单但没走完」的请求，将其对应的 Redis 库存回补。

```sql
SELECT stock_log_id, item_id, amount FROM stock_log
WHERE status = 1 AND create_time < NOW() - INTERVAL 5 MINUTE;
```

对每条结果：`redisTemplate.opsForValue().increment("promo_item_stock_" + itemId, amount)`

| 优点 | 缺点 |
|------|------|
| 最终一致性保证 | 不是实时的，有窗口期 |
| 不侵入主流程 | 需要额外的定时任务 |
| 能修复各种异常（包括进程崩溃） | 对账逻辑要小心幂等（不要重复回补） |

---

### 方案对比

| 维度 | A: TransactionSync | B: catch 回补 | C: 先DB后Redis | D: 定时对账 |
|------|:---:|:---:|:---:|:---:|
| 实时性 | ✅ 立即 | ✅ 立即 | ✅ 立即 | ❌ 延迟 |
| 覆盖 RuntimeException | ✅ | ❌ 只覆盖 BusinessException | ✅ | ✅ |
| 覆盖进程崩溃 | ❌ | ❌ | ❌ | ✅ |
| 对现有代码侵入 | 低 | 极低 | 高（颠覆流程） | 零（独立任务） |
| 秒杀场景适用 | ✅ | ✅ | ❌ | ✅ |
| 推荐程度 | ⭐⭐⭐ | ⭐ | ❌ | ⭐⭐ |

---

## 三、推荐方案：TransactionSynchronizationManager + 定时对账

**单独用一个方案都有盲区**，最佳实践是组合：

```
TransactionSynchronizationManager  → 覆盖 99.9% 的正常回滚场景（实时）
定时对账（扫描 stock_log status=1） → 兜底进程崩溃等极端场景（最终一致）
```

### 为什么不用方案 B（catch 回补）？

`executeLocalTransaction` 只 catch `BusinessException`。如果是 `RuntimeException`（DB 连接断开、NPE 等），异常直接穿透到 RocketMQ 框架层，Redis 回补永远不会执行。

### 为什么 TransactionSynchronizationManager 是最合适的？

1. **Spring 原生机制**——`@Transactional` 本身就是 Spring 管理的，用 Spring 提供的回调是与之最契合的方式
2. **自动感知 commit/rollback**——不需要在每个异常路径上手动加回补代码
3. **与事务型 MQ 流程兼容**——不干扰现有的 `executeLocalTransaction` → commit/rollback 流程。事务提交成功 → `afterCompletion(status=COMMITTED)` → 什么都不用做。事务回滚 → `afterCompletion(status=ROLLED_BACK)` → 回补 Redis

### 执行流程（修复后）

```
OrderServiceImpl.createOrder()
    │
    ├─ ② Redis decreaseStock (INCR -1)
    │
    ├─ 注册 TransactionSynchronization:
    │     afterCompletion(status):
    │       if ROLLED_BACK → Redis increaseStock (+1 回补)
    │
    ├─ ③ insertOrder
    ├─ ④ increaseSales
    └─ ⑤ update stockLog status=2
         │
         ├─ 全部成功 → commit
         │   └─ afterCompletion(COMMITTED) → 空操作
         │   └─ MqProducer 返回 COMMIT_MESSAGE → Consumer 扣 DB 库存
         │
         └─ 任一步失败 → rollback
             └─ afterCompletion(ROLLED_BACK) → Redis +1 回补 ✅
             └─ stockLog status 保持 1 → checkLocalTransaction 仍返回 UNKNOW
```

### 仍存在的盲区与兜底

| 盲区 | 发生概率 | 后果 | 兜底 |
|------|----------|------|------|
| 进程在 ② 和注册回调之间崩溃 | 极低 | Redis 已扣，DB 未写，回补代码未注册 | **定时对账**：扫描 status=1 超时记录，回补 Redis |
| `afterCompletion` 里回补 Redis 失败 | 极低 | Redis 连接恢复后数据仍然不一致 | 日志 + 告警 + 定时对账 |
| stockLog 没来得及 insert 就崩溃 | 极低 | 无 stockLog 记录 | 定时对账扫不到这条，但 DB 也没写，只有 Redis 少了一个——需用 Redis 的 MONITOR 或慢日志辅助 |

---

## 四、代码实现

`OrderServiceImpl.createOrder` 中，在 Redis 扣减成功后、DB 操作之前，加入：

```java
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;

// 在 decreaseStock 之后立即注册
TransactionSynchronizationManager.registerSynchronization(
    new TransactionSynchronizationAdapter() {
        @Override
        public void afterCompletion(int status) {
            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                // DB 事务回滚了 → Redis 库存回补
                itemService.increaseStock(itemId, amount);
            }
        }
    });
```

完整改动位置：`OrderServiceImpl.createOrder`，第 ② 步 `decreaseStock` 成功后（约第 89 行之后），第 ③ 步 `insertSelective` 之前。

---

## 五、验证方法

### 5.1 正常流程——不应回补

1. 重置 Redis 和 DB 库存均为 100
2. 浏览器下单一次
3. 预期：Redis = 99，DB 订单 +1，无回补日志

### 5.2 模拟 DB 失败——应回补

1. 在 `OrderServiceImpl.createOrder` 的 `insertSelective` 之前临时加一行：
   ```java
   throw new RuntimeException("模拟 DB 写入失败");
   ```
2. 浏览器下单一次
3. 预期：
   - 页面返回「下单失败」
   - Redis 库存恢复为 100（回补生效）
   - `afterCompletion(ROLLED_BACK)` 日志可见

### 5.3 验证不会超卖

1. 用 JMeter 并发下单，确保库存足够
2. 测试后 Redis 和 DB 库存扣减数应一致
3. 确认回补只发生在真正回滚的请求上（`stock_log.status=1` 超时记录不应增长）

---

## 六、常见疑问：会不会多回补？

### `decreaseStock` 内部已经有回补了，再加 `afterCompletion` 回补，不会重复吗？

**不会。** 两个回补的触发条件是互斥的，走的是两条完全不同的分支：

```
decreaseStock(itemId, amount)
    │
    ├─ result >= 0  → Redis 扣减成功，return true
    │                  └→ createOrder 继续执行
    │                       └→ 走到注册 TransactionSynchronization 的代码
    │                            └→ DB 失败 → 事务回滚 → afterCompletion 回补 ①
    │
    └─ result < 0   → 库存不够，内部执行 increaseStock 回补 ②
                       └→ return false
                            └→ createOrder 抛 STOCK_NOT_ENOUGH
                                 └→ 方法直接退出，不会走到注册 TransactionSynchronization
```

**关键点**：`TransactionSynchronization` 的注册代码写在 `decreaseStock` 返回 `true` **之后**。如果 `decreaseStock` 返回 `false`，代码在 `throw STOCK_NOT_ENOUGH` 就退出了，永远不会注册回调。所以 ① 和 ② 只会触发其中一个。

### 会不会把库存加多了（超过原始值）？

不会。每次回补都是 `INCR +amount`，而触发回补的那次扣减一定是 `INCR -amount`。加减次数一一对应，数量相等，方向相反。

---

## 六附、潜在问题：回补操作本身失败了怎么办？

你提出的这个问题非常关键——`afterCompletion` 里的 `increaseStock` 只是「发起回补」，不等于「回补一定成功」。回补这个动作本质是一次 Redis 网络调用，它可能因为 Redis 挂了、网络抖了、连接池满了而失败。

### 回补失败的分层看待

```
第一层思考：回补可能失败，那是不是有 bug？
第二层思考：分布式系统里，所有补偿操作都可能失败。不是消灭失败，而是让失败可控。
```

数据库事务之所以能「保证」回滚，是因为 binlog/redo log 是写到磁盘上的，写失败了可以重试，重试仍然失败数据库就不可用了（此时整个系统宕了，无所谓数据对不对）。

Redis 回补没有这个机制。但你也不需要它 100% 成功——你需要的是**失败能被发现，发现后能被修复**。

### 实际做法：三层兜底

```
① try-catch + 重试（秒级）
    increaseStock 失败 → 重试 3 次（间隔 100ms / 500ms / 1s）
    成功 → 结束了

② 日志告警（分钟级）
    3 次重试都失败 → 打 ERROR 日志，带上 itemId + amount + stockLogId
    监控系统看到这个日志 → 告警通知人

③ 定时对账（分钟级）
    人没看到告警也没关系
    定时任务扫描 stock_log status=1 超时记录 → 自动回补 Redis
    这是最后的兜底
```

**核心原则**：补偿可以失败，但不能「失败得静悄悄」。

### 代码大概长这样

```java
TransactionSynchronizationManager.registerSynchronization(
    new TransactionSynchronizationAdapter() {
        @Override
        public void afterCompletion(int status) {
            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                // 重试 3 次
                for (int i = 0; i < 3; i++) {
                    try {
                        itemService.increaseStock(itemId, amount);
                        return;   // 成功，退出
                    } catch (Exception e) {
                        if (i < 2) {
                            try { Thread.sleep(100L * (i + 1)); } catch (InterruptedException ignored) {}
                        } else {
                            // 3 次都失败 → 记日志，让定时对账兜底
                            log.error("Redis回补失败！itemId={}, amount={}, stockLogId={}",
                                      itemId, amount, stockLogId, e);
                        }
                    }
                }
            }
        });
```

### 为什么不应该无限重试？

1. **`afterCompletion` 阻塞当前线程**——如果 Redis 长时间不可用，无限重试会把线程池耗尽
2. **定时对账已经兜底了**——无限重试的成本由整个系统承担，而放到定时对账里只需要一个后台线程

### 这个视角补充到总结中

| 问题 | 态度 |
|------|------|
| 回补可能失败吗？ | 可能 |
| 能 100% 保证成功吗？ | 不能（分布式系统里不存在 100%） |
| 所以怎么办？ | 三层兜底：重试 → 告警 → 对账 |
| 这样够了吗？ | 够了，剩余风险是分钟级的数据不一致，秒杀业务可接受 |

---

## 七、面试级总结

### 问题是什么？

在秒杀下单链路中，Redis 库存扣减和数据库订单写入**不在同一个事务边界内**。Spring `@Transactional` 只能回滚数据库操作，Redis 的 `INCR -1` 一旦执行就无法自动回滚。当 DB 写入失败导致事务回滚时，Redis 库存已经扣掉了但没有恢复，造成**缓存多扣、数据库未扣**的不一致。

### 怎么发现的？

梳理 `OrderServiceImpl.createOrder` 的代码执行顺序时发现：第②步 Redis 扣库存在前，第③④⑤步 DB 操作在后，且只有 DB 操作被 `@Transactional` 包裹。进一步分析 `MqProducer.executeLocalTransaction` 的异常处理——只 catch `BusinessException`，`RuntimeException` 会直接穿透——确认存在「DB 回滚但 Redis 不恢复」的路径。

### 会导致什么后果？

1. **库存少卖**：Redis 里库存被"吃掉"了，实际还有货但用户买不到
2. **数据不一致扩散**：Redis 和 DB 的库存数长期对不上
3. **回查死循环**：`checkLocalTransaction` 发现 stockLog 永远是 status=1，不断返回 UNKNOWN，浪费 RocketMQ 回查次数

### 怎么定位的？

画出完整链路图，标注每一步在不在 `@Transactional` 范围内，然后逐一推演失败场景。核心定位方法：**事务边界分析**——把代码按「是否受 Spring 事务管理」分成两类，检查跨边界的操作是否有补偿机制。

### 有哪些解决方案？

| 方案 | 思路 | 覆盖范围 |
|------|------|:---:|
| A: `TransactionSynchronizationManager` | 注册事务完成回调，回滚时回补 Redis | 正常回滚 ✅ / 进程崩溃 ❌ |
| B: catch 块里手动回补 | 在 `executeLocalTransaction` 的 catch 里加 `increaseStock` | 只覆盖 BusinessException |
| C: 先 DB 后 Redis | 调换顺序 | 秒杀场景不适用 |
| D: 定时对账 | 扫描超时 status=1 的 stockLog，回补 Redis | 全部 ✅ / 但不实时 |

### 最终选择了什么？为什么？

**A + D 组合：TransactionSynchronizationManager（主） + 定时对账（兜底）**。

选择 A 的原因：
- Spring 原生机制，与 `@Transactional` 天然契合，零额外依赖
- `afterCompletion(status)` 自动感知 commit/rollback，不需要在每个异常路径上手写回补
- 与现有事务型 MQ 流程完全兼容，不干扰 `executeLocalTransaction` 的 commit/rollback 判断
- 只在 Redis 扣减成功后才注册回调，与 `decreaseStock` 内部的回补互斥，不会重复

选择 D 兜底的原因：
- A 无法覆盖进程在 Redis 扣减后、回调注册前崩溃的极端场景（概率极低但存在）
- 定时对账作为最终一致性保证，独立于主流程，不影响性能

### 解决效果？

- **正常回滚**：事务回滚后 Redis 库存立即恢复（毫秒级）
- **极端崩溃**：定时对账在分钟级修复不一致
- **不会多回补**：两个回补路径互斥，加减一一对应
- **维持秒杀性能**：不改变 Redis 先行的快速过滤路径

### 核心教训

1. **跨存储系统没有真正的分布式事务**——Redis + MySQL 之间没有 XA/Seata 协调，任何跨存储的写操作都必须配套补偿逻辑

2. **`@Transactional` 只保护 DB**——标注了 `@Transactional` 不代表方法里所有操作都受保护，要搞清楚每个资源的事务边界

3. **补偿逻辑要写在不漏的地方**——catch 块只抓声明类型异常，`TransactionSynchronizationManager.afterCompletion` 才覆盖所有回滚场景

4. **补偿本身也会失败**——分布式系统里不存在「100% 成功的补偿」。做法不是消灭失败，而是让失败可控：重试 → 告警 → 定时对账兜底。**失败不可怕，失败得静悄悄才可怕。**

5. **极端情况用异步任务兜底**——实时补偿机制不管多完善，进程崩溃都是盲区，最终一致性需要定时对账保证
