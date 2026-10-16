package com.hmdp.ratelimit;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.*;
import javax.servlet.http.HttpServletRequest;
@Aspect
@Component
public class RateLimitAspect {
    private final RedisWindowLimiter limiter;
    public RateLimitAspect(RedisWindowLimiter limiter) { this.limiter=limiter; }
    @Around(value="@annotation(rule)", argNames="joinPoint,rule")
    public Object limit(ProceedingJoinPoint joinPoint, RateLimit rule) throws Throwable {
        if(!rule.name().matches("[a-zA-Z0-9:_-]{1,64}")) throw new IllegalArgumentException("Invalid limiter name");
        String identity;
        switch(rule.dimension()) {
            case USER:
                UserDTO user=UserHolder.getUser();
                if(user==null || user.getId()==null) throw new RateLimitException(HttpStatus.UNAUTHORIZED,"请先登录");
                identity=user.getId().toString();
                break;
            case IP:
                RequestAttributes attributes=RequestContextHolder.getRequestAttributes();
                if(!(attributes instanceof ServletRequestAttributes)) throw new IllegalStateException("IP limit requires HTTP request");
                HttpServletRequest request=((ServletRequestAttributes)attributes).getRequest();
                // 不信任客户端可伪造的 X-Forwarded-For；代理部署需配置可信转发处理。
                identity=request.getRemoteAddr();
                break;
            default:
                identity="all";
        }
        String key="rate:"+rule.name()+":"+rule.dimension().name()+":"+identity;
        limiter.check(key,rule.windowSeconds(),rule.maxRequests());
        return joinPoint.proceed();
    }
}
