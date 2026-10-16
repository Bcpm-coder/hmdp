package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(com.hmdp.ratelimit.RateLimitException.class)
    public org.springframework.http.ResponseEntity<Result> handleRateLimit(com.hmdp.ratelimit.RateLimitException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatus()).body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
