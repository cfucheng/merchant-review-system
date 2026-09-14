package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 *@Author: chengfu Chen
 *@CreateTime: 2026-09-14  16:03
 *@Description: TODO
 *@Version: 1.0
 */
@Component
@Slf4j
public class RedisWorker {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 开始时间戳
    private final static long BEGIN_TIMESTAMP = 1767225600L;
    // 序列号位数
    private final static int COUNT_BITS = 32;


    public Long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1获取到当前日期, 精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;

    }


    public static void main(String[] args) {
        LocalDateTime dateTime = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 0);
        long second = dateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second: " + second);
    }


}
