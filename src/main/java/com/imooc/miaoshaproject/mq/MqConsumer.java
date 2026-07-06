package com.imooc.miaoshaproject.mq;

import com.alibaba.fastjson.JSON;
import com.imooc.miaoshaproject.dao.ItemStockDOMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Created by hzllb on 2019/2/23.
 */
@Component
public class MqConsumer {

    private DefaultMQPushConsumer consumer;

    @Value("${mq.nameserver.addr}")
    private String nameAddr;

    @Value("${mq.topicname}")
    private String topicName;

    @Autowired
    private ItemStockDOMapper itemStockDOMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Value("${miaosha.mq-async-sync:true}")
    private boolean mqAsyncSync;

    @PostConstruct
    public void init() throws MQClientException {
        consumer = new DefaultMQPushConsumer("stock_consumer_group");
        consumer.setNamesrvAddr(nameAddr);
        consumer.subscribe(topicName, "*");

        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
                                                            ConsumeConcurrentlyContext context) {
                for (MessageExt msg : msgs) {
                    String jsonString = new String(msg.getBody());
                    Map<String, Object> map = JSON.parseObject(jsonString, Map.class);
                    Integer itemId = (Integer) map.get("itemId");
                    Integer amount = (Integer) map.get("amount");
                    String stockLogId = (String) map.get("stockLogId");

                    // 幂等：Redis setIfAbsent 防重复消费
                    String lockKey = "stock_log_synced_" + stockLogId;
                    Boolean firstTime = redisTemplate.opsForValue().setIfAbsent(lockKey, "1");
                    if (Boolean.FALSE.equals(firstTime)) {
                        // 已处理过，跳过
                        continue;
                    }
                    // setIfAbsent 成功后单独设过期时间
                    redisTemplate.expire(lockKey, 1, TimeUnit.HOURS);

                    // 真正扣减数据库库存（mq-async-sync=false 时跳过，已同步扣）
                    if (mqAsyncSync) {
                        itemStockDOMapper.decreaseStock(itemId, amount);
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });

        consumer.start();
    }
}
