package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.PathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 *@Author: chengfu Chen
 *@CreateTime: 2026-09-16  09:38
 *@Description: TODO
 *@Version: 1.0
 *  *
 *  * 实现原理：
 *  *   - 加锁：使用 SETNX（setIfAbsent）命令设置锁标识，key 不存在时设置成功即获得锁
 *  *   - 解锁：通过 Lua 脚本原子地判断锁归属并删除，防止误删其他线程持有的锁
 *  *
 *  * 线程标识构成：{JVM实例UUID}-{线程ID}
 *  *   - UUID：区分不同 JVM 实例（多节点部署时避免标识冲突）
 *  *   - 线程ID：区分同一 JVM 内的不同线程
 *  *
 *  * 局限性：
 *  *   - 不支持可重入（同一线程重复加锁会失败）
 *  *   - 不可重试（加锁失败后不会自动重试）
 *  *   - 适用于对锁可靠性要求不极端的场景，高可靠场景建议使用 Redisson
 *  *
 *  *@Version: 1.0
 *
 */

public class SimpleRedisLock implements ILock {
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final String LOCK_KEY_PREFIX = "lock:";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


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
        // 调用Lua脚本执行解锁操作，保证「判断锁归属 + 删除锁」两步操作的原子性
        // KEYS[1] = 锁的key（前缀 + 业务名称），ARGV[1] = 当前线程的唯一标识
        // Lua脚本逻辑：若 KEYS[1] 的值 == ARGV[1]（即锁属于当前线程），则执行 del 释放锁；否则返回0不做任何操作
        stringRedisTemplate.
                execute(UNLOCK_SCRIPT,
                        Collections.singletonList(LOCK_KEY_PREFIX + name),
                        ID_PREFIX + Thread.currentThread().getId());




    /*    // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的线程标示
        String lockThreadId = stringRedisTemplate.opsForValue().get(LOCK_KEY_PREFIX + name);
        // 判断标示是否一致
        if (threadId.equals(lockThreadId)){
            // 释放锁
            stringRedisTemplate.delete(LOCK_KEY_PREFIX + name);
        }*/
    }
}
