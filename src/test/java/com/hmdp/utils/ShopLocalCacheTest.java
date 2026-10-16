package com.hmdp.utils;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ShopLocalCacheTest {
 private Shop shop() { Shop s=new Shop(); s.setId(1L); s.setName("old"); return s; }
 @Test void hitsAvoidReloadAndReturnIndependentObjects() {
  ShopLocalCache c=new ShopLocalCache(mock(StringRedisTemplate.class),500,30); AtomicInteger loads=new AtomicInteger();
  Shop first=c.get(1L,()->{loads.incrementAndGet();return shop();}); first.setName("mutated");
  assertEquals("old",c.get(1L,()->{throw new AssertionError();}).getName()); assertEquals(1,loads.get());
  c.invalidate(1L); assertEquals("new",c.get(1L,()->{Shop s=shop();s.setName("new");return s;}).getName());
 }
 @Test void invalidationWaitsForCommitAndRollbackDoesNotEvict() {
  StringRedisTemplate redis=mock(StringRedisTemplate.class); ShopLocalCache c=new ShopLocalCache(redis,500,30);
  TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
  try { c.invalidateAfterCommit(1L); verifyNoInteractions(redis);
   for(TransactionSynchronization s:TransactionSynchronizationManager.getSynchronizations()) s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
   verifyNoInteractions(redis);
  } finally {TransactionSynchronizationManager.clear();}
  TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
  try {c.invalidateAfterCommit(1L); for(TransactionSynchronization s:TransactionSynchronizationManager.getSynchronizations()) s.afterCommit();
   verify(redis).delete("cache:shop:1"); verify(redis).convertAndSend(ShopLocalCache.CHANNEL,"1");
  } finally {TransactionSynchronizationManager.clear();}
 }
 @Test void failedRedisDeletionIsRetriedBeforeBroadcast() {
  StringRedisTemplate redis=mock(StringRedisTemplate.class); ShopLocalCache c=new ShopLocalCache(redis,500,30);
  when(redis.delete("cache:shop:1")).thenThrow(new IllegalStateException("offline")).thenReturn(true);
  c.invalidateAfterCommit(1L); verify(redis,never()).convertAndSend(anyString(),any());
  c.retryInvalidations(); verify(redis,times(2)).delete("cache:shop:1"); verify(redis).convertAndSend(ShopLocalCache.CHANNEL,"1");
  c.retryInvalidations(); verify(redis,times(2)).delete("cache:shop:1");
 }
 @Test void concurrentMissesLoadOnceWithinInstance() throws Exception {
  ShopLocalCache c=new ShopLocalCache(mock(StringRedisTemplate.class),500,30); AtomicInteger loads=new AtomicInteger(); ExecutorService pool=Executors.newFixedThreadPool(8);
  CountDownLatch start=new CountDownLatch(1); java.util.List<Future<Shop>> futures=new java.util.ArrayList<>();
  try {for(int i=0;i<8;i++) futures.add(pool.submit(()->{start.await();return c.get(1L,()->{loads.incrementAndGet();return shop();});})); start.countDown();
   for(Future<Shop> f:futures) assertNotNull(f.get(5,TimeUnit.SECONDS)); assertEquals(1,loads.get());
  } finally {pool.shutdownNow();}
 }
 @Test void absentShopIsNotCachedLocally() {
  ShopLocalCache c=new ShopLocalCache(mock(StringRedisTemplate.class),500,30);
  assertNull(c.get(1L,()->null)); assertNotNull(c.get(1L,this::shop));
 }
}
