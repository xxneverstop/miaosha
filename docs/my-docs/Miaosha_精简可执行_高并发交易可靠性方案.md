# Miaosha — 高并发交易可靠性与性能优化系统

> **版本**：V2.0 · 2026-11-19 · 简历/面试导向的可执行改造方案  
> **基于**：用户提供的《Miaosha_高并发后端_简历项目完善方案(1).md》及前期对 GitHub 主链源码的核查。  
> **仓库**：[xxneverstop/miaosha](https://github.com/xxneverstop/miaosha)；原文审计基线 `f316113c7087870e01c4b17f66a55212fee765b1`。实施前须以实际 HEAD 再核对。  
> **提醒**：这是**待实施方案**，没有完成新增功能，也没有复测历史性能。未做出的技术点不得出现在已完成简历中。

## 0. 核心决策：保留秒杀，但从“技术栈展示”转为“正确性与性能实验”

**为什么保留**：现有 Java/Spring Boot、Redis Lua、RocketMQ 事务消息、库存流水、缓存、限流与压测文档已有投入。重新做 QuotaFlow 的题材辨识度可能更好，但在有限时间内从零建立业务正确性与实验链路的风险更高。QuotaFlow 留作**系统设计场景题**准备，不在当前阶段开发。

**项目真正要证明的三件事**：

1. 业务链路在重复消息、异常回滚和服务中断下，哪些操作能保证正确，哪些仍然需要核查。
2. 在**相同业务约束和实验条件**下，Redis Lua 与消息异步化带来了什么性能收益，以及付出了什么一致性/复杂度成本。
3. 关键问题能够通过**集成测试、故障注入、真实压测原始工件**复现，而不是只背 Redis、MQ 八股。

**明确不做**：商城业务补齐、完整微服务重构、注册中心、K8s、分库分表、生产级自动补偿平台、无边界的技术栈升级。已有单体不是缺陷本身；核心交易链路是否正确比进程数量重要。

```text
用户 → 令牌/限流/售罄检查
       │
       ▼
创建库存流水 stock_log → RocketMQ 半消息
                                │
                                ▼
                     订单本地事务（MySQL）
                                │
                   Redis Lua 预扣（独立资源）
                                │
                   订单/销量/流水落库并提交
                                │
                   MQ COMMIT → Consumer
                                │
                                ▼
                MySQL 消费幂等记录 + 库存同步
                       （同一 DB 事务）
                                │
                                ▼
                 读对账 / 故障验证 / 压测
```

**核心事实边界**：RocketMQ 事务消息协调的是订单本地事务与消息放行，不能让 Redis 和 MySQL 原子提交。异步确认 HTTP 与 DB 库存最终同步也不是同一时刻；项目必须分别度量。

---

## 1. 当前源码事实与需要解决的问题

| 当前事实 | 代码/文档位置 | 本阶段处理 |
|---|---|---|
| Redis 去重 `SETNX → EXPIRE → DB 扣减`，最后 ACK | `mq/MqConsumer.java` | **必修**：去重与库存更新必须进入同一个数据库事务，失败要重投 |
| `BusinessException extends Exception`，`createOrder()` 仅 `@Transactional` | `error/BusinessException.java`、`OrderServiceImpl.java` | **必修**：明确受检异常回滚语义，并测试事务消息状态 |
| RocketMQ 半消息、本地事务与回查已存在 | `mq/MqProducer.java` | **必修**：明确成功、失败、状态未知的判据 |
| `stock-mode=mysql` 可能与后续同步扣 DB 路径重复扣减 | `ItemServiceImpl.decreaseStock()`、`OrderServiceImpl.createOrder()` | **必修**：固定合法模式后再压测，避免错误数据 |
| `generateOrderNo()` 是 `private @Transactional(REQUIRES_NEW)`，同类内部调用 | `OrderServiceImpl.java` | **压测前修**：不要误以为事务传播生效，并发发号需原子化 |
| Redis 回补使用 `increaseStock`，源码无自动定时对账 | `OrderServiceImpl.java`、`ItemServiceImpl.java` | **分档**：先有失败证据与只读对账；确认回滚的幂等补偿是增强项 |
| Controller 当前每实例 `RateLimiter.create(5)`，且 `Future.get()` 同步等待 | `OrderController.java` | **压测前修**：参数化限流；不要宣传 HTTP 全异步 |
| 历史报告有 `142 → 1200 QPS`，缺原始 `.jmx/.jtl` | `docs/秒杀系统压测结果报告.md` | **必须重测**：旧数值只能视为历史记录，不能直接写简历 |
| 多级缓存、令牌、售罄标记已有 | `ItemController`、`CacheServiceImpl`、`PromoServiceImpl` | 先保留，不扩成商城功能 |

**明确优先级**：数据库订单/库存正确性 > 幂等与异常语义 > 可信压测 > 进阶多实例。简单的“多一个 Redis 或 MQ”不构成加分项。

## 2. 目标简历：分为基础达标稿与增强稿

### 基础达标稿（只需要 M0–M4）

**Miaosha — 高并发交易可靠性与性能优化系统**  
**技术**：Java、Spring Boot、MySQL、Redis、Lua、RocketMQ、Caffeine、Docker、JMeter

- **热点交易性能优化**：围绕 MySQL 热点库存行锁竞争，实现并对比数据库直接扣减与 Redis Lua 原子预扣链路，结合限流/售罄快速失败，对固定负载下的业务成功吞吐、P99、DB 锁等待与排队开销进行复测分析【填真实结果】。
- **消息可靠性与事务幂等**：针对 RocketMQ 消费重投、事务回滚及消费标记先于库存更新的故障窗口，修复 Spring 事务语义，将消费去重与 MySQL 库存同步纳入同一本地事务，实现重复消息安全处理。
- **故障验证与一致性检查**：构建订单回滚、重复投递、DB 异常和服务中断测试，检查订单、库存流水和库存变化的不变量；保留异常状态、重试记录与复现工件，明确跨 Redis/MySQL 操作的保证边界。

### 增强项真的实现后才可替换相应表述

- 如完成 **Redis 预留标识 + 幂等回补 + 对账**，第二/第三条可扩为“可追踪补偿及对账”；只做只读检测，**不能写成自动恢复一致性**。
- 如完成 **两实例 + 独立 Consumer**，可以增加一个多实例验证结论，但不建议另占第四条简历 bullet，除非有具体新结果。
- 任何 `【A→B】`、`【P99 ↓X%】` 必须有本轮 `.jmx/.jtl`、业务状态解析、测试环境、配置快照与数据库校验，禁止搬用历史 1200 QPS。

## 3. 开发范围和时间上限

| 里程碑 | 建议开发投入 | 交付与停止条件 | 范围外 |
|---|---|---|---|
| **M0 启动与测试基线** | 1–2 个开发日 | 固定依赖、无密钥配置、完整链路可启动，最小集成测试 | 大升级框架 |
| **M1 订单事务与库存路径正确性** | 3–4 个开发日 | 回滚语义、MQ 回查、合法库存模式、订单号发号修复 | 全业务重写 |
| **M2 Consumer 事务内幂等** | 3–4 个开发日 | 同消息多次重投只扣 DB 一次，异常必重试 | 分布式锁平台 |
| **M3 异常核查与故障验证** | 3–4 个开发日 | 库存不变量、只读对账、至少 5 种故障注入 | 自动修复所有异常 |
| **M4 当前代码压测** | 3–4 个开发日 | 同环境受控对照、成功吞吐与 P99、原始数据 | 宣传高并发生产规模 |
| M5（增强）预留/幂等补偿/多实例 | 有余力时，按单项验收 | 无法在预算内做扎实就停 | K8s/全面微服务 |

**两项目组合时间**：Miaosha 最多约 3 周的集中开发，Page 约 2–3 周。并行补算法、Java 八股及技术口述，不应为全部增强项牺牲 2027 年 2 月前的准备。

---

## 4. 按文件实施：M0–M4 最小必做方案

> Java 文件路径（以下采用相对包路径）均在 `src/main/java/com/imooc/miaoshaproject/` 下；新增文件为**建议位置**而非当前实际存在。每个阶段完成后提交一次稳定的可运行版本和 `docs/build-logs/` 实施记录。

### M0 — 固定环境与回归基础

**涉及**：`pom.xml`、`README.md`、`docker-compose.yml`、`sql/miaosha.sql`、`scripts/**`、`src/main/resources/application*.yml`、`src/test/**`。

**动作**：

- 维护无密钥 `application-example.yml` 和 `.env.example`，列清 MySQL/Redis/RocketMQ 地址、应用端口、库存模式、消息开关；固定版本。若原有脚本可用则复用，不为追求一键启动重造编排。
- 用隔离数据库初始化；避免在含真实数据的实例执行 `DROP TABLE` 种子脚本。
- 增加最小集成测试：正常创建活动与订单后，检查订单、`stock_log`、Redis 库存、MySQL 库存；确保测试能等待异步消费完成，且等待有超时，不使用无界 sleep。

**验收**：本地从空库启动并跑通默认 `lua + mq-async-sync=true`；重启后能正确查询已提交订单。未达到这一步不开始大规模重构。

### M1 — 先修真实交易正确性

**涉及**：

```text
service/impl/OrderServiceImpl.java       # createOrder、generateOrderNo
mq/MqProducer.java                       # executeLocalTransaction、checkLocalTransaction
service/impl/ItemServiceImpl.java        # decreaseStock 的 mode 分支
controller/OrderController.java         # 参数化限流，核对 Future.get()
src/main/resources/mapping/SequenceDOMapper.xml  # 序列号更新，实际路径按仓库核对
```

**改动 1：受检异常的事务语义。** `BusinessException extends Exception`；因此 `OrderServiceImpl.createOrder()` 原来的 `@Transactional` 不默认因它回滚。采用 `@Transactional(rollbackFor = Exception.class)`，并检查其余事务写路径是否抛同类异常。测试应在**已写订单/流水**后注入受检异常，确认数据库回滚；不能只验证异常抛出。

```java
// 目标要点示意，不是可直接替换的完整方法
@Transactional(rollbackFor = Exception.class)
public OrderModel createOrder(...) throws BusinessException {
    // 订单、销量、流水操作；异常时必须整体回滚
}
```

**改动 2：事务消息三态。** `MqProducer.executeLocalTransaction()` 应区分：确知本地订单事务提交→COMMIT；确知回滚→ROLLBACK；数据库写入/响应状态不确定→UNKNOWN 并回查 `stock_log` 与订单事实。回查也有业务超时/长期 UNKNOWN 处理边界，不能把 `status=1` 永远当成功或失败。`stock_log=2` 仅代表订单本地提交，**不代表 Consumer 已完成 DB 库存同步**。

**改动 3：消除错误库存模式。** 固定以下模式并拒绝不支持的组合：

| 模式 | Redis 入口 | DB 库存实际更新 | 用途 |
|---|---|---|---|
| `mysql + sync` | 不扣 | `createOrder` 本地事务扣一次 | 基线 |
| `lua + sync` | Lua 扣一次 | `createOrder` 本地事务扣一次 | 分离 Redis 准入收益与 DB 同步成本 |
| `lua + async` | Lua 扣一次 | Consumer 事务内扣一次 | 目标链路 |

原 `mysql` 分支若在 `decreaseStock` 已经扣 DB，则不能在 `!mqAsyncSync` 分支再次扣。对所有模式检查返回值和影响行数，失败不能装作下单成功。若旧 Redis INCR 模式无法满足正确性，标为“历史对照、禁止简历性能结论”，不强行修复所有模式。

**改动 4：订单号并发安全。** `generateOrderNo()` 是同类 `private` 调用，`REQUIRES_NEW` 不会通过 Spring 事务代理生效。改为独立原子发号服务（数据库安全的原子序列更新或 Redis `INCR` + 持久化唯一约束）并验证。没有可靠订单号就不开展并发成功吞吐实验。

**M1 验收**：业务异常/系统异常路径 DB 不留脏订单；3 种支持模式在 1 次下单中各库存资源只扣应扣次数；并发订单号唯一；MQ 回查不会将状态未知误作肯定成功。

### M2 — 真正的 MQ 消费幂等：数据库事务内完成

**当前问题**：`mq/MqConsumer.java` 先 `redisTemplate.opsForValue().setIfAbsent()`，再设置 TTL，再执行 `itemStockDOMapper.decreaseStock()`，无条件返回 `CONSUME_SUCCESS`。这不是事务性消费：在标记后、DB 更新前宕机可能永远漏扣；TTL 过期又可能发生重扣。

**建议新增**：

```text
sql/migrations/001_stock_consume_log.sql
service/StockConsumeService.java
service/impl/StockConsumeServiceImpl.java
dao/StockConsumeLogMapper.java
src/main/resources/mapping/StockConsumeLogMapper.xml
src/test/java/com/imooc/miaoshaproject/mq/StockConsumeIT.java
```

建议数据库表（仅示意，实际类型以现有 `stock_log` 字段对齐）：

```sql
CREATE TABLE stock_consume_log (
  stock_log_id VARCHAR(64) NOT NULL PRIMARY KEY,
  item_id INT NOT NULL,
  amount INT NOT NULL,
  consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
```

**执行规则**：独立 Spring Bean 的 `consumeOnce(stockLogId,itemId,amount)` 包含同一 MySQL 事务：验证 `stock_log` 订单已提交且信息匹配 → 原子插入消费记录（主键去重）→ 条件扣 DB 库存并验证影响行数为 1 → 事务提交。重复主键要核对旧记录的业务字段相同，再按“已成功消费”返回；推荐使用数据库支持的 `INSERT IGNORE`/等价事务安全手段并核对结果，避免在已经失败的事务中错误地吞掉异常。**一切失败必须回滚去重记录与库存更新**。

```java
// 方案级伪代码；具体异常分类和 MyBatis 接口需结合仓库实现
@Transactional(rollbackFor = Exception.class)
public void consumeOnce(String stockLogId, int itemId, int amount) {
    assertOrderCommittedAndPayloadMatches(stockLogId, itemId, amount);
    int inserted = consumeLogMapper.insertIfAbsent(stockLogId, itemId, amount);
    if (inserted == 0) {
        assertExistingConsumeLogMatches(stockLogId, itemId, amount);
        return; // 前一次已完整提交，安全 ACK
    }
    int affected = itemStockMapper.decreaseStock(itemId, amount);
    if (affected != 1) throw new StockSyncException(); // 回滚消费记录
}
```

`MqConsumer.java` 只负责解析消息、调用事务 Bean、根据结果决定 ACK/RECONSUME_LATER。遇到暂时性 DB 错误应重试；永久性非法数据需记录、报警并走有限重试/死信或人工处理流程，不应静默 ACK。**单批含多条消息**时可能整体重投，幂等记录仍需保证已处理消息不会再扣库存。

**M2 验收**：同 `stockLogId` 2/10/100 次重复投递 DB 只扣一次；去重记录插入后故障、库存更新后故障均不会出现“记录成功但库存没变”；DB 更新 0 行不能 ACK 为成功；不同 `itemId/amount` 不能冒用同一 `stockLogId`。

### M3 — 先实现“可证明的核查”，不要一上来宣称自动最终一致

**目标**：发现异常、解释异常、输出安全处置方式。先做**只读对账**，再决定是否实现自动补偿；这能显著降低开发复杂度和错误恢复风险。

**建议新增**：

```text
service/StockReconcileService.java
service/impl/StockReconcileServiceImpl.java
service/model/StockReconcileReport.java
scripts/check_stock_invariants.sql
src/test/java/com/imooc/miaoshaproject/service/StockReconcileIT.java
benchmarks/faults/README.md
```

**对账数据源**：初始库存、已提交订单 `SUM(amount)`、`stock_log` 状态、`stock_consume_log` 成功记录、MySQL 当前库存、Redis 当前库存。对账需考虑观察时点，**有 MQ 积压/进行中订单时不能套用最终静态等式**。

在测试活动入口关闭、所有在途事务收敛、没有取消/补货的简化模型下：

```text
初始库存 - MySQL 现库存 = 已完成 DB 同步的数量
初始库存 - Redis 现库存 = 已成功预留且尚未回补的数量
收敛后两者应等于有效成功订单购买总量
```

无法核实的 Redis 预留与未知订单状态，标记 `REVIEW_REQUIRED`，**不可直接 Redis SET 成 MySQL 值**。在现有源码只靠单个 `stock_log` 不能保证逐笔识别所有 Redis 预扣；至少要让对账输出“库存差额/悬空记录/消费滞留”，并明确哪些异常只可检测、不可安全自动恢复。

**可选增强 M5-A**（若需要在简历写“幂等回补”）：按 `stockLogId` 原子写 Redis Reservation 与扣减；对**确认未提交订单**的预留通过补偿 Lua 进行按身份去重的回补，保留补偿证据；未知状态不自动回补。Redis 数据丢失、Reservation 过期仍存在风险，因此不能宣称跨 Redis/MySQL Exactly Once。这部分需要单独完成状态机、TTL/持久化语义和崩溃测试，不能直接粘贴原方案的 Lua 草稿。

**最小故障矩阵**（先做 5 类）：

| 失败场景 | 自动断言/应得结果 |
|---|---|
| 订单事务在写入后抛 `BusinessException` | 订单/销量/流水更新按设计回滚，MQ 不误提交 |
| 消费记录插入后 DB 抛异常 | 消费标记与库存更新同事务回滚；重投能继续 |
| 同一消息重复投递 | 只扣 DB 一次，最终处理可查询 |
| 消费时 DB 暂停或不可用 | MQ 不误 ACK、恢复后正确重试，积压可查询 |
| 订单已提交但消费延迟/中断 | 对账检测到待同步状态，消费恢复后库存收敛 |

进阶故障：Redis Lua 预扣后进程直接退出、补偿 Lua 重复执行、MQ 半消息长期 UNKNOWN、双实例重复请求。**只要未完成受控预留/补偿机制，Redis 崩溃窗口应该作为“已识别但尚无法自动恢复”的系统边界，不要伪称通过。**

**M3 验收**：每次故障保留 `stockLogId`、注入时刻、日志、DB/Redis 前后值、MQ 状态、断言结果；可以清楚回答“发生了什么、谁是真相源、当前会如何处理”。

### M4 — 可信性能实验：性能和一致性必须一起看

**现有**：`docs/秒杀系统压测方案.md`、`docs/秒杀系统压测结果报告.md`；历史数字**只存档，不直接写简历**。

**新增/更新**：

```text
benchmarks/jmeter/*.jmx
benchmarks/scripts/reset_test_data.sh         # 仅隔离环境可用
benchmarks/scripts/check_invariants.sql
benchmarks/results/<experiment-id>/
  environment.md      # CPU/内存、JVM、Redis/MySQL/MQ 版本
  config-snapshot.yml # 限流、库存、缓存和 MQ 开关（脱敏）
  requests.jtl        # JMeter 原始记录
  business.csv        # 业务状态：成功/售罄/限流/超时/系统错误
  resources.csv       # CPU、GC、DB/Redis/QPS、队列/Consumer Lag
  report.md
```

**重点只做三组有解释价值的对照**：

| 组别 | 配置/说明 | 研究问题 |
|---|---|---|
| A | `mysql + sync` | DB 同一热点行锁如何影响成功吞吐与 P99？ |
| B | `lua + sync` | Redis 准入是否改善有效请求数/入口竞争？同步 DB 仍是瓶颈吗？ |
| C | `lua + async` | HTTP 链路变快多少？MQ 积压、DB 完成耗时及异常恢复代价是多少？ |

A、B、C 的业务行为、限流阈值、请求总量、商品库存、用户数、消息处理完成标准必须尽量相同。**C 组不能将 HTTP 已返回就当成库存已经完成 DB 同步。** 单独记录“请求成功响应吞吐”和“全部 DB 同步完成耗时/最终完成吞吐”，说明非等价阶段。不要跨越不同库存模式的正确性缺陷强行做性能结论。

配置要求：将 `OrderController.java` 的 `RateLimiter.create(5)` 改为可配置；并明确每实例限流与全局限流是不同概念。测试用可重复的多用户数据、足够库存和独立隔离数据库；分别压“库存充足的有效购买流量”和“售罄/限流拒绝流量”，禁止把后者的高 HTTP QPS 当业务处理能力。

**原始指标**：业务成功 QPS、HTTP 请求 QPS、P50/P95/P99、系统错误率、限流/售罄比率、JVM/CPU、DB 行锁/连接池、Redis、MQ Lag、DB 库存同步结束时间、最终库存不变量。每次固定环境至少重复两轮，异常结果先定位再解释。

**M4 验收**：重置隔离数据→运行 `.jmx`→从 `.jtl` 统计业务状态→等待异步消费结束→SQL/Redis 验证库存→生成报告。这条流程他人能按 README 复现；所有数值有负载、环境与统计口径。

---

## 5. M5 可选增强：最多选一项

**M5-A 可追踪 Redis 预留/幂等回补**：适合在 M3 已发现明确跨系统窗口、并有时间证明安全状态机时做。改动 `ItemServiceImpl`、`OrderServiceImpl` 及 Lua/Reservation 流程；重点是可确认的回补、不确定状态人工核查。**收益**：分布式一致性面试故事更完整。**成本**：高、容易引入新漏洞。

**M5-B 两实例 + 独立 Consumer**：使用最小 Docker Compose/Nginx 或两个进程，不搞微服务注册发现。验证每实例限流与全局限流差别、Consumer 重投幂等、缓存过期语义。**收益**：更直接的分布式应用证据。**成本**：中等。

两项只选一项，且**M4 压测和面试复盘完成以后再选**。如时间不足，M5 全部不做。

## 6. 两条主力面试故事与追问证据

### 故事 A — RocketMQ 事务消息为什么还会出现错误库存？

**背景**：订单写 DB、Redis 预扣、MQ 消费更新 MySQL 库存跨三个资源。  
**故障**：消费者先写 Redis 去重标记，再 DB 扣减；中途宕机会漏处理，TTL 过期后可能重扣。  
**解决**：消费流水唯一键 + MySQL 同事务扣减 + 回滚/ACK 规则；补充只读核查和未知状态处理。  
**证据**：`MqConsumer.java`、`StockConsumeServiceImpl`、故障注入日志、库存 SQL。  
**深挖**：事务消息保证了什么、不保证什么？重复消息、0 行更新、消息积压、Redis 数据丢失、UNKNOWN 如何处理？

### 故事 B — 为什么 Lua 比 MySQL 直扣快，为什么不能只看 QPS？

**背景**：同一商品的条件更新争夺一条 MySQL 库存行。  
**取舍**：MySQL 同步强约束、Lua 快速准入、MQ 异步库存同步；性能改善伴随一致性与故障处理成本。  
**证据**：A/B/C 组 `.jmx/.jtl`、DB 锁等待、MQ Lag、库存差额和 P99。  
**深挖**：成功吞吐与拒绝吞吐的区别；为何 C 组 HTTP 快不代表全部 DB 事务完成得快；瓶颈转移到哪里？

第三条（可作为补充而非主要故事）：限流/售罄标记/有界线程池如何防过载；注意 Controller 当前 `Future.get()` 仍在等结果，不能说“HTTP 请求完整异步”。

## 7. 必补知识与简历审计

| 学习点 | 回到代码与实验 |
|---|---|
| Spring AOP / `@Transactional` | `BusinessException`、私有方法同类调用、代理边界 |
| MySQL 事务 / 锁 / 唯一约束 | 库存原子条件更新、消费流水去重、订单号唯一 |
| RocketMQ 半消息 / 回查 / 重投 | `MqProducer` 与 `MqConsumer` 三态、失败处理 |
| Redis Lua / TTL / 幂等 | 库存预扣与回补、不确定副作用 |
| 性能工程 | JMeter 业务断言、P99、成功吞吐、DB Lag、队列与资源瓶颈 |

### 最终交付自检

- [ ] 新环境能启动；测试库可重置；无真实密钥进入仓库。
- [ ] 受检异常与运行时异常会按设计回滚，MQ 不能误提交。
- [ ] 消费去重与库存同步同一 DB 事务；重投不会导致重复扣减。
- [ ] 库存模式无双扣；并发订单号唯一；数据库影响行数检查完整。
- [ ] 最少 5 类故障有自动/半自动断言，有可复现异常记录。
- [ ] 有只读库存对账；未知跨 Redis/MySQL 状态不被盲目自动修复。
- [ ] A/B/C 性能测试数据来自本次代码，可区分响应吞吐与最终落库吞吐。
- [ ] 讲得清楚两个核心故事，并能现场定位源码和实验结果。
- [ ] 没有把历史 `142→1200` 当本轮数据，也没有宣称生产级 Exactly Once。

**止损规则**：M0–M2 达到正确性、M3 有故障证据、M4 有可信压测，即停止新增功能并投入面试训练。项目题材虽常见，但深度来自**真实缺陷修复 + 可验证取舍**，不是更大商城或更多服务数量。

## 8. 源码索引与实施方式

- [README](https://github.com/xxneverstop/miaosha/blob/master/README.md) · [OrderServiceImpl](https://github.com/xxneverstop/miaosha/blob/master/src/main/java/com/imooc/miaoshaproject/service/impl/OrderServiceImpl.java) · [ItemServiceImpl](https://github.com/xxneverstop/miaosha/blob/master/src/main/java/com/imooc/miaoshaproject/service/impl/ItemServiceImpl.java)
- [MqProducer](https://github.com/xxneverstop/miaosha/blob/master/src/main/java/com/imooc/miaoshaproject/mq/MqProducer.java) · [MqConsumer](https://github.com/xxneverstop/miaosha/blob/master/src/main/java/com/imooc/miaoshaproject/mq/MqConsumer.java) · [OrderController](https://github.com/xxneverstop/miaosha/blob/master/src/main/java/com/imooc/miaoshaproject/controller/OrderController.java)
- [压测方案](https://github.com/xxneverstop/miaosha/blob/master/docs/秒杀系统压测方案.md) · [历史结果](https://github.com/xxneverstop/miaosha/blob/master/docs/秒杀系统压测结果报告.md)

> 每个里程碑：`Plan → Review → Implement → Verify → Build-log → Interview note`。Codex 负责常规实现、测试脚手架和文档整理；你本人必须审查事务状态机、幂等键、MQ 三态、压测口径及故障结论。每个计划放 `docs/plans/`，至少写清改前/改后、文件改动、边界、回归、手工验证和停止条件。
