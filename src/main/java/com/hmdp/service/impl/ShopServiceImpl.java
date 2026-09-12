package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    public Result queryById(Long id) {
        // 解决缓存穿透
        // Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);

        // 如果shop为空，返回错误信息
        if (shop == null) {
            return Result.fail("商铺不存在!");
        }

        return Result.ok(shop);
    }


    /**
     * 根据id查询商铺信息，实现逻辑过期
     * @param id 商铺id
     * @return 商铺信息
     */

    // 创建线程池 用于缓存重建
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        // 1.在redis中查询商铺信息
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String cacheShop = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(cacheShop)) {
            // 2. 未命中 直接返回
            return null;
        }
        // 3.命中 需要将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(cacheShop, RedisData.class);
        JSONObject jsonData = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(jsonData, Shop.class);
        // 4. 判断缓存是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 4.1未过期 直接返回商铺信息
            return shop;
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
                    return JSONUtil.toBean((JSONObject) newRedisData.getData(), Shop.class);
                }
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                System.out.println("正在重建缓存...");
                //重建缓存
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4无论是否抢到锁，都直接返回旧数据
        return shop;
    }


    /**
     * 将商铺信息保存到Redis中，并设置逻辑过期时间
     * @param id 商铺id
     * @param expireTime 过期时间
     */
    public void saveShop2Redis(Long id, Long expireTime) {
        // 1.查询商铺数据
        Shop shop = getById(id);
        // 模拟延迟
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        // 3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 根据id查询商铺信息，实现缓存击穿
     * @param id 商铺id
     * @return 商铺信息
     */
    public Shop queryWithMutex(Long id) {
        // 1.在redis中查询商铺信息
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String cacheShop = stringRedisTemplate.opsForValue().get(key);
        // 2.存在 直接返回
        if (StrUtil.isNotBlank(cacheShop)) {
            return JSONUtil.toBean(cacheShop, Shop.class);
        }
        // 如果cacheShop为空值 则不去查数据库
        if (cacheShop != null) {
            return null;
        }
        // 3.实现缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
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
                    cacheShop = stringRedisTemplate.opsForValue().get(key);
                    // 3.3缓存中存在，直接返回
                    if (StrUtil.isNotBlank(cacheShop)) {
                        return JSONUtil.toBean(cacheShop, Shop.class);
                    }
                    // 3.4缓存中不存在，根据id查询数据库
                    shop = getById(id);
                    if (shop != null) {
                        // 写入缓存
                        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    } else {
                        // 缓存空值，防穿透
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    }
                    return shop;
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


    /**
     * 根据id查询商铺信息，实现缓存穿透
     * @param id 商铺id
     * @return 商铺信息
     */
    public Shop queryWithPassThrough(Long id) {
        // 1.在redis中查询商铺信息
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String cacheShop = stringRedisTemplate.opsForValue().get(key);
        // 2.存在 直接返回
        if (StrUtil.isNotBlank(cacheShop)) {
            return JSONUtil.toBean(cacheShop, Shop.class);
        }
        // 如果cacheShop为空值 则不去查数据库
        if (cacheShop != null) {
            return null;
        }

        // 3.不存在，根据id查询数据库
        Shop shop = getById(id);
        // 4.数据库不存在，返回错误
        if (shop == null) {
            // 解决缓存穿透：数据库未查到时，将空值写入缓存，避免后续重复穿透到数据库
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5.数据库存在，写入redis并返回
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 6.返回
        return shop;
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @Transactional
    public Result update(Shop shop) {
        // 缓存更新策略: 实现缓存与数据库双写一致
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        // 返回
        return Result.ok();
    }
}
