package com.exchange.app.ledger.processor.post;

import com.exchange.app.ledger.config.CustomCacheProperties;
import com.exchange.app.ledger.dao.repository.AccountRepository;
import com.exchange.app.ledger.dao.repository.LedgerEntryRepository;
import com.exchange.app.ledger.dao.repository.LedgerTxnRepository;
import com.exchange.app.ledger.dao.store.LedgerTxnStore;
import com.exchange.app.ledger.po.account.Account;
import com.exchange.app.ledger.po.enums.AccountCategory;
import com.exchange.app.ledger.po.enums.AccountStatus;
import com.exchange.app.ledger.po.enums.NormalSide;
import com.exchange.app.ledger.po.enums.OwnerType;
import com.exchange.app.ledger.po.enums.ServiceId;
import com.exchange.app.ledger.po.ledger.LedgerEntry;
import com.exchange.app.ledger.po.ledger.LedgerTxn;
import com.exchange.app.ledger.result.PostTransactionResult;
import com.exchange.common.db.utils.DbTxnExecutor;
import com.exchange.common.identity.DefaultEntityRefCodec;
import com.exchange.common.identity.EntityIdentityFactory;
import com.exchange.common.identity.EntityRefCodec;
import com.exchange.common.identity.EntityRefType;
import com.exchange.common.identity.GeneratedEntityIdentity;
import com.exchange.common.redis.idemp.IdempotencyClient;
import com.exchange.common.result.Result;
import com.exchange.proto.ledger.common.LedgerDirectionPb;
import com.exchange.proto.ledger.post.LedgerEntryPb;
import com.exchange.proto.ledger.post.PostTransactionRequestPb;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostLedgerProcessorTest {
    @Test
    void allocatesTypedOwnerIdentitiesBeforeOpeningDbTransaction() {
        EntityRefCodec codec = new DefaultEntityRefCodec();
        String sourceTxnRef = codec.encode(EntityRefType.WALLET_TRANSACTION, 1L);
        String ledgerAccountRef = codec.encode(EntityRefType.LEDGER_ACCOUNT, 2L);

        AccountRepository accountRepository = mock(AccountRepository.class);
        LedgerTxnRepository txnRepository = mock(LedgerTxnRepository.class);
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        LedgerTxnStore txnStore = mock(LedgerTxnStore.class);
        IdempotencyClient idempotencyClient = mock(IdempotencyClient.class);

        when(accountRepository.getByLedgerAccountRefs(List.of(ledgerAccountRef)))
                .thenReturn(List.of(Account.create(
                        2L, ledgerAccountRef, ServiceId.WALLET, "wallet-ref",
                        AccountCategory.ASSET, NormalSide.DEBIT, "owner-1", OwnerType.USER,
                        "asset-1", AccountStatus.OPEN)));
        when(idempotencyClient.claimIdempIfAbsent(
                any(), any(), any(), any(), any(), any())).thenReturn(Result.success(true));
        when(txnRepository.insertIgnore(any(LedgerTxn.class))).thenReturn(1);
        when(entryRepository.batchInsert(anyList())).thenAnswer(
                invocation -> ((List<?>) invocation.getArgument(0)).size());
        when(idempotencyClient.markIdempDone(
                any(), any(), any(), any(), any(), any(), any())).thenReturn(Result.success());

        AtomicBoolean transactionOpen = new AtomicBoolean();
        DbTxnExecutor txnExecutor = new DbTxnExecutor() {
            @Override
            public <T extends Result<?>> T executeWithDefault(Supplier<T> supplier) {
                transactionOpen.set(true);
                try {
                    return supplier.get();
                } finally {
                    transactionOpen.set(false);
                }
            }
        };

        AtomicLong nextId = new AtomicLong(100L);
        EntityIdentityFactory identityFactory = type -> {
            // Generator/database work must finish before the business transaction begins.
            assertFalse(transactionOpen.get());
            long internalId = nextId.incrementAndGet();
            return new GeneratedEntityIdentity(internalId, codec.encode(type, internalId));
        };

        CustomCacheProperties.Idemp idempConfig = new CustomCacheProperties.Idemp();
        idempConfig.setPendingTtl(Duration.ofMinutes(1));
        idempConfig.setDoneTtl(Duration.ofHours(1));
        idempConfig.setJitterMs(1);

        PostLedgerProcessor processor = new PostLedgerProcessor(
                idempConfig, entryRepository, accountRepository, txnRepository,
                idempotencyClient, txnExecutor, txnStore, identityFactory, codec);

        PostTransactionResult result = processor.postTransaction(request(
                sourceTxnRef, ledgerAccountRef));

        assertTrue(result.isCreated());
        assertEquals(codec.encode(EntityRefType.LEDGER_TRANSACTION, 101L), result.ledgerTxnRef());

        ArgumentCaptor<LedgerTxn> txnCaptor = ArgumentCaptor.forClass(LedgerTxn.class);
        verify(txnRepository).insertIgnore(txnCaptor.capture());
        LedgerTxn storedTxn = txnCaptor.getValue();
        assertEquals(101L, storedTxn.getInternalId());
        assertEquals(sourceTxnRef, storedTxn.getSourceTxnRef());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(entryRepository).batchInsert(entriesCaptor.capture());
        List<LedgerEntry> entries = entriesCaptor.getValue();
        assertEquals(List.of(102L, 103L),
                entries.stream().map(LedgerEntry::getInternalId).toList());
        assertTrue(entries.stream().allMatch(
                entry -> storedTxn.getLedgerTxnRef().equals(entry.getLedgerTxnRef())));
    }

    private PostTransactionRequestPb request(String sourceTxnRef, String ledgerAccountRef) {
        return PostTransactionRequestPb.newBuilder()
                .setSourceTxnRef(sourceTxnRef)
                .setDescription("transfer")
                .addEntries(entry(ledgerAccountRef, LedgerDirectionPb.LedgerDirection_Debit))
                .addEntries(entry(ledgerAccountRef, LedgerDirectionPb.LedgerDirection_Credit))
                .build();
    }

    private LedgerEntryPb entry(String ledgerAccountRef, LedgerDirectionPb direction) {
        return LedgerEntryPb.newBuilder()
                .setLedgerAccountRef(ledgerAccountRef)
                .setDirection(direction)
                .setAmount(100L)
                .setAssetId("asset-1")
                .build();
    }
}
