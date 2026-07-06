package com.imooc.miaoshaproject.service.impl;

import com.imooc.miaoshaproject.dao.ItemDOMapper;
import com.imooc.miaoshaproject.dao.ItemStockDOMapper;
import com.imooc.miaoshaproject.dao.StockLogDOMapper;
import com.imooc.miaoshaproject.dataobject.ItemDO;
import com.imooc.miaoshaproject.dataobject.ItemStockDO;
import com.imooc.miaoshaproject.dataobject.StockLogDO;
import com.imooc.miaoshaproject.error.BusinessException;
import com.imooc.miaoshaproject.error.EmBusinessError;
import com.imooc.miaoshaproject.mq.MqProducer;
import com.imooc.miaoshaproject.service.ItemService;
import com.imooc.miaoshaproject.service.PromoService;
import com.imooc.miaoshaproject.service.model.ItemModel;
import com.imooc.miaoshaproject.service.model.PromoModel;
import com.imooc.miaoshaproject.validator.ValidationResult;
import com.imooc.miaoshaproject.validator.ValidatorImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Created by hzllb on 2018/11/18.
 */
@Service
public class ItemServiceImpl implements ItemService {
    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private ValidatorImpl validator;

    @Autowired
    private ItemDOMapper itemDOMapper;

    @Autowired
    private PromoService promoService;

    @Autowired
    private ItemStockDOMapper itemStockDOMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private StockLogDOMapper stockLogDOMapper;

    @Value("${miaosha.stock-mode:lua}")
    private String stockMode;

    // 新增字段：把 Lua 脚本定义为常量（或配置为 Bean）
    private static final String DECREASE_STOCK_LUA =
            "local stock = redis.call('GET', KEYS[1])\n" +
            "if not stock then return -1 end\n" +
            "stock = tonumber(stock)\n" +
            "local amount = tonumber(ARGV[1])\n" +
            "if stock < amount then return -2 end\n" +
            "local newStock = stock - amount\n" +
            "redis.call('SET', KEYS[1], newStock)\n" +
            "if newStock == 0 then redis.call('SET', KEYS[2], 'true') end\n" +
            "return newStock";

    // 预编译脚本（用 DefaultRedisScript，Spring 会自动缓存 SHA）
    private DefaultRedisScript<Long> decreaseStockScript;


    @PostConstruct
    public void initScript() {
        decreaseStockScript = new DefaultRedisScript<>();
        decreaseStockScript.setScriptText(DECREASE_STOCK_LUA);
        decreaseStockScript.setResultType(Long.class);
    }

    private ItemDO convertItemDOFromItemModel(ItemModel itemModel){
        if(itemModel == null){
            return null;
        }
        ItemDO itemDO = new ItemDO();
        BeanUtils.copyProperties(itemModel,itemDO);
        itemDO.setPrice(itemModel.getPrice().doubleValue());
        return itemDO;
    }
    private ItemStockDO convertItemStockDOFromItemModel(ItemModel itemModel){
        if(itemModel == null){
            return null;
        }
        ItemStockDO itemStockDO = new ItemStockDO();
        itemStockDO.setItemId(itemModel.getId());
        itemStockDO.setStock(itemModel.getStock());
        return itemStockDO;
    }



    @Override
    public ItemModel getItemByIdInCache(Integer id) {
        ItemModel itemModel = (ItemModel) redisTemplate.opsForValue().get("item_validate_" + id);
        if(itemModel == null){
            itemModel = this.getItemById(id);
            redisTemplate.opsForValue().set("item_validate_" + id, itemModel);
            redisTemplate.expire("item_validate_" + id, 10, TimeUnit.MINUTES);
        }
        return itemModel;
    }

    @Override
    @Transactional
    public ItemModel createItem(ItemModel itemModel) throws BusinessException {
        //校验入参
        ValidationResult result = validator.validate(itemModel);
        if(result.isHasErrors()){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,result.getErrMsg());
        }

        //转化itemmodel->dataobject
        ItemDO itemDO = this.convertItemDOFromItemModel(itemModel);

        //写入数据库
        itemDOMapper.insertSelective(itemDO);
        itemModel.setId(itemDO.getId());

        ItemStockDO itemStockDO = this.convertItemStockDOFromItemModel(itemModel);

        itemStockDOMapper.insertSelective(itemStockDO);

