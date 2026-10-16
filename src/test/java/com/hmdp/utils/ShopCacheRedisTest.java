package com.hmdp.utils;

import com.hmdp.config.ShopCacheConfiguration;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: starts an isolated local redis-server, never connects to the application's Redis. */
@EnabledIfSystemProperty(named = "shop.redis.integration", matches = "true")
class ShopCacheRedisTest {
    private Shop shop(String name) { Shop s=new Shop(); s.setId(1L); s.setName(name); return s; }
    private void await(BooleanSupplier ready) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!ready.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(20);
        assertTrue(ready.getAsBoolean(),"Timed out waiting for Redis/subscription/cache expiry");
    }
    @Test void broadcastsToBothCachesAndTtlRecoversMissedNotification() throws Exception {
        int port; try(ServerSocket socket=new ServerSocket(0)) {port=socket.getLocalPort();}
        Path log=Files.createTempFile("hmdp-cache-redis-",".log");
        Process server=new ProcessBuilder("redis-server","--port",Integer.toString(port),"--bind","127.0.0.1","--save","","--appendonly","no")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        LettuceConnectionFactory factory=new LettuceConnectionFactory("127.0.0.1",port);
        RedisMessageListenerContainer aListener=null,bListener=null;
        try {
            factory.afterPropertiesSet();
            StringRedisTemplate redis=new StringRedisTemplate(factory);
            await(()-> { try (org.springframework.data.redis.connection.RedisConnection connection=factory.getConnection()) {return "PONG".equals(connection.ping());} catch(Exception e){return false;} });
            ShopLocalCache a=new ShopLocalCache(redis,500,30),b=new ShopLocalCache(redis,500,30);
            a.get(1L,()->shop("old")); b.get(1L,()->shop("old"));
            ShopCacheConfiguration config=new ShopCacheConfiguration();
            aListener=config.shopCacheListener(factory,a); bListener=config.shopCacheListener(factory,b);
            aListener.afterPropertiesSet(); bListener.afterPropertiesSet(); aListener.start(); bListener.start();
            await(()-> { try (org.springframework.data.redis.connection.RedisConnection connection=factory.getConnection()) {return connection.publish(ShopLocalCache.CHANNEL.getBytes(java.nio.charset.StandardCharsets.UTF_8),new byte[]{48}) >= 2;} });
            redis.opsForValue().set("cache:shop:1","old");
            a.invalidateAfterCommit(1L);
            assertFalse(redis.hasKey("cache:shop:1"));
            await(()->"new".equals(b.get(1L,()->shop("new")).getName()));
            assertEquals("new",a.get(1L,()->shop("new")).getName());
            // No subscriber: expiration, not a lost Pub/Sub message replay, forces reload.
            ShopLocalCache offline=new ShopLocalCache(redis,500,1);
            offline.get(1L,()->shop("old"));
            redis.convertAndSend(ShopLocalCache.CHANNEL,"1");
            assertEquals("old",offline.get(1L,()->shop("new")).getName());
            await(()->"new".equals(offline.get(1L,()->shop("new")).getName()));
        } finally {
            if(aListener!=null) aListener.destroy(); if(bListener!=null) bListener.destroy();
            factory.destroy(); server.destroy(); server.waitFor(5,TimeUnit.SECONDS); if(server.isAlive()) server.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
}
