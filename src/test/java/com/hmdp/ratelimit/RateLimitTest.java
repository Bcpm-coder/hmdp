package com.hmdp.ratelimit;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import com.hmdp.config.WebExceptionAdvice;
import org.junit.jupiter.api.*;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Collections;
class RateLimitTest {
 @org.springframework.web.bind.annotation.RestController
 public static class Endpoints {
  @RateLimit(name="test") public String user() {return "ok";}
  @RateLimit(name="test",dimension=LimitDimension.IP) public String ip() {return "ok";}
  @org.springframework.web.bind.annotation.GetMapping("/limited")
  @RateLimit(name="test",dimension=LimitDimension.GLOBAL) public String global() {return "ok";}
 }
 private RedisWindowLimiter limiter;
 private Endpoints endpoints;
 @BeforeEach void setup() {
  limiter=mock(RedisWindowLimiter.class); AspectJProxyFactory f=new AspectJProxyFactory(new Endpoints());
  f.addAspect(new RateLimitAspect(limiter)); endpoints=f.getProxy();
 }
 @AfterEach void cleanup() { UserHolder.removeUser(); RequestContextHolder.resetRequestAttributes(); }
 @Test void userKeyScopesAllInstancesToSameUser() {
  UserDTO user=new UserDTO();user.setId(42L);UserHolder.saveUser(user);
  assertEquals("ok",endpoints.user()); verify(limiter).check("rate:test:USER:42",10,3);
 }
 @Test void unauthenticatedUserDoesNotConsumeQuota() {
  assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(RateLimitException.class,()->endpoints.user()).getStatus());verifyNoInteractions(limiter);
 }
 @Test void ipIgnoresUntrustedForwardedHeader() {
  MockHttpServletRequest request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.1");request.addHeader("X-Forwarded-For","fake");
  RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  endpoints.ip();verify(limiter).check("rate:test:IP:192.0.2.1",10,3);
 }
 @Test void globalKeyIsShared() { endpoints.global();verify(limiter).check("rate:test:GLOBAL:all",10,3); }
 @Test void rejectedCallsDoNotRunBusinessMethod() {
  doThrow(new RateLimitException(HttpStatus.TOO_MANY_REQUESTS,"limited")).when(limiter).check(anyString(),anyInt(),anyInt());
  assertThrows(RateLimitException.class,()->endpoints.global());
 }
 @Test void redisDenialAndFailureHaveDifferentHttpStatuses() {
  StringRedisTemplate redis=mock(StringRedisTemplate.class); RedisWindowLimiter real=new RedisWindowLimiter(redis);
  when(redis.execute(any(RedisScript.class),anyList(),any(),any(),any())).thenReturn(0L);
  assertEquals(HttpStatus.TOO_MANY_REQUESTS,assertThrows(RateLimitException.class,()->real.check("k",10,3)).getStatus());
  when(redis.execute(any(RedisScript.class),anyList(),any(),any(),any())).thenThrow(new IllegalStateException("offline"));
  assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(RateLimitException.class,()->real.check("k",10,3)).getStatus());
 }
 @Test void advicePreservesHttpStatusAndResponseFormat() {
  org.springframework.http.ResponseEntity<com.hmdp.dto.Result> response=new WebExceptionAdvice().handleRateLimit(new RateLimitException(HttpStatus.TOO_MANY_REQUESTS,"limited"));
  assertEquals(429,response.getStatusCodeValue());assertFalse(response.getBody().getSuccess());
 }
 @Test void httpRequestsReceive429And503WithoutInvokingBusiness() throws Exception {
  org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(endpoints)
      .setControllerAdvice(new WebExceptionAdvice()).build();
  doThrow(new RateLimitException(HttpStatus.TOO_MANY_REQUESTS,"limited")).when(limiter).check(anyString(),anyInt(),anyInt());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/limited"))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests())
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(false));
  doThrow(new RateLimitException(HttpStatus.SERVICE_UNAVAILABLE,"offline")).when(limiter).check(anyString(),anyInt(),anyInt());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/limited"))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());
 }

}
