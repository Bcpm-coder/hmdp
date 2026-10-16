package com.hmdp.mq;
import org.junit.jupiter.api.Test;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.springframework.transaction.support.TransactionTemplate;
import org.redisson.api.*;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.messaging.Message;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MessageReliabilityTest {
 @Test void lockFailureTriggersRetryRatherThanAcknowledging() {
  RedissonClient client=mock(RedissonClient.class); RLock lock=mock(RLock.class);
  TransactionTemplate tx=mock(TransactionTemplate.class);
  when(client.getLock("lock:order:2:3")).thenReturn(lock);
  VoucherOrderServiceImpl service=new VoucherOrderServiceImpl(); ReflectionTestUtils.setField(service,"redisson",client); ReflectionTestUtils.setField(service,"transactions",tx);
  OrderConsumer consumer=new OrderConsumer(); ReflectionTestUtils.setField(consumer,"voucherOrderService",service);
  OrderEvent e=new OrderEvent(); e.setOrderId(1L); e.setUserId(2L); e.setVoucherId(3L); e.setCreatedAt(4L); e.setSchemaVersion(1);
  assertThrows(IllegalStateException.class,()->consumer.onMessage(e));
  verifyNoInteractions(tx); verify(lock,never()).unlock();
 }
 @Test void transactionFailurePropagatesAndReleasesLock() {
  RedissonClient client=mock(RedissonClient.class); RLock lock=mock(RLock.class);
  TransactionTemplate tx=mock(TransactionTemplate.class);
  when(client.getLock(anyString())).thenReturn(lock); when(lock.tryLock()).thenReturn(true);
  VoucherOrderServiceImpl service=new VoucherOrderServiceImpl(); ReflectionTestUtils.setField(service,"redisson",client); ReflectionTestUtils.setField(service,"transactions",tx);
  OrderConsumer consumer=new OrderConsumer(); ReflectionTestUtils.setField(consumer,"voucherOrderService",service);
  OrderEvent e=new OrderEvent(); e.setOrderId(1L); e.setUserId(2L); e.setVoucherId(3L); e.setCreatedAt(4L); e.setSchemaVersion(1);
  when(tx.execute(any())).thenThrow(new IllegalStateException("database unavailable"));
  assertThrows(IllegalStateException.class,()->consumer.onMessage(e)); verify(lock).unlock();
 }
 private OutboxRelay relay(StringRedisTemplate redis,RocketMQTemplate mq) {
  OutboxRelay relay=new OutboxRelay(); ReflectionTestUtils.setField(relay,"redis",redis); ReflectionTestUtils.setField(relay,"mq",mq);
  ReflectionTestUtils.setField(relay,"topic","hmdp-seckill-order"); ReflectionTestUtils.setField(relay,"timeout",3000L);
  when(redis.execute(any(RedisScript.class),anyList(),any(),any(),any())).thenReturn(Arrays.asList("0-0","1-0","{\"orderId\":1,\"userId\":2,\"voucherId\":3,\"createdAt\":4,\"schemaVersion\":1}"));
  return relay;
 }
 @Test void failedSendDoesNotAcknowledgeOutbox() {
  StringRedisTemplate redis=mock(StringRedisTemplate.class); RocketMQTemplate mq=mock(RocketMQTemplate.class);
  OutboxRelay relay=relay(redis,mq);
  when(mq.syncSend(anyString(),any(Message.class),anyLong())).thenThrow(new IllegalStateException("broker offline"));
  relay.relay(); verify(redis,never()).execute(any(RedisScript.class),anyList(),any(),any());
 }
 @Test void onlySendOkAcknowledgesOutbox() {
  StringRedisTemplate redis=mock(StringRedisTemplate.class); RocketMQTemplate mq=mock(RocketMQTemplate.class);
  OutboxRelay relay=relay(redis,mq); SendResult sent=new SendResult(); sent.setSendStatus(SendStatus.SEND_OK);
  when(mq.syncSend(anyString(),any(Message.class),anyLong())).thenReturn(sent);
  relay.relay(); verify(redis).execute(any(RedisScript.class),eq(Collections.singletonList("stream:seckill:outbox")),eq("rocketmq-relay"),eq("1-0"));
 }
}
