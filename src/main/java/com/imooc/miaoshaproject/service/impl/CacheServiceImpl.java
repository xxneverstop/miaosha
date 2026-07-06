package com.imooc.miaoshaproject.service.impl;

import com.github.benmanes.caffeine.cache.Caffeine;
//import com.google.common.cache.Cache;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.imooc.miaoshaproject.service.CacheService;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

/**
 * Created by hzllb on 2019/2/16.
 */
@Service
public class CacheServiceImpl implements CacheService {

    private Cache<String,Object> commonCache = null;

    // 执行完在构建bean
    @PostConstruct
    public void init(){
        commonCache = Caffeine.newBuilder()
                //设置缓存容器的初始容量为10
                .initialCapacity(10)
                //设置缓存中最大可以存储100个KEY,超过100个之后会按照LRU的策略移除缓存项
                .maximumSize(100)
                //设置写缓存后多少秒过期
                .expireAfterWrite(300, TimeUnit.SECONDS)
                .<String, Object>build();;
    }

    @Override
    public void setCommonCache(String key, Object value) {
            commonCache.put(key,value);
    }

    @Override
    public Object getFromCommonCache(String key) {
        return commonCache.getIfPresent(key);
    }
}
