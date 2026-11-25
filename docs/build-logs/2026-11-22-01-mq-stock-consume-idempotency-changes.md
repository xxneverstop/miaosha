# MQ 库存消费幂等完整改动

对比基线为 `24be56a`。本次修改 7 个文件，新增 8 个文件，共 +677 / −83 行。 [构建记录](2026-11-22-01-mq-stock-consume-idempotency.md)

每个文件下方保留完整的变更片段。`+` 是新增行，`-` 是删除行，`@@` 后的数字是改动位置。

[消费代码与数据库](#消费代码与数据库) · [测试与部署](#测试与部署) · [项目文档](#项目文档)

## 消费代码与数据库

### MqConsumer.java

**修改 · +69 / −47**。移除 Redis 去重，改为调用库存事务服务。 [文件](../../src/main/java/com/imooc/miaoshaproject/mq/MqConsumer.java)

<details>
<summary>展开逐行改动</summary>

~~~diff
@@ -1,30 +1,40 @@
 package com.imooc.miaoshaproject.mq;
 
-import com.alibaba.fastjson.JSON;
-import com.imooc.miaoshaproject.dao.ItemStockDOMapper;
+import com.fasterxml.jackson.core.JsonParser;
+import com.fasterxml.jackson.databind.DeserializationFeature;
+import com.fasterxml.jackson.databind.JsonNode;
+import com.fasterxml.jackson.databind.ObjectMapper;
+import com.imooc.miaoshaproject.service.impl.StockConsumeService;
+import org.apache.commons.lang3.StringUtils;
 import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
 import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
 import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
 import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
 import org.apache.rocketmq.client.exception.MQClientException;
 import org.apache.rocketmq.common.message.MessageExt;
-import org.springframework.beans.factory.annotation.Autowired;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
 import org.springframework.beans.factory.annotation.Value;
-import org.springframework.data.redis.core.RedisTemplate;
 import org.springframework.stereotype.Component;
 
 import javax.annotation.PostConstruct;
+import java.io.IOException;
+import java.nio.ByteBuffer;
+import java.nio.charset.StandardCharsets;
 import java.util.List;
-import java.util.Map;
-import java.util.concurrent.TimeUnit;
 
-/**
- * Created by hzllb on 2019/2/23.
- */
 @Component
-public class MqConsumer {
+public class MqConsumer implements MessageListenerConcurrently {
 
-    private DefaultMQPushConsumer consumer;
+    private static final Logger LOGGER = LoggerFactory.getLogger(MqConsumer.class);
+    // 拒绝有歧义的 JSON，并从解析异常的位置描述中移除原始消息。
+    private static final ObjectMapper MESSAGE_MAPPER = new ObjectMapper()
+            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
+            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
+            .disable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION);
+
+    private final StockConsumeService stockConsumeService;
+    private final boolean mqAsyncSync;
 
     @Value("${mq.nameserver.addr}")
     private String nameAddr;
@@ -32,51 +42,63 @@ public class MqConsumer {
     @Value("${mq.topicname}")
     private String topicName;
 
-    @Autowired
-    private ItemStockDOMapper itemStockDOMapper;
-
-    @Autowired
-    private RedisTemplate redisTemplate;
-
-    @Value("${miaosha.mq-async-sync:true}")
-    private boolean mqAsyncSync;
+    public MqConsumer(StockConsumeService stockConsumeService,
+                      @Value("${miaosha.mq-async-sync:true}") boolean mqAsyncSync) {
+        this.stockConsumeService = stockConsumeService;
+        this.mqAsyncSync = mqAsyncSync;
+    }
 
     @PostConstruct
     public void init() throws MQClientException {
-        consumer = new DefaultMQPushConsumer("stock_consumer_group");
+        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("stock_consumer_group");
         consumer.setNamesrvAddr(nameAddr);
         consumer.subscribe(topicName, "*");
+        consumer.registerMessageListener(this);
+        consumer.start();
+    }
 
-        consumer.registerMessageListener(new MessageListenerConcurrently() {
-            @Override
-            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
-                                                            ConsumeConcurrentlyContext context) {
-                for (MessageExt msg : msgs) {
-                    String jsonString = new String(msg.getBody());
-                    Map<String, Object> map = JSON.parseObject(jsonString, Map.class);
-                    Integer itemId = (Integer) map.get("itemId");
-                    Integer amount = (Integer) map.get("amount");
-                    String stockLogId = (String) map.get("stockLogId");
-
-                    // 幂等：Redis setIfAbsent 防重复消费
-                    String lockKey = "stock_log_synced_" + stockLogId;
-                    Boolean firstTime = redisTemplate.opsForValue().setIfAbsent(lockKey, "1");
-                    if (Boolean.FALSE.equals(firstTime)) {
-                        // 已处理过，跳过
-                        continue;
-                    }
-                    // setIfAbsent 成功后单独设过期时间
-                    redisTemplate.expire(lockKey, 1, TimeUnit.HOURS);
+    @Override
+    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
+        // 同步模式在下单事务内扣库，监听器只确认消息。
+        if (!mqAsyncSync) {
+            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
+        }
 
-                    // 真正扣减数据库库存（mq-async-sync=false 时跳过，已同步扣）
-                    if (mqAsyncSync) {
-                        itemStockDOMapper.decreaseStock(itemId, amount);
-                    }
+        for (MessageExt message : messages) {
+            String stockLogId = null;
+            try {
+                String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(message.getBody())).toString();
+                JsonNode payload = MESSAGE_MAPPER.readTree(json);
+                if (payload == null || !payload.isObject()) {
+                    throw new IllegalArgumentException("Stock message must contain a JSON object");
+                }
+                String id = payload.path("stockLogId").textValue();
+                if (StringUtils.isBlank(id) || id.length() > 64) {
+                    throw new IllegalArgumentException("stockLogId must be a non-blank string of at most 64 characters");
                 }
-                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
+                stockLogId = id;
+                int itemId = positiveInt(payload, "itemId");
+                int amount = positiveInt(payload, "amount");
+                // Spring 事务代理完成提交后才返回，消费者随后才能确认成功。
+                stockConsumeService.consumeOnce(stockLogId, itemId, amount);
+            } catch (IOException e) {
+                LOGGER.warn("Invalid UTF-8 JSON stock message, msgId={}, reconsumeTimes={}, reason={}",
+                        message.getMsgId(), message.getReconsumeTimes(), e.getClass().getSimpleName());
+                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
+            } catch (RuntimeException e) {
+                LOGGER.warn("Stock message consumption failed, msgId={}, stockLogId={}, reconsumeTimes={}",
+                        message.getMsgId(), stockLogId, message.getReconsumeTimes(), e);
+                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
             }
-        });
+        }
+        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
+    }
 
-        consumer.start();
+    private static int positiveInt(JsonNode payload, String field) {
+        JsonNode value = payload.path(field);
+        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
+            throw new IllegalArgumentException(field + " must be a positive int");
+        }
+        return value.intValue();
     }
 }
~~~

</details>

### StockConsumeService.java

**新增 · +76 / −0**。在同一事务内登记消费记录、判重和扣库存。 [文件](../../src/main/java/com/imooc/miaoshaproject/service/impl/StockConsumeService.java)

