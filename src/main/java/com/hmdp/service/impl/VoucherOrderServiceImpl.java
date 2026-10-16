package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.*;
import com.hmdp.mapper.*;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.*;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;

import com.hmdp.mq.OrderEvent;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.util.*;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private VoucherOrderMapper orders;
    @Resource
    private SeckillVoucherMapper stocks;
    @Resource
    private RedissonClient redisson;
    @Resource
    private TransactionTemplate transactions;

    /**
     * RocketMQ 消费入口：事务提交后才释放锁，异常交由 MQ 重试。
     */
    @Override
    public void handleOrder(OrderEvent event) {
        event.validate();
        RLock lock = redisson.getLock("lock:order:" + event.getUserId() + ":" + event.getVoucherId());
        if (!lock.tryLock()) {
            throw new IllegalStateException("Order busy; retry");
        }
        try {
            // 编程式事务避免同类内部调用绕过 Spring 事务代理。
            transactions.execute(status -> {
                persistOrder(event);
                return null;
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * 幂等检查、库存扣减、订单和终态保存，全部在同一个事务内执行。
     */
    private void persistOrder(OrderEvent e) {
        e.validate();
        SeckillRequest old = requests.selectById(e.getOrderId());
        if (old != null) {
            if (!old.getUserId().equals(e.getUserId()) || !old.getVoucherId().equals(e.getVoucherId()))
                throw new IllegalStateException("Order identity mismatch");
            return;
        }
        SeckillRequest r = new SeckillRequest();
        r.setId(e.getOrderId());
        r.setUserId(e.getUserId());
        r.setVoucherId(e.getVoucherId());
        r.setCompensated(false);
        VoucherOrder existing = orders.selectOne(new QueryWrapper<VoucherOrder>().eq("user_id", e.getUserId()).eq("voucher_id", e.getVoucherId()));
        if (existing != null) {
            r.setStatus(existing.getId().equals(e.getOrderId()) ? "SUCCESS" : "FAILED");
            r.setReason(existing.getId().equals(e.getOrderId()) ? null : "ALREADY_ORDERED");
        } else if (stocks.update(null, new UpdateWrapper<SeckillVoucher>().setSql("stock = stock - 1").eq("voucher_id", e.getVoucherId()).gt("stock", 0)) != 1) {
            r.setStatus("FAILED");
            r.setReason("DB_STOCK_EXHAUSTED");
        } else {
            VoucherOrder o = new VoucherOrder();
            o.setId(e.getOrderId());
            o.setUserId(e.getUserId());
            o.setVoucherId(e.getVoucherId());
            if (orders.insert(o) != 1) throw new IllegalStateException("Order insert failed");
            r.setStatus("SUCCESS");
        }
        if (requests.insert(r) != 1) throw new IllegalStateException("Request insert failed");
    }

    @Resource
    private StringRedisTemplate redis;
    @Resource
    private RedisIdWorker ids;
    @Resource
    private SeckillRequestMapper requests;
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SCRIPT.setResultType(Long.class);
    }

    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = ids.nextId("order");
        Long result = redis.execute(SCRIPT, Arrays.asList("seckill:stock:" + voucherId, "seckill:order:" + voucherId, "seckill:owners:" + voucherId, "seckill:request:" + orderId, "stream:seckill:outbox"), userId.toString(), Long.toString(orderId), voucherId.toString(), Long.toString(System.currentTimeMillis()));
        if (result == null) throw new IllegalStateException("No reservation result");
        return result == 0 ? Result.ok(orderId) : Result.fail(result == 1 ? "库存不足" : "不能重复下单");
    }

    public Result queryResult(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        SeckillRequest r = requests.selectById(orderId);
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        if (r != null) {
            if (!r.getUserId().equals(userId)) return Result.fail("订单不存在");
            result.put("status", r.getStatus());
            result.put("reason", r.getReason());
        } else {
            Map<Object, Object> pending = redis.opsForHash().entries("seckill:request:" + orderId);
            if (!userId.toString().equals(pending.get("userId"))) return Result.fail("订单不存在");
            result.put("status", "PENDING");
        }
        return Result.ok(result);
    }
}
