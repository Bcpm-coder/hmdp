package com.hmdp.ratelimit;
import java.lang.annotation.*;
/** 单条规则选择一个维度；同名、同维度规则共享 Redis 额度。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
    String name();
    LimitDimension dimension() default LimitDimension.USER;
    int windowSeconds() default 10;
    int maxRequests() default 3;
}