~~~diff
@@ -0,0 +1,76 @@
+package com.imooc.miaoshaproject.service.impl;
+
+import com.imooc.miaoshaproject.dao.ItemStockDOMapper;
+import com.imooc.miaoshaproject.dao.StockConsumeLogMapper;
+import com.imooc.miaoshaproject.dao.StockLogDOMapper;
+import com.imooc.miaoshaproject.dataobject.StockConsumeLogDO;
+import com.imooc.miaoshaproject.dataobject.StockLogDO;
+import org.springframework.dao.DuplicateKeyException;
+import org.springframework.stereotype.Service;
+import org.springframework.transaction.annotation.Transactional;
+
+import java.util.Objects;
+
+@Service
+public class StockConsumeService {
+    private static final int ORDER_COMMITTED_STATUS = 2;
+
+    private final StockLogDOMapper stockLogDOMapper;
+    private final StockConsumeLogMapper stockConsumeLogMapper;
+    private final ItemStockDOMapper itemStockDOMapper;
+
+    public StockConsumeService(StockLogDOMapper stockLogDOMapper, StockConsumeLogMapper stockConsumeLogMapper,
+                               ItemStockDOMapper itemStockDOMapper) {
+        this.stockLogDOMapper = stockLogDOMapper;
+        this.stockConsumeLogMapper = stockConsumeLogMapper;
+        this.itemStockDOMapper = itemStockDOMapper;
+    }
+
+    @Transactional(rollbackFor = Exception.class)
+    public void consumeOnce(String stockLogId, int itemId, int amount) {
+        // 已提交的订单流水用于核对本次库存扣减的商品和数量。
+        StockLogDO stockLog = stockLogDOMapper.selectByPrimaryKey(stockLogId);
+        if (stockLog == null) {
+            throw new IllegalStateException("库存流水不存在 stockLogId=" + stockLogId);
+        }
+        if (!Objects.equals(stockLog.getStatus(), ORDER_COMMITTED_STATUS)) {
+            throw new IllegalStateException("库存流水的订单未提交 stockLogId=" + stockLogId
+                    + ", status=" + stockLog.getStatus());
+        }
+        if (!Objects.equals(stockLog.getItemId(), itemId) || !Objects.equals(stockLog.getAmount(), amount)) {
+            throw new IllegalStateException("库存消息与原流水的商品或数量不一致 stockLogId=" + stockLogId
+                    + ", itemId=" + itemId + ", amount=" + amount);
+        }
+
+        StockConsumeLogDO consumeLog = new StockConsumeLogDO();
+        consumeLog.setStockLogId(stockLogId);
+        consumeLog.setItemId(itemId);
+        consumeLog.setAmount(amount);
+
+        // 由数据库唯一键决定本次消费是否负责扣库。
+        int insertedRows;
+        try {
+            insertedRows = stockConsumeLogMapper.insert(consumeLog);
+        } catch (DuplicateKeyException duplicate) {
+            // 锁定读能看到并发事务刚提交的记录，即使先前查询已建立事务快照。
+            StockConsumeLogDO existing = stockConsumeLogMapper.selectByStockLogIdForShare(stockLogId);
+            if (existing == null || !Objects.equals(existing.getItemId(), itemId)
+                    || !Objects.equals(existing.getAmount(), amount)) {
+                throw new IllegalStateException("已存在的消费流水缺失或与消息不一致 stockLogId=" + stockLogId
+                        + ", itemId=" + itemId + ", amount=" + amount, duplicate);
+            }
+            return;
+        }
+        if (insertedRows != 1) {
+            throw new IllegalStateException("消费流水写入行数异常 stockLogId=" + stockLogId
+                    + ", insertedRows=" + insertedRows);
+        }
+
+        int affectedRows = itemStockDOMapper.decreaseStock(itemId, amount);
+        if (affectedRows != 1) {
+            // 让消费记录随失败的扣库事务回滚，保留重试机会。
+            throw new IllegalStateException("库存扣减更新行数异常 stockLogId=" + stockLogId + ", itemId=" + itemId
+                    + ", amount=" + amount + ", affectedRows=" + affectedRows);
+        }
+    }
+}
~~~

### StockConsumeLogMapper.java

**新增 · +10 / −0**。声明消费记录插入与锁定读接口。 [文件](../../src/main/java/com/imooc/miaoshaproject/dao/StockConsumeLogMapper.java)

~~~diff
@@ -0,0 +1,10 @@
+package com.imooc.miaoshaproject.dao;
+
+import com.imooc.miaoshaproject.dataobject.StockConsumeLogDO;
+import org.apache.ibatis.annotations.Param;
+
+public interface StockConsumeLogMapper {
+    int insert(StockConsumeLogDO log);
+
+    StockConsumeLogDO selectByStockLogIdForShare(@Param("stockLogId") String stockLogId);
+}
~~~

### StockConsumeLogMapper.xml

**新增 · +21 / −0**。映射消费记录写入与主键查询。 [文件](../../src/main/resources/mapping/StockConsumeLogMapper.xml)

