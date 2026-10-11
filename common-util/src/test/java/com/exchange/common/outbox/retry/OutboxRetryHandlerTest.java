package com.exchange.common.outbox.retry;

import com.exchange.common.db.utils.DbTxnExecutor;
import com.exchange.common.kafka.producer.IPublisher;
import com.exchange.common.kafka.producer.PublishSource;
import com.exchange.common.outbox.config.OutboxProperties;
import com.exchange.common.outbox.dao.repository.OutboxRepository;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.outbox.po.enums.OutboxStatus;
import com.exchange.common.result.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRetryHandlerTest {
    @Mock
    private OutboxRepository repository;
    @Mock
    private DbTxnExecutor dbTxnExecutor;
    @Mock
    private IPublisher publisher;

    @SuppressWarnings("unchecked")
    @Test
    void retryWorkerMarksPublisherAttemptAsRetrySource() {
        // Retry-source tagging separates scheduled recovery from latency-sensitive immediate sends.
        OutboxProperties properties = new OutboxProperties();
        properties.setPageLimit(10);
        properties.setMaxRetries(5);
        properties.setAttemptIntervalSec(5);
        Outbox outbox = Outbox.create(
                1L,
                1,
                "event-1",
                "command-1",
                OutboxStatus.PENDING,
                "topic-1",
                "command-1",
                new byte[]{1},
                1,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                null
        );

        when(dbTxnExecutor.executeWithDefault(any())).thenAnswer(invocation ->
                ((Supplier<Result<List<Outbox>>>) invocation.getArgument(0)).get());
        when(repository.selectForClaimSkipLock(
                any(LocalDateTime.class), eq(5), nullable(LocalDateTime.class), anyLong(), eq(10)))
                .thenReturn(List.of(outbox));
        when(repository.batchClaim(anyList(), any(LocalDateTime.class))).thenReturn(1);
        when(publisher.publishAsync(outbox, PublishSource.RETRY))
                .thenReturn(CompletableFuture.completedFuture(Result.success()));
        when(repository.batchUpdateStatusToFinalize(
                anyList(),
                eq(OutboxStatus.PENDING),
                eq(OutboxStatus.SENT),
                any(LocalDateTime.class)
        )).thenReturn(1);

        new OutboxRetryHandler(properties, repository, dbTxnExecutor, publisher).retry();

        verify(publisher).publishAsync(outbox, PublishSource.RETRY);
    }

    @SuppressWarnings("unchecked")
    @Test
    void retryWorkerAdvancesTupleCursorWithinFixedRightBoundary() {
        OutboxProperties properties = new OutboxProperties();
        properties.setPageLimit(1);
        properties.setMaxRetries(5);
        properties.setAttemptIntervalSec(5);
        LocalDateTime attemptAt = LocalDateTime.of(2026, 8, 24, 12, 0);
        Outbox outbox = outbox(11L, attemptAt);

        when(dbTxnExecutor.executeWithDefault(any())).thenAnswer(invocation ->
                ((Supplier<Result<List<Outbox>>>) invocation.getArgument(0)).get());
        when(repository.selectForClaimSkipLock(
                any(LocalDateTime.class), eq(5), nullable(LocalDateTime.class), anyLong(), eq(1)))
                .thenReturn(List.of(outbox), List.of());
        when(repository.batchClaim(anyList(), any(LocalDateTime.class))).thenReturn(1);
        when(publisher.publishAsync(outbox, PublishSource.RETRY))
                .thenReturn(CompletableFuture.completedFuture(Result.success()));
        when(repository.batchUpdateStatusToFinalize(
                anyList(), eq(OutboxStatus.PENDING), eq(OutboxStatus.SENT), any(LocalDateTime.class)))
                .thenReturn(1);

        new OutboxRetryHandler(properties, repository, dbTxnExecutor, publisher).retry();

        ArgumentCaptor<LocalDateTime> boundaryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> attemptCursorCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Long> idCursorCaptor = ArgumentCaptor.forClass(Long.class);
        verify(repository, times(2)).selectForClaimSkipLock(
                boundaryCaptor.capture(), eq(5), attemptCursorCaptor.capture(), idCursorCaptor.capture(), eq(1));
        assertEquals(boundaryCaptor.getAllValues().get(0), boundaryCaptor.getAllValues().get(1));
        assertNull(attemptCursorCaptor.getAllValues().get(0));
        assertEquals(attemptAt, attemptCursorCaptor.getAllValues().get(1));
        assertEquals(0L, idCursorCaptor.getAllValues().get(0));
        assertEquals(11L, idCursorCaptor.getAllValues().get(1));
    }

    private Outbox outbox(long id, LocalDateTime nextAttemptAt) {
        Outbox outbox = Outbox.create(
                id,
                1,
                "event-" + id,
                "command-" + id,
                OutboxStatus.PENDING,
                "topic-1",
                "command-" + id,
                new byte[]{1},
                1,
                nextAttemptAt.minusSeconds(5),
                nextAttemptAt,
                null,
                null
        );
        return outbox;
    }
}
