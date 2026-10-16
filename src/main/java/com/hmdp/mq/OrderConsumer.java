package com.hmdp.mq;

import com.hmdp.service.IVoucherOrderService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

/** 消息适配层；下单业务统一由 VoucherOrderServiceImpl 处理。 */
@Component
@RocketMQMessageListener(topic = "${seckill.topic}", consumerGroup = "${seckill.consumer-group}", maxReconsumeTimes = 16)
public class OrderConsumer implements RocketMQListener<OrderEvent> {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(OrderEvent event) {
        voucherOrderService.handleOrder(event);
    }
}
