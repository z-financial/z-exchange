package com.exchange.common.outbox.retry;

import com.exchange.common.db.utils.DbTxnExecutor;
import com.exchange.common.kafka.producer.IPublisher;
import com.exchange.common.kafka.producer.PublishSource;
import com.exchange.common.outbox.config.OutboxProperties;
import com.exchange.common.outbox.dao.repository.OutboxRepository;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.outbox.po.enums.OutboxStatus;
import com.exchange.common.result.Result;
import com.exchange.common.result.error.OutboxErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Recovers pending outbox events through bounded, page-oriented publish attempts.
 *
 * <p>A short database transaction claims each page by advancing its next-attempt time. All sends
 * in the page are started before waiting for results, allowing broker acknowledgments to arrive
 * concurrently. Only acknowledged sends are finalized as {@link OutboxStatus#SENT}; failed or
 * timed-out sends remain pending for a later retry cycle.</p>
 *
 * <p>Delivery is at-least-once: if Kafka accepts an event but database finalization fails, the row
 * is published again later. Consumers therefore must process event identifiers idempotently.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxRetryHandler {
    private final OutboxProperties outboxConfig;
    private final OutboxRepository outboxManager;
    private final DbTxnExecutor dbTxnExecutor;
    private final IPublisher publisher;

    // TODO maybe add a execute time limit
    public void retry() {
        /*
         * TODO: exhausted rows currently remain PENDING after maxRetries and are no longer
         *  selected. Before exposing pending count/oldest-age/dead-count gauges, add an atomic
         *  PENDING-to-DEAD transition; otherwise one exhausted ancient row permanently distorts
         *  the pending backlog and oldest-age signals.
         */
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastNextAttemptAt = null;
        long lastInternalId = 0L;
        int successCount = 0, failedCount = 0;
        while (true) {
            Result<List<Outbox>> result = claimPage(now, lastNextAttemptAt, lastInternalId);
            if (!result.isSuccess()) {
                log.error("claim failed, stopping current retry cycle, lastNextAttemptAt: {}, lastInternalId: {}, result: {}",
                        lastNextAttemptAt, lastInternalId, result);
                break;
            }
            List<Outbox> outboxes = result.getValue();
            if (!outboxes.isEmpty()) {
                Outbox last = outboxes.getLast();
                // batchClaim updates the database directly, so these are the pre-claim values.
                // Advancing both fields preserves keyset pagination when timestamps are equal.
                lastNextAttemptAt = last.getNextAttemptAt();
                lastInternalId = last.getInternalId();
            }

            List<Outbox> successOutboxes = new ArrayList<>(), failedOutboxes = new ArrayList<>();
            // Start the whole page before joining so broker acknowledgment waits overlap.
            List<CompletableFuture<PublishAttempt>> publishFutures = outboxes.stream()
                    .map(this::publishAsync)
                    .toList();
            for (CompletableFuture<PublishAttempt> publishFuture : publishFutures) {
                PublishAttempt attempt = publishFuture.join();
                if (attempt.success()) {
                    successOutboxes.add(attempt.outbox());
                } else {
                    failedOutboxes.add(attempt.outbox());
                }
            }
            // Finalize only broker-acknowledged attempts; every other row remains retryable.
            if (!successOutboxes.isEmpty()) {
                int updatedCount = outboxManager.batchUpdateStatusToFinalize(successOutboxes.stream().map(Outbox::getInternalId).collect(Collectors.toList()), OutboxStatus.PENDING, OutboxStatus.SENT, now);
                if (updatedCount != successOutboxes.size()) {
                    log.error("fail to finalize success outboxes, updated count: {}, need update size: {}, outboxes: {}", updatedCount, successOutboxes.size(), successOutboxes);
                }
                successCount += updatedCount;
                failedCount += successOutboxes.size() - updatedCount;
            }
            if (!failedOutboxes.isEmpty()) {
                log.error("fail to publish outboxes: {}", failedOutboxes);
                failedCount += failedOutboxes.size();
            }
            if (outboxes.size() < outboxConfig.getPageLimit()) {
                break;
            }
        }
        log.info("outbox retry success: {}, failed: {}", successCount, failedCount);
    }

    private CompletableFuture<PublishAttempt> publishAsync(Outbox outbox) {
        try {
            CompletableFuture<Result<Void>> publishFuture = publisher.publishAsync(outbox, PublishSource.RETRY);
            if (publishFuture == null) {
                log.error("outbox publish returned null future, eventRef={}, commandRef={}", outbox.getEventRef(), outbox.getCommandRef());
                return CompletableFuture.completedFuture(PublishAttempt.failed(outbox));
            }
            return publishFuture.handle((result, throwable) -> {
                if (throwable != null) {
                    log.error(
                            "outbox publish completed exceptionally, eventRef={}, commandRef={}",
                            outbox.getEventRef(),
                            outbox.getCommandRef(),
                            throwable
                    );
                    return PublishAttempt.failed(outbox);
                }
                return result != null && result.isSuccess()
                        ? PublishAttempt.succeeded(outbox)
                        : PublishAttempt.failed(outbox);
            });
        } catch (Exception e) {
            log.error(
                    "outbox publish setup threw exception, eventRef={}, commandRef={}",
                    outbox.getEventRef(),
                    outbox.getCommandRef(),
                    e
            );
            return CompletableFuture.completedFuture(PublishAttempt.failed(outbox));
        }
    }

    private Result<List<Outbox>> claimPage(LocalDateTime now, LocalDateTime lastNextAttemptAt, long lastInternalId) {
        // select for update skip lock + update next_attempt_at to claim
        return dbTxnExecutor.executeWithDefault(() -> {
            List<Outbox> locked = outboxManager.selectForClaimSkipLock(
                    now, outboxConfig.getMaxRetries(), lastNextAttemptAt, lastInternalId, outboxConfig.getPageLimit());
            if (locked.isEmpty()) {
                return Result.success(List.of());
            }
            int claimedCount = outboxManager.batchClaim(locked.stream().map(Outbox::getInternalId).collect(Collectors.toList()), now.plusSeconds(outboxConfig.getAttemptIntervalSec()));
            if (claimedCount != locked.size()) {
                log.error("unexpected claim outbox failure, claimed: {}, locked: {}", claimedCount, locked);
                return Result.failure(OutboxErrorCode.UNEXPECTED_DB_ERROR, "unexpected claim outbox failure");
            }
            return Result.success(List.copyOf(locked));
        });
    }

    private record PublishAttempt(Outbox outbox, boolean success) {
        private static PublishAttempt succeeded(Outbox outbox) {
            return new PublishAttempt(outbox, true);
        }

        private static PublishAttempt failed(Outbox outbox) {
            return new PublishAttempt(outbox, false);
        }
    }
}
