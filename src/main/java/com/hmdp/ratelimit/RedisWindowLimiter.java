package com.hmdp.ratelimit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import java.util.Collections;
import java.util.UUID;
@Slf4j
@Component
public class RedisWindowLimiter {
    private static final DefaultRedisScript<Long> SCRIPT=new DefaultRedisScript<>();
    static { SCRIPT.setLocation(new ClassPathResource("rate-limit.lua")); SCRIPT.setResultType(Long.class); }
    private final StringRedisTemplate redis;
    public RedisWindowLimiter(StringRedisTemplate redis) { this.redis=redis; }
    public void check(String key, int windowSeconds, int maxRequests) {
        if(windowSeconds<1 || windowSeconds>86400 || maxRequests<1 || maxRequests>100000) {
            throw new IllegalArgumentException("Invalid rate-limit bounds");
        }
        Long allowed;
        try {
            allowed=redis.execute(SCRIPT,Collections.singletonList(key),
                    Long.toString(windowSeconds*1000L),Integer.toString(maxRequests),UUID.randomUUID().toString());
            if(allowed==null || (allowed!=0 && allowed!=1)) throw new IllegalStateException("Invalid limiter result");
        } catch(RuntimeException ex) {
            log.error("Rate-limit Redis unavailable for {}",key,ex);
            throw new RateLimitException(HttpStatus.SERVICE_UNAVAILABLE,"服务暂不可用，请稍后重试");
        }
        if(allowed==0) throw new RateLimitException(HttpStatus.TOO_MANY_REQUESTS,"请求过于频繁，请稍后重试");
    }
}
