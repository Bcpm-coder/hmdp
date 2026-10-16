package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 仅缓存商铺详情；短 TTL 为遗漏失效通知兜底，允许短暂旧数据。 */
@Slf4j
@Component
public class ShopLocalCache {
    public static final String CHANNEL = "cache:shop:invalidate";
    private final StringRedisTemplate redis;
    private final Cache<Long, Shop> cache;
    // 进程内重试，不是可靠消息；进程退出后依赖 TTL 与排查修复。
    private final Set<Long> retries = ConcurrentHashMap.newKeySet();

    public ShopLocalCache(StringRedisTemplate redis,
                          @Value("${shop.local-cache.maximum-size:500}") long maximumSize,
                          @Value("${shop.local-cache.ttl-seconds:30}") long ttlSeconds) {
        this.redis = redis;
        this.cache = Caffeine.newBuilder().maximumSize(maximumSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS).recordStats().build();
    }

    public Shop get(Long id, Supplier<Shop> loader) {
        // 同一实例同 key 的加载合并；null 不写入本地缓存，保留 Redis 空值处理。
        Shop shop = cache.get(id, key -> loader.get());
        // 不向业务层暴露共享的可变实体。
        return shop == null ? null : BeanUtil.copyProperties(shop, Shop.class);
    }

    public com.github.benmanes.caffeine.cache.stats.CacheStats stats() {
        return cache.stats();
    }

    public void invalidate(Long id) {
        cache.invalidate(id);
    }

    public void invalidateAfterCommit(Long id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evictAndPublish(id); }
            });
        } else {
            evictAndPublish(id);
        }
    }

    private void evictAndPublish(Long id) {
        invalidate(id);
        try {
            // 删除失败时不宣称失效已完成；进入重试。
            redis.delete(RedisConstants.CACHE_SHOP_KEY + id);
            redis.convertAndSend(CHANNEL, id.toString());
            retries.remove(id);
        } catch (RuntimeException ex) {
            retries.add(id);
            log.error("Shop cache invalidation pending for {}", id, ex);
        }
    }

    @Scheduled(fixedDelayString = "${shop.local-cache.retry-delay-ms:5000}")
    public void retryInvalidations() {
        for (Long id : retries.toArray(new Long[0])) {
            evictAndPublish(id);
        }
    }
}
