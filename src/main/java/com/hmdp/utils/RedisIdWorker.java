package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String prefix){
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long epochSecond = now.toEpochSecond(ZoneOffset.UTC) - 1640995200L;
        long count = stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + date);
        return epochSecond << 32 | count;
    }
}