~~~diff
@@ -0,0 +1,21 @@
+<?xml version="1.0" encoding="UTF-8"?>
+<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
+<mapper namespace="com.imooc.miaoshaproject.dao.StockConsumeLogMapper">
+  <resultMap id="StockConsumeLogResult" type="com.imooc.miaoshaproject.dataobject.StockConsumeLogDO">
+    <id column="stock_log_id" property="stockLogId" />
+    <result column="item_id" property="itemId" />
+    <result column="amount" property="amount" />
+  </resultMap>
+
+  <insert id="insert" parameterType="com.imooc.miaoshaproject.dataobject.StockConsumeLogDO">
+    insert into stock_consume_log (stock_log_id, item_id, amount)
+    values (#{stockLogId}, #{itemId}, #{amount})
+  </insert>
+
+  <select id="selectByStockLogIdForShare" resultMap="StockConsumeLogResult">
+    select stock_log_id, item_id, amount
+    from stock_consume_log
+    where stock_log_id = #{stockLogId}
+    lock in share mode
+  </select>
+</mapper>
~~~

### StockConsumeLogDO.java

**新增 · +31 / −0**。定义流水号、商品和数量字段。 [文件](../../src/main/java/com/imooc/miaoshaproject/dataobject/StockConsumeLogDO.java)

~~~diff
@@ -0,0 +1,31 @@
+package com.imooc.miaoshaproject.dataobject;
+
+public class StockConsumeLogDO {
+    private String stockLogId;
+    private Integer itemId;
+    private Integer amount;
+
+    public String getStockLogId() {
+        return stockLogId;
+    }
+
+    public void setStockLogId(String stockLogId) {
+        this.stockLogId = stockLogId;
+    }
+
+    public Integer getItemId() {
+        return itemId;
+    }
+
+    public void setItemId(Integer itemId) {
+        this.itemId = itemId;
+    }
+
+    public Integer getAmount() {
+        return amount;
+    }
+
+    public void setAmount(Integer amount) {
+        this.amount = amount;
+    }
+}
~~~

### upgrade-001-stock-consume-log.sql

**新增 · +8 / −0**。以库存流水号为主键建立消费记录表。 [文件](../../sql/upgrade-001-stock-consume-log.sql)

~~~diff
@@ -0,0 +1,8 @@
+-- 同一库存流水共用一条扣库凭证，凭证与扣库一起由 InnoDB 提交。
+CREATE TABLE IF NOT EXISTS stock_consume_log (
+    stock_log_id VARCHAR(64) NOT NULL,
+    item_id INT NOT NULL,
+    amount INT NOT NULL,
+    consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
+    PRIMARY KEY (stock_log_id)
+) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_general_ci;
~~~

## 测试与部署

### MqConsumerTest.java

**新增 · +111 / −0**。覆盖消息校验、失败重投和同步模式。 [文件](../../src/test/java/com/imooc/miaoshaproject/mq/MqConsumerTest.java)

<details>
<summary>展开逐行改动</summary>

~~~diff
@@ -0,0 +1,111 @@
+package com.imooc.miaoshaproject.mq;
+
+import com.imooc.miaoshaproject.service.impl.StockConsumeService;
+import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
+import org.apache.rocketmq.common.message.MessageExt;
+import org.junit.Test;
+
+import java.nio.charset.StandardCharsets;
+import java.util.ArrayList;
+import java.util.Arrays;
+import java.util.Collections;
+import java.util.List;
+
+import static org.junit.Assert.assertEquals;
+import static org.junit.Assert.assertTrue;
+
+public class MqConsumerTest {
+
+    @Test
+    public void acknowledgesEveryValidMessageInBatch() {
+        RecordingStockConsumeService service = new RecordingStockConsumeService();
+        MqConsumer consumer = new MqConsumer(service, true);
+
+        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, consumer.consumeMessage(Arrays.asList(
+                stockMessage("流水-01"), stockMessage("S002")), null));
+        assertEquals(Arrays.asList("流水-01:6:1", "S002:6:1"), service.calls);
+    }
+
+    @Test
+    public void rejectsInvalidStockMessageWithoutCallingService() {
+        RecordingStockConsumeService service = new RecordingStockConsumeService();
+        MqConsumer consumer = new MqConsumer(service, true);
+        List<MessageExt> invalidMessages = Arrays.asList(
+                message(new byte[]{(byte) 0xC3, 0x28}),
+                message(stockJson("S001").getBytes(StandardCharsets.UTF_16LE)),
+                message("not-json"),
+                message(stockJson("S001") + "{}"),
+                message("{\"stockLogId\":\"S001\",\"stockLogId\":\"S002\",\"itemId\":6,\"amount\":1}"),
+                message("{\"itemId\":6,\"amount\":1}"),
+                stockMessage(" "),
+                stockMessage(String.join("", Collections.nCopies(65, "S"))),
+                message("{\"stockLogId\":\"S001\",\"itemId\":6,\"amount\":0}"),
+                message("{\"stockLogId\":\"S001\",\"itemId\":-1,\"amount\":1}"),
+                message("{\"stockLogId\":\"S001\",\"itemId\":6,\"amount\":2147483648}"),
+                message("{\"stockLogId\":\"S001\",\"itemId\":6,\"amount\":1.0}"),
+                message("{\"stockLogId\":\"S001\",\"itemId\":\"6\",\"amount\":1}"));
+
+        for (MessageExt invalid : invalidMessages) {
+            assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER,
+                    consumer.consumeMessage(Collections.singletonList(invalid), null));
+        }
+        assertTrue(service.calls.isEmpty());
+    }
+
+    @Test
+    public void requestsRetryForWholeBatchOnServiceFailure() {
+        RecordingStockConsumeService service = new RecordingStockConsumeService();
+        service.failingStockLogId = "S002";
+        MqConsumer consumer = new MqConsumer(service, true);
+
+        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, consumer.consumeMessage(Arrays.asList(
+                stockMessage("S001"), stockMessage("S002"), stockMessage("S003")), null));
+        assertEquals(Arrays.asList("S001:6:1", "S002:6:1"), service.calls);
+    }
+
+    @Test
+    public void synchronousModeAcknowledgesWithoutParsingOrConsuming() {
+        RecordingStockConsumeService service = new RecordingStockConsumeService();
+        MqConsumer consumer = new MqConsumer(service, false);
+
+        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, consumer.consumeMessage(Arrays.asList(
+                message("not-json"), stockMessage("S001")), null));
+        assertTrue(service.calls.isEmpty());
+    }
+
+    private static MessageExt stockMessage(String stockLogId) {
+        return message(stockJson(stockLogId));
+    }
+
+    private static String stockJson(String stockLogId) {
+        return "{\"stockLogId\":\"" + stockLogId + "\",\"itemId\":6,\"amount\":1}";
+    }
+
+    private static MessageExt message(String json) {
+        return message(json.getBytes(StandardCharsets.UTF_8));
+    }
+
+    private static MessageExt message(byte[] body) {
+        MessageExt message = new MessageExt();
+        message.setMsgId("test-message");
+        message.setBody(body);
+        return message;
+    }
+
+    private static class RecordingStockConsumeService extends StockConsumeService {
+        private final List<String> calls = new ArrayList<>();
+        private String failingStockLogId;
+
+        private RecordingStockConsumeService() {
+            super(null, null, null);
+        }
+
+        @Override
+        public void consumeOnce(String stockLogId, int itemId, int amount) {
+            calls.add(stockLogId + ":" + itemId + ":" + amount);
+            if (stockLogId.equals(failingStockLogId)) {
+                throw new IllegalStateException("Test failure in consumption transaction");
+            }
+        }
+    }
+}
~~~

</details>

### StockConsumeMySqlIT.java

**新增 · +286 / −0**。用真实 MySQL 验证事务回滚与并发判重。 [文件](../../src/test/java/com/imooc/miaoshaproject/service/impl/StockConsumeMySqlIT.java)

<details>
<summary>展开逐行改动</summary>

