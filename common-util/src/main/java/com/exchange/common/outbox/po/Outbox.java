package com.exchange.common.outbox.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.exchange.common.db.po.BaseEntity;
import com.exchange.common.outbox.po.enums.OutboxStatus;
import lombok.*;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@TableName("outbox")
public class Outbox extends BaseEntity {
    @TableId(type = IdType.INPUT)
    @ToString.Exclude
    private Long internalId;
    private Integer eventType;
    // Immutable message identity exposed at the delivery boundary.
    private String eventRef;
    // Typed business identity used for correlation.
    private String commandRef;
    private OutboxStatus outboxStatus;
    // kafka: topic
    private String destination;
    // kafka: key
    private String partitionKey;
    // recommend raw proto bytes for better performance and schema evolution
    private byte[] payload;
    private Integer attemptCount;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime nextAttemptAt;
    private String lastError;
    private LocalDateTime finalizedAt;
    // recommend saving json payload here for debugging and readability
    private String metadata;

    private Outbox(Long internalId, Integer eventType, String eventRef, String commandRef, OutboxStatus outboxStatus, String destination, String partitionKey, byte[] payload, Integer attemptCount, LocalDateTime lastAttemptAt, LocalDateTime nextAttemptAt, String lastError, LocalDateTime finalizedAt) {
        this.internalId = internalId;
        this.eventType = eventType;
        this.eventRef = eventRef;
        this.commandRef = commandRef;
        this.outboxStatus = outboxStatus;
        this.destination = destination;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.attemptCount = attemptCount;
        this.lastAttemptAt = lastAttemptAt;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = lastError;
        this.finalizedAt = finalizedAt;
    }

    public static Outbox create(Long internalId, Integer eventType, String eventRef, String commandRef, OutboxStatus outboxStatus, String destination, String partitionKey, byte[] payload, Integer attemptCount, LocalDateTime lastAttemptAt, LocalDateTime nextAttemptAt, String lastError, LocalDateTime finalizedAt) {
        return new Outbox(internalId, eventType, eventRef, commandRef, outboxStatus, destination, partitionKey, payload, attemptCount, lastAttemptAt, nextAttemptAt, lastError, finalizedAt);
    }
}
