package com.exchange.common.outbox.dao.repository;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exchange.common.db.manager.DbBaseRepository;
import com.exchange.common.kafka.listener.outbox.OutboxInsertDecision;
import com.exchange.common.outbox.dao.mapper.OutboxMapper;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.outbox.po.enums.OutboxStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Component
public class OutboxRepository extends DbBaseRepository<Outbox, OutboxMapper> {
    public OutboxRepository(OutboxMapper mapper) {
        super(mapper);
    }

    public int insertWithClaim(Outbox outbox, LocalDateTime nextAttemptAt) {
        outbox.setAttemptCount(1);
        outbox.setNextAttemptAt(nextAttemptAt);
        return insertIgnore(outbox);
    }

    /*
     * INSERT IGNORE only tells us whether this attempt inserted a row. When it does not,
     * verify the existing event reference before treating the duplicate as success; same intent is
     * idempotent delivery, missing state is ambiguous, and different intent is a conflict.
     */
    public OutboxInsertDecision insertWithClaimAndVerify(Outbox outbox, LocalDateTime nextAttemptAt) {
        if (insertWithClaim(outbox, nextAttemptAt) == 1) {
            return OutboxInsertDecision.inserted(outbox);
        }

        Outbox existing = selectByEventRef(outbox.getEventRef());
        if (existing == null) {
            return OutboxInsertDecision.existingMissingAmbiguous(outbox);
        }
        if (sameIntent(outbox, existing)) {
            return OutboxInsertDecision.existingSameIntent(outbox, existing);
        }
        return OutboxInsertDecision.existingConflict(outbox, existing);
    }

    public Outbox selectByEventRef(String eventRef) {
        var query = Wrappers.<Outbox>lambdaQuery().eq(Outbox::getEventRef, eventRef);
        return mapper.selectOne(query);
    }

    public int updateStatusToFinalize(Outbox outbox, OutboxStatus newStatus, LocalDateTime finalizedAt) {
        var updateWrapper = new LambdaUpdateWrapper<Outbox>();
        updateWrapper.eq(Outbox::getEventRef, outbox.getEventRef())
                .eq(Outbox::getOutboxStatus, outbox.getOutboxStatus())
                .set(Outbox::getOutboxStatus, newStatus)
                .set(Outbox::getFinalizedAt, finalizedAt);
        return mapper.update(updateWrapper);
    }

    public int batchUpdateStatusToFinalize(List<Long> outboxInternalIds, OutboxStatus oldStatus, OutboxStatus newStatus, LocalDateTime finalizedAt) {
        var updateWrapper = Wrappers.<Outbox>lambdaUpdate()
                .in(Outbox::getInternalId, outboxInternalIds)
                .eq(Outbox::getOutboxStatus, oldStatus)
                .set(Outbox::getOutboxStatus, newStatus)
                .set(Outbox::getFinalizedAt, finalizedAt);
        return mapper.update(updateWrapper);
    }

    public int batchClaim(List<Long> outboxInternalIds, LocalDateTime nextAttemptAt) {
        var updateWrapper = Wrappers.<Outbox>lambdaUpdate()
                .in(Outbox::getInternalId, outboxInternalIds)
                .set(Outbox::getNextAttemptAt, nextAttemptAt)
                .setSql("attempt_count=attempt_count+1");
        return mapper.update(updateWrapper);
    }

    /**
     * Claims are read oldest-due-first using the same column order as idx_outbox_claim.
     * The exclusive (nextAttemptAt, internalId) cursor is required because internalId alone is not monotonic
     * in next-attempt order. The upper boundary stays fixed for one retry cycle so the worker
     * scans a bounded due-time window instead of chasing newly eligible rows.
     *
     * <pre>{@code
     * SELECT *
     * FROM outbox
     * WHERE outbox_status = :pending
     *   AND attempt_count < :maxRetries
     *   AND next_attempt_at <= :rightBoundary
     *   AND (
     *       next_attempt_at > :lastNextAttemptAt
     *       OR (next_attempt_at = :lastNextAttemptAt AND internal_id > :lastInternalId)
     *   )
     * ORDER BY next_attempt_at ASC, internal_id ASC
     * LIMIT :limit
     * FOR UPDATE SKIP LOCKED
     * }</pre>
     *
     * The tuple-cursor predicate is omitted for the first page when lastNextAttemptAt is null.
     */
    public List<Outbox> selectForClaimSkipLock(LocalDateTime rightBoundary, int maxRetries,
                                                LocalDateTime lastNextAttemptAt, long lastInternalId, int limit) {
        var query = Wrappers.<Outbox>lambdaQuery()
                .eq(Outbox::getOutboxStatus, OutboxStatus.PENDING)
                .lt(Outbox::getAttemptCount, maxRetries)
                .le(Outbox::getNextAttemptAt, rightBoundary)
                .and(lastNextAttemptAt != null, cursor -> cursor
                        .gt(Outbox::getNextAttemptAt, lastNextAttemptAt)
                        .or(sameAttemptAt -> sameAttemptAt
                                .eq(Outbox::getNextAttemptAt, lastNextAttemptAt)
                                .gt(Outbox::getInternalId, lastInternalId)))
                .orderByAsc(Outbox::getNextAttemptAt, Outbox::getInternalId)
                .last("limit " + limit + " for update skip locked");
        return mapper.selectList(query);
    }

    private boolean sameIntent(Outbox intended, Outbox existing) {
        return Objects.equals(intended.getEventType(), existing.getEventType())
                && Objects.equals(intended.getEventRef(), existing.getEventRef())
                && Objects.equals(intended.getCommandRef(), existing.getCommandRef())
                && Objects.equals(intended.getDestination(), existing.getDestination())
                && Objects.equals(intended.getPartitionKey(), existing.getPartitionKey())
                && Arrays.equals(intended.getPayload(), existing.getPayload());
    }
}
