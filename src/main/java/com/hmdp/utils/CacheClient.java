package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @Package: com.hmdp.utils
 * @Description: 封装 redis 工具类
 */

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将数据 存入redis
     * */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 设置逻辑过期
     * */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        // 1.设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        //2.写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /** 设置空值 解决缓存穿透问题
     * */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1. 从redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. redis中存在，返回结果
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 判断命中是否为空值
        if (json != null) {
            return null;
        }
        // 3. redis中不存在，查询数据库
        R r = dbFallback.apply(id);
        // 4. 数据库中不存在，报错
        if (r == null) {
            // 缓存穿透解决方案
            // 将空值存入缓存
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5. 数据库中存在，将数据写入redis
        // 设置过期时间
        this.set(key, r, time, unit);
        // 6.返回结果
        return r;
    }

    /** 缓存击穿 - 逻辑过期
     * */
    private static final ExecutorService CACHE_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix ,ID id,Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isBlank(json)) { //判断字符串既不为null，也不是空字符串(""),且也不是空白字符
            // 存在 直接返回
            return null;
        }
        // 3.命中，把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        // 4.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 4.1. 未过期，返回数据
            return r;
        }
        // 过期
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
                    // 1.查询数据库
                    R r1 = dbFallback.apply(id);
                    // 2.写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockey);
                }
            });
        }
        // 5.2. 未成功，返回数据
        return r;
    }

    // 获取锁
    private Boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    // 释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
