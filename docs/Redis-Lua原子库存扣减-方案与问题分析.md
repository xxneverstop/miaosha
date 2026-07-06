# Redis Lua 原子库存扣减 — 方案与问题分析

---

## 目录

1. [背景：INCR 方案有什么问题](#一背景incr-方案有什么问题)
2. [方案：引入 Lua 原子脚本](#二方案引入-lua-原子脚本)
3. [代码改动清单](#三代码改动清单)
4. [仍然存在的问题：Redis 与 MySQL 的事务边界](#四仍然存在的问题redis-与-mysql-的事务边界)
5. [高优先级改进方案](#五高优先级改进方案)
6. [其他潜在问题与简要方案](#六其他潜在问题与简要方案)
7. [总结](#七总结)

---

## 一、背景：INCR 方案有什么问题

### 1.1 当前实现

```java
// ItemServiceImpl.decreaseStock — 改造前
long result = redisTemplate.opsForValue()
        .increment("promo_item_stock_" + itemId, amount.intValue() * -1);

if (result > 0) {
    return true;   // 扣减成功
} else if (result == 0) {
    // 分开的两步：INCR 和 SET 之间不原子
    redisTemplate.opsForValue().set("promo_item_stock_invalid_" + itemId, "true");
    return true;
} else {
    // 扣成负数了，回补
    increaseStock(itemId, amount);
    return false;
}
```

### 1.2 两个具体问题

**问题一：INCR 和 SET 售罄标记之间存在竞态窗口**

`INCRBY` 命令本身是原子的，但返回 0 之后的 `SET` 是第二次 Redis 调用。虽然 Redis 单线程，但两个命令之间可以被另一个客户端的命令插入：

```
时间轴 →

请求A: INCRBY stock -1 → 返回 0
        ← [窗口] 请求B: INCRBY stock -1 → 返回 -1   ❌ 请求B超抢了
请求A: SET invalid "true"

结果：标记打上了，但请求B已经 INCR 成了 -1（超卖了一件），
      然后就会走到 else 分支回补。
```

反过来也可能：

```
请求A: INCRBY stock -1 → 返回 0
请求B: INCRBY stock -1 → 返回 -1
        ← 请求B 先走到 else，回补 INCRBY +1 → stock=0
        ← 然后请求B返回 false（下单失败）
请求A: SET invalid "true"

结果：标记打上了，但请求B回补后库存恢复为 0，
      标记误杀后续请求（实际可能还有库存，但被标记拦截）。
```

更严重的一种：

```
库存=1

请求A: INCRBY -1 → 返回 0
请求B: INCRBY -1 → 返回 -1
请求B: else 分支 → increaseStock +1 → stock=0
请求A: SET invalid "true"      ← 标记打了
请求C: INCRBY -1 → 返回 -1     ← 又有库存了？因为 increaseStock
请求C: increaseStock +1 → stock=0

结果：标记存在但库存反复在 0 和 -1 之间振荡，一直有请求超抢然后回补。
```

这些窗口非常小（微秒级），但在秒杀场景的极端并发下**一定会发生**。

**问题二：扣减模型是"先扣再判"**

`INCRBY` 不看当前值就直接减，这是一种"先开枪再问是谁"的模式。扣成负数之后才知道不够，然后用 `increaseStock` 回补。回补本身又是一次 Redis 调用，而且回补后有可能又把库存从 0 加到正数（见问题一的最后一个场景），导致售罄标记失效。

### 1.3 思考过程：要不要加分布式锁？

直觉反应：两个操作不是原子的，加个锁不就行了？

**分析**：

- 分布式锁（Redisson / SET NX）需要至少 2 次 Redis 调用（加锁 + 解锁），加上原来的 INCR + SET，总共 4 次调用
- 锁的持有时间虽然极短（<1ms），但 5 万并发下锁竞争会急剧放大延迟
- 每个请求排队等锁 → 原本 1ms 能完成的操作变成 10-50ms → TPS 断崖式下跌
- 而且锁只解决了 Redis 层面的原子性，对后面要讨论的"Redis 和 MySQL 之间的事务边界"毫无帮助

**结论：不加分布式锁。用 Lua 脚本——把多步操作打包成一步，交给 Redis 服务端原子执行。**

---

## 二、方案：引入 Lua 原子脚本

### 2.1 核心思路

把"读取库存 → 判断是否充足 → 扣减 → 打售罄标记"这四步写成一个 Lua 脚本，用 `EVAL` 一次性提交给 Redis。Redis 单线程执行整段脚本，期间不会有任何其他命令插入。

同时把扣减模型从"先扣再判"改为"**先判再扣**"：先 `GET` 看库存够不够，够才 `SET` 扣减，不够直接返回 -2，不需要回补。

### 2.2 Lua 脚本

```lua
-- KEYS[1] = promo_item_stock_{itemId}
-- KEYS[2] = promo_item_stock_invalid_{itemId}
-- ARGV[1] = amount (购买数量)
--
-- 返回值:
--   -1  库存 key 不存在（还没 publishPromo）
--   -2  库存不足
--   >=0 扣减成功，值为剩余库存

local stock = redis.call('GET', KEYS[1])
if not stock then
    return -1
end

stock = tonumber(stock)
local amount = tonumber(ARGV[1])

if stock < amount then
    return -2          -- 不够就不扣，不需要回补
end

local newStock = stock - amount
redis.call('SET', KEYS[1], newStock)

if newStock == 0 then
    redis.call('SET', KEYS[2], 'true')   -- 扣到 0 同时打标记，绝无缝隙
end

return newStock
```

### 2.3 对比：改前 vs 改后

| 维度 | INCR（改前） | Lua（改后） |
|------|-------------|------------|
| 扣减模型 | 先扣再判，扣多了回补 | 先判再扣，不够直接拒 |
| 原子范围 | 只有 INCR 本身，和 SET 分离 | 整段脚本，GET→判断→SET→标记 |
| Redis 调用次数 | 1-3 次（正常 1 / 售罄 2 / 超卖回补 3） | 始终 1 次 |
| 回补逻辑 | 需要 `increaseStock` 回补 | 正常流程不需要（事务回滚保留） |
| 售罄标记窗口 | 有（INCR 和 SET 之间） | 无 |
| 单次延迟 | 1-3 次网络 RTT | 1 次网络 RTT |

### 2.4 思考过程：为什么用 Lua 而不是 Redis 事务（MULTI/EXEC）？

Redis 的 `MULTI/EXEC` 也能打包多个命令顺序执行，但它不支持**条件判断**：

```
MULTI
GET stock           ← 读到值，但无法在事务中判断它
SET stock newValue  ← 无法根据 GET 的结果决定是否执行
SET invalid "true"  ← 无法根据 GET 的结果决定是否执行
EXEC
```

`MULTI/EXEC` 里所有命令都会执行，没办法写 `if stock < amount then return` 这种逻辑。要在 Redis 里做条件判断，只能上 Lua。

### 2.5 思考过程：Lua 阻塞 Redis 怎么办？

Lua 脚本执行期间 Redis 不能处理其他命令，这是事实。但这不构成问题，因为：

1. 这段脚本只有 GET + 判断 + SET，没有循环，执行时间 <0.1ms，跟一次 INCR 差不多
2. Redis 本身就是单线程，所有命令都要排队。Lua 只是把原来 2-3 次排队合并成 1 次——反而**减少了**排队次数
3. 高并发下，Lua 的实际延迟比 2-3 次独立调用更短（少了网络往返）

---

## 三、代码改动清单

### 3.1 `ItemServiceImpl.java` — 核心改动

**新增字段：**

```java
import org.springframework.data.redis.core.script.DefaultRedisScript;
import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;

// Lua 脚本常量
private static final String DECREASE_STOCK_LUA =
    "local stock = redis.call('GET', KEYS[1])\n" +
    "if not stock then return -1 end\n" +
    "stock = tonumber(stock)\n" +
    "local amount = tonumber(ARGV[1])\n" +
    "if stock < amount then return -2 end\n" +
    "local newStock = stock - amount\n" +
    "redis.call('SET', KEYS[1], newStock)\n" +
    "if newStock == 0 then redis.call('SET', KEYS[2], 'true') end\n" +
    "return newStock";

// 预编译脚本（Spring 自动做 EVAL → EVALSHA 的优化）
private DefaultRedisScript<Long> decreaseStockScript;

@PostConstruct
public void initScript() {
    decreaseStockScript = new DefaultRedisScript<>();
    decreaseStockScript.setScriptText(DECREASE_STOCK_LUA);
    decreaseStockScript.setResultType(Long.class);
}
```

**改造 `decreaseStock` 方法：**

```java
@Override
@Transactional
public boolean decreaseStock(Integer itemId, Integer amount) throws BusinessException {
    List<String> keys = Arrays.asList(
        "promo_item_stock_" + itemId,
        "promo_item_stock_invalid_" + itemId
    );

    Long result = (Long) redisTemplate.execute(
        decreaseStockScript,
        keys,
        amount
    );

    if (result == null) {
        throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "Lua脚本执行异常");
    }
    if (result == -1) {
        throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "活动库存未初始化");
    }
    if (result == -2) {
        return false;   // 库存不足，不需要回补！
    }
    return true;        // result >= 0，扣减成功
}
```

**修复 `increaseStock`（回补用的 key 写错了）：**

```java
@Override
public boolean increaseStock(Integer itemId, Integer amount) throws BusinessException {
    // 修复：原来是 "item_validate_"，应该是 "promo_item_stock_"
    redisTemplate.opsForValue().increment("promo_item_stock_" + itemId, amount.intValue());
    return true;
}
```

### 3.2 `PromoServiceImpl.java` — 发布时清理旧标记

```java
@Override
public void publishPromo(Integer promoId) {
    PromoDO promoDO = promoDOMapper.selectByPrimaryKey(promoId);
    if (promoDO.getItemId() == null || promoDO.getItemId().intValue() == 0) {
        return;
    }
    ItemModel itemModel = itemService.getItemById(promoDO.getItemId());

    // 同步库存到 Redis
    redisTemplate.opsForValue().set(
        "promo_item_stock_" + itemModel.getId(), itemModel.getStock()
    );

    // 清除上一轮的售罄标记（防止残留误杀）
    redisTemplate.delete("promo_item_stock_invalid_" + itemModel.getId());
}
```

### 3.3 不需要改动的文件

| 文件 | 原因 |
|------|------|
| `ItemService.java` 接口 | 方法签名不变，只是实现变了 |
| `OrderServiceImpl.java` | `decreaseStock` 是同一个方法，调用方无感 |
| `MqProducer.java` | 库存扣减逻辑在 `OrderServiceImpl` 中，MQ 层不感知 |

---

## 四、仍然存在的问题：Redis 与 MySQL 的事务边界

Lua 脚本解决了**Redis 内部的并发安全**问题，但整个下单链路跨了 Redis 和 MySQL 两个数据源。把流程拆开看：

```
executeLocalTransaction()  ← 这是 RocketMQ 事务消息的本地事务
  │
  ├── Step 1: decreaseStock(itemId, amount)   // Lua，Redis 扣库存 ✅
  │
  ├── Step 2: orderService.createOrder(...)    // @Transactional，MySQL
  │           ├── INSERT 订单
  │           ├── UPDATE 销量
  │           └── UPDATE stock_log status=2
  │
  └── 返回 COMMIT / ROLLBACK
```

### 4.1 隐患一：JVM 崩溃导致 Redis 库存丢失（高）

**场景**：Step 1 成功（Redis 扣了），Step 2 执行到一半 JVM 崩溃（OOM / kill -9 / 断电）：

| 组件 | 状态 | 后果 |
|------|------|------|
| Redis 库存 | **已扣减** | 永久少了 1 |
| stock_log | status=**1**（初始化后没来得及更新） | 证明这个操作"开始过" |
| MySQL 订单 | **未创建** | 没有订单 |
| MQ 半消息 | **挂起** | 没收到 commit/rollback |
| TransactionSynchronization | **没执行** | JVM 死了 |

MQ 随后回调 `checkLocalTransaction` → 查 stock_log status=1 → 返回 **UNKNOW**。回查 15 次都是 UNKNOW → 进入**死信队列**。

**最终结果：Redis 库存少了 1，没人知道，永久少卖一件。**

这就是当前方案最大的风险点——不是并发竞争问题，而是**崩溃后无补偿**问题。

### 4.2 隐患二：回补后售罄标记残留（高）

TransactionSynchronization 正常工作时也有问题。当事务回滚触发回补：

```java
// OrderServiceImpl 中的回补回调
itemService.increaseStock(itemId, amount);   // INCRBY +1
```

回补只加了库存数值，没有删除售罄标记：

```
库存=1，请求A 下单 → Lua 扣为 0，打标记 "true"
请求A 的事务失败 → increaseStock 回补 +1 → 库存=1
但标记仍是 "true"！
后续所有请求 → OrderController 检查标记 → 命中 → 直接拒绝！
```

**库存恢复了但标记没删，后续所有正常请求都被误杀。**

### 4.3 分析思路：为什么不能用分布式锁解决？

上面两个问题（JVM 崩溃、标记残留）的本质是**跨资源的操作结果不一致**——Redis 写了但 MySQL 没写，或者事务回滚了但 Redis 没感知到。这些都是"分布式事务"的范畴，不是"并发锁"的范畴：

| 场景 | 分布式锁能解决吗 | 为什么 |
|------|:---:|------|
| Redis 内部竞态（INCR + SET 不原子） | ❌ 太重 | Lua 更优 |
| JVM 崩溃后 Redis 库存未回补 | ❌ 不能 | 锁随进程死，JVM 挂了锁自动释放但不回补 |
| 回补后售罄标记未清理 | ❌ 不能 | 这是业务逻辑问题，不是并发问题 |

**处理分布式事务的正确思路不是"加锁"，而是"补偿"——允许短暂不一致，通过异步机制最终修正。**

---

## 五、高优先级改进方案

### 5.1 改进一：定时对账 — 修复崩溃后的 Redis 库存丢失

**问题**：JVM 崩溃后 stock_log 停留在 status=1，Redis 库存永久丢失。

**方案**：增加一个定时任务，扫描 `stock_log` 中**超时未完成**的记录，自动回补 Redis 库存。

**新增类 `StockReconciliationTask.java`**：

```java
package com.imooc.miaoshaproject.task;

import com.imooc.miaoshaproject.dao.StockLogDOMapper;
import com.imooc.miaoshaproject.dataobject.StockLogDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class StockReconciliationTask {

    @Autowired
    private StockLogDOMapper stockLogDOMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    // 每分钟执行一次
    @Scheduled(fixedDelay = 60000)
    public void reconcileDanglingStock() {
        // 查询 status=1 且超过 5 分钟未更新的流水
        List<StockLogDO> danglingList = stockLogDOMapper.selectDanglingLogs(1, 5);
        // ↑ 需要自己在 StockLogDOMapper.xml 中加一条 SQL：
        //   SELECT * FROM stock_log WHERE status = #{status}
        //   AND TIMESTAMPDIFF(MINUTE, create_time, NOW()) >= #{minute}

        for (StockLogDO log : danglingList) {
            try {
                // 回补 Redis 库存
                redisTemplate.opsForValue().increment(
                    "promo_item_stock_" + log.getItemId(),
                    log.getAmount()
                );

                // 如果有售罄标记，也清除
                redisTemplate.delete("promo_item_stock_invalid_" + log.getItemId());

                // 标记流水为回滚
                log.setStatus(3);
                stockLogDOMapper.updateByPrimaryKeySelective(log);

                log.warn("对账回补: stockLogId={}, itemId={}, amount={}",
                    log.getStockLogId(), log.getItemId(), log.getAmount());

            } catch (Exception e) {
                log.error("对账回补失败: stockLogId={}", log.getStockLogId(), e);
            }
        }
    }
}
```

**配套改动**：

1. `stock_log` 表新增 `create_time` 字段：
   ```sql
   ALTER TABLE stock_log ADD COLUMN create_time DATETIME DEFAULT CURRENT_TIMESTAMP;
   ```

2. `StockLogDOMapper.xml` 新增查询：
   ```xml
   <select id="selectDanglingLogs" resultMap="BaseResultMap">
       SELECT <include refid="Base_Column_List"/>
       FROM stock_log
       WHERE status = #{status}
       AND TIMESTAMPDIFF(MINUTE, create_time, NOW()) >= #{minute}
   </select>
   ```

3. 启动类加 `@EnableScheduling`。

**为什么选 5 分钟**：正常下单链路从 stock_log 初始化到更新 status=2 只需 1-2 秒。MQ 回查 15 次大约耗时 3-5 分钟。选 5 分钟作为阈值，既能给 MQ 回查留足时间，又不会让库存缺口存在太久。

### 5.2 改进二：回补同步清除售罄标记

**问题**：回补只加库存数值，没删标记，导致后续请求被误杀。

**当前代码（有问题）**：

```java
// ItemServiceImpl.increaseStock
redisTemplate.opsForValue().increment("promo_item_stock_" + itemId, amount.intValue());
// 缺少：删除售罄标记
```

**方案一（最简单）**：在回补后加一行删除：

```java
@Override
public boolean increaseStock(Integer itemId, Integer amount) throws BusinessException {
    redisTemplate.opsForValue().increment("promo_item_stock_" + itemId, amount.intValue());
    redisTemplate.delete("promo_item_stock_invalid_" + itemId);   // 加这一行
    return true;
}
```

**方案二（更严谨）**：但如果回补发生在售罄标记被打上之前（时序问题），DELETE 一个不存在的 key 也不会有副作用。不过 `INCRBY` 和 `DELETE` 之间仍有微小缝隙，最严谨的做法是写成 Lua：

```java
private static final String INCREASE_STOCK_LUA =
    "local stock = redis.call('GET', KEYS[1])\n" +
    "if stock then\n" +
    "    redis.call('SET', KEYS[1], tonumber(stock) + tonumber(ARGV[1]))\n" +
    "else\n" +
    "    redis.call('SET', KEYS[1], ARGV[1])\n" +
    "end\n" +
    "redis.call('DEL', KEYS[2])\n" +     -- 无论如何清除标记
    "return redis.call('GET', KEYS[1])";

@Override
public boolean increaseStock(Integer itemId, Integer amount) throws BusinessException {
    List<String> keys = Arrays.asList(
        "promo_item_stock_" + itemId,
        "promo_item_stock_invalid_" + itemId
    );
    redisTemplate.execute(increaseStockScript, keys, amount);
    return true;
}
```

**建议先用方案一快速上线，方案二作为后续优化。**

---

## 六、其他潜在问题与简要方案

### 6.1 Redis 热 Key 瓶颈

**问题**：所有秒杀请求操作同一个 key `promo_item_stock_{itemId}`。Redis 单节点这个 key 的 Lua 脚本 TPS 天花板约 5-10 万。头部大厂顶级秒杀（如茅台抢购）单商品可能百万级 QPS，会打满单节点 CPU。

**方案**：库存分片——把库存拆到 N 个 key：

```
promo_item_stock_{itemId}_0  ← 存 1/N 库存
promo_item_stock_{itemId}_1  ← 存 1/N 库存
...
```

请求随机路由到一个分片的 key 进行扣减，扣减失败时换另一个分片重试。售罄的判断需要并发查询所有分片。**当前量级暂不需要，等到 Redis CPU 使用率 >60% 再考虑。**

### 6.2 Redis 与 MySQL 长期运行偏差

**问题**：MQ Consumer 异步扣减 MySQL 库存可能失败（Consumer 重启、网络抖动等），长时间运行后 Redis 和 MySQL 库存会累积微小的偏差。

**方案**：增加全量对账定时任务（每日凌晨执行）：

1. MySQL 侧：用原始库存减去 `order` 表中的已售数量
2. Redis 侧：`GET promo_item_stock_{id}`
3. 差异 > 阈值 → 告警 + 以 MySQL 为准修复 Redis
4. 差异较大的商品打上标记，人工确认是否发生了异常

**复杂度较高，当前阶段给出方案即可，实际实现可后续迭代。**

### 6.3 售罄标记没有 TTL

**问题**：如果因某种原因（如上面分析的各种异常）标记残留，且定时任务还没跑到，标记会长期存在阻断所有请求。

**方案**：写入售罄标记时设置 TTL（比如 30 分钟——超过一场秒杀的时间）：

Lua 脚本中的 SET 加上过期：

```lua
if newStock == 0 then
    redis.call('SET', KEYS[2], 'true', 'EX', 1800)  -- 30 分钟自动删除
end
```

这样即使异常情况下标记没被删除，最多 30 分钟后自动恢复。标记的本质是**性能优化**（不是正确性保障），有 TTL 兜底哪怕误删也不影响正确性，后续请求会走到 Lua 脚本正常判库存。

---

## 七、总结

### 决策链回顾

```
问题: 秒杀库存扣减 MySQL 行锁瓶颈 (TPS ~500)
  │
  └→ 解决: 库存预热到 Redis，INCR 原子扣减 (TPS 50000+)
        │
        新问题: INCR 和 SET 售罄标记之间有竞态窗口
        │
        ├→ 考虑过: 分布式锁 → 否决（太重，降 TPS）
        ├→ 考虑过: MULTI/EXEC → 否决（不支持条件判断）
        │
        └→ 选择: Lua 原子脚本（先判再扣 + 打标记，一步到位）
              │
              新问题: Redis 与 MySQL 事务边界 — 崩溃回补、标记残留
              │
              ├→ 高优: 定时对账任务（扫 stock_log 回补）
              ├→ 高优: 回补同步清除售罄标记
              ├→ 中优: 售罄标记加 TTL 兜底
              └→ 低优: 库存全量对账、热 Key 分片
```

### 核心认知

1. **并发安全性可以在一个数据源内部解决（Lua），分布式一致性只能靠补偿机制解决（对账）。**
2. **分布式锁是最后的手段，不是首选。** Lua 脚本、CAS 乐观锁、消息队列的顺序保证——这些都比分布式锁更轻量，应该优先考虑。
3. **"宁可少卖，不可超卖"是秒杀系统的基本原则。** 崩溃后库存回补失败导致少卖一件，比超卖一件好得多。定时对账把这个"少卖"的窗口控制在 5 分钟内。
4. **售罄标记是性能优化，不是正确性保障。** 标记丢了不会超卖（Lua 会判断库存），标记残留会导致误杀——所以标记应该加 TTL 兜底 + 回补时主动清理。

### 当前要做的事

| 优先级 | 改动 | 文件 |
|------|------|------|
| 🔴 立刻 | `decreaseStock` 改用 Lua 脚本 | `ItemServiceImpl.java` |
| 🔴 立刻 | 修复 `increaseStock` 的 key 错误 | `ItemServiceImpl.java` |
| 🔴 立刻 | `publishPromo` 清理旧售罄标记 | `PromoServiceImpl.java` |
| 🟡 尽快 | 增加定时对账任务 | 新建 `StockReconciliationTask.java` |
| 🟡 尽快 | 回补时同步清除售罄标记 | `ItemServiceImpl.increaseStock()` |
| 🟢 后续 | 售罄标记加 30 分钟 TTL | Lua 脚本 |
| 🟢 后续 | 库存全量对账任务 | 新建对账 Task |
