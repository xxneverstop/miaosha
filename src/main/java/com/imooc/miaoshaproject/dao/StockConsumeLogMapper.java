package com.imooc.miaoshaproject.dao;

import com.imooc.miaoshaproject.dataobject.StockConsumeLogDO;
import org.apache.ibatis.annotations.Param;

public interface StockConsumeLogMapper {
    int insert(StockConsumeLogDO log);

    StockConsumeLogDO selectByStockLogIdForShare(@Param("stockLogId") String stockLogId);
}
