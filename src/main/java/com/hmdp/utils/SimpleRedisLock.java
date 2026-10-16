package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String prefix = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long time) {
        String id = prefix + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent("lock:" + name, id, time, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unlock(){
        // lua脚本 确保判断和解锁原子性
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList("lock:" + name),
                prefix + Thread.currentThread().getId());
    }
//    @Override
//    public void unlock() {
//        if ((prefix + Thread.currentThread().getId()).equals(stringRedisTemplate.opsForValue().get("lock:" + name))) {
//            stringRedisTemplate.delete("lock:" + name);
//        }
//    }
}
