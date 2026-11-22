> 保存于 2026-11-21 08:53（Asia/Shanghai）

# 订单异常回滚修复与复测步骤

本次已将 [OrderServiceImpl.createOrder()](../../src/main/java/com/imooc/miaoshaproject/service/impl/OrderServiceImpl.java) 的注解修改为：

```java
@Transactional(rollbackFor = Exception.class)
```

它修复的是本轮已复现的问题：写入订单后抛出 `BusinessException`，数据库仍提交。生产代码本次只改这一行；原有临时故障注入保留，方便直接复测。没有修改Producer、Consumer、补偿重试、数据库结构或运行配置。

## 1. 已经执行了哪些验证

新增 [OrderTransactionTest.java](../../src/test/java/com/imooc/miaoshaproject/service/impl/OrderTransactionTest.java)，使用真实的 `OrderServiceImpl` 和Spring事务代理，在调用订单写入后、增加销量时分别抛出业务异常和运行时异常，检查事务决定及已登记的回补回调。

| 验证 | 实际结果 |
| --- | --- |
| 修复前，业务异常 | 测试失败，观察到 `begin → insert → commit`，没有回补 |
| 修复前，运行时异常 | 测试通过，走回滚并调用回补 |
| 修复后，业务异常 | 测试通过，`begin → insert → rollback → restore` |
| 修复后，运行时异常 | 测试通过，同样回滚并调用一次回补 |
| 原有配置测试 | 3项通过 |

修复后总计5项通过，失败0项，错误0项。测试也检查了原异常继续向外传播。

这些测试没有连接真实MySQL、Redis或RocketMQ。`insert`、`restore` 记录的是对相应协作者的调用，事务管理器记录的是Spring要求提交还是回滚；真实数据库撤销、Redis回补和MQ状态仍需要下面的本机手工验证。

普通环境可在JDK8下运行：

```powershell
mvn -Dtest=OrderTransactionTest test
mvn test
```

本次执行环境遇到了Maven缓存仓库ID不一致，以及Surefire子进程启动失败，最终使用了工作区 `target/order-rollback-test-settings.xml` 中的临时离线配置，并在Maven进程内运行测试。实际通过的命令为：

```powershell
mvn -o -gs target/order-rollback-test-settings.xml -s target/order-rollback-test-settings.xml -DforkCount=0 test
```

没有修改全局Maven配置或新增项目依赖。这个临时文件位于构建目录，`mvn clean` 后会消失；它不属于业务修复要求。

## 2. 重启后端，使用最新源码

停止旧的Java后端，再按你已经配好的运行方式启动。在已设置数据库、Redis、MQ连接环境变量的PowerShell窗口，可以执行：

```powershell
Set-Location D:\workspace\dianshangmiaosha
$env:JAVA_HOME = 'D:\software\jdk-8'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn spring-boot:run
```

若使用IDEA，重新构建并重启。如果使用 `java -jar`，先重新成功打包再启动，已有JAR不会自动包含这次源码修改。不要同时运行两份后端。

保持本次测试组合：`stock-mode=lua`、`mq-async-sync=true`、`queue-discharge=true`、`verify-code=false`。

## 3. 先记录本轮基线

只在本机专用测试环境进行。暂停其他下单，等旧消息处理完成，确认商品6的活动有效、Redis及数据库库存充足。测试过程中不要重新发布活动覆盖Redis库存。

在PowerShell登录MySQL：

```powershell
docker exec -it miaosha-mysql mysql -uroot -p miaosha
```

执行并保存输出：

```sql
SELECT stock FROM item_stock WHERE item_id = 6;
SELECT sales FROM item WHERE id = 6;
SELECT COUNT(*) AS order_count, COALESCE(SUM(amount), 0) AS ordered_amount
FROM order_info WHERE item_id = 6 AND promo_id = 1;
SELECT stock_log_id, status FROM stock_log WHERE item_id = 6;
```

另开窗口连接Redis，输入现用密码：

```powershell
docker exec -it miaosha-redis redis-cli --askpass -n 10
```

