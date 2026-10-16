package com.hmdp.mq;

import lombok.Data;

@Data
public class OrderEvent {
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private Long createdAt;
    private Integer schemaVersion;

    public void validate() {
        if (orderId == null || userId == null || voucherId == null || createdAt == null || !Integer.valueOf(1).equals(schemaVersion))
            throw new IllegalArgumentException("Invalid order event");
    }
}
