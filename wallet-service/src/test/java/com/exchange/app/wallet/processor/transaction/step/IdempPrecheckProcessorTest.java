package com.exchange.app.wallet.processor.transaction.step;

import com.exchange.app.wallet.config.CustomCacheProperties;
import com.exchange.app.wallet.dao.repository.WalletTransactionRepository;
import com.exchange.app.wallet.po.enums.transaction.TransactionType;
import com.exchange.app.wallet.processor.transaction.model.RequestInfo;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.common.redis.idemp.IdempotencyClient;
import com.exchange.common.result.Result;
import com.exchange.common.result.error.IdempErrorCode;
import com.exchange.common.result.error.RedisErrorCode;
import com.exchange.proto.wallet.common.BusinessTypePb;
import com.exchange.proto.wallet.common.ServiceIdPb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempPrecheckProcessorTest {
    @Mock
    private IdempotencyClient idempotencyClient;
    @Mock
    private WalletTransactionRepository walletTransactionRepository;

    private IdempPrecheckProcessor processor;

    @BeforeEach
    void setUp() {
        CustomCacheProperties.Idemp config = new CustomCacheProperties.Idemp();
        config.setPendingTtl(Duration.ofMinutes(2));
        config.setDoneTtl(Duration.ofHours(24));
        config.setJitterMs(100);
        processor = new IdempPrecheckProcessor(idempotencyClient, config, walletTransactionRepository);
    }

    @Test
    void redisClaimFailureFallsBackToAuthoritativeDbCheck() {
        when(idempotencyClient.claimIdempIfAbsent(
                anyString(), anyString(), eq("2:upstream-request-1"),
                anyString(), anyString(), any(Duration.class)))
                .thenReturn(Result.failure(RedisErrorCode.CONNECTION, "redis unavailable"));
        when(walletTransactionRepository.selectByUpstreamRequestRef(any(), anyString())).thenReturn(null);

        Result<String> result = processor.idempAndValidatePrecheck(requestInfo());

        assertTrue(result.isSuccess());
        verify(walletTransactionRepository).selectByUpstreamRequestRef(any(), anyString());
    }

    @Test
    void hashConflictMapsToWalletBoundaryError() {
        when(idempotencyClient.claimIdempIfAbsent(
                anyString(), anyString(), anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(Result.success(false));
        when(idempotencyClient.getIdempAndVerifyHash(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Result.failure(IdempErrorCode.HASH_CONFLICT, "hash conflict"));

        Result<String> result = processor.idempAndValidatePrecheck(requestInfo());

        assertTrue(result.isFailed());
        assertEquals(WalletServiceErrorCode.REQUEST_HASH_CONFLICT, result.getErrorCode());
    }

    @Test
    void malformedRedisValueIsDeletedBeforeDbFallback() {
        when(idempotencyClient.claimIdempIfAbsent(
                anyString(), anyString(), anyString(), anyString(), anyString(), any(Duration.class)))
                .thenReturn(Result.success(false));
        when(idempotencyClient.getIdempAndVerifyHash(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Result.failure(RedisErrorCode.MALFORMED_VALUE, "malformed"));
        when(idempotencyClient.forceDeleteIdemp(anyString(), anyString(), anyString()))
                .thenReturn(Result.success(true));
        when(walletTransactionRepository.selectByUpstreamRequestRef(any(), anyString())).thenReturn(null);

        Result<String> result = processor.idempAndValidatePrecheck(requestInfo());

        assertTrue(result.isSuccess());
        verify(idempotencyClient).forceDeleteIdemp(anyString(), anyString(), anyString());
        verify(walletTransactionRepository).selectByUpstreamRequestRef(any(), anyString());
    }

    private RequestInfo requestInfo() {
        return new RequestInfo(
                "upstream-request-1",
                ServiceIdPb.ServiceIdPb_User,
                BusinessTypePb.BusinessTypePb_Transfer,
                TransactionType.ATOMIC,
                List.of(),
                "atomic_transaction",
                "token-1"
        );
    }
}
