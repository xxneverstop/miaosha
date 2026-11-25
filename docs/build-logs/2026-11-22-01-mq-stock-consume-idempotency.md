---
plan_id: "2026-11-22-01-mq-stock-consume-idempotency"
title: "MQ 库存消费幂等实施记录"
status: completed
executed_at: "2026-11-23"
plan_path: "docs/plans/2026-11-22-01-mq-stock-consume-idempotency.md"
base_commit: "24be56a7f75781427b56d255bdc9b5f8b6b89b0d"
result_commit: "not-created"
---

# MQ 库存消费幂等构建记录

> 文档重写于 2026-11-23 16:17（北京时间）。本次实施日期为 2026-11-23。

## 1. 这次修了什么

秒杀项目接到一笔订单后，先占用 Redis 里的可售库存，再由后台根据 RocketMQ 消息扣减 MySQL 库存。假设商品原来有 10 件，用户只买 1 件，MySQL 最后应剩 9 件。旧代码在两种重试场景下分别剩了 10 件和 8 件。

这次把**登记已扣库存的记录**和**实际扣 MySQL 库存**放进同一个数据库事务。这样，只要同一个库存流水号的扣减已提交，这条消息再送一次，库存仍是 9。这个性质叫**消费幂等**。代码和隔离环境验收均已完成。执行前的分支是 `master`，基线是 `24be56a`，改动保存在工作区。 [实施计划](../plans/2026-11-22-01-mq-stock-consume-idempotency.md) · [15 个文件的完整改动](2026-11-22-01-mq-stock-consume-idempotency-changes.md)

## 2. 跟着一笔订单走

以商品 6 买 1 件为例，假设这次占库存的流水号是 `S001`。流程对应当前演示配置 `stock-mode=lua`、`mq-async-sync=true`。

~~~text
POST /order/createorder
  → 写 stock_log，登记本次库存流水 S001
  → MqProducer 先发一条暂不可消费的消息，再执行下单
  → Redis 库存 10 → 9，MySQL 写订单、更新销量，stock_log.status → 2
  → 下单成功后，RocketMQ 将库存消息交给 MqConsumer
  → StockConsumeService 在同一事务内登记 S001 并把 MySQL 库存 10 → 9
~~~

| 环节 | 这一笔留下什么 |
| --- | --- |
| 下单入口收到商品和购买数量 | `OrderController` 调用 `ItemService.initStockLog`，生成 `stockLogId`。这是这一笔库存操作的编号。重试消费时继续使用消息里的这个编号。 |
| 下单事务完成 | `order_info` 新增订单，`stock_log.status=2` 说明订单已提交。Redis 此时已扣可售库存，MySQL 的 `item_stock.stock` 还可能在等后台同步。 |
| 后台收到扣库消息 | 消息带着 `stockLogId=S001`、`itemId=6` 和 `amount=1`。`stock_topic` 是库存消息的主题。RocketMQ Broker 是负责存储和投递这些消息的服务端。 |
| MySQL 扣减完成 | `stock_consume_log` 留下一条已提交的消费记录，说明这笔后台扣减已完成。之后遇到同一个流水号时，消费者可以据此判重。 |

