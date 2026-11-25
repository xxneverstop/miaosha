package com.imooc.miaoshaproject.service.impl;

import com.imooc.miaoshaproject.dao.ItemStockDOMapper;
import com.imooc.miaoshaproject.dao.StockConsumeLogMapper;
import com.imooc.miaoshaproject.dao.StockLogDOMapper;
import com.imooc.miaoshaproject.dataobject.StockConsumeLogDO;
import com.imooc.miaoshaproject.dataobject.StockLogDO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class StockConsumeService {
    private static final int ORDER_COMMITTED_STATUS = 2;

    private final StockLogDOMapper stockLogDOMapper;
    private final StockConsumeLogMapper stockConsumeLogMapper;
    private final ItemStockDOMapper itemStockDOMapper;

    public StockConsumeService(StockLogDOMapper stockLogDOMapper, StockConsumeLogMapper stockConsumeLogMapper,
                               ItemStockDOMapper itemStockDOMapper) {
        this.stockLogDOMapper = stockLogDOMapper;
        this.stockConsumeLogMapper = stockConsumeLogMapper;
        this.itemStockDOMapper = itemStockDOMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void consumeOnce(String stockLogId, int itemId, int amount) {
        // 已提交的订单流水用于核对本次库存扣减的商品和数量。
        StockLogDO stockLog = stockLogDOMapper.selectByPrimaryKey(stockLogId);
        if (stockLog == null) {
            throw new IllegalStateException("库存流水不存在 stockLogId=" + stockLogId);
        }
        if (!Objects.equals(stockLog.getStatus(), ORDER_COMMITTED_STATUS)) {
            throw new IllegalStateException("库存流水的订单未提交 stockLogId=" + stockLogId
                    + ", status=" + stockLog.getStatus());
        }
        if (!Objects.equals(stockLog.getItemId(), itemId) || !Objects.equals(stockLog.getAmount(), amount)) {
            throw new IllegalStateException("库存消息与原流水的商品或数量不一致 stockLogId=" + stockLogId
                    + ", itemId=" + itemId + ", amount=" + amount);
        }

        StockConsumeLogDO consumeLog = new StockConsumeLogDO();
        consumeLog.setStockLogId(stockLogId);
        consumeLog.setItemId(itemId);
        consumeLog.setAmount(amount);

        // 由数据库唯一键决定本次消费是否负责扣库。
        int insertedRows;
        try {
            insertedRows = stockConsumeLogMapper.insert(consumeLog);
        } catch (DuplicateKeyException duplicate) {
            // 锁定读能看到并发事务刚提交的记录，即使先前查询已建立事务快照。
            StockConsumeLogDO existing = stockConsumeLogMapper.selectByStockLogIdForShare(stockLogId);
            if (existing == null || !Objects.equals(existing.getItemId(), itemId)
                    || !Objects.equals(existing.getAmount(), amount)) {
                throw new IllegalStateException("已存在的消费流水缺失或与消息不一致 stockLogId=" + stockLogId
                        + ", itemId=" + itemId + ", amount=" + amount, duplicate);
            }
            return;
        }
        if (insertedRows != 1) {
            throw new IllegalStateException("消费流水写入行数异常 stockLogId=" + stockLogId
                    + ", insertedRows=" + insertedRows);
        }

        int affectedRows = itemStockDOMapper.decreaseStock(itemId, amount);
        if (affectedRows != 1) {
            // 让消费记录随失败的扣库事务回滚，保留重试机会。
            throw new IllegalStateException("库存扣减更新行数异常 stockLogId=" + stockLogId + ", itemId=" + itemId
                    + ", amount=" + amount + ", affectedRows=" + affectedRows);
        }
    }
}
