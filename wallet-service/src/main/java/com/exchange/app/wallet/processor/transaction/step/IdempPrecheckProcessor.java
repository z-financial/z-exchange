package com.exchange.app.wallet.processor.transaction.step;

import com.exchange.app.wallet.config.CustomCacheProperties;
import com.exchange.app.wallet.dao.repository.*;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.app.wallet.processor.transaction.model.RequestInfo;
import com.exchange.app.wallet.processor.transaction.util.Constant;
import com.exchange.app.wallet.processor.transaction.util.TransactionValidateHelper;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.app.wallet.utils.EnumPbMappers;
import com.exchange.common.redis.idemp.IdempotencyClient;
import com.exchange.common.redis.idemp.utils.IdempValue;
import com.exchange.common.constant.GlobalServiceId;
import com.exchange.common.result.Result;
import com.exchange.common.result.error.IdempErrorCode;
import com.exchange.common.result.error.RedisErrorCode;
import com.exchange.common.utils.JitterHelper;
import com.exchange.proto.wallet.common.ServiceIdPb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableConfigurationProperties(CustomCacheProperties.Idemp.class)
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class IdempPrecheckProcessor {
    private final IdempotencyClient idempotencyClient;

    private final CustomCacheProperties.Idemp idempConfig;

    private final WalletTransactionRepository walletTransactionManager;

    // Return the persisted Wallet transaction reference when this is an accepted replay.
    // return null if not exists
    public Result<String> idempAndValidatePrecheck(RequestInfo requestInfo) {
        Result<Void> precheckResult = TransactionValidateHelper.requestPrecheck(requestInfo);
        if (precheckResult.isFailed()) {
            // fast fail before claim idemp
            return Result.failure(precheckResult);
        }

        Result<IdempValue> valueResult = claimIdempCacheIfAbsent(requestInfo);
        // there should be no idemp value with the given token in the cache if fails here. no need to release here
        if (valueResult.isFailed()) {
            return Result.failure(valueResult);
        }

        IdempValue value = valueResult.getValue();
        // when value is not null, it means claim idemp fails because it has been claimed, no need to release here
        if (value != null) {
            switch (value.status) {
                case PENDING:
                    // another request with same params is in processing (but likely no wallet_txn has been persisted in db)
                    log.error("another same request in processing, requestInfo:{}, idempValue:{}", requestInfo, value);
                    return Result.failure(WalletServiceErrorCode.REQUEST_IN_PROCESSING, "request still processing");
                case ACCEPTED:
                    // return txn id
                    return Result.success(value.content, "wallet txn already accepted");
                default:
                    log.error("unknown idemp state:{}", value);
                    return Result.failure(WalletServiceErrorCode.INVALID_ENUM_ERROR, "unknown idemp state");
            }
        }

        WalletTransaction walletTxn = idempDbCheck(
                requestInfo.serviceIdPb, requestInfo.upstreamRequestRef);
        if (walletTxn != null) {
            // set idemp to DONE when found wallet txn
            Result<Void> markDoneResult = idempotencyClient.markIdempDone(
                    GlobalServiceId.WALLET.code, Constant.SCOPE,
                    requestInfo.idempotencyCacheKey(),
                    String.valueOf(requestInfo.getStableHash()), requestInfo.token, walletTxn.getWalletTxnRef(),
                    JitterHelper.jitter(idempConfig.getDoneTtl(), idempConfig.getJitterMs()));
            if (markDoneResult.isFailed()) {
                // DB idempotency is authoritative; Redis refresh failure must not hide a committed replay.
                log.warn("mark wallet idempotency done failed after DB replay, result={}", markDoneResult);
            }

            log.info("wallet txn already exists: {}", walletTxn);
            return Result.success(walletTxn.getWalletTxnRef(), "wallet txn already exists");
        }

        return Result.success();
    }



    // return null if set success
    // return idemp info in the cache when fail to set
    private Result<IdempValue> claimIdempCacheIfAbsent(RequestInfo requestInfo) {
        String globalCode = GlobalServiceId.WALLET.code;
        String idempotencyCacheKey = requestInfo.idempotencyCacheKey();
        String reqHash = requestInfo.getStableHash();
        Result<Boolean> claimResult = idempotencyClient.claimIdempIfAbsent(
                globalCode, Constant.SCOPE, idempotencyCacheKey,
                reqHash, requestInfo.token, JitterHelper.jitter(idempConfig.getPendingTtl(), idempConfig.getJitterMs()));
        if (claimResult.isFailed()) {
            // Redis accelerates idempotency; its failure degrades to the authoritative DB check.
            log.warn("claim wallet idempotency failed, falling back to DB, result={}", claimResult);
            return Result.success();
        }
        if (Boolean.TRUE.equals(claimResult.getValue())) {
            return Result.success();
        }

        Result<IdempValue> valueResult = idempotencyClient.getIdempAndVerifyHash(
                globalCode, Constant.SCOPE, idempotencyCacheKey, reqHash);
        if (valueResult.isFailed()) {
            if (Result.is(valueResult, IdempErrorCode.HASH_CONFLICT)) {
                return Result.failure(WalletServiceErrorCode.REQUEST_HASH_CONFLICT, valueResult.getDetail());
            }
            if (Result.is(valueResult, RedisErrorCode.MALFORMED_VALUE)) {
                log.error("parse wallet idempotency value failed, deleting malformed Redis entry");
                Result<Boolean> deleteResult = idempotencyClient.forceDeleteIdemp(
                        globalCode, Constant.SCOPE, idempotencyCacheKey);
                if (deleteResult.isFailed()) {
                    log.warn("delete malformed wallet idempotency value failed, result={}", deleteResult);
                }
                return Result.success();
            }
            log.warn("read wallet idempotency failed, falling back to DB, result={}", valueResult);
            return Result.success();
        }
        return valueResult;
    }

    private WalletTransaction idempDbCheck(
            ServiceIdPb serviceIdPb,
            String upstreamRequestRef) {
        // TODO(idempotency-db-authority): Persist the canonical request hash with Wallet's DB
        //  transaction and compare it on every DB fallback/replay. The same
        //  (initiator, upstream_request_ref) with the same hash must replay the stored result;
        //  a different hash must return HASH_CONFLICT even after Redis loss or expiry. Target design:
        //  design/proposals/redis-idempotency-db-authoritative.md.
        ServiceId serviceId = EnumPbMappers.serviceIdPbMapper.to(serviceIdPb);
        return walletTransactionManager.selectByUpstreamRequestRef(serviceId, upstreamRequestRef);
    }
}
