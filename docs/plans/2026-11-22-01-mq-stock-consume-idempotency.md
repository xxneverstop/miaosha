---
plan_id: "2026-11-22-01-mq-stock-consume-idempotency"
title: "用 MySQL 消费记录修复 MQ 库存漏扣和多扣"
status: draft
created_at: "2026-11-22"
updated_at: "2026-11-22"
build_log: "docs/build-logs/2026-11-22-01-mq-stock-consume-idempotency.md"
---

# 用 MySQL 消费记录修复 MQ 库存漏扣和多扣

> 草案，待审查。对应 [Roadmap 第 2 步第 2 项](../roadmap.md#5-第-2-步修复订单与-mq-的正确性)。

## 1. 先看结论

用户买了商品 `6` 的 `1` 件，RocketMQ 发给后台的扣库消息带着库存流水号 `S001`。MySQL 库存原有 `10` 件。同一条消息即使送来 100 次，只要有一次正常处理完成，MySQL 库存就应是 `9`。

```text
改动前  MqConsumer → 写 Redis 去重键 → 扣 MySQL item_stock.stock → 确认消费
改动后  MqConsumer → 同一 MySQL 事务 [插入 stock_consume_log + 扣 item_stock.stock]
                   → 事务提交后确认消费
```

Redis 标记只能说明消费者开始处理过。新表 `stock_consume_log` 中已提交的一行，可以证明这一笔 MySQL 扣减也提交过。第一次扣库失败，消费记录一起回滚。第一次提交成功，重投由主键认出，不会再扣。

## 2. 现状、目标和边界

以当前默认的 `stock-mode=lua`、`mq-async-sync=true` 为主路径。以下用库存流水号 `S001` 和订单号 `O001` 举例。

| 阶段 | 数据写到哪里 | 示例 |
| --- | --- | --- |
| 初始化库存流水 | MySQL 库存流水表 `stock_log` | `stock_log_id=S001, item_id=6, amount=1, status=1` |
| 发送事务半消息 | RocketMQ 的默认 Topic `stock_topic` | `{"stockLogId":"S001","itemId":6,"amount":1}`，暂时对消费者不可见 |
| 本地下单 | Redis 的 `promo_item_stock_6`、MySQL 订单表 `order_info` 和库存流水表 `stock_log` | Redis `10 → 9`；`order_info` 新增 `id=O001`、商品和购买数量等字段；`stock_log` 中 `S001` 改为 `status=2` |
| 消费扣库 | MySQL 商品库存表 `item_stock` | `MqConsumer` 收到已提交的消息后，按 `item_id=6` 将 `stock` 从 `10` 扣到 `9` |

`stock_log.status=2` 记录的是本地下单阶段成功。此时 `item_stock.stock` 可能还在等消费扣减。当前 `order_info` 没有 `stock_log_id` 字段，消费时用消息里的 `stockLogId` 对应 `stock_log.stock_log_id`。 [生成库存流水](../../src/main/java/com/imooc/miaoshaproject/service/impl/ItemServiceImpl.java#L177) · [事务消息](../../src/main/java/com/imooc/miaoshaproject/mq/MqProducer.java#L103) · [写入订单和状态](../../src/main/java/com/imooc/miaoshaproject/service/impl/OrderServiceImpl.java#L149) · [表结构](../../sql/miaosha.sql#L62)

本次改造聚焦 `MqConsumer` 的 MySQL 扣库。该路径纳入 Roadmap 第 3 项的零行检查。其他扣库调用、库存模式组合、Redis 预扣与回补、HTTP 重试和新增 `order_info` 与 `stock_log` 的持久关联，交由 Roadmap 对应任务处理。 [Roadmap](../roadmap.md)

## 3. 方案怎么工作

### 3.1 消费和确认顺序

```text
stock_topic 消息 {stockLogId=S001, itemId=6, amount=1}
  ↓ MqConsumer 校验消息，再调用独立 Spring 服务
StockConsumeService.consumeOnce(S001, 6, 1) 进入 MySQL 事务
  ↓ 核对 stock_log 里的 S001 和商品、数量、下单状态
  ├─ 插入 stock_consume_log 成功 → 扣 item_stock.stock → 提交
  ├─ 主键已存在 → 核对已提交的记录 → 结束事务
  └─ SQL 失败、扣库影响行数异常或数据不符 → 事务失败
事务服务正常返回 → CONSUME_SUCCESS
事务失败         → RECONSUME_LATER，按原因记录日志
```

`consumeOnce` 由 `MqConsumer` 通过 Spring 代理调用。代理在方法正常返回给消费者前提交事务。这样即使提交完成后进程退出，下一次投递也能读到 `stock_consume_log` 里的 `S001`。

### 3.2 表结构与扣库规则

新增一份增量 SQL `sql/upgrade-001-stock-consume-log.sql`。`stock_log_id` 与现有 `stock_log` 采用相同长度和排序规则。首版一笔成功扣库保留一行，记录保留期至少覆盖所有允许的重投和人工重放，本次保留全部记录。

```sql
CREATE TABLE IF NOT EXISTS stock_consume_log (
    stock_log_id VARCHAR(64) NOT NULL,
    item_id INT NOT NULL,
    amount INT NOT NULL,
    consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_general_ci;
```

1. `MqConsumer` 按 UTF-8 解析消息。要求 `stockLogId` 为非空字符串且不超过 64 字符，`itemId`、`amount` 为正整数且在 Java `int` 范围内。记录失败原因和可用的流水号、消息 ID、重试次数。
2. 新增一个可注入的 `StockConsumeService`。它在公开的 `consumeOnce` 上声明 `@Transactional(rollbackFor = Exception.class)`。消费记录 Mapper 和现有库存 Mapper 使用同一 `DataSource` 和事务管理器。按主键读 `stock_log`，要求 `status=2` 且 `item_id`、`amount` 与消息一致。
3. 对 `stock_consume_log` 执行普通 `INSERT`。只在这条 `INSERT` 周围捕获 `DuplicateKeyException`。主键冲突后用 `SELECT ... LOCK IN SHARE MODE` 读取同一个 `stock_log_id`，确认 `item_id` 和 `amount` 一致，再作为已消费返回。锁定读能看到最新已提交的记录，避免前面查询 `stock_log` 建立的旧事务快照影响判断。记录缺失或不匹配则报错。保留其他 SQL 异常，交给外层处理。验收时实测 MySQL 5.7、现有 JDBC 驱动和 MyBatis 是否把该冲突翻译成 `DuplicateKeyException`。 [MySQL 5.7 InnoDB 错误处理源码](https://github.com/mysql/mysql-server/blob/mysql-5.7.44/storage/innobase/row/row0mysql.cc#L699-L728) · [MySQL 锁定读说明](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)
4. `INSERT` 成功才调用现有 `ItemStockDOMapper.decreaseStock(itemId, amount)`。SQL 按 `item_id` 找到商品库存，要求 `stock >= amount`。只接受更新 1 行。0 行或其他行数都抛出错误，由事务回滚刚插入的 `stock_consume_log` 记录。 [现有扣库 SQL](../../src/main/resources/mapping/ItemStockDOMapper.xml#L126)

若同一流水同时到两个线程，第一次插入未完成时，另一个线程要等 MySQL 裁决。先插入的事务提交，后面的线程走重复分支。先插入的事务回滚，后面的线程可以取得插入机会。死锁、锁等待超时或提交结果未知时，重新执行整笔消费，由唯一键处理可能已提交的情况。

### 3.3 消费者的返回规则

- **确认为成功**。本次扣库事务已提交，或同一流水的已提交记录与消息匹配。整个批次都处理到可确认状态后返回 `CONSUME_SUCCESS`。
- **等下次再试**。连接故障、死锁、库存更新 0 行、提交结果未知等返回 `RECONSUME_LATER`。批次中任何一条失败都按批次重试。前面已提交的消息靠唯一主键安全重投。
- **留下异常证据**。格式错误、缺流水、商品或数量不一致、`stock_log` 状态异常等也返回失败，并按 RocketMQ 的有界重试进入死信队列（多次失败后暂存消息、供人工核查）。字段冲突按数据异常记录，库存更新 0 行按待核查记录。修正根因后再决定是否人工重放。 [RocketMQ 并发消费重试](https://rocketmq.apache.org/zh/docs/4.x/consumer/02push/#消息重试)
- **同步扣库模式**。`mq-async-sync=false` 在监听器入口跳过这段异步库存同步，确认消息，`stock_consume_log` 无新增记录。只在旧异步消息收敛后切换该开关。

现有正常下单发布的事务消息已有 `stockLogId`。旧的 `MqProducer.asyncReduceStock` 普通消息方法缺这个字段，仓库内上层方法没有活跃的业务调用。改造后，异步扣库收到这种消息走缺字段的失败分支。临时生成新流水号会让同一条旧消息每次都像新消息一样可扣库。 [旧发送方法](../../src/main/java/com/imooc/miaoshaproject/mq/MqProducer.java#L138) · [当前消费入口](../../src/main/java/com/imooc/miaoshaproject/mq/MqConsumer.java#L50)

独立表让 `stock_log` 继续记录下单结果，`stock_consume_log` 直接回答「这笔 MySQL 库存扣减有没有提交」。复用 `stock_log` 并增加独立同步状态也能实现同等的事务保证。这里沿用已选的新表方案，代价是每笔成功异步扣库增加一次数据库写入和一行持久记录。

## 4. 预计修改哪些文件

| 操作 | 文件 | 计划改动 |
| --- | --- | --- |
| 新增 | `sql/upgrade-001-stock-consume-log.sql` | 保存唯一一份增量建表语句。放在 `sql/` 顶层，首次初始化时排在 `miaosha.sql` 之后 |
| 新增 | `src/main/java/com/imooc/miaoshaproject/dataobject/StockConsumeLogDO.java` | 映射 `stock_log_id`、`item_id`、`amount`，`consumed_at` 由数据库写入 |
| 新增 | `src/main/java/com/imooc/miaoshaproject/dao/StockConsumeLogMapper.java`、`src/main/resources/mapping/StockConsumeLogMapper.xml` | 提供 `insert` 和 `selectByStockLogIdForShare`。复用已有 Mapper 扫描和 XML 路径 |
| 新增 | `src/main/java/com/imooc/miaoshaproject/service/impl/StockConsumeService.java` | `consumeOnce` 负责读取库存流水、唯一键判重、扣库存和事务提交 |
| 修改 | `src/main/java/com/imooc/miaoshaproject/mq/MqConsumer.java` | 移除 Redis 去重调用。解析消息、调用事务服务、按结果确认或请求重试。保留批次处理与原同步开关 |
| 新增 | `src/test/java/com/imooc/miaoshaproject/mq/MqConsumerTest.java` | 验证监听器的输入、返回值、批次失败以及同步开关 |
| 新增 | `src/test/java/com/imooc/miaoshaproject/service/impl/StockConsumeMySqlIT.java` | 在独立的真实 MySQL 5.7 库验证主键冲突、并发和事务回滚 |
| 更新 | `README.md`、`AGENTS.md`、`docs/develop/本机开发运行指南-Windows后端与Docker依赖.md` | 更新实际消费链路、Redis key 说明和已有库增量迁移入口 |

`ItemStockDOMapper.xml` 的条件扣减语句可以复用。消费者这次检查其返回值。当前配置已经扫描 `com.imooc.miaoshaproject.dao` 和 `classpath:mapping/*.xml`。 [Mapper 扫描](../../src/main/java/com/imooc/miaoshaproject/App.java#L7) · [配置](../../src/main/resources/application.yml#L4)

## 5. 按什么顺序实施

### 第 1 步：准备隔离环境并复现旧问题

- [ ] 在 JDK 8 下记录 Git 基线和已有工作区改动。用单独的测试库、Redis 和独占测试 Broker，开 `lua + mq-async-sync=true`。每轮用新流水号和库存充足的测试商品，从 `item_stock.stock=10` 开始，只下一单买 `1` 件。
- [ ] **漏扣复现**。在旧 `MqConsumer` 设置 Redis TTL 后、调用扣库 SQL 前，针对首次消费临时返回 `RECONSUME_LATER`。保留 1 小时 TTL。记录第二次相同 `stockLogId` 因标记存在跳过，`item_stock.stock` 仍为 `10`。
- [ ] **多扣复现**。旧代码把该场景的标记 TTL 临时改为 1 秒。首次扣库成功后、确认前临时返回 `RECONSUME_LATER`。确认重试到达前标记已经过期，记录同一个 `stockLogId` 两次扣减后 `item_stock.stock=8`。每轮保留首次与重投日志。实验改动单独撤回或留在隔离实验副本。
- **完成标志**。两轮分别有业务流水号、Redis 标记状态和 MySQL 前后快照，可复核是同一条消息的变化。

### 第 2 步：先建表，再完成数据库事务

- [ ] 应用增量 SQL。用 `SHOW CREATE TABLE stock_consume_log` 和 `SHOW CREATE TABLE item_stock` 核对 InnoDB、主键与商品索引。保证已有 `order_info`、`stock_log` 和库存数据不被初始化脚本覆盖。
- [ ] 实现 Mapper 和 `StockConsumeService.consumeOnce`。在真实 MySQL 上先跑首次消费、顺序重投、主键并发竞争、扣库更新 0 行与回滚测试。
- **完成标志**。一笔流水最多留下 1 条已提交的消费记录。扣库和记录对外同时生效，任何一项失败时，两项都能回滚。

### 第 3 步：接入 RocketMQ 并回归旧故障

- [ ] 接入新服务，移除 `MqConsumer` 对 `stock_log_synced_*` 的读写，保留 RocketMQ 启动、订阅和同步开关语义。
- [ ] 在真实 Broker 上验证「第一次写记录后失败，重投后扣成」以及「第一次提交后未确认，重投时跳过」。测试故障通过测试夹具或隔离实验副本触发，发布代码只保留正常消费逻辑。
- [ ] 更新运行文档。记录测试命令、退出码、测试数据、消息日志和查询结果，最后核对 Git diff。
- **完成标志**。两种故障恢复后，测试订单只增加 1 行，`item_stock.stock` 都为 `9`，`stock_consume_log` 对应流水都只有 1 行。

## 6. 如何测试、怎样算完成

真实 MySQL 测试沿用 JUnit 4 和现有依赖。用一个小 Spring 测试上下文装配真实 `DataSource`、事务管理器、MyBatis Mapper 和服务。测试按实际方式从代理外调用 `consumeOnce`，事务返回后使用独立连接查询结果。测试内部的故障注入可以包一层测试 Mapper，在真实 SQL 已完成后抛一次异常。这样能验证「库存已扣但事务还没提交」的路径。

| 场景 | 执行和通过标准 |
| --- | --- |
| 首次正常消费 | 流水 `S001`、商品 `6`、数量 `1`。`stock_consume_log` 新增 1 行，`item_stock.stock` 从 `10 → 9`，事务提交后返回成功 |
| 同一流水顺序和并发重投 | 分别重投同一消息 100 次，并发轮用有界线程池同时起跑。收敛后该流水记录仍为 1 行，库存仍为 `9`。另用不同 `stockLogId` 购买同一商品，确认该笔也能正常扣库 |
| 插入消费记录后、扣库前失败 | 注入一次异常。第一次返回失败，独立连接看到 0 条记录、库存 `10`。同一消息再处理后为 1 条记录、库存 `9` |
| 扣库后、事务提交前失败 | 真实库存更新成功后注入一次异常。独立连接看到记录数为 `0`、库存为 `10`，重试后才变为 `1` 条记录和库存 `9` |
| 事务已提交、确认遗漏 | 在实验 Broker 上让首次成功提交后的监听器返回一次 `RECONSUME_LATER`，模拟确认失败。重投的 `stockLogId` 相等，库存保持 `9`，消费记录仍为 1 行 |
| 扣库更新 0 行 | 让库存不足或商品库存行不存在。消费服务抛错，记录回滚，监听器不确认成功 |
| 消息错误或流水冲突 | 缺流水号、数量非法、`stock_log` 状态异常或载荷不一致，均不扣库、不返回成功，有足够日志定位。重复分支同样核对消费记录的商品和数量 |
| 批次部分失败及同步模式 | 一条已提交、一条失败时整批可重投，成功那条不再扣。`mq-async-sync=false` 时监听器不调用库存消费服务 |

执行时使用以下 Maven 入口。`StockConsumeMySqlIT` 采用显式运行，计划从 `MIAOSHA_STOCK_IT_JDBC_URL`、`MIAOSHA_STOCK_IT_USERNAME`、`MIAOSHA_STOCK_IT_PASSWORD` 读取专用测试库连接，缺项直接报错。先用 `SELECT DATABASE()` 核对实际连接的是专用库（建议名 `miaosha_stock_consume_it`）。每个场景用自己的测试商品和流水号，只清理该场景创建的记录。测试库连接配置写入本机运行指南，实际凭证由执行环境提供。

```powershell
mvn '-Dtest=MqConsumerTest' test
mvn '-Dtest=StockConsumeMySqlIT' test
mvn clean package
```

- [ ] 新旧两轮对比记录了 `stockLogId`、消息处理结果、`item_stock.stock` 和实际消费记录。抽查真实下单时同时比对 Redis 库存及 `order_info` 增量。
- [ ] 正常、重投和失败的库存变化符合上表。尤其保留「扣库后抛错」「提交后重投」两份可复核证据。
- [ ] Maven 构建、原有回归与真实 MySQL 及 MQ 验证结果分别记录。吞吐留给实际压测。

## 7. 风险与回滚

- **已有消息**。旧的 `stock_log.status=2` 只能说明下单阶段成功，旧 Redis 标记也可能写了但未扣库。两者都无法作为回填 `stock_consume_log` 的凭证。首次验收在隔离环境从新 Topic 和新流水开始。把历史消息重放到空的新消费表，会让已扣过的老消息再扣一次。
- **需要切换现有环境时**。另行确认旧消息边界。先冻结下单，核对旧 Topic、消费组位点、延迟重试、待决半消息和死信。仍无法判断是否扣过的流水留待对账。若新旧两代消息需要并存，用独立 Topic 加独立消费组，或用独占 Broker。当前消费组写死为 `stock_consumer_group`，单独改 Topic 不足以隔离旧重试队列。
- **需要回退时**。先停新增入口和消费者，保留现有消息及 `stock_consume_log` 供核查。新版已经成功扣库的消息若交给旧消费者重投，旧代码会绕过新表去重。可在独立测试环境回退应用复现；真实环境的优先处理是暂停消费、依据新表核对进度，再修复后恢复。保留增量表可同时保留处理证据。

## 8. 完成后怎么用

1. **首次创建 Docker 测试库**。现有脚本和 Compose 已将 `sql/` 挂进 MySQL 初始化目录。确认首次初始化按文件名先执行 `miaosha.sql`，再执行增量脚本，用 `SHOW CREATE TABLE stock_consume_log` 验证结果。手动初始化的新库同样按这个顺序运行。
2. **已有测试库**。确认当前库名，只执行增量 SQL。若沿用本机开发指南中已挂载的 `sql/`，可以进入该测试库的 MySQL 客户端执行 `SELECT DATABASE();`，再运行 `SOURCE /docker-entrypoint-initdb.d/upgrade-001-stock-consume-log.sql`。含 `DROP TABLE` 的 `miaosha.sql` 仅用于可重建的库。 [本机开发运行指南](../develop/本机开发运行指南-Windows后端与Docker依赖.md#43-创建-mysql-并初始化测试库)
3. 按运行指南启动后端，从原有下单入口 `POST /order/createorder` 下单。在消费日志里取本轮 `stockLogId`，分别核对 `stock_log`、`stock_consume_log` 和 `item_stock`。若验证 `order_info`，在隔离环境以本轮订单 ID 或前后增量检查。

```sql
SELECT stock_log_id, item_id, amount, status FROM stock_log WHERE stock_log_id = 'S001';
SELECT stock_log_id, item_id, amount, consumed_at FROM stock_consume_log WHERE stock_log_id = 'S001';
SELECT item_id, stock FROM item_stock WHERE item_id = 6;
```

执行交付包括增量 SQL、消费者与事务服务、测试和对应的 Build Log。使用实测的测试流水号替换查询示例 `S001`。

## 9. 供人工审查

- 审查消费事务是否通过 Spring 代理，主键冲突分支是否核对载荷，扣库更新 0 行是否回滚。
- 审查旧消息切换和回退边界。首次验收按本机隔离环境准备，具体测试库、Broker 地址执行时核定。
- 当前状态为待审查。收到明确按本计划实施的请求后，开始代码和测试改造。
