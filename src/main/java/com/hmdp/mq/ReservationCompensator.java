package com.hmdp.mq;

import com.hmdp.entity.SeckillRequest;
import com.hmdp.mapper.SeckillRequestMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.util.Arrays;

@Slf4j
@Component
public class ReservationCompensator {
    @Resource
    private SeckillRequestMapper requests;
    @Resource
    private StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setLocation(new ClassPathResource("compensate.lua"));
        SCRIPT.setResultType(Long.class);
    }

    @Scheduled(fixedDelayString = "${seckill.compensation-delay-ms:5000}")
    public void compensate() {
        try {
            for (SeckillRequest r : requests.selectList(new QueryWrapper<SeckillRequest>().eq("status", "FAILED").eq("compensated", false).orderByAsc("id").last("LIMIT 100"))) {
                try {
                    Long done = redis.execute(SCRIPT, Arrays.asList("seckill:stock:" + r.getVoucherId(), "seckill:owners:" + r.getVoucherId(), "seckill:order:" + r.getVoucherId()), r.getUserId().toString(), r.getId().toString());
                    if (done == null) throw new IllegalStateException("No compensation result");
                    requests.update(null, new UpdateWrapper<SeckillRequest>().eq("id", r.getId()).eq("status", "FAILED").set("compensated", true));
                } catch (Exception ex) {
                    log.error("Compensation pending for order {}", r.getId(), ex);
                }
            }
        } catch (Exception ex) {
            log.error("Compensation scan unavailable; no inventory released", ex);
        }
    }
}