生产者先发送的消息叫**半消息**。RocketMQ 等生产者确认下单成功，再把它交给消费者。本次变动位于最后的 MySQL 扣库环节。 [下单入口](../../src/main/java/com/imooc/miaoshaproject/controller/OrderController.java#L149) · [生产者](../../src/main/java/com/imooc/miaoshaproject/mq/MqProducer.java#L59) · [下单写库](../../src/main/java/com/imooc/miaoshaproject/service/impl/OrderServiceImpl.java#L149)

## 3. 旧代码哪里出错，新代码如何处理

旧的 `MqConsumer` 收到消息后，先用 `SETNX` 写入 Redis 去重键，再设置 TTL，然后扣 MySQL 库存。`SETNX` 表示键不存在时才写入，TTL 是键的有效时间。后续收到同一笔时，旧代码看到键还在就跳过扣库。

- **漏扣。** Redis 去重键已写，MySQL 尚未扣库时发生故障。重投看到键还在，直接跳过，MySQL 库存停在 10。
- **多扣。** 第一次已经把 MySQL 库存扣到 9。等 Redis 去重键过期后，同一笔消息又到达，旧代码再扣一次，库存变成 8。

新版的 `MqConsumer` 负责解析消息、检查字段，再把扣库存交给 `StockConsumeService.consumeOnce`。服务先到 `stock_log` 核对流水已提交，并核对商品和数量。然后按下面的结果处理。

| 遇到的情况 | MySQL 怎么处理 | 消费者怎么回复 |
| --- | --- | --- |
| 第一次处理，扣库存成功 | 插入 `stock_consume_log`，扣减 `item_stock`。两个动作一起提交。 | 事务提交后返回 `CONSUME_SUCCESS`，表示本轮处理完成。 |
| 库存不足，或写入中途报错 | 扣库更新 0 行也算失败。事务回滚，本轮消费记录和库存修改一起撤销。 | 返回 `RECONSUME_LATER`，让 RocketMQ 稍后再试。 |
| 上次扣库已经提交，消息再次到达 | `stock_log_id` 是主键，同一流水只能插入一次。遇到重复时读取已提交的记录，核对商品和数量一致后直接结束。 | 返回 `CONSUME_SUCCESS`，库存保持原值。 |
| 消息无效、流水不匹配或处理异常 | 拒绝确认这笔扣减。若批次中有消息失败，这轮回调请求重试。 | 失败原因写入日志。重试耗尽后，Broker 把消息移入死信队列，供人工核查。 |

查询重复记录时，实现在 MySQL 中用 `LOCK IN SHARE MODE` 读取最新已提交的行。这样，即使这一轮事务之前读取过 `stock_log`，也能查到另一个并发事务刚提交的记录。`StockConsumeService` 的事务由 Spring 管理，消费者等调用成功返回才确认。在配置 `mq-async-sync=false` 时，下单侧执行同步扣库，消费者跳过这段异步处理。 [消费者](../../src/main/java/com/imooc/miaoshaproject/mq/MqConsumer.java) · [库存事务服务](../../src/main/java/com/imooc/miaoshaproject/service/impl/StockConsumeService.java) · [Mapper SQL](../../src/main/resources/mapping/StockConsumeLogMapper.xml)

## 4. 实际验收结果

### 用真实 RocketMQ 对照旧版和新版

每轮只买 1 件。下单前 MySQL 库存为 10。下单后 Redis 库存都是 9，每轮在 `order_info` 都新增一条订单。表中的记录数指该流水在 `stock_consume_log` 里的行数。

| 场景 | 本轮 stockLogId | 首次处理后 | 同一消息重投后 |
| --- | --- | --- | --- |
| 旧版写 Redis 标记后、扣 MySQL 前请求重试 | `1c009a04ae0142c48b8c5147b1bb7453` | 库存 10，记录 0 | 库存 **10**，记录 0。标记还在，旧代码跳过了扣库。 |
| 旧版扣库后请求重试，标记 TTL 在测试中缩到 1 秒 | `a0a703feaf524cf09627befbb94cc5e0` | 库存 9，记录 0 | 库存 **8**，记录 0。标记过期后重新写入，旧代码再次扣库。 |
| 新版插入消费记录后抛错 | `ad435636a738477d84a1a7c5190e3b3a` | 库存 10，记录 0。消费事务已回滚。 | 库存 **9**，记录 **1**。重试完成扣库。 |
| 新版扣库已提交后故意返回一次 `RECONSUME_LATER` | `e763497f4bfe4370ab524bb3f5300d3a` | 库存 9，记录 1 | 库存 **9**，记录 **1**。已提交的记录挡住了再次扣库。 |

最后一轮还收到上一轮同一消费组的重投，已提交的消费记录仍能挡住重复扣库。四轮实验使用本机日志目录里的 `broker_probe.py` 构建隔离的故障注入副本，并提交真实 HTTP 订单。每轮 `app.log` 和 `result.json` 保存在 `logs/stock-consume-it-20261123/`。本次通过主动请求重试，在真实 Broker 上验证重投。实际网络确认丢失和 Broker 崩溃场景尚未运行。

### 自动化与部署检查

| 实际运行 | 结果 |
| --- | --- |
| JDK 8 下运行 `mvn -o '-DforkCount=0' clean package` | 9 个常规测试通过，生成可执行 JAR。 |
| `mvn -o '-DforkCount=0' '-Dtest=StockConsumeMySqlIT' test` | 7 个真实 MySQL 用例通过。同编号顺序重放 100 次和并发处理 100 次均只扣一次。插入记录后失败、扣库后失败和更新 0 行均回滚。 |
| 使用 Git Bash 运行 `python scripts/tests/verify-deployment.py` | 29 项检查通过，包括增量 SQL 能进入部署包、缺少该文件时保留旧部署包。 |
| 补完代码注释后运行 `mvn -o '-DforkCount=0' test` | 9 个常规测试再次通过，其中 4 个是消费者用例。真实 MySQL 与 Broker 的结果沿用上面记录的实验。 |

Windows 上默认的 Surefire 测试子进程启动失败，验证时改用 `forkCount=0`，让测试在 Maven 的 JVM 内运行。第一次运行的 Spring DEBUG 日志输出了进程环境。已清理对应的本地日志和报告，复验移除了相关凭证变量，并将测试日志级别设为 WARN。已单独通知用户轮换暴露的令牌。

## 5. 测试用到哪些资源，最后如何清理

| 资源 | 写入或操作 | 验收后的状态 |
| --- | --- | --- |
| **MySQL 5.7.44** | 用专用容器 `miaosha-stock-it-mysql` 对外绑定 `127.0.0.1:23306`。只读挂载项目 SQL，初始化 `miaosha_stock_consume_it`。集成用例创建本轮的商品库存和流水。真实下单使用隔离的测试账号，调整了活动 1 的时间窗口和商品 6 的库存，写入订单、销量、流水、消费记录及序列变化。 | 集成用例按本轮 ID 删除自己创建的行。隔离库重跑增量 SQL 前后，订单数、库存流水数、消费记录数和商品 6 的库存分别保持 `8、17、2、9`。验收后停止专用容器并清理匿名数据卷。 |
| **Redis 5.0** | 专用容器 `miaosha-stock-it-redis` 只绑定 `127.0.0.1:26379`。应用使用 DB 10 写入测试登录状态、令牌、发放令牌的计数及可售库存。旧版实验还写入了 `stock_log_synced_*`。 | 四轮 Redis 库存均从 10 降到 9。两轮新版没有为实验流水创建旧去重键。验收后移除专用容器和匿名数据卷。 |
| **RocketMQ 4.9.4** | 起独立的 NameServer 和 Broker。NameServer 提供 Broker 的路由信息。测试使用端口 `29876`、`21911`、`21909`。每轮建一个测试 Topic，在固定消费组下投递、重试并推进消费位点。 | 已移除专用 NameServer、Broker 和 `miaosha-stock-it-net` 网络。测试 Topic、消息和消费进度随专用环境清理。 |
| **应用与文件** | 用 JDK 8 在 `28091` 启动测试应用。部署检查用临时目录里的模拟 `.env`、Maven 和部署包，Compose 只做配置检查。 | 测试应用已停止。保留本地实验结果和可执行 JAR。部署脚本未主动删除模拟数据目录，路径见 `deployment-verification.log` 的 `fixture`。 |

以上写入只发生在专用测试资源中。容器以 `--rm` 运行，退出时清理本次使用的匿名卷。 [Docker 匿名卷清理](https://docs.docker.com/engine/storage/volumes/#remove-anonymous-volumes)

## 6. 部署和回退时要核对什么

已有库先执行增量建表 SQL，并检查旧消息的消费进度。`stock_log.status=2` 能确认订单提交，旧 Redis 键能看到当时写过标记。历史 MySQL 扣减是否成功，需要结合其他可核对的记录判断。直接让空的新消费表接手历史重放，有可能再扣一次已经扣过的库存。

变更消费模式或回退时，先暂停下单和消费者，核对 Broker 的在途消息、重试消息、半消息和消费位点，再确定哪一版消费者可以接手。保留 `stock_consume_log` 作为核对依据，尤其要隔离新版已经扣过、旧版可能再次处理的消息。 [部署与切换说明](../../README.md#配置开关)

本次验收限定在异步消费扣库。生产切换、性能压测和重试耗尽实验尚未执行。Redis 预扣的回补策略、同步库存模式和重复 HTTP 请求按各自的任务处理。

## 7. 人工测试与验收

### A. 检查现有测试库迁移

1. 在该测试库的 `mysql>` 提示符执行 `SELECT DATABASE();`。结果须是本次要升级的库。
2. 容器内客户端执行 `SOURCE /docker-entrypoint-initdb.d/upgrade-001-stock-consume-log.sql`。Windows 上直接运行的 `mysql.exe` 执行 `SOURCE D:/workspace/dianshangmiaosha/sql/upgrade-001-stock-consume-log.sql`。基础脚本带有删表语句，现有库只运行增量。
3. 执行 `SHOW CREATE TABLE stock_consume_log;`。通过标准是 `ENGINE=InnoDB`，`stock_log_id` 为主键，另有 `item_id`、`amount`、`consumed_at`。迁移前后的现有订单行数、库存流水行数和库存值相同。 [本机运行指南](../develop/本机开发运行指南-Windows后端与Docker依赖.md#43-创建-mysql-并初始化测试库)

### B. 跑真实 MySQL 并发和回滚测试

在仓库根目录开一个新的 PowerShell 7 窗口。选一个空闲本机端口，以下用 `23306`。容器使用独立名称和匿名数据卷。首次启动会读取挂载的两份 SQL。

```powershell
$fixtureSql = (Resolve-Path -LiteralPath 'sql').Path
$env:JAVA_HOME = 'D:\software\jdk-8'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:MIAOSHA_STOCK_IT_JDBC_URL = 'jdbc:mysql://127.0.0.1:23306/miaosha_stock_consume_it?useSSL=false'
$env:MIAOSHA_STOCK_IT_USERNAME = 'root'
$env:MIAOSHA_STOCK_IT_PASSWORD = Read-Host '为新测试库设置临时密码' -MaskInput
$env:MYSQL_ROOT_PASSWORD = $env:MIAOSHA_STOCK_IT_PASSWORD
try {
    docker run --rm -d --name miaosha-stock-manual-mysql -p '127.0.0.1:23306:3306' --env MYSQL_ROOT_PASSWORD --env 'MYSQL_DATABASE=miaosha_stock_consume_it' --mount "type=bind,source=$fixtureSql,target=/docker-entrypoint-initdb.d,readonly" mysql:5.7 --sql-mode=NO_ENGINE_SUBSTITUTION
    if ($LASTEXITCODE -ne 0) { throw '专用 MySQL 容器启动失败' }
} finally {
    Remove-Item Env:MYSQL_ROOT_PASSWORD -ErrorAction SilentlyContinue
}
```

等容器完成初始化后，用 `docker exec -it miaosha-stock-manual-mysql mysql -uroot -p miaosha_stock_consume_it` 登录，执行 `SELECT DATABASE();` 和 `SHOW CREATE TABLE stock_consume_log;` 确认连接与结构。退出客户端，运行下面的命令。将示例 JDK 路径替换为本机的 JDK 8 安装路径。

```powershell
mvn '-Dtest=StockConsumeMySqlIT' test
```

通过标准是 7 个用例全部通过。新版对同一 ID 顺序重放和并发重放后只保留一条消费记录、库存只扣一次。插入记录后失败、扣库后失败、扣库更新 0 行时，事务回滚后库存和记录都可以安全重试。Windows 如果 Surefire 子进程启动失败，使用 `mvn '-DforkCount=0' '-Dtest=StockConsumeMySqlIT' test`。

如果只跑 MySQL 测试，到这里可以用 `docker stop miaosha-stock-manual-mysql` 清理专用容器，并关闭测试终端清除连接环境。继续做 C 时，等验收完成再停止。

### C. 下单并人工重放同一笔库存消息

1. 在 B 的基础上，按本机指南起一套独立 Redis 和 RocketMQ 环境。使用隔离的 Broker 和新 Topic，例如 `stock_consume_manual`。App 设置 `stock-mode=lua`、`mq-async-sync=true`、专用的 `SPRING_DATASOURCE_*`、`SPRING_REDIS_*` 和 `MQ_NAMESERVER_ADDR`，`MQ_TOPICNAME` 指向该 Topic。使用指南的注册、登录、发布活动和下单步骤。 [本机指南第 4 至 7 节](../develop/本机开发运行指南-Windows后端与Docker依赖.md#4-首次创建-docker-依赖)
2. 在隔离库中把商品 6 的 `item_stock.stock` 设置为 10，确保活动 1 正在进行，发布活动一次，使 Redis 的 `promo_item_stock_6` 也为 10。记录这次之前的 `stock_log` 流水和 `order_info` 中商品 6 的订单数。只提交一张买 1 件的订单，比较前后的流水列表找到新的 `stock_log_id`。等到 MySQL 库存为 9，且该流水已出现一条 `stock_consume_log` 记录。
3. 在 PowerShell 中把下面的容器名、容器内 NameServer 地址、Topic 和流水号设成刚才这笔测试数据。使用完全相同的流水号、商品和数量重发一次扣库载荷。

```powershell
$testBroker = '本次的测试 Broker 容器名'
$testNameServer = '本次的测试 NameServer 容器名:9876'
$testTopic = 'stock_consume_manual'
$stockLogId = Read-Host '输入本轮 stock_log_id'
$body = @{stockLogId=$stockLogId; itemId=6; amount=1} | ConvertTo-Json -Compress
$body | docker exec -i $testBroker sh -c 'IFS= read -r body; exec sh mqadmin sendMessage -n "$1" -t "$2" -p "$body"' sh $testNameServer $testTopic
docker exec $testBroker sh mqadmin consumerProgress -n $testNameServer -g stock_consumer_group
```

4. 看到 `SEND_OK` 后，找到发送结果对应的 Topic 和队列。重复查询消费组进度，等 `Consumer Offset` 大于该消息的 `queueOffset`，再查询 `item_stock`、`stock_consume_log` 和 `order_info`。通过标准是库存保持 9、该流水的消费记录仍只有一行、本轮订单增量仍为一行。消息 ID 会变，业务流水号保持一致。这个操作验证人工重放业务载荷。上面的实测记录另外覆盖了 Broker 自己安排的重试。 [RocketMQ 4.9.4 发送命令源码](https://github.com/apache/rocketmq/blob/rocketmq-all-4.9.4/tools/src/main/java/org/apache/rocketmq/tools/command/message/SendMessageCommand.java#L43-L55)
5. 记录重放前后库存、消费行和位点。结束测试 App，停止自己创建的测试容器，并移除专用网络。

本节记录的是人工复验方法。补充记录时只重跑了常规 Maven 测试，C 的手工重放命令用 Context7 和 4.9.4 源码核对。

## 8. 从哪里读代码和改动

1. 从 `MqProducer.transactionAsyncReduceStock` 看流水号、商品 ID 和数量怎么进入库存消息，接着读 `OrderServiceImpl.createOrder`，看到订单提交与后台扣库是两个阶段。 [MqProducer.java](../../src/main/java/com/imooc/miaoshaproject/mq/MqProducer.java) · [OrderServiceImpl.java](../../src/main/java/com/imooc/miaoshaproject/service/impl/OrderServiceImpl.java)
2. 从 `MqConsumer.consumeMessage` 看哪里校验、确认和重试，再读 `StockConsumeService.consumeOnce` 看主键判重和事务。`StockConsumeLogDO` 承载记录字段。Mapper 接口与 XML 把 Java 调用对应到插入和锁定读 SQL。增量 SQL 负责建表。 [MqConsumer.java](../../src/main/java/com/imooc/miaoshaproject/mq/MqConsumer.java) · [StockConsumeService.java](../../src/main/java/com/imooc/miaoshaproject/service/impl/StockConsumeService.java) · [StockConsumeLogDO.java](../../src/main/java/com/imooc/miaoshaproject/dataobject/StockConsumeLogDO.java) · [Mapper 接口](../../src/main/java/com/imooc/miaoshaproject/dao/StockConsumeLogMapper.java) · [Mapper SQL](../../src/main/resources/mapping/StockConsumeLogMapper.xml) · [增量 SQL](../../sql/upgrade-001-stock-consume-log.sql)
3. 用消费者单测对照返回值，用 MySQL 集成测试对照数据库提交和回滚。打包脚本和部署检查补录了增量 SQL，测试日志配置只进入测试类路径。文档和计划的变更也在完整改动里。 [消费者单测](../../src/test/java/com/imooc/miaoshaproject/mq/MqConsumerTest.java) · [MySQL 集成测试](../../src/test/java/com/imooc/miaoshaproject/service/impl/StockConsumeMySqlIT.java) · [打包脚本](../../package.bat) · [部署检查](../../scripts/tests/verify-deployment.py) · [测试日志配置](../../src/test/resources/logback-test.xml) · [完整改动](2026-11-22-01-mq-stock-consume-idempotency-changes.md)