~~~diff
@@ -0,0 +1,286 @@
+package com.imooc.miaoshaproject.service.impl;
+
+import com.imooc.miaoshaproject.dao.ItemStockDOMapper;
+import com.imooc.miaoshaproject.dao.StockConsumeLogMapper;
+import com.imooc.miaoshaproject.dao.StockLogDOMapper;
+import com.imooc.miaoshaproject.dataobject.StockConsumeLogDO;
+import org.junit.After;
+import org.junit.BeforeClass;
+import org.junit.Test;
+import org.mybatis.spring.SqlSessionFactoryBean;
+import org.mybatis.spring.SqlSessionTemplate;
+import org.springframework.aop.framework.ProxyFactory;
+import org.springframework.core.io.ClassPathResource;
+import org.springframework.core.io.Resource;
+import org.springframework.jdbc.core.JdbcTemplate;
+import org.springframework.jdbc.datasource.DataSourceTransactionManager;
+import org.springframework.jdbc.datasource.DriverManagerDataSource;
+import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
+import org.springframework.transaction.interceptor.TransactionInterceptor;
+
+import java.lang.reflect.InvocationTargetException;
+import java.lang.reflect.Proxy;
+import java.net.URI;
+import java.util.ArrayList;
+import java.util.List;
+import java.util.UUID;
+import java.util.concurrent.CountDownLatch;
+import java.util.concurrent.ExecutorService;
+import java.util.concurrent.Executors;
+import java.util.concurrent.Future;
+import java.util.concurrent.ThreadLocalRandom;
+import java.util.concurrent.TimeUnit;
+
+import static org.junit.Assert.assertEquals;
+import static org.junit.Assert.assertSame;
+import static org.junit.Assert.assertTrue;
+import static org.junit.Assert.fail;
+
+public class StockConsumeMySqlIT {
+    private static JdbcTemplate jdbc;
+    private static DataSourceTransactionManager transactionManager;
+    private static StockLogDOMapper stockLogMapper;
+    private static StockConsumeLogMapper consumeLogMapper;
+    private static ItemStockDOMapper itemStockMapper;
+
+    private final List<String> stockLogIds = new ArrayList<>();
+    private Integer itemId;
+
+    @BeforeClass
+    public static void connectToTestDatabase() throws Exception {
+        String jdbcUrl = requiredEnvironmentVariable("MIAOSHA_STOCK_IT_JDBC_URL");
+        String database = requireLocalTestDatabase(jdbcUrl);
+        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl,
+                requiredEnvironmentVariable("MIAOSHA_STOCK_IT_USERNAME"),
+                requiredEnvironmentVariable("MIAOSHA_STOCK_IT_PASSWORD"));
+        jdbc = new JdbcTemplate(dataSource);
+        assertEquals("Integration test must use its dedicated database", database,
+                jdbc.queryForObject("SELECT DATABASE()", String.class));
+
+        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
+        factory.setDataSource(dataSource);
+        factory.setMapperLocations(new Resource[]{new ClassPathResource("mapping/StockLogDOMapper.xml"),
+                new ClassPathResource("mapping/StockConsumeLogMapper.xml"),
+                new ClassPathResource("mapping/ItemStockDOMapper.xml")});
+        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
+        stockLogMapper = session.getMapper(StockLogDOMapper.class);
+        consumeLogMapper = session.getMapper(StockConsumeLogMapper.class);
+        itemStockMapper = session.getMapper(ItemStockDOMapper.class);
+        transactionManager = new DataSourceTransactionManager(dataSource);
+    }
+
+    @After
+    public void deleteFixtures() {
+        for (String stockLogId : stockLogIds) {
+            jdbc.update("DELETE FROM stock_consume_log WHERE stock_log_id = ?", stockLogId);
+            jdbc.update("DELETE FROM stock_log WHERE stock_log_id = ?", stockLogId);
+        }
+        if (itemId != null) {
+            jdbc.update("DELETE FROM item_stock WHERE item_id = ?", itemId);
+        }
+    }
+
+    @Test
+    public void firstCommitAndOneHundredReplaysDeductOncePerLog() {
+        createStock(10);
+        String stockLogId = createStockLog(1, 2);
+        StockConsumeService service = transactionalService(consumeLogMapper, itemStockMapper);
+
+        service.consumeOnce(stockLogId, itemId, 1);
+        assertStockAndReceipt(stockLogId, 9, 1);
+        for (int replay = 0; replay < 100; replay++) {
+            service.consumeOnce(stockLogId, itemId, 1);
+        }
+        assertStockAndReceipt(stockLogId, 9, 1);
+
+        String secondLogId = createStockLog(2, 2);
+        service.consumeOnce(secondLogId, itemId, 2);
+        assertStockAndReceipt(secondLogId, 7, 1);
+    }
+
+    @Test
+    public void oneHundredConcurrentDeliveriesDeductOnce() throws Exception {
+        createStock(10);
+        String stockLogId = createStockLog(1, 2);
+        int workerCount = 8;
+        CountDownLatch atInsert = new CountDownLatch(workerCount);
+        StockConsumeLogMapper competingInserts = new StockConsumeLogMapper() {
+            @Override
+            public int insert(StockConsumeLogDO record) {
+                // Establish overlapping transactions before any receipt can commit.
+                atInsert.countDown();
+                await(atInsert);
+                return consumeLogMapper.insert(record);
+            }
+
+            @Override
+            public StockConsumeLogDO selectByStockLogIdForShare(String id) {
+                return consumeLogMapper.selectByStockLogIdForShare(id);
+            }
+        };
+        StockConsumeService service = transactionalService(competingInserts, itemStockMapper);
+        ExecutorService workers = Executors.newFixedThreadPool(workerCount);
+        try {
+            List<Future<?>> deliveries = new ArrayList<>();
+            for (int delivery = 0; delivery < 100; delivery++) {
+                deliveries.add(workers.submit(() -> service.consumeOnce(stockLogId, itemId, 1)));
+            }
+            for (Future<?> delivery : deliveries) {
+                delivery.get(20, TimeUnit.SECONDS);
+            }
+        } finally {
+            workers.shutdownNow();
+            assertTrue("Stock consumption test workers did not stop", workers.awaitTermination(20, TimeUnit.SECONDS));
+        }
+        assertStockAndReceipt(stockLogId, 9, 1);
+    }
+
+    @Test
+    public void failureAfterReceiptInsertRollsBackAndCanRetry() {
+        RuntimeException fault = new IllegalStateException("Injected failure after receipt INSERT");
+        StockConsumeLogMapper failingMapper = failAfterWrite(StockConsumeLogMapper.class, consumeLogMapper, "insert", fault);
+        assertRollbackAndRetry(transactionalService(failingMapper, itemStockMapper), fault);
+    }
+
+    @Test
+    public void failureAfterStockUpdateRollsBackBothWritesAndCanRetry() {
+        RuntimeException fault = new IllegalStateException("Injected failure after item_stock UPDATE");
+        ItemStockDOMapper failingMapper = failAfterWrite(ItemStockDOMapper.class, itemStockMapper, "decreaseStock", fault);
+        assertRollbackAndRetry(transactionalService(consumeLogMapper, failingMapper), fault);
+    }
+
+    @Test
+    public void zeroRowsUpdatedLeavesNoReceiptAndCanRetry() {
+        createStock(0);
+        String stockLogId = createStockLog(1, 2);
+        StockConsumeService service = transactionalService(consumeLogMapper, itemStockMapper);
+
+        expectFailure(() -> service.consumeOnce(stockLogId, itemId, 1));
+        assertStockAndReceipt(stockLogId, 0, 0);
+
+        jdbc.update("UPDATE item_stock SET stock = 10 WHERE item_id = ?", itemId);
+        service.consumeOnce(stockLogId, itemId, 1);
+        assertStockAndReceipt(stockLogId, 9, 1);
+    }
+
+    @Test
+    public void rejectsUncommittedSourceAndPayloadMismatches() {
+        createStock(10);
+        String pendingLogId = createStockLog(1, 1);
+        String mismatchedLogId = createStockLog(1, 2);
+        StockConsumeService service = transactionalService(consumeLogMapper, itemStockMapper);
+
+        expectFailure(() -> service.consumeOnce(pendingLogId, itemId, 1));
+        assertStockAndReceipt(pendingLogId, 10, 0);
+        expectFailure(() -> service.consumeOnce(mismatchedLogId, itemId, 2));
+        assertStockAndReceipt(mismatchedLogId, 10, 0);
+    }
+
+    @Test
+    public void matchingSourceCannotAcknowledgeAConflictingReceipt() {
+        createStock(10);
+        String stockLogId = createStockLog(1, 2);
+        jdbc.update("INSERT INTO stock_consume_log (stock_log_id, item_id, amount) VALUES (?, ?, ?)",
+                stockLogId, itemId, 2);
+        StockConsumeService service = transactionalService(consumeLogMapper, itemStockMapper);
+
+        expectFailure(() -> service.consumeOnce(stockLogId, itemId, 1));
+        assertStockAndReceipt(stockLogId, 10, 1);
+    }
+
+    private void assertRollbackAndRetry(StockConsumeService failingService, RuntimeException fault) {
+        createStock(10);
+        String stockLogId = createStockLog(1, 2);
+        assertSame(fault, expectFailure(() -> failingService.consumeOnce(stockLogId, itemId, 1)));
+        assertStockAndReceipt(stockLogId, 10, 0);
+
+        transactionalService(consumeLogMapper, itemStockMapper).consumeOnce(stockLogId, itemId, 1);
+        assertStockAndReceipt(stockLogId, 9, 1);
+    }
+
+    private void createStock(int stock) {
+        int fixtureItemId = ThreadLocalRandom.current().nextInt(1_000_000_000, Integer.MAX_VALUE);
+        jdbc.update("INSERT INTO item_stock (item_id, stock) VALUES (?, ?)", fixtureItemId, stock);
+        itemId = fixtureItemId;
+    }
+
+    private String createStockLog(int amount, int status) {
+        String stockLogId = UUID.randomUUID().toString().replace("-", "");
+        jdbc.update("INSERT INTO stock_log (stock_log_id, item_id, amount, status) VALUES (?, ?, ?, ?)",
+                stockLogId, itemId, amount, status);
+        stockLogIds.add(stockLogId);
+        return stockLogId;
+    }
+
+    private void assertStockAndReceipt(String stockLogId, int stock, int receipts) {
+        assertEquals(stock, jdbc.queryForObject("SELECT stock FROM item_stock WHERE item_id = ?", Integer.class, itemId).intValue());
+        assertEquals(receipts, jdbc.queryForObject("SELECT COUNT(*) FROM stock_consume_log WHERE stock_log_id = ?",
+                Integer.class, stockLogId).intValue());
+    }
+
+    private static StockConsumeService transactionalService(StockConsumeLogMapper receiptMapper, ItemStockDOMapper stockMapper) {
+        StockConsumeService target = new StockConsumeService(stockLogMapper, receiptMapper, stockMapper);
+        ProxyFactory factory = new ProxyFactory(target);
+        factory.setProxyTargetClass(true);
+        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
+        return (StockConsumeService) factory.getProxy();
+    }
+
+    private static <T> T failAfterWrite(Class<T> mapperType, T delegate, String writeMethod, RuntimeException fault) {
+        return mapperType.cast(Proxy.newProxyInstance(mapperType.getClassLoader(), new Class<?>[]{mapperType},
+                (proxy, method, args) -> {
+                    Object result;
+                    try {
+                        result = method.invoke(delegate, args);
+                    } catch (InvocationTargetException error) {
+                        throw error.getCause();
+                    }
+                    if (method.getName().equals(writeMethod)) {
+                        assertEquals(1, result);
+                        throw fault;
+                    }
+                    return result;
+                }));
+    }
+
+    private static RuntimeException expectFailure(Runnable callback) {
+        try {
+            callback.run();
+        } catch (RuntimeException failure) {
+            return failure;
+        }
+        fail("Expected stock consumption to fail");
+        return null;
+    }
+
+    private static void await(CountDownLatch latch) {
+        try {
+            assertTrue("Competing transactions did not reach INSERT", latch.await(20, TimeUnit.SECONDS));
+        } catch (InterruptedException failure) {
+            Thread.currentThread().interrupt();
+            throw new AssertionError("Interrupted while waiting for competing transactions", failure);
+        }
+    }
+
+    private static String requireLocalTestDatabase(String jdbcUrl) {
+        if (!jdbcUrl.startsWith("jdbc:mysql://")) {
+            throw new IllegalArgumentException("Expected a JDBC URL for the local stock-consumption test database");
+        }
+        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
+        String host = uri.getHost();
+        String path = uri.getPath();
+        if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host))
+                || uri.getUserInfo() != null || path == null || !path.matches("/miaosha_stock_consume_it(?:_[a-z0-9]+)*")) {
+            throw new IllegalArgumentException("Use a loopback MySQL host and a miaosha_stock_consume_it database");
+        }
+        return path.substring(1);
+    }
+
+    private static String requiredEnvironmentVariable(String name) {
+        String value = System.getenv(name);
+        if (value == null || value.trim().isEmpty()) {
+            throw new IllegalStateException("Set " + name + " before running StockConsumeMySqlIT");
+        }
+        return value;
+    }
+}
~~~