查询并保存：

```text
GET promo_item_stock_6
```

你的旧实验结束值是：MySQL库存68、销量145、订单6、Redis库存67。仅当这些数据此后没有变化时，才能继续把它们当本轮基线。修改注解不会修复旧实验已经留下的差异。

## 4. 保留故障注入，只点击一次下单

当前源码仍在更新流水状态2之后，对“商品6、购买1件”抛出临时业务异常。这正是要复测的故障点。

浏览器登录，进入商品6，打开开发者工具的Network，点击一次下单。记录 `/order/createorder` 的JSON响应，以及后端控制台输出的：

```text
STEP2_REPRO orderId=本次订单号, stockLogId=本次流水号
```

**仍然显示下单失败是预期结果。** 此时要验证的是失败后数据有没有撤销，而不是页面是否改成成功。

如果控制台没有本次 `STEP2_REPRO`，而页面提示令牌、活动、限流或库存错误，说明请求没有到达故障点，这次不能算回滚验证通过。

## 5. 故障发生后立即核对数据

先不要继续下另一单。在MySQL中替换下面的两个占位符：

```sql
SELECT * FROM order_info WHERE id = '本次打印的orderId';
SELECT stock_log_id, item_id, amount, status
FROM stock_log WHERE stock_log_id = '本次打印的stockLogId';
```

第一条应返回0行，第二条应保留一条状态3的失败流水。流水本身在下单事务之前单独创建，因此不会整条消失。

然后重查第3节的库存、销量、订单统计和Redis库存，与本轮基线比较：

| 检查项 | 通过标准 |
| --- | --- |
| 接口JSON业务状态 | fail |
| 本次打印的订单 | 查不到 |
| 商品销量 | 不变 |
| 活动订单数及购买数量总和 | 不变 |
| MySQL库存 | 不变 |
| Redis库存 | 预扣后回补，最终与本轮基线一致 |
| 本次失败流水 | 最终状态3 |

如果本轮基线还是旧实验结束值，那么测试后应仍然是68、145、6、67，而不是强求Redis恢复到旧实验开始前的68。

订单号序列也在订单事务中，回滚后，后续正常单可能使用刚才未保存的号码。因此应在这次失败后立即查，避免下一次正常订单影响判断。

如果订单仍然新增，先确认后端确实运行最新构建；如果订单已回滚但Redis没有恢复，检查回补日志和Redis连接。两者都不能只凭页面显示失败就认定通过。

## 6. 回滚验证通过后，删除临时注入，再测正常单

在 `OrderServiceImpl.createOrder()` 删除包含 `STEP2_REPRO` 和 `throw new BusinessException(...)` 的整段临时 `if (itemId == 6 && amount == 1)`，保留新的事务注解。重新构建并重启后端。

重新记录一次基线，再购买1件。接口应返回success；等待正常消费完成后，本轮订单数和销量各增加1，Redis与MySQL库存各减少1。本次新增流水应为2。

正常流水没有时间字段，UUID也不能代表新旧顺序；可对比第3节保存的流水ID列表找出本次新增记录。不要按UUID排序猜“最新流水”。

旧实验差异仍需单独核查；正常单也按本轮增量判断，不以两边库存必须立刻消除历史差异作为通过条件。

## 7. 运行时异常测试的边界

离线回归已覆盖运行时异常。如果还要手工验证，可在相同故障点暂时改抛 `IllegalStateException`，重新构建启动后重复基线和数据核查。数据库应回滚，Redis正常时本轮预扣应恢复。

但当前Producer只捕获 `BusinessException`。运行时异常不会走它标记流水3的分支，可能留下状态1流水和MQ `UNKNOW`，这属于Roadmap第二步第4项尚未修复的状态处理边界。不要用“必须状态3”判断运行时异常这条路径，也不要据此把所有异常都强制改成MQ回滚。

手工实验结束后删除临时异常代码，并保留实验记录。本次交付尚未执行真实MySQL、Redis和MQ故障复测，也没有修复旧实验数据或其他MQ、回补问题。
