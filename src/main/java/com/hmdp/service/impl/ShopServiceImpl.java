package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
    * 添加缓存
    */
    @Override
    public Result queryById(Long id) {
       // 1.缓存穿透
//         Shop shop = cacheClient
//                 .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class ,this::getById, CACHE_NULL_TTL, TimeUnit.MINUTES);

        // 2.互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);

        // 2.逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /** 缓存穿透
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. redis中存在，返回结果
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断命中是否为空值
        if (shopJson != null) {
            return null;
        }
        // 3. redis中不存在，查询数据库
        Shop shop = getById(id);
        // 4. 数据库中不存在，报错
        if (shop == null) {
            // 缓存穿透解决方案
            // 将空值存入缓存
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5. 数据库中存在，将数据写入redis
        // 设置过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 6.返回结果
        return shop;
    }*/

    /** 缓存击穿 - 互斥锁
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. redis中命中，返回结果
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断命中是否为空值
        if (shopJson != null) {
            return null;
        }
        // 3. redis中不存在，查询数据库
        Shop shop = getById(id);
        // 4.缓存重建
        String lockey = LOCK_SHOP_KEY + id;
        // 4.1 获取互斥锁
        try {
            Boolean islock = trylock(lockey);
            if (!islock) {
                // 4.2 无，等待
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.3 获取成功
            // 4. 数据库中不存在，报错
            if (shop == null) {
                // 缓存穿透解决方案
                // 将空值存入缓存
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 5. 数据库中存在，将数据写入redis
            // 设置过期时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 6.释放锁
            unlock(lockey);
        }
        // 7.返回结果
        return shop;
    }*/

   /**
    * 缓存击穿 - 逻辑过期
    * private static final ExecutorService CACHE_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);

    // 缓存击穿 - 逻辑过期
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. redis未命中，返回null
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        // 3.命中，把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        // 4.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 4.1. 未过期，返回数据
            return shop;
        }
        // 缓存重建
        String lockey = LOCK_SHOP_KEY + id;
        // 4.2. 过期，获取互斥锁
        Boolean islock = trylock(lockey);
        // 5. 判断获取锁成功
        if (islock) {
            // 5.1. 成功，开启新线程
            CACHE_REBUILD_EXCUTOR.submit(()->{
                try {
                    // 重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockey);
                }
            });
        }
        // 5.2. 未成功，返回数据
        return shop;
    }*/

//    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
//        // 1.从数据库查询数据
//        Shop shop = getById(id);
//        // 模拟服务器延时
//        Thread.sleep(200);
//        // 2.设置逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        // 3.写入redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
//    }

    // 获取锁
    private Boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    // 释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


    /**
    * 更新数据库
    */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
