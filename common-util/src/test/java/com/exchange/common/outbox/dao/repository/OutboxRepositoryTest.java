package com.exchange.common.outbox.dao.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exchange.common.kafka.listener.outbox.OutboxInsertDecisionType;
import com.exchange.common.outbox.dao.mapper.OutboxMapper;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.outbox.po.enums.OutboxStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRepositoryTest {
    @Mock
    private OutboxMapper mapper;

    private OutboxRepository repository;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(OutboxMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, Outbox.class);
        repository = new OutboxRepository(mapper);
    }

    @Test
    void insertedRowReturnsInsertedDecision() {
        // KL-OR-001: a successful insert needs no duplicate-intent verification.
        Outbox intended = outbox();
        when(mapper.insert(intended)).thenReturn(1);

        var decision = repository.insertWithClaimAndVerify(intended, LocalDateTime.now());

        assertEquals(OutboxInsertDecisionType.INSERTED, decision.type());
        assertSame(intended, decision.intended());
        verify(mapper, never()).selectOne(anyWrapper());
    }

    @Test
    void insertZeroAndExistingMissingReturnsAmbiguous() {
        // KL-OR-002: insert-ignore affected zero rows but lookup missed the row, so state is ambiguous.
        Outbox intended = outbox();
        when(mapper.insert(intended)).thenReturn(0);
        when(mapper.selectOne(anyWrapper())).thenReturn(null);

        var decision = repository.insertWithClaimAndVerify(intended, LocalDateTime.now());

        assertEquals(OutboxInsertDecisionType.EXISTING_MISSING_AMBIGUOUS, decision.type());
    }

    @Test
    void insertZeroAndSameStableIntentReturnsSameIntent() {
        // KL-OR-003: lifecycle fields do not affect reply intent comparison.
        Outbox intended = outbox();
        Outbox existing = outbox();
        existing.setOutboxStatus(OutboxStatus.SENT);
        existing.setAttemptCount(9);
        existing.setLastAttemptAt(LocalDateTime.now().minusHours(1));
        existing.setNextAttemptAt(LocalDateTime.now().plusHours(1));
        existing.setLastError("old error");
        existing.setFinalizedAt(LocalDateTime.now());
        when(mapper.insert(intended)).thenReturn(0);
        when(mapper.selectOne(anyWrapper())).thenReturn(existing);

        var decision = repository.insertWithClaimAndVerify(intended, LocalDateTime.now());

        assertEquals(OutboxInsertDecisionType.EXISTING_SAME_INTENT, decision.type());
        assertSame(existing, decision.existing());
    }

    @Test
    void insertZeroAndDifferentPayloadReturnsConflict() {
        // KL-OR-004: same event id with different payload means different reply intent.
        Outbox intended = outbox();
        Outbox existing = outbox();
        existing.setPayload("different".getBytes());
        when(mapper.insert(intended)).thenReturn(0);
        when(mapper.selectOne(anyWrapper())).thenReturn(existing);

        var decision = repository.insertWithClaimAndVerify(intended, LocalDateTime.now());

        assertEquals(OutboxInsertDecisionType.EXISTING_CONFLICT, decision.type());
    }

    @Test
    void insertZeroAndDifferentRoutingFieldReturnsConflict() {
        // KL-OR-005: stable routing and business identifiers are part of reply intent.
        List<Consumer<Outbox>> mutations = List.of(
                outbox -> outbox.setDestination("other-topic"),
                outbox -> outbox.setPartitionKey("other-key"),
                outbox -> outbox.setEventType(2),
                outbox -> outbox.setCommandRef("other-command")
        );

        for (Consumer<Outbox> mutation : mutations) {
            reset(mapper);
            Outbox intended = outbox();
            Outbox existing = outbox();
            mutation.accept(existing);
            when(mapper.insert(intended)).thenReturn(0);
            when(mapper.selectOne(anyWrapper())).thenReturn(existing);

            var decision = repository.insertWithClaimAndVerify(intended, LocalDateTime.now());

            assertEquals(OutboxInsertDecisionType.EXISTING_CONFLICT, decision.type());
        }
    }

    @Test
    void insertWithClaimInitializesAttemptFields() {
        // KL-OR-006: first claim starts outbox delivery attempt tracking at one.
        Outbox intended = outbox();
        LocalDateTime nextAttemptAt = LocalDateTime.of(2026, 7, 11, 12, 0);
        when(mapper.insert(intended)).thenReturn(1);

        int inserted = repository.insertWithClaim(intended, nextAttemptAt);

        assertEquals(1, inserted);
        assertEquals(1, intended.getAttemptCount());
        assertEquals(nextAttemptAt, intended.getNextAttemptAt());
        verify(mapper).insert(eq(intended));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void claimQueryUsesDueTimeTupleCursorAndMatchingOrder() {
        LocalDateTime rightBoundary = LocalDateTime.of(2026, 8, 24, 12, 0);
        LocalDateTime lastNextAttemptAt = rightBoundary.minusMinutes(1);
        when(mapper.selectList(anyWrapper())).thenReturn(List.of());

        repository.selectForClaimSkipLock(rightBoundary, 5, lastNextAttemptAt, 42L, 10);

        ArgumentCaptor<Wrapper<Outbox>> wrapperCaptor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(mapper).selectList(wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql.contains("next_attempt_at <="));
        assertTrue(sql.contains("next_attempt_at >"));
        assertTrue(sql.contains("next_attempt_at ="));
        assertTrue(sql.contains("internal_id >"));
        assertTrue(sql.contains("order by next_attempt_at asc,internal_id asc"));
        assertTrue(sql.contains("for update skip locked"));
    }

    private Wrapper<Outbox> anyWrapper() {
        return any();
    }

    private Outbox outbox() {
        return Outbox.create(
                1L,
                1,
                "ledger-reply-event-ref",
                "wallet-transaction-ref",
                OutboxStatus.PENDING,
                "ledger.wallet.reply.posting",
                "wallet-command-123",
                "payload".getBytes(),
                0,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                null
        );
    }
}
