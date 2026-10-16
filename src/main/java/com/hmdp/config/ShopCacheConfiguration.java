package com.hmdp.config;

import com.hmdp.utils.ShopLocalCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
public class ShopCacheConfiguration {
    @Bean
    public RedisMessageListenerContainer shopCacheListener(RedisConnectionFactory factory, ShopLocalCache cache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) -> {
            String id = new String(message.getBody(), StandardCharsets.UTF_8);
            try {
                cache.invalidate(Long.valueOf(id));
            } catch (NumberFormatException ex) {
                log.warn("Invalid shop cache notification: {}", id);
            }
        }, new ChannelTopic(ShopLocalCache.CHANNEL));
        return container;
    }
}
