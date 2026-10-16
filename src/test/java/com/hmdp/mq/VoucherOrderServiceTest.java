package com.hmdp.mq;
import com.hmdp.entity.*;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class VoucherOrderServiceTest {
 private VoucherOrderMapper orders;
 private SeckillVoucherMapper stocks;
 private SeckillRequestMapper requests;
 private VoucherOrderServiceImpl service;
 private OrderEvent event;
 @BeforeEach void init() {
  orders=mock(VoucherOrderMapper.class); stocks=mock(SeckillVoucherMapper.class); requests=mock(SeckillRequestMapper.class);
  service=new VoucherOrderServiceImpl();
  ReflectionTestUtils.setField(service,"orders",orders); ReflectionTestUtils.setField(service,"stocks",stocks); ReflectionTestUtils.setField(service,"requests",requests);
  event=new OrderEvent(); event.setOrderId(10L); event.setUserId(20L); event.setVoucherId(30L); event.setCreatedAt(1L); event.setSchemaVersion(1);
  when(requests.insert(any())).thenReturn(1);
 }
 @Test void duplicateDeliveryDoesNotDeductStock() {
  SeckillRequest r=new SeckillRequest(); r.setUserId(20L); r.setVoucherId(30L); r.setStatus("SUCCESS");
  when(requests.selectById(10L)).thenReturn(r); ReflectionTestUtils.invokeMethod(service,"persistOrder",event);
  verifyNoInteractions(stocks,orders); verify(requests,never()).insert(any());
 }
 @Test void exhaustedStockDoesNotCreateOrder() {
  when(stocks.update(isNull(),any())).thenReturn(0); ReflectionTestUtils.invokeMethod(service,"persistOrder",event);
  verify(orders,never()).insert(any()); verify(requests).insert(argThat(r->"FAILED".equals(r.getStatus()) && "DB_STOCK_EXHAUSTED".equals(r.getReason())));
 }
 @Test void existingPurchaseDoesNotDeductAgain() {
  VoucherOrder old=new VoucherOrder(); old.setId(99L); when(orders.selectOne(any())).thenReturn(old);
  ReflectionTestUtils.invokeMethod(service,"persistOrder",event); verifyNoInteractions(stocks); verify(requests).insert(argThat(r->"ALREADY_ORDERED".equals(r.getReason())));
 }
 @Test void failedInsertPropagatesForTransactionRollback() {
  when(stocks.update(isNull(),any())).thenReturn(1); when(orders.insert(any())).thenReturn(0);
  assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(service,"persistOrder",event)); verify(requests,never()).insert(any());
 }
 @Test void successfulOrderRecordsTerminalResult() {
  when(stocks.update(isNull(),any())).thenReturn(1); when(orders.insert(any())).thenReturn(1);
  ReflectionTestUtils.invokeMethod(service,"persistOrder",event); verify(requests).insert(argThat(r->"SUCCESS".equals(r.getStatus())));
 }
}
