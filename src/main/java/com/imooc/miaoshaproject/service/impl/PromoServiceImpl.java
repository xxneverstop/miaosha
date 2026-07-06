package com.imooc.miaoshaproject.service.impl;

import com.imooc.miaoshaproject.dao.PromoDOMapper;
import com.imooc.miaoshaproject.dataobject.PromoDO;
import com.imooc.miaoshaproject.service.ItemService;
import com.imooc.miaoshaproject.service.PromoService;
import com.imooc.miaoshaproject.service.UserService;
import com.imooc.miaoshaproject.service.model.ItemModel;
import com.imooc.miaoshaproject.service.model.PromoModel;
import com.imooc.miaoshaproject.service.model.UserModel;
import org.joda.time.DateTime;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Created by hzllb on 2018/11/18.
 */
@Service
public class PromoServiceImpl implements PromoService {

    @Autowired
    private PromoDOMapper promoDOMapper;

    @Autowired
    private ItemService itemService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private UserService userService;


    @Override
    public String generateSecondKillToken(Integer promoId, Integer itemId, Integer userId) {
        // 第1步：判断是否库存已售罄
        if (redisTemplate.hasKey("promo_item_stock_invalid_" + itemId)) {
            return null;
        }

        // 第2步：校验活动是否存在
        PromoDO promoDO = promoDOMapper.selectByPrimaryKey(promoId);
        PromoModel promoModel = convertFromDataObject(promoDO);
        if (promoModel == null) {
            return null;
        }

        // 第3步：判断活动状态（复用时间判断逻辑）
        if (promoModel.getStartDate().isAfterNow()) {
            promoModel.setStatus(1);
        } else if (promoModel.getEndDate().isBeforeNow()) {
            promoModel.setStatus(3);
        } else {
            promoModel.setStatus(2);
        }
        // 只有进行中的活动才发放令牌
        if (promoModel.getStatus().intValue() != 2) {
            return null;
        }

        // 第4步：校验商品和用户是否存在
        ItemModel itemModel = itemService.getItemByIdInCache(itemId);
        if (itemModel == null) {
            return null;
        }
        UserModel userModel = userService.getUserByIdInCache(userId);
        if (userModel == null) {
            return null;
        }

        // 第5步：秒杀大闸计数 -1（详见"秒杀大闸"章节）
        long result = redisTemplate.opsForValue().increment("promo_door_count_" + promoId, -1);
        if (result < 0) {
            return null;
        }

        // 第6步：生成 token 并存入 Redis（5分钟有效期）
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                "promo_token_" + promoId + "_userid_" + userId + "_itemid_" + itemId, token);
        redisTemplate.expire(
                "promo_token_" + promoId + "_userid_" + userId + "_itemid_" + itemId, 30, TimeUnit.MINUTES);

        return token;
    }

    @Override
    public PromoModel getPromoByItemId(Integer itemId) {
        //获取对应商品的秒杀活动信息
        PromoDO promoDO = promoDOMapper.selectByItemId(itemId);

        //dataobject->model
        PromoModel promoModel = convertFromDataObject(promoDO);
        if(promoModel == null){
            return null;
        }

        //判断当前时间是否秒杀活动即将开始或正在进行
        if(promoModel.getStartDate().isAfterNow()){
            promoModel.setStatus(1);
        }else if(promoModel.getEndDate().isBeforeNow()){
            promoModel.setStatus(3);
        }else{
            promoModel.setStatus(2);
        }
        return promoModel;
    }

    @Override
    public void publishPromo(Integer promoId) {
        PromoDO promoDO = promoDOMapper.selectByPrimaryKey(promoId);
        if(promoDO.getItemId() == null || promoDO.getItemId().intValue() == 0){
            return;
        }

        ItemModel itemModel = itemService.getItemById(promoDO.getItemId());

        // 库存预热到redis缓存
        redisTemplate.opsForValue().set("promo_item_stock_" + itemModel.getId(), itemModel.getStock());

        // 清除上一轮的售罄标记
        redisTemplate.delete("promo_item_stock_invalid_" + itemModel.getId());

        // 初始化秒杀大闸
        redisTemplate.opsForValue().set("promo_door_count_" + promoId, itemModel.getStock().intValue() * 5);
    }

    private PromoModel convertFromDataObject(PromoDO promoDO){
        if(promoDO == null){
            return null;
        }
        PromoModel promoModel = new PromoModel();
        BeanUtils.copyProperties(promoDO,promoModel);
        promoModel.setPromoItemPrice(new BigDecimal(promoDO.getPromoItemPrice()));
        promoModel.setStartDate(new DateTime(promoDO.getStartDate()));
        promoModel.setEndDate(new DateTime(promoDO.getEndDate()));
        return promoModel;
    }
}