</details>

### logback-test.xml

**新增 · +11 / −0**。限制测试日志级别。 [文件](../../src/test/resources/logback-test.xml)

~~~diff
@@ -0,0 +1,11 @@
+<configuration>
+    <!-- Spring DEBUG logging can print the process environment during JDBC exception translation. -->
+    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
+        <encoder>
+            <pattern>%level %logger{36} - %msg%n</pattern>
+        </encoder>
+    </appender>
+    <root level="WARN">
+        <appender-ref ref="CONSOLE"/>
+    </root>
+</configuration>
~~~

### package.bat

**修改 · +5 / −3**。检查并复制部署包所需的增量 SQL。 [文件](../../package.bat)

~~~diff
@@ -40,7 +40,7 @@ if errorlevel 1 (
     echo [FAIL] Maven build failed.
     exit /b 1
 )
-for %%F in ("%JAR%" "Dockerfile" "docker-compose.yml" ".dockerignore" ".env" "application.yml" "sql\miaosha.sql") do (
+for %%F in ("%JAR%" "Dockerfile" "docker-compose.yml" ".dockerignore" ".env" "application.yml" "sql\miaosha.sql" "sql\upgrade-001-stock-consume-log.sql") do (
     if not exist "%%~F" (
         echo [FAIL] Missing deploy input: %%~F
         exit /b 1
@@ -63,8 +63,10 @@ for %%F in ("Dockerfile" "docker-compose.yml" ".dockerignore" ".env" "applicatio
     call :copyFile "%%~F" "%MIAOSHA_PACKAGE_DEPLOY%\%%~F"
     if errorlevel 1 exit /b 1
 )
-call :copyFile "sql\miaosha.sql" "%MIAOSHA_PACKAGE_DEPLOY%\sql\miaosha.sql"
-if errorlevel 1 exit /b 1
+for %%F in ("miaosha.sql" "upgrade-001-stock-consume-log.sql") do (
+    call :copyFile "sql\%%~F" "%MIAOSHA_PACKAGE_DEPLOY%\sql\%%~F"
+    if errorlevel 1 exit /b 1
+)
 
 echo [3/3] Done: target\deploy
 echo Upload the whole deploy directory, including .env and .dockerignore.
~~~

### verify-deployment.py

**修改 · +4 / −3**。检查增量脚本打包及输入缺失时的行为。 [文件](../../scripts/tests/verify-deployment.py)

~~~diff
@@ -68,7 +68,8 @@ check(result.returncode!=0 and not any(call[0] in ("stop","rm","run") for call i
 package_root=fixture/"package fixture";package_root.mkdir()
 for name in ("package.bat","Dockerfile","docker-compose.yml",".dockerignore"):shutil.copyfile(repo/name,package_root/name)
 (package_root/"sql").mkdir()
-(package_root/"sql"/"miaosha.sql").write_text("-- fixture only\n")
+for name in ("miaosha.sql", "upgrade-001-stock-consume-log.sql"):
+    (package_root/"sql"/name).write_text("-- fixture only\n")
 (package_root/".env").write_text(env_text)
 (package_root/"application.yml").write_text("logging: {}\n")
 stub=fixture/"stub tools";(stub/"bin").mkdir(parents=True)
@@ -83,12 +84,12 @@ if result.returncode:
     print(result.stderr.decode("utf-8",errors="replace"))
 check(result.returncode==0,"Batch: stub Maven succeeds")
 deploy=package_root/"target"/"deploy"
-check(all((deploy/name).is_file() for name in ("miaosha.jar","Dockerfile","docker-compose.yml",".dockerignore",".env","application.yml","sql/miaosha.sql")),"Batch: complete deployment package")
+check(all((deploy/name).is_file() for name in ("miaosha.jar","Dockerfile","docker-compose.yml",".dockerignore",".env","application.yml","sql/miaosha.sql","sql/upgrade-001-stock-consume-log.sql")),"Batch: complete deployment package")
 (deploy/"stale-marker").write_text("fixture")
 result=run_package();check(result.returncode==0 and not (deploy/"stale-marker").exists(),"Batch: safe cleanup")
 failure_env=package_env.copy();failure_env["STUB_MAVEN_FAILURE"]="1"
 check(run_package(failure_env).returncode!=0,"Batch: build failure is nonzero")
-source=package_root/"sql"/"miaosha.sql";data=source.read_bytes();source.unlink()
+source=package_root/"sql"/"upgrade-001-stock-consume-log.sql";data=source.read_bytes();source.unlink()
 (deploy/"preserve-marker").write_text("fixture")
 result=run_package();check(result.returncode!=0 and (deploy/"preserve-marker").exists(),"Batch: missing input preserves previous package")
 source.write_bytes(data)
~~~

## 项目文档

### AGENTS.md

**修改 · +4 / −4**。记录新的消费者事务与表用途。 [文件](../../AGENTS.md)

~~~diff
@@ -97,9 +97,9 @@ return newStock
 
 ### MQ Consumer 幂等（关键）
 
-`MqConsumer` 用 `stockLogId`（`initStockLog` 生成的 UUID）+ Redis `setIfAbsent` 做去重。RocketMQ 是 at-least-once 语义——rebalance、Broker 崩溃重启、处理超时都会触发重投。不加幂等 = 重复扣 DB 库存。
+`MqConsumer` 调用独立事务服务 `StockConsumeService.consumeOnce`。服务以 `stock_consume_log.stock_log_id` 唯一键判重，在同一 MySQL 事务内插入消费记录、条件更新 `item_stock`。库存更新影响 1 行才提交，消费者在事务提交后确认消息。已提交的重复记录需核对商品和数量，失败请求由 RocketMQ 重试。
 
-当前去重是 `SETNX → EXPIRE → DB UPDATE`，并非同一事务：置位后宕机或 DB 失败会漏处理，TTL 到期后重投可能重扣；Consumer 还未检查扣库影响行数。订单 `@Transactional` 未声明受检业务异常回滚规则，Redis 回补也无幂等记录和自动对账。不得把当前实现写成“无条件保证最终一致”或“消费恰好一次”。`stock_log.status=2` 仅代表本地订单成功，不代表异步库存已同步。
+`stock_log.status=2` 仅代表本地订单成功。已提交的 `stock_consume_log` 记录才证明异步库存同步完成。已有库需先应用 `sql/upgrade-001-stock-consume-log.sql` 并核查历史消息。Redis 回补的幂等和跨进程恢复仍待处理。
 
 `stock-mode=mysql` 本身已扣 DB，后续同步 / 异步分支还有再次扣减风险；库存模式不是任意可组合。当前主演示路径为 `lua + mq-async-sync=true`。序列查询已用 `FOR UPDATE`，但私有同类调用的 `REQUIRES_NEW` 不生效，不应据此误判订单号必然重复。
 
@@ -114,7 +114,6 @@ return newStock
 | `promo_door_count_{promoId}` | 大闸计数器，初始 = 库存×5 | `publishPromo`（初始化）、`generateSecondKillToken`（递减） |
 | `promo_token_{promoId}_userid_{uid}_itemid_{iid}` | 秒杀令牌（30 分钟 TTL） | `generateSecondKillToken` |
 | `verify_code_{uid}` | 验证码（30 分钟 TTL） | `generateverifycode` |
-| `stock_log_synced_{stockLogId}` | MQ Consumer 幂等锁（1 小时 TTL） | `MqConsumer` |
 | `item_validate_{id}` | 商品缓存（10 分钟 TTL） | `getItemByIdInCache` |
 | `item_{id}` | 商品详情 Redis 缓存（30 分钟 TTL），Caffeine 300 秒 | `ItemController.getItem` |
 
@@ -135,7 +134,8 @@ return newStock
 | `service/impl/ItemServiceImpl.java` | Lua 库存脚本、`increaseStock` 回补、`initStockLog` 流水初始化 |
 | `service/impl/PromoServiceImpl.java` | `generateSecondKillToken`（令牌+大闸）、`publishPromo`（库存预热+大闸初始化） |
 | `mq/MqProducer.java` | 事务 + 异步 RocketMQ Producer，`TransactionListener` 实现 |
-| `mq/MqConsumer.java` | 库存同步消费者，`setIfAbsent` 幂等 |
+| `mq/MqConsumer.java` | 校验库存消息，提交后确认，失败时请求重试 |
+| `service/impl/StockConsumeService.java` | MySQL 消费记录和库存扣减同事务，按库存流水号判重 |
 
 ### 支撑文件
 
~~~

### README.md

**修改 · +12 / −10**。更新消费流程、数据模型与迁移、切换说明。 [文件](../../README.md)

~~~diff
@@ -125,17 +125,17 @@ sequenceDiagram
     alt 本地事务成功
         S-->>MQ: COMMIT_MESSAGE
         MQ->>W: 投递库存同步消息
-        W->>R: SETNX stock_log_synced_{stockLogId}
-        W->>DB: 条件扣减 item_stock
+        W->>DB: 同一事务写 stock_consume_log 并条件扣减 item_stock
+        W-->>MQ: 提交后确认消费，失败时请求重试
     else 本地事务实际回滚
         S->>R: afterCompletion 回补库存，最多尝试 3 次
         S-->>MQ: 业务异常分支返回 ROLLBACK_MESSAGE
     end
 ```
 
-Broker 回查 `stock_log`：`2` 返回 COMMIT，`1` 或记录不存在返回 UNKNOWN，其他状态返回 ROLLBACK。生产者捕获 `BusinessException` 时将流水更新为 `3`。消费者使用 `stockLogId` 构造 Redis 去重 Key，设置 1 小时过期后执行数据库库存更新。
+Broker 回查 `stock_log` 时，`2` 返回 COMMIT，`1` 或记录不存在返回 UNKNOWN，其他状态返回 ROLLBACK。生产者捕获 `BusinessException` 时将流水更新为 `3`。消费者核对消息与已下单的 `stock_log`，再以 `stock_consume_log.stock_log_id` 主键区分首次扣库与重投。
 
-这些机制用于协调订单与库存同步，但 Redis 去重与数据库更新不在同一事务，不能据此无条件宣称最终一致。消费失败恢复、异常回滚语义等限制见[当前实现边界](#当前实现边界)。源码中尚无定时对账任务。
+消费记录和库存更新一起提交。重复消息的商品与数量和已提交的消费记录一致时，消费者直接确认。扣库更新 0 行或其他事务失败时回滚并请求重试。超出重试上限的消息需要从死信队列核查。 [当前实现边界](#当前实现边界)
 
 ## 快速开始
 
@@ -185,7 +185,7 @@ bash scripts/start-nginx.sh
 bash scripts/status.sh
 ```
 
-MySQL 脚本会在数据卷首次初始化时导入 [sql/miaosha.sql](sql/miaosha.sql)，其中包含 `DROP TABLE` 和演示数据，只应用于允许重建的测试库。已有数据卷不会重新导入。Dockerfile 的构建输入是根目录 `miaosha.jar`，每次修改 Java 代码后都需要重新打包并复制。
+MySQL 会在新数据卷中依次导入 [基础 SQL](sql/miaosha.sql) 和 [消费记录表增量 SQL](sql/upgrade-001-stock-consume-log.sql)。基础 SQL 含 `DROP TABLE` 和演示数据，适用于允许重建的测试库。已有库升级前核对数据库、旧消费位点、延迟重试、半消息和死信，单独执行增量 SQL。旧 Redis 标记和 `stock_log.status=2` 无法证明某笔 MySQL 库存已经扣减，历史消息须先核查再决定是否重放。Dockerfile 的构建输入是根目录 `miaosha.jar`，每次修改 Java 代码后都需要重新打包并复制。
 
 | 服务 | 宿主机端口 | 容器端口 |
 | --- | --- | --- |
@@ -223,7 +223,7 @@ Compose 与分服务脚本的数据库数据卷不同，也会争用部分容器
 
 ### 4. 本机 Maven 运行
 
-先准备 MySQL、Redis、RocketMQ，并在隔离测试库中导入 `sql/miaosha.sql`。本机访问分服务脚本启动的依赖时，MySQL 使用 `127.0.0.1:13306`、Redis 使用 `127.0.0.1:16379`、NameServer 使用 `127.0.0.1:19876`，Broker 通告地址还须能从本机访问。
+先准备 MySQL、Redis、RocketMQ。在全新隔离测试库中依次执行 `sql/miaosha.sql` 和 `sql/upgrade-001-stock-consume-log.sql`。本机访问分服务脚本启动的依赖时，MySQL 使用 `127.0.0.1:13306`、Redis 使用 `127.0.0.1:16379`、NameServer 使用 `127.0.0.1:19876`，Broker 通告地址还须能从本机访问。
 
 先检查 `.env` 中本机使用的 `SPRING_DATASOURCE_URL`、Redis 地址及 MQ 地址；`MYSQL_HOST` / `MYSQL_PORT` 用于 Compose，不能直接视为本机数据库地址。Bash 中手动导出配置后运行：
 
@@ -327,6 +327,8 @@ curl -X POST 'http://localhost:18091/order/createorder?token=<登录token>' \
 
 **库存开关并非任意组合都可用。** 默认 `lua + mq-async-sync=true` 执行一次 Redis 扣减、一次消费者 DB 扣减；`lua + false` 执行 Redis 扣减、订单事务内 DB 扣减。当前 `mysql` 模式本身已经扣过数据库库存，后续同步或异步分支还会再尝试扣一次，不能直接把它当作正确的 MySQL 基线。切换实验模式前需梳理重复扣减和补偿分支。
 
+同一消费组的实例使用相同的 `mq-async-sync` 设置。切换前冻结新订单，核查在途消息、延迟重试、半消息和历史重放范围。需要同时保留两种模式时，使用隔离的 Topic 和消费组或独占 Broker。新版已扣库的消息交给旧消费者重放，仍可能重复扣减。 [切换与回退说明](docs/plans/2026-11-22-01-mq-stock-consume-idempotency.md#7-风险与回滚)
+
 ## 数据模型与 Redis Key
 
 | MySQL 表 | 用途 |
@@ -336,6 +338,7 @@ curl -X POST 'http://localhost:18091/order/createorder?token=<登录token>' \
 | `promo` | 商品活动、起止时间与活动价格 |
 | `order_info` | 用户、商品、活动、购买数量与成交金额 |
 | `stock_log` | UUID 库存流水；1 初始、2 下单成功、3 失败标记 |
+| `stock_consume_log` | 已提交的异步库存扣减凭证，`stock_log_id` 为主键，记录商品、数量和时间 |
 | `sequence_info` | 订单号序列，查询使用 `SELECT ... FOR UPDATE` |
 
 订单号由日期、补齐到 6 位的数据库序列和固定后缀 `00` 拼接。表字段定义见 [初始化 SQL](sql/miaosha.sql)，实际库存更新使用 `WHERE item_id = ? AND stock >= ?` 防止单次 SQL 把数据库库存减为负数。
@@ -348,7 +351,6 @@ curl -X POST 'http://localhost:18091/order/createorder?token=<登录token>' \
 | `promo_door_count_{promoId}` | 令牌大闸，发布时库存 × 5，无显式 TTL |
 | `promo_item_stock_{iid}` | Redis 可售库存，无显式 TTL |
 | `promo_item_stock_invalid_{iid}` | 售罄标记，回补或发布时清除，无显式 TTL |
-| `stock_log_synced_{stockLogId}` | 消费去重，1 小时 |
 | `item_{iid}` | 商品详情，30 分钟；Caffeine 同名 Key 为 300 秒 |
 | `item_validate_{iid}` | 下单商品校验缓存，10 分钟 |
 | `user_calidate_{uid}` | 当前源码实际读写的用户校验 Key；TTL 存在拼写错误，见下文 |
@@ -390,9 +392,9 @@ Redis 初始库存 - Redis 剩余库存
 
 | 项目 | 当前行为与限制 |
 | --- | --- |
-| 消费幂等 | Redis `setIfAbsent` 后才扣 DB；加锁、设置 TTL、扣库不是同一原子事务。拿到锁后宕机或 DB 失败可能导致重投被跳过，锁过期后的重投也可能再次扣库；尚无数据库唯一消费记录 |
-| DB 库存同步 | Consumer 未检查 `decreaseStock` 的影响行数；可能在更新 0 行时仍确认消费成功 |
-| 事务回滚 | `BusinessException` 继承受检 `Exception`，`createOrder` 的 `@Transactional` 未声明 `rollbackFor`；不能假设所有业务异常都触发回滚。Redis 补偿回调只在事务实际回滚时执行 |
+| 消费幂等 | 异步消费者以 `stock_log_id` 判重，与 MySQL 库存扣减同事务。已提交的消费记录需保留到允许重放的窗口之后 |
+| DB 库存同步 | 异步消费者检查更新行数并对失败请求重试。同步分支仍有行数检查和库存模式组合的边界；积压和死信仍需核查 |
+| 事务回滚 | `createOrder` 声明 `rollbackFor = Exception.class`，覆盖受检业务异常。Redis 补偿回调在本地事务明确回滚时执行 |
 | Redis 补偿 | `increaseStock` 的 INCR 与删除售罄标记分开执行；重试缺少补偿幂等记录，进程崩溃或重试也有恢复窗口。补偿失败只记录日志，尚无自动对账任务 |
 | 一人一单与请求幂等 | 尚未实现；秒杀令牌不会在下单后消费，允许同一用户重复下单；大闸限制令牌发放次数，不能限制令牌被使用的总次数 |
 | 缓存一致性 | 没有主动失效和跨实例本地缓存广播；库存、销量、活动状态可能在 TTL 内陈旧。用户缓存读写 `user_calidate_`，过期设置却使用 `user_validate_`，实际 Key 未被设置 TTL |
~~~

### 本机开发运行指南-Windows后端与Docker依赖.md

**修改 · +14 / −1**。补上已有测试库迁移和 MySQL 集成测试入口。 [文件](../../docs/develop/本机开发运行指南-Windows后端与Docker依赖.md)

~~~diff
@@ -190,6 +190,19 @@ exit;
 
 应能看到商品 `6`、`7`，以及活动 `1`。`sql/miaosha.sql` **包含 DROP TABLE，只用于可重建的测试库**。自动导入仅在 MySQL 数据目录首次初始化时发生；已有命名卷不会重新导入，修改密码变量也不会自动修改已有库的密码。
 
+项目的 `sql/` 目录被挂载到 MySQL 容器的 `/docker-entrypoint-initdb.d/`。MySQL 首次初始化空数据目录时，先运行 `miaosha.sql` 建基础表，再运行 `upgrade-001-stock-consume-log.sql` 增加消费记录表。已有数据目录要手动执行增量 SQL。
+
+如果按本节启动了本机测试容器 `miaosha-mysql`，用 PowerShell 执行 `docker exec -it miaosha-mysql mysql -uroot -p miaosha`。输入本机测试库的密码后，在 `mysql>` 提示符中先执行 `SELECT DATABASE();`，确认结果是本次要升级的测试库，再执行下列命令。
+
+```sql
+SOURCE /docker-entrypoint-initdb.d/upgrade-001-stock-consume-log.sql
+SHOW CREATE TABLE stock_consume_log;
+```
+
+`SOURCE` 由 MySQL 命令行客户端读取文件。客户端在容器内运行时用上面的容器路径，从 Windows 直接运行 `mysql.exe` 时用项目里的 SQL 文件路径，例如 `SOURCE D:/workspace/dianshangmiaosha/sql/upgrade-001-stock-consume-log.sql`。看到 `ENGINE=InnoDB` 和 `PRIMARY KEY (stock_log_id)` 表示建表符合预期。已有测试库只运行增量文件。 [MySQL SOURCE 文档](https://dev.mysql.com/doc/refman/8.4/en/mysql-commands.html)
+
+`miaosha_stock_consume_it` 是给 JUnit 用例单独建的本机 MySQL 5.7 库。`MIAOSHA_STOCK_IT_JDBC_URL`、`MIAOSHA_STOCK_IT_USERNAME`、`MIAOSHA_STOCK_IT_PASSWORD` 只供测试进程使用。测试会核对目标库，写入和清理自己建立的 `item_stock`、`stock_log` 和 `stock_consume_log` 记录。可以按构建记录从空库搭建、运行并核对七个结果。 [人工验收步骤](../build-logs/2026-11-22-01-mq-stock-consume-idempotency.md#7-人工测试与验收)
+
 ### 4.4 创建 Redis
 
 ```powershell
@@ -438,7 +451,7 @@ SELECT COUNT(*) AS order_count, COALESCE(SUM(amount), 0) AS ordered_amount
 FROM order_info WHERE item_id = 6 AND promo_id = 1;
 ```
 
-服务成功启动和单次下单成功，只代表相应路径通过。当前消费幂等、异常事务回滚、补偿和重复请求仍有已知边界，见 [根 README](../../README.md#当前实现边界)；本指南没有修复这些业务问题。
+服务成功启动和单次下单成功只覆盖正常路径。验证异步 MySQL 扣库时，从本轮 `stock_log` 取库存流水号，再核对 `stock_consume_log` 中的一条消费记录及 `item_stock` 的库存变化。补偿和重复下单等业务边界见 [根 README](../../README.md#当前实现边界)。
 
 ## 8. 以后每天怎么运行
 
~~~

### 2026-11-22-01-mq-stock-consume-idempotency.md

**修改 · +15 / −15**。记录计划完成状态与验收证据链接。 [文件](../../docs/plans/2026-11-22-01-mq-stock-consume-idempotency.md)

~~~diff
@@ -1,15 +1,15 @@
 ---
 plan_id: "2026-11-22-01-mq-stock-consume-idempotency"
 title: "用 MySQL 消费记录修复 MQ 库存漏扣和多扣"
-status: draft
+status: completed
 created_at: "2026-11-22"
-updated_at: "2026-11-22"
+updated_at: "2026-11-23"
 build_log: "docs/build-logs/2026-11-22-01-mq-stock-consume-idempotency.md"
 ---
 
 # 用 MySQL 消费记录修复 MQ 库存漏扣和多扣
 
-> 草案，待审查。对应 [Roadmap 第 2 步第 2 项](../roadmap/roadmap.md#5-第-2-步修复订单与-mq-的正确性)。
+> 已完成代码和隔离验收。对应 [Roadmap 第 2 步第 2 项](../roadmap/roadmap.md#5-第-2-步修复订单与-mq-的正确性)。实测结果见 [Build Log](../build-logs/2026-11-22-01-mq-stock-consume-idempotency.md)。
 
 ## 1. 先看结论
 
@@ -107,22 +107,22 @@ CREATE TABLE IF NOT EXISTS stock_consume_log (
 
 ### 第 1 步：准备隔离环境并复现旧问题
 
-- [ ] 在 JDK 8 下记录 Git 基线和已有工作区改动。用单独的测试库、Redis 和独占测试 Broker，开 `lua + mq-async-sync=true`。每轮用新流水号和库存充足的测试商品，从 `item_stock.stock=10` 开始，只下一单买 `1` 件。
-- [ ] **漏扣复现**。在旧 `MqConsumer` 设置 Redis TTL 后、调用扣库 SQL 前，针对首次消费临时返回 `RECONSUME_LATER`。保留 1 小时 TTL。记录第二次相同 `stockLogId` 因标记存在跳过，`item_stock.stock` 仍为 `10`。
-- [ ] **多扣复现**。旧代码把该场景的标记 TTL 临时改为 1 秒。首次扣库成功后、确认前临时返回 `RECONSUME_LATER`。确认重试到达前标记已经过期，记录同一个 `stockLogId` 两次扣减后 `item_stock.stock=8`。每轮保留首次与重投日志。实验改动单独撤回或留在隔离实验副本。
+- [x] 在 JDK 8 下记录 Git 基线和已有工作区改动。用单独的测试库、Redis 和独占测试 Broker，开 `lua + mq-async-sync=true`。每轮用新流水号和库存充足的测试商品，从 `item_stock.stock=10` 开始，只下一单买 `1` 件。
+- [x] **漏扣复现**。在旧 `MqConsumer` 设置 Redis TTL 后、调用扣库 SQL 前，针对首次消费临时返回 `RECONSUME_LATER`。保留 1 小时 TTL。记录第二次相同 `stockLogId` 因标记存在跳过，`item_stock.stock` 仍为 `10`。
+- [x] **多扣复现**。旧代码把该场景的标记 TTL 临时改为 1 秒。首次扣库成功后、确认前临时返回 `RECONSUME_LATER`。确认重试到达前标记已经过期，记录同一个 `stockLogId` 两次扣减后 `item_stock.stock=8`。每轮保留首次与重投日志。实验改动单独撤回或留在隔离实验副本。
 - **完成标志**。两轮分别有业务流水号、Redis 标记状态和 MySQL 前后快照，可复核是同一条消息的变化。
 
 ### 第 2 步：先建表，再完成数据库事务
 
-- [ ] 应用增量 SQL。用 `SHOW CREATE TABLE stock_consume_log` 和 `SHOW CREATE TABLE item_stock` 核对 InnoDB、主键与商品索引。保证已有 `order_info`、`stock_log` 和库存数据不被初始化脚本覆盖。
-- [ ] 实现 Mapper 和 `StockConsumeService.consumeOnce`。在真实 MySQL 上先跑首次消费、顺序重投、主键并发竞争、扣库更新 0 行与回滚测试。
+- [x] 应用增量 SQL。用 `SHOW CREATE TABLE stock_consume_log` 和 `SHOW CREATE TABLE item_stock` 核对 InnoDB、主键与商品索引。保证已有 `order_info`、`stock_log` 和库存数据不被初始化脚本覆盖。
+- [x] 实现 Mapper 和 `StockConsumeService.consumeOnce`。在真实 MySQL 上先跑首次消费、顺序重投、主键并发竞争、扣库更新 0 行与回滚测试。
 - **完成标志**。一笔流水最多留下 1 条已提交的消费记录。扣库和记录对外同时生效，任何一项失败时，两项都能回滚。
 
 ### 第 3 步：接入 RocketMQ 并回归旧故障
 
-- [ ] 接入新服务，移除 `MqConsumer` 对 `stock_log_synced_*` 的读写，保留 RocketMQ 启动、订阅和同步开关语义。
-- [ ] 在真实 Broker 上验证「第一次写记录后失败，重投后扣成」以及「第一次提交后未确认，重投时跳过」。测试故障通过测试夹具或隔离实验副本触发，发布代码只保留正常消费逻辑。
-- [ ] 更新运行文档。记录测试命令、退出码、测试数据、消息日志和查询结果，最后核对 Git diff。
+- [x] 接入新服务，移除 `MqConsumer` 对 `stock_log_synced_*` 的读写，保留 RocketMQ 启动、订阅和同步开关语义。
+- [x] 在真实 Broker 上验证「第一次写记录后失败，重投后扣成」以及「第一次提交后未确认，重投时跳过」。测试故障通过测试夹具或隔离实验副本触发，发布代码只保留正常消费逻辑。
+- [x] 更新运行文档。记录测试命令、退出码、测试数据、消息日志和查询结果，最后核对 Git diff。
 - **完成标志**。两种故障恢复后，测试订单只增加 1 行，`item_stock.stock` 都为 `9`，`stock_consume_log` 对应流水都只有 1 行。
 
 ## 6. 如何测试、怎样算完成
@@ -148,9 +148,9 @@ mvn '-Dtest=StockConsumeMySqlIT' test
 mvn clean package
 ```
 
-- [ ] 新旧两轮对比记录了 `stockLogId`、消息处理结果、`item_stock.stock` 和实际消费记录。抽查真实下单时同时比对 Redis 库存及 `order_info` 增量。
-- [ ] 正常、重投和失败的库存变化符合上表。尤其保留「扣库后抛错」「提交后重投」两份可复核证据。
-- [ ] Maven 构建、原有回归与真实 MySQL 及 MQ 验证结果分别记录。吞吐留给实际压测。
+- [x] 新旧两轮对比记录了 `stockLogId`、消息处理结果、`item_stock.stock` 和实际消费记录。抽查真实下单时同时比对 Redis 库存及 `order_info` 增量。
+- [x] 正常、重投和失败的库存变化符合上表。尤其保留「扣库后抛错」「提交后重投」两份可复核证据。
+- [x] Maven 构建、原有回归与真实 MySQL 及 MQ 验证结果分别记录。吞吐留给实际压测。
 
 ## 7. 风险与回滚
 
@@ -176,4 +176,4 @@ SELECT item_id, stock FROM item_stock WHERE item_id = 6;
 
 - 审查消费事务是否通过 Spring 代理，主键冲突分支是否核对载荷，扣库更新 0 行是否回滚。
 - 审查旧消息切换和回退边界。首次验收按本机隔离环境准备，具体测试库、Broker 地址执行时核定。
-- 当前状态为待审查。收到明确按本计划实施的请求后，开始代码和测试改造。
+- 当前状态为已完成，执行证据见 [Build Log](../build-logs/2026-11-22-01-mq-stock-consume-idempotency.md)。
~~~
