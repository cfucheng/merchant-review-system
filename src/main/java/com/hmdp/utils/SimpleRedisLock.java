package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 *@Author: chengfu Chen
 *@CreateTime: 2026-09-16  09:38
 *@Description: TODO
 *@Version: 1.0
 */

public class SimpleRedisLock implements ILock {
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String ID_PREFIX = UUID.randomUUID().toString(true);
    private static final String LOCK_KEY_PREFIX = "lock:";


    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeout) {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 设置锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY_PREFIX + name, threadId, timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的线程标示
        String lockThreadId = stringRedisTemplate.opsForValue().get(LOCK_KEY_PREFIX + name);
        // 判断标示是否一致
        if (threadId.equals(lockThreadId)){
            // 释放锁
            stringRedisTemplate.delete(LOCK_KEY_PREFIX + name);
        }
    }
}
