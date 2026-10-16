package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class OutboxRelay {
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private RocketMQTemplate mq;
    @Value("${seckill.topic}")
    private String topic;
    @Value("${seckill.send-timeout-ms:3000}")
    private long timeout;
    private final String consumer = UUID.randomUUID().toString();
    private String cursor = "0-0";
    private static final String STREAM = "stream:seckill:outbox", GROUP = "rocketmq-relay";
    private static final DefaultRedisScript<List> READ = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> ACK = new DefaultRedisScript<>();

    static {
        READ.setLocation(new ClassPathResource("outbox-read.lua"));
        READ.setResultType(List.class);
        ACK.setScriptText("local n=redis.call('xack',KEYS[1],ARGV[1],ARGV[2]); if n==1 then redis.call('xdel',KEYS[1],ARGV[2]) end; return n");
        ACK.setResultType(Long.class);
    }

    private String str(Object o) {
        return o instanceof byte[] ? new String((byte[]) o, StandardCharsets.UTF_8) : o.toString();
    }

    @Scheduled(fixedDelayString = "${seckill.relay-delay-ms:1000}")
    public void relay() {
        try {
            List values = redis.execute(READ, Collections.singletonList(STREAM), GROUP, consumer, cursor);
            if (values == null || values.isEmpty()) return;
            cursor = str(values.get(0));
            for (int i = 1; i < values.size(); i += 2) {
                String recordId = str(values.get(i));
                try {
                    OrderEvent e = JSONUtil.toBean(str(values.get(i + 1)), OrderEvent.class);
                    e.validate();
                    SendResult sent = mq.syncSend(topic, MessageBuilder.withPayload(e).setHeader(RocketMQHeaders.KEYS, e.getOrderId().toString()).build(), timeout);
                    if (sent.getSendStatus() != SendStatus.SEND_OK)
                        throw new IllegalStateException("Broker did not confirm persistence: " + sent.getSendStatus());
                    redis.execute(ACK, Collections.singletonList(STREAM), GROUP, recordId);
                } catch (Exception ex) {
                    log.error("Outbox event {} retained for retry", recordId, ex);
                }
            }
        } catch (Exception ex) {
            log.error("Outbox relay unavailable", ex);
        }
    }
}
