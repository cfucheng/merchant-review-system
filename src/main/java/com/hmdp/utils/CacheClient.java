package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 *@Author: chengfu Chen
 *@CreateTime: 2026-09-13  19:44
 *@Description: TODO
 *@Version: 1.0
 */
@Component
@Slf4j
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 将对象写入redis
     * @param key key
     * @param value value
     * @param time 时间
     * @param unit 时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 将对象写入redis，设置逻辑过期时间
     * @param key key
     * @param value value
     * @param time 时间
     * @param unit 时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据id查询商铺信息，实现缓存穿透
     * @param keyPrefix key的前缀
     * @param id 商铺id
     * @param type 商铺类型
     * @param dbFallback 查询数据库的函数
     * @param time 缓存时间
     * @param unit 时间单位
     * @return 商铺信息
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 1.在redis中查询商铺信息
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.存在 直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 如果cacheShop为空值 则不去查数据库
        if (json != null) {
            return null;
        }

        // 3.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 4.数据库不存在，返回错误
        if (r == null) {
            // 解决缓存穿透：数据库未查到时，将空值写入缓存，避免后续重复穿透到数据库
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5.数据库存在，写入redis并返回
        this.set(key, r, time, unit);
        // 6.返回
        return r;
    }

    /**
     * 根据id查询商铺信息，实现缓存击穿
     * @param keyPrefix key的前缀
     * @param id 商铺id
     * @param type 商铺类型
     * @param dbFallback 查询数据库的函数
     * @param time 缓存时间
     * @param unit 时间单位
     * @return 商铺信息
     */
    // 创建线程池 用于缓存重建
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 1.在redis中查询商铺信息
        String key = keyPrefix + id;
        String cacheShop = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(cacheShop)) {
            // 2. 未命中 直接返回
            return null;
        }
        // 3.命中 需要将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(cacheShop, RedisData.class);
        JSONObject jsonData = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(jsonData, type);
        // 4. 判断缓存是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 4.1未过期 直接返回商铺信息
            return r;
        }
        // 5.2已过期 需要缓存重建
        // 6.缓存重建
        // 6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Boolean isLock = tryLock(lockKey);
        // 6.2判断是否获取锁成功
        if (isLock) {
            // 6.3获取到锁 开启独立线程 实现缓存重建
            // 3.2 DoubleCheck：抢到锁后再查一次缓存
            String cacheShop2 = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(cacheShop2)) {
                RedisData newRedisData = JSONUtil.toBean(cacheShop2, RedisData.class);
                // 如果新的还没过期，说明别人刚重建好了，直接释放锁并返回
                if (newRedisData.getExpireTime().isAfter(LocalDateTime.now())) {
                    unlock(lockKey);
                    return JSONUtil.toBean((JSONObject) newRedisData.getData(), type);
                }
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                System.out.println("正在重建缓存...");
                //重建缓存
                try {
                    // 先查数据库
                    R r1 = dbFallback.apply(id);
                    // 写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4无论是否抢到锁，都直接返回旧数据
        return r;
    }

    /**
     * 根据id查询商铺信息，实现缓存击穿
     * @param keyPrefix key的前缀
     * @param id 商铺id
     * @param type 商铺类型
     * @param dbFallback 查询数据库的函数
     * @param time 缓存时间
     * @param unit 时间单位
     * @return 商铺信息
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit,String lockKeyPrefix) {
        // 1.在redis中查询商铺信息
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.存在 直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 如果cacheShop为空值 则不去查数据库
        if (json != null) {
            return null;
        }
        // 3.实现缓存重建
        String lockKey = lockKeyPrefix + id;
        R r = null;
        boolean isLocked = false; // 1. 增加标志位
        try {
            //自旋
            int retryCount = 0;
            while (retryCount < 100) {
                // 3.1判断是否拿到锁 自旋+休眠
                Boolean isLock = tryLock(lockKey);
                if (isLock) {
                    isLocked = true; // 2. 抢到锁了，标记为 true
                    // 3.2 DoubleCheck：抢到锁后再查一次缓存
                    json = stringRedisTemplate.opsForValue().get(key);
                    // 3.3缓存中存在，直接返回
                    if (StrUtil.isNotBlank(json)) {
                        return JSONUtil.toBean(json, type);
                    }
                    // 3.4缓存中不存在，根据id查询数据库
                    r = dbFallback.apply(id);
                    if (r != null) {
                        // 写入缓存
                        this.set(key, r, time, unit);
                    } else {
                        // 缓存空值，防穿透
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    }
                    return r;
                }
                // 3.5获取锁失败，休眠并重试
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // 恢复中断状态，这是标准做法
                    Thread.currentThread().interrupt();
                    // 抛出运行时异常，确保跳出循环并触发 finally 释放锁
                    throw new RuntimeException("系统繁忙，请稍后再试", e);
                }
                retryCount++;
            }
            throw new RuntimeException("系统繁忙，请稍后再试");
        } finally {
            // 3. 只有自己抢到了锁，才去释放，千万别释放别人的锁！
            if (isLocked) {
                unlock(lockKey);
            }
        }
    }


    /**
     * 尝试获取锁
     * @param lockKey 锁的key
     * @return 是否获取成功
     */
    private Boolean tryLock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param lockKey 锁的key
     */
    private void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

}