        //返回创建完成的对象
        return this.getItemById(itemModel.getId());
    }

    @Override
    public List<ItemModel> listItem() {
        List<ItemDO> itemDOList = itemDOMapper.listItem();
        List<ItemModel> itemModelList =  itemDOList.stream().map(itemDO -> {
            ItemStockDO itemStockDO = itemStockDOMapper.selectByItemId(itemDO.getId());
            ItemModel itemModel = this.convertModelFromDataObject(itemDO,itemStockDO);
            return itemModel;
        }).collect(Collectors.toList());
        return itemModelList;
    }

    @Override
    public ItemModel getItemById(Integer id) {
        ItemDO itemDO = itemDOMapper.selectByPrimaryKey(id);
        if(itemDO == null){
            return null;
        }
        //操作获得库存数量
        ItemStockDO itemStockDO = itemStockDOMapper.selectByItemId(itemDO.getId());


        //将dataobject->model
        ItemModel itemModel = convertModelFromDataObject(itemDO,itemStockDO);

        //获取活动商品信息
        PromoModel promoModel = promoService.getPromoByItemId(itemModel.getId());
        if(promoModel != null && promoModel.getStatus().intValue() != 3){
            itemModel.setPromoModel(promoModel);
        }
        return itemModel;
    }

    // 初始化对应的库存流水
    @Override
    @Transactional
    public String initStockLog(Integer itemId, Integer amount) {
        StockLogDO stockLogDO = new StockLogDO();
        stockLogDO.setItemId(itemId);
        stockLogDO.setAmount(amount);
        stockLogDO.setStockLogId(UUID.randomUUID().toString().replace("-", ""));
        stockLogDO.setStatus(1);   // 1 = 初始状态

        stockLogDOMapper.insertSelective(stockLogDO);

        return stockLogDO.getStockLogId();
    }

    //Redis 回补
    @Override
    public boolean increaseStock(Integer itemId, Integer amount) throws BusinessException {
        // 修 bug：原来是 "item_validate_" + itemId，应该是 "promo_item_stock_"
        redisTemplate.opsForValue().increment("promo_item_stock_" + itemId, amount);
        // 回补同步清除售罄标记
        redisTemplate.delete("promo_item_stock_invalid_" + itemId);   // 加这一行

        // 这里：INCRBY 和 DELETE 之间仍有微小缝隙，最严谨的做法是写成 Lua

        return true;
    }

    // 发 MQ 异步同步到数据库
    @Override
    public boolean asyncDecreaseStock(Integer itemId, Integer amount) {
        boolean mqResult = mqProducer.asyncReduceStock(itemId,amount);
        return mqResult;
    }

    @Override
    @Transactional
    public boolean decreaseStock(Integer itemId, Integer amount) throws BusinessException {
        switch (stockMode) {
            case "mysql":
                // === 改前：MySQL 行锁扣库存 ===
                int affectedRow = itemStockDOMapper.decreaseStock(itemId, amount);
                return affectedRow > 0;

            case "redis":
                // === 中间版本：Redis INCR 扣库存 ===
                long redisResult = redisTemplate.opsForValue()
                        .increment("promo_item_stock_" + itemId, amount.intValue() * -1);
                if (redisResult > 0) {
                    return true;
                } else if (redisResult == 0) {
                    redisTemplate.opsForValue().set("promo_item_stock_invalid_" + itemId, "true");
                    return true;
                } else {
                    increaseStock(itemId, amount);
                    return false;
                }

            case "lua":
                // === 最终版：Redis Lua 原子扣库存 ===
                List<String> keys = Arrays.asList(
                        "promo_item_stock_" + itemId,
                        "promo_item_stock_invalid_" + itemId
                );
                Long luaResult = (Long) redisTemplate.execute(decreaseStockScript, keys, amount);
                if (luaResult == null) {
                    throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "Lua脚本执行异常");
                }
                if (luaResult == -1) {
                    throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "活动库存未初始化");
                }
                if (luaResult == -2) {
                    return false;
                }
                return true;

            default:
                throw new BusinessException(EmBusinessError.UNKNOWN_ERROR,
                        "未知的库存扣减模式: " + stockMode);
        }
    }



    @Override
    @Transactional
    public void increaseSales(Integer itemId, Integer amount) throws BusinessException {
        itemDOMapper.increaseSales(itemId,amount);
    }

    private ItemModel convertModelFromDataObject(ItemDO itemDO,ItemStockDO itemStockDO){
        ItemModel itemModel = new ItemModel();
        BeanUtils.copyProperties(itemDO,itemModel);
        itemModel.setPrice(new BigDecimal(itemDO.getPrice()));
        itemModel.setStock(itemStockDO.getStock());

        return itemModel;
    }

}
