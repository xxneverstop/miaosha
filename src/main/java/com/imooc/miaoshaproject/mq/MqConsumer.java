package com.imooc.miaoshaproject.mq;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imooc.miaoshaproject.service.impl.StockConsumeService;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class MqConsumer implements MessageListenerConcurrently {

    private static final Logger LOGGER = LoggerFactory.getLogger(MqConsumer.class);
    // 拒绝有歧义的 JSON，并从解析异常的位置描述中移除原始消息。
    private static final ObjectMapper MESSAGE_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .disable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION);

    private final StockConsumeService stockConsumeService;
    private final boolean mqAsyncSync;

    @Value("${mq.nameserver.addr}")
    private String nameAddr;

    @Value("${mq.topicname}")
    private String topicName;

    public MqConsumer(StockConsumeService stockConsumeService,
                      @Value("${miaosha.mq-async-sync:true}") boolean mqAsyncSync) {
        this.stockConsumeService = stockConsumeService;
        this.mqAsyncSync = mqAsyncSync;
    }

    @PostConstruct
    public void init() throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("stock_consumer_group");
        consumer.setNamesrvAddr(nameAddr);
        consumer.subscribe(topicName, "*");
        consumer.registerMessageListener(this);
        consumer.start();
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        // 同步模式在下单事务内扣库，监听器只确认消息。
        if (!mqAsyncSync) {
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }

        for (MessageExt message : messages) {
            String stockLogId = null;
            try {
                String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(message.getBody())).toString();
                JsonNode payload = MESSAGE_MAPPER.readTree(json);
                if (payload == null || !payload.isObject()) {
                    throw new IllegalArgumentException("Stock message must contain a JSON object");
                }
                String id = payload.path("stockLogId").textValue();
                if (StringUtils.isBlank(id) || id.length() > 64) {
                    throw new IllegalArgumentException("stockLogId must be a non-blank string of at most 64 characters");
                }
                stockLogId = id;
                int itemId = positiveInt(payload, "itemId");
                int amount = positiveInt(payload, "amount");
                // Spring 事务代理完成提交后才返回，消费者随后才能确认成功。
                stockConsumeService.consumeOnce(stockLogId, itemId, amount);
            } catch (IOException e) {
                LOGGER.warn("Invalid UTF-8 JSON stock message, msgId={}, reconsumeTimes={}, reason={}",
                        message.getMsgId(), message.getReconsumeTimes(), e.getClass().getSimpleName());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            } catch (RuntimeException e) {
                LOGGER.warn("Stock message consumption failed, msgId={}, stockLogId={}, reconsumeTimes={}",
                        message.getMsgId(), stockLogId, message.getReconsumeTimes(), e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private static int positiveInt(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw new IllegalArgumentException(field + " must be a positive int");
        }
        return value.intValue();
    }
}
