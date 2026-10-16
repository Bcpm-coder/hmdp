package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
@AllArgsConstructor
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
        RedisData redisData = new RedisData(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)), value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    // 缓存穿透
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit){
        String key = keyPrefix + id;
        //redis查询
        String json = stringRedisTemplate.opsForValue().get(key);
        if("".equals(json)){
            //命中k：“”数据直接返回
            return null;
        }
        if (StrUtil.isNotBlank(json)) {
            //redis有 直接返回
            return JSONUtil.toBean(json, type);
        }
        R r = dbFallBack.apply(id);
        //sql不存在报错
        if(r == null){
            // null写入redis 应对缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", time, timeUnit);
            return null;
        }
        //redis缓存一下
        this.set(key, r, time, timeUnit);
        return r;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private  void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit){
        String key = keyPrefix + id;
        //redis查询
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            //redis无 直接返回（热点数据已预加载redis）
            return null;
        }
        // 反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        // 逻辑未过期
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return r;
        }
        // 逻辑过期

        // 获取mutex
        String lockKey = LOCK_SHOP_KEY + id;
        // 拿到（第一个线程拿到）开启新线程执行sql查询更新redis
        if (tryLock(lockKey)) {
            // 新线程
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r1 = dbFallBack.apply(id);
                    Thread.sleep(200);
                    setWithLogicalExpire(key, r1, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 未拿到锁
        return r;
    }
}
