package com.imooc.miaoshaproject.mq;

import com.imooc.miaoshaproject.service.impl.StockConsumeService;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// MqConsumer 的入口单测。直接调用 RocketMQ 监听回调，检查消息校验、库存服务调用和返回给客户端的消费状态。
// 库存服务用能记录调用和注入故障的测试替身，无需启动 Broker 或 MySQL。真实扣库、事务回滚和流水去重由 StockConsumeMySqlIT 验证。
public class MqConsumerTest {

    // 验证一批合法消息都被转交给库存服务后，消费者才返回整批成功。
    @Test
    public void acknowledgesEveryValidMessageInBatch() {
        RecordingStockConsumeService service = new RecordingStockConsumeService();
        MqConsumer consumer = new MqConsumer(service, true);

        // 使用中文流水号覆盖 UTF-8 解码，再核对两次调用收到的流水号、商品 ID 和扣减数量。
        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, consumer.consumeMessage(Arrays.asList(
                stockMessage("流水-01"), stockMessage("S002")), null));
        assertEquals(Arrays.asList("流水-01:6:1", "S002:6:1"), service.calls);
    }

    // 验证非法消息在进入库存服务前被拦截，消费结果为 RECONSUME_LATER。
    @Test
    public void rejectsInvalidStockMessageWithoutCallingService() {
        RecordingStockConsumeService service = new RecordingStockConsumeService();
        MqConsumer consumer = new MqConsumer(service, true);
        List<MessageExt> invalidMessages = Arrays.asList(
                // 编码错误与 JSON 歧义。覆盖非法 UTF-8、错误编码、尾随对象和重复字段。
                message(new byte[]{(byte) 0xC3, 0x28}),
                message(stockJson("S001").getBytes(StandardCharsets.UTF_16LE)),
                message("not-json"),
                message(stockJson("S001") + "{}"),
                message("{\"stockLogId\":\"S001\",\"stockLogId\":\"S002\",\"itemId\":6,\"amount\":1}"),
                // 流水号必须存在、非空白且最多 64 字符，对应 stock_consume_log.stock_log_id。
                message("{\"itemId\":6,\"amount\":1}"),
                stockMessage(" "),
                stockMessage(String.join("", Collections.nCopies(65, "S"))),
                // 商品 ID 和数量必须是 int 范围内的正整数，小数和字符串也会被拒绝。
                message("{\"stockLogId\":\"S001\",\"itemId\":6,\"amount\":0}"),
                message("{\"stockLogId\":\"S001\",\"itemId\":-1,\"amount\":1}"),
                message("{\"stockLogId\":\"S001\",\"itemId\":6,\"amount\":2147483648}"),
                message("{\"stockLogId\":\"S001\",\"itemId\":6,\"amount\":1.0}"),
                message("{\"stockLogId\":\"S001\",\"itemId\":\"6\",\"amount\":1}"));

        // 每次只交给消费者一条坏消息，确保每种输入都单独经过校验。
        for (MessageExt invalid : invalidMessages) {
            assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER,
                    consumer.consumeMessage(Collections.singletonList(invalid), null));
        }
        assertTrue(service.calls.isEmpty());
    }

    // 验证批量处理途中抛异常时，消费者把整批标为需要重试。
    @Test
    public void requestsRetryForWholeBatchOnServiceFailure() {
        RecordingStockConsumeService service = new RecordingStockConsumeService();
        service.failingStockLogId = "S002";
        MqConsumer consumer = new MqConsumer(service, true);

        // S001 先处理成功，S002 抛出故障后停止遍历，调用记录中应没有 S003。
        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, consumer.consumeMessage(Arrays.asList(
                stockMessage("S001"), stockMessage("S002"), stockMessage("S003")), null));
        assertEquals(Arrays.asList("S001:6:1", "S002:6:1"), service.calls);
    }

    // 验证关闭异步扣库时，MQ 消费者只确认消息。扣库由下单事务处理。
    @Test
    public void synchronousModeAcknowledgesWithoutParsingOrConsuming() {
        RecordingStockConsumeService service = new RecordingStockConsumeService();
        MqConsumer consumer = new MqConsumer(service, false);

        // 坏消息能确认说明跳过了解析，合法消息没有服务调用说明也跳过了异步扣库。
        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, consumer.consumeMessage(Arrays.asList(
                message("not-json"), stockMessage("S001")), null));
        assertTrue(service.calls.isEmpty());
    }

    // 构造一条标准库存消息，只改变用于定位本次扣减的业务流水号。
    private static MessageExt stockMessage(String stockLogId) {
        return message(stockJson(stockLogId));
    }

    // 生成商品 6 扣 1 件的 JSON 载荷，也供异常用例改写编码或拼接非法内容。
    private static String stockJson(String stockLogId) {
        return "{\"stockLogId\":\"" + stockLogId + "\",\"itemId\":6,\"amount\":1}";
    }

    // 用正常消息的 UTF-8 编码把任意测试文本封装成回调参数。
    private static MessageExt message(String json) {
        return message(json.getBytes(StandardCharsets.UTF_8));
    }

    // 直接传入原始消息体，保留错误编码和非法字节，供消费者自己解码。
    private static MessageExt message(byte[] body) {
        MessageExt message = new MessageExt();
        message.setMsgId("test-message");
        message.setBody(body);
        return message;
    }

    // 记录消费者传来的参数，必要时让指定流水抛异常，模拟库存服务处理失败。
    private static class RecordingStockConsumeService extends StockConsumeService {
        private final List<String> calls = new ArrayList<>();
        private String failingStockLogId;

        // consumeOnce 已由替身实现，测试不会访问父类的数据库 Mapper。
        private RecordingStockConsumeService() {
            super(null, null, null);
        }

        // 先记录调用再注入失败，方便区分尝试后失败和根本没有执行的消息。
        @Override
        public void consumeOnce(String stockLogId, int itemId, int amount) {
            calls.add(stockLogId + ":" + itemId + ":" + amount);
            if (stockLogId.equals(failingStockLogId)) {
                throw new IllegalStateException("Test failure in consumption transaction");
            }
        }
    }
}
