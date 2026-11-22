package com.imooc.miaoshaproject.service.impl;

import com.imooc.miaoshaproject.dao.ItemStockDOMapper;
import com.imooc.miaoshaproject.dao.OrderDOMapper;
import com.imooc.miaoshaproject.dao.SequenceDOMapper;
import com.imooc.miaoshaproject.dao.StockLogDOMapper;
import com.imooc.miaoshaproject.dao.UserDOMapper;
import com.imooc.miaoshaproject.dataobject.OrderDO;
import com.imooc.miaoshaproject.dataobject.SequenceDO;
import com.imooc.miaoshaproject.dataobject.StockLogDO;
import com.imooc.miaoshaproject.error.BusinessException;
import com.imooc.miaoshaproject.error.EmBusinessError;
import com.imooc.miaoshaproject.service.ItemService;
import com.imooc.miaoshaproject.service.OrderService;
import com.imooc.miaoshaproject.service.UserService;
import com.imooc.miaoshaproject.service.model.ItemModel;
import com.imooc.miaoshaproject.service.model.OrderModel;
import com.imooc.miaoshaproject.service.model.UserModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Created by hzllb on 2018/11/18.
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private SequenceDOMapper sequenceDOMapper;

    @Autowired
    private ItemService itemService;

    @Autowired
    private UserService userService;

    @Autowired
    private OrderDOMapper orderDOMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private StockLogDOMapper stockLogDOMapper;

    @Autowired
    private ItemStockDOMapper itemStockDOMapper;

    @Value("${miaosha.mq-async-sync:true}")
    private boolean mqAsyncSync;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderModel createOrder(Integer userId, Integer itemId, Integer promoId, Integer amount, String stockLogId) throws BusinessException {
        //1.校验下单状态,下单的商品是否存在，用户是否合法，购买数量是否正确
        //    ItemModel itemModel = itemService.getItemById(itemId);
            ItemModel itemModel = itemService.getItemByIdInCache(itemId);
            if(itemModel == null){
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"商品信息不存在");
            }

            UserModel userModel = userService.getUserByIdInCache(userId);
            if(userModel == null){
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"用户信息不存在");
        }
        if(amount <= 0 || amount > 99){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"数量信息不正确");
        }

        //校验活动信息
        if(promoId != null){
            //（1）校验对应活动是否存在这个适用商品
            if(promoId.intValue() != itemModel.getPromoModel().getId()){
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"活动信息不正确");
                //（2）校验活动是否正在进行中
            }else if(itemModel.getPromoModel().getStatus().intValue() != 2) {
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"活动信息还未开始");
            }
        }

        //2.落单减redis库存
        boolean result = itemService.decreaseStock(itemId,amount);
        if(!result){
            throw new BusinessException(EmBusinessError.STOCK_NOT_ENOUGH);
        }

        // 当前 Lua 模式已预扣 Redis 库存，须在后续写库前登记回调，处理数据库回滚后的补偿。
        // 此处只登记回调，不会立即回补；MySQL 事务不会自动撤销 Redis 的修改。
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronizationAdapter() {
                    @Override
                    // Spring 在本次数据库事务提交或回滚完成后调用；本链路仍由当前下单线程执行。
                    public void afterCompletion(int status) {
                        // status 是 Spring 的事务完成状态，与 stock_log.status 不同；仅明确回滚时回补。
                        if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                            // 最多尝试 3 次（含首次），成功后立即退出。
                            for (int i = 0; i < 3; i++) {
                                try {
                                    itemService.increaseStock(itemId, amount);
                                    return;   // 成功，退出
                                } catch (Exception e) {
                                    if (i < 2) {
                                        try {
                                            Thread.sleep(100L * (i + 1));
                                        } catch (InterruptedException ignored) {
                                        }
                                    } else {
                                        // 3 次都失败只记录日志，保留 stockLogId 供后续核查；当前没有自动对账。
                                        log.error("Redis回补失败！itemId={}, amount={}, stockLogId={}",
                                                itemId, amount, stockLogId, e);
                                    }
                                }
                            }
                        }
                    }
                });




        //3.订单入库
        OrderModel orderModel = new OrderModel();
        orderModel.setUserId(userId);
        orderModel.setItemId(itemId);
        orderModel.setAmount(amount);
        if(promoId != null){
            orderModel.setItemPrice(itemModel.getPromoModel().getPromoItemPrice());
        }else{
            orderModel.setItemPrice(itemModel.getPrice());
        }
        orderModel.setPromoId(promoId);
        orderModel.setOrderPrice(orderModel.getItemPrice().multiply(new BigDecimal(amount)));

        //生成交易流水号,订单号
        orderModel.setId(generateOrderNo());
        OrderDO orderDO = convertFromOrderModel(orderModel);
        // 写入订单
        orderDOMapper.insertSelective(orderDO);

        // 写入销量
        itemService.increaseSales(itemId,amount);

        // 设置库存流水状态为成功
        StockLogDO stockLogDO = stockLogDOMapper.selectByPrimaryKey(stockLogId);
        if (stockLogDO == null) {
            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        }
        stockLogDO.setStatus(2);   // 2 = 成功
        stockLogDOMapper.updateByPrimaryKeySelective(stockLogDO);

        //// 临时故障注入：验证写入后发生业务异常时能否回滚，实验结束后删除
        //if (itemId == 6 && amount == 1) {
        //    System.out.println("STEP2_REPRO orderId=" + orderModel.getId()
        //            + ", stockLogId=" + stockLogId);
        //    throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        //}

        // mq-async-sync=false 时：同步更新 MySQL 库存（不经过 MQ），方便压测对比
        if (!mqAsyncSync) {
            itemStockDOMapper.decreaseStock(itemId, amount);
        }

        //// 在订单入库后、返回前端前，恢复事务后异步更新库存的逻辑
        //TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
        //    @Override
        //    public void afterCommit() {
        //        // 事务提交成功后，异步更新数据库库存
        //        boolean mqResult = itemService.asyncDecreaseStock(itemId, amount);
        //    }
        //});

        //4.返回前端
        return orderModel;
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private String generateOrderNo(){
        //订单号有16位
        StringBuilder stringBuilder = new StringBuilder();
        //前8位为时间信息，年月日
        LocalDateTime now = LocalDateTime.now();
        String nowDate = now.format(DateTimeFormatter.ISO_DATE).replace("-","");
        stringBuilder.append(nowDate);

        //中间6位为自增序列
        //获取当前sequence
        int sequence = 0;
        SequenceDO sequenceDO =  sequenceDOMapper.getSequenceByName("order_info");
        sequence = sequenceDO.getCurrentValue();
        sequenceDO.setCurrentValue(sequenceDO.getCurrentValue() + sequenceDO.getStep());
        sequenceDOMapper.updateByPrimaryKeySelective(sequenceDO);
        String sequenceStr = String.valueOf(sequence);
        for(int i = 0; i < 6-sequenceStr.length();i++){
            stringBuilder.append(0);
        }
        stringBuilder.append(sequenceStr);


        //最后2位为分库分表位,暂时写死
        stringBuilder.append("00");

        return stringBuilder.toString();
    }
    private OrderDO convertFromOrderModel(OrderModel orderModel){
        if(orderModel == null){
            return null;
        }
        OrderDO orderDO = new OrderDO();
        BeanUtils.copyProperties(orderModel,orderDO);
        orderDO.setItemPrice(orderModel.getItemPrice().doubleValue());
        orderDO.setOrderPrice(orderModel.getOrderPrice().doubleValue());
        return orderDO;
    }
}
