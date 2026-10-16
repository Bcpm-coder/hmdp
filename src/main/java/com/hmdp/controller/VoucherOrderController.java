package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.ratelimit.RateLimit;
import com.hmdp.ratelimit.LimitDimension;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author YJn
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @GetMapping("result/{orderId}")
    public Result result(@PathVariable Long orderId) { return voucherOrderService.queryResult(orderId); }

    @RateLimit(name = "seckill", dimension = LimitDimension.USER, windowSeconds = 10, maxRequests = 3)
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
     }
}
