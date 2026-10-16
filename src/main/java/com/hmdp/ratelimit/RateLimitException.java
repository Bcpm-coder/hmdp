package com.hmdp.ratelimit;
import org.springframework.http.HttpStatus;
public class RateLimitException extends RuntimeException {
    private final HttpStatus status;
    public RateLimitException(HttpStatus status, String message) { super(message); this.status=status; }
    public HttpStatus getStatus() { return status; }
}
