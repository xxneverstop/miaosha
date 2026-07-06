package com.imooc.miaoshaproject.service;

import com.imooc.miaoshaproject.service.model.PromoModel;

/**
 * Created by hzllb on 2018/11/18.
 */
public interface PromoService {
    // 新增这个方法（在 interface 中添加）
    String generateSecondKillToken(Integer promoId, Integer itemId, Integer userId);

    //根据itemid获取即将进行的或正在进行的秒杀活动
    PromoModel getPromoByItemId(Integer itemId);

    // 活动发布，实现活动发布后将活动库存load进redis，进行预热
    void publishPromo(Integer promoId);
}
