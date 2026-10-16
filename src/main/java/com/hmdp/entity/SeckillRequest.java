package com.hmdp.entity;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.*;
@Data
@TableName("tb_seckill_request")
public class SeckillRequest {
 @TableId(type=IdType.INPUT) private Long id;
 private Long userId;
 private Long voucherId;
 private String status;
 private String reason;
 private Boolean compensated;
}
