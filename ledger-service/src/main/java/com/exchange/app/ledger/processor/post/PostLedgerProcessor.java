package com.exchange.app.ledger.processor.post;

import com.exchange.app.ledger.config.CustomCacheProperties;
import com.exchange.app.ledger.dao.repository.AccountRepository;
import com.exchange.app.ledger.dao.repository.LedgerEntryRepository;
import com.exchange.app.ledger.dao.repository.LedgerTxnRepository;
import com.exchange.app.ledger.dao.store.LedgerTxnStore;
import com.exchange.app.ledger.po.account.Account;
import com.exchange.app.ledger.po.enums.Direction;
import com.exchange.app.ledger.po.ledger.LedgerEntry;
import com.exchange.app.ledger.po.ledger.LedgerTxn;
import com.exchange.app.ledger.result.LedgerBoundaryErrorMapper;
import com.exchange.app.ledger.result.LedgerServiceErrorCode;
import com.exchange.app.ledger.result.PostTransactionResult;
import com.exchange.common.redis.idemp.IdempotencyClient;
import com.exchange.common.redis.idemp.utils.CommonIdempHelper;
import com.exchange.common.redis.idemp.utils.IdempValue;
import com.exchange.common.constant.GlobalServiceId;
import com.exchange.common.db.utils.DbTxnExecutor;
import com.exchange.common.idgen.InternalIdGenerator;
import com.exchange.common.identity.EntityRefCodec;
import com.exchange.common.identity.EntityRefType;
import com.exchange.common.result.error.IdempErrorCode;
import com.exchange.common.result.error.RedisErrorCode;
import com.exchange.common.result.IResult;
import com.exchange.common.result.Result;
import com.exchange.common.utils.JitterHelper;
import com.exchange.common.utils.StableHashHelper;
import com.exchange.common.utils.TokenHelper;
import com.exchange.proto.ledger.post.LedgerEntryPb;
import com.exchange.proto.ledger.post.PostTransactionRequestPb;
import com.exchange.app.ledger.utils.EnumMappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@EnableConfigurationProperties(CustomCacheProperties.Idemp.class)
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class PostLedgerProcessor {
    static final private String SCOPE = "post_ledger";

    final private CustomCacheProperties.Idemp idempConfig;

    final private LedgerEntryRepository ledgerEntryRepository;
    final private AccountRepository accountManager;
    final private LedgerTxnRepository ledgerTxnRepository;

    final private IdempotencyClient idempotencyClient;

    final private DbTxnExecutor dbTxnExecutor;
    private final LedgerTxnStore ledgerTxnStore;
    private final InternalIdGenerator internalIdGenerator;
    private final EntityRefCodec entityRefCodec;

    public PostTransactionResult postTransaction(PostTransactionRequestPb req) {
        String token = TokenHelper.generateToken(GlobalServiceId.LEDGER.name());
        try {
            PostTransactionResult result = doPostTransaction(req, token);
            if (!result.isSuccess()) {
                releaseOwnedIdempotencySafely(req, token);
            }
            return result;
        } catch (RuntimeException failure) {
            // In particular, generator failures must not strand an owned PENDING claim.
            releaseOwnedIdempotencySafely(req, token);
            throw failure;
        }
    }

    private PostTransactionResult doPostTransaction(PostTransactionRequestPb req, String token) {
        Result<IdempDecision> idempResult = idempClaimAndReqValidate(req, token);
        if (idempResult.isFailed()) {
            return toFailure(idempResult);
        }

        IdempDecision idempDecision = idempResult.getValue();
        if (idempDecision.isReplay()) {
            return PostTransactionResult.idempotentReplay(
                    req.getWalletTxnRef(),
                    idempDecision.txnId(),
                    "already exists",
                    idempDecision.source()
            );
        }

        List<String> accountRefs = req.getEntriesList().stream().map(LedgerEntryPb::getAccountRef).distinct().collect(Collectors.toList());
        Set<String> persistedAccountRefs = accountManager.getByLedgerAccountRefs(accountRefs).stream()
                .map(Account::getLedgerAccountRef)
                .collect(Collectors.toSet());
        if (persistedAccountRefs.size() != accountRefs.size()) {
            Set<String> notFound = new HashSet<>(accountRefs);
            notFound.removeAll(persistedAccountRefs);
            log.error("account not found, notFoundRefs={}", notFound);
            return PostTransactionResult.failure(
                    LedgerServiceErrorCode.ACCOUNT_NOT_FOUND, "account_not_found, refs: " + notFound);
        }

        // Allocate the complete Ledger object graph before entering the business DB transaction.
        String txnId = nextInternalId();
        LedgerTxn ledgerTxn = LedgerTxn.create(txnId, req.getWalletTxnRef());
        List<LedgerEntry> entries = new ArrayList<>();
        for (LedgerEntryPb entryPb : req.getEntriesList()) {
            entries.add(createLedgerEntry(
                    nextInternalId(), txnId, entryPb, entryPb.getAccountRef()));
        }
        Result<Void> result = dbTxnExecutor.executeWithDefault(() -> {
            if (ledgerTxnRepository.insertIgnore(ledgerTxn) == 0) {
                // TODO(idempotency-db-authority): Resolve this duplicate-insert race by loading the
                //  winning Ledger transaction and comparing its persisted canonical request hash.
                //  Replay the winner for the same hash; return REQUEST_HASH_CONFLICT for a different
                //  hash. See design/proposals/redis-idempotency-db-authoritative.md.
                log.error("duplicated ledger txn: {}", ledgerTxn);
                return Result.failure(LedgerServiceErrorCode.LEDGER_DUPLICATED, "duplicated ledger txn");
            }
            if (ledgerEntryRepository.batchInsert(entries) < entries.size()) {
                log.error("unexpected ledger txn already exists, {}", ledgerTxn);
                // should be a server error?
                return Result.failure(LedgerServiceErrorCode.SERVER_ERROR, "unexpected duplicated_ledger_entry");
            }
            return Result.success();
        });
        if (result.isFailed()) {
            return toFailure(result);
        }
        // clean negative cache (if exist) after inserting ledger txn
        ledgerTxnStore.postInsert(ledgerTxn);
        // set idemp to DONE when found ledger txn
        Result<Void> markDoneResult = idempotencyClient.markIdempDone(GlobalServiceId.LEDGER.code, SCOPE,
                req.getWalletTxnRef(), getReqStableHash(req), token, txnId,
                JitterHelper.jitter(idempConfig.getDoneTtl(), idempConfig.getJitterMs()));
        if (markDoneResult.isFailed()) {
            log.warn("mark ledger idempotency done failed after DB commit, result={}", markDoneResult);
        }

        // Return domain facts; callers decide how to expose them as protobuf, outbox, or metrics.
        return PostTransactionResult.created(req.getWalletTxnRef(), txnId, "success", entries.size());
    }

    private Result<IdempDecision> idempClaimAndReqValidate(PostTransactionRequestPb req, String token) {
        Result<Void> precheckResult = reqPrecheck(req);
        if (precheckResult.isFailed()) {
            return Result.failure(precheckResult);
        }

        Result<IdempValue> idempValueResult = claimIdempCacheIfAbsent(req, token);
        if (idempValueResult.isFailed()) {
            return Result.failure(idempValueResult);
        }

        IdempValue idempValue = idempValueResult.getValue();
        if (idempValue != null) {
            switch (idempValue.status) {
                case PENDING:
                    // another request with same params is in processing (but likely no wallet_txn has been persisted in db)
                    log.error("another same ledger request is still processing, wallet_txn_ref={}", req.getWalletTxnRef());
                    return Result.failure(LedgerServiceErrorCode.REQUEST_IN_PROCESSING, "request still processing");
                case ACCEPTED:
                    log.info("ledger request replayed from Redis idempotency, wallet_txn_ref={}", req.getWalletTxnRef());
                    return Result.success(IdempDecision.replay(idempValue.content, PostTransactionResult.IdempotentSource.REDIS));
                default:
                    log.error("unknown idemp state:{}", idempValue);
                    return Result.failure(LedgerServiceErrorCode.INTERNAL_ERROR, "unknown idemp state");
            }
        }

        // TODO(idempotency-db-authority): Persist the canonical request hash with the Ledger
        //  transaction and compare it before treating this reference ID as a replay. A different
        //  request using the same reference ID must remain a conflict after Redis loss or expiry.
        //  Target design: design/proposals/redis-idempotency-db-authoritative.md.
        LedgerTxn txn = ledgerTxnRepository.getByRefId(req.getWalletTxnRef());
        if (txn != null) {
            // set idemp to DONE when found ledger txn
            Result<Void> markDoneResult = idempotencyClient.markIdempDone(GlobalServiceId.LEDGER.code, SCOPE,
                    req.getWalletTxnRef(), getReqStableHash(req), token, txn.getTxnId(),
                    JitterHelper.jitter(idempConfig.getDoneTtl(), idempConfig.getJitterMs()));
            if (markDoneResult.isFailed()) {
                log.warn("mark ledger idempotency done failed after DB replay, result={}", markDoneResult);
            }
            log.info("ledger txn already exists, {}", txn);
            return Result.success(IdempDecision.replay(txn.getTxnId(), PostTransactionResult.IdempotentSource.DB));
        }
        return Result.success(IdempDecision.newTransaction());
    }

    private Result<IdempValue> claimIdempCacheIfAbsent(PostTransactionRequestPb req, String token) {
        String reqHash = getReqStableHash(req);
        Result<Boolean> claimResult = idempotencyClient.claimIdempIfAbsent(GlobalServiceId.LEDGER.code, SCOPE,
                req.getWalletTxnRef(), reqHash, token, JitterHelper.jitter(idempConfig.getPendingTtl(), idempConfig.getJitterMs()));
        if (claimResult.isFailed()) {
            log.warn("claim ledger idempotency failed, falling back to DB idempotency, result={}", claimResult);
            return Result.success();
        }
        if (Boolean.TRUE.equals(claimResult.getValue())) {
            return Result.success();
        }

        Result<IdempValue> valueResult = idempotencyClient.getIdempAndVerifyHash(
                GlobalServiceId.LEDGER.code, SCOPE, req.getWalletTxnRef(), reqHash);
        if (valueResult.isFailed()) {
            if (Result.is(valueResult, IdempErrorCode.HASH_CONFLICT)) {
                return Result.failure(LedgerServiceErrorCode.REQUEST_HASH_CONFLICT, valueResult.getDetail());
            }
            if (Result.is(valueResult, RedisErrorCode.MALFORMED_VALUE)) {
                log.error("parse ledger idempotency value failed, deleting malformed Redis idempotency entry");
                // rare case, delete invalid value to self-recover.
                // force delete key here, value is malformed and possibly cannot get a token to verify the owner.
                // this could mis-delete the key created by a concurrent identical request, but this is extremely rare,
                // and we have db idemp as the backstop
                Result<Boolean> deleteResult = idempotencyClient.forceDeleteIdemp(GlobalServiceId.LEDGER.code, SCOPE, req.getWalletTxnRef());
                if (deleteResult.isFailed()) {
                    log.warn("delete malformed ledger idempotency value failed, result={}", deleteResult);
                }
                return Result.success();
            }
            log.warn("read ledger idempotency failed, falling back to DB idempotency, result={}", valueResult);
            return Result.success();
        }
        IdempValue idempValue = valueResult.getValue();
        return Result.success(idempValue);
    }

    private Result<Void> reqPrecheck(PostTransactionRequestPb req) {
        Result<Void> referenceValidation =
                entityRefCodec.validate(req.getWalletTxnRef(), EntityRefType.WALLET_TRANSACTION);
        if (referenceValidation.isFailed()) {
            return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER,
                    referenceValidation.getDetail());
        }
        Map<String, Long> debitSumMap = new HashMap<>(), creditSumMap = new HashMap<>();
        int debitCount = 0, creditCount = 0;

        for (LedgerEntryPb entry : req.getEntriesList()) {
            referenceValidation = entityRefCodec.validate(
                    entry.getAccountRef(), EntityRefType.LEDGER_ACCOUNT);
            if (referenceValidation.isFailed()) {
                return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER,
                        referenceValidation.getDetail());
            }
            if (entry.getAmount() == 0) {
                return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER, "zero_amount: " + entry);
            }
            Direction d = EnumMappers.directionPbMapper.to(entry.getDirection());
            String assetId = entry.getAssetId();
            if (d == null || d == Direction.UNKNOWN) {
                return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid_direction: " + Direction.UNKNOWN);
            }
            if (d == Direction.DEBIT) {
                debitCount++;
            } else {
                creditCount++;
            }
            Map<String, Long> targetSumMap = d == Direction.DEBIT ? debitSumMap : creditSumMap;
            if (!targetSumMap.containsKey(assetId)) {
                targetSumMap.put(assetId, 0L);
            }
            targetSumMap.put(assetId, targetSumMap.get(assetId) + entry.getAmount());
        }

        if (debitCount == 0 || creditCount == 0) {
            return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER, "imbalanced_entry: debit: " + debitSumMap + ", credit: " + creditSumMap);
        }

        if (debitSumMap.size() != creditSumMap.size()) {
            return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER, "imbalanced_entry: debit: " + debitSumMap + ", credit: " + creditSumMap);
        }
        for (Map.Entry<String, Long> entry: debitSumMap.entrySet()) {
            String assetId = entry.getKey();
            Long debitAmount = entry.getValue();
            if (!Objects.equals(creditSumMap.get(assetId), debitAmount)) {
                return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER, ("imbalanced_entry: debit: " + debitSumMap + ", credit: " + creditSumMap));
            }
        }
        return Result.success();
    }

    private LedgerEntry createLedgerEntry(String entryId, String txnId, LedgerEntryPb entryPb,
                                          String ledgerAccountRef) {
        return LedgerEntry.create(
                entryId,
                txnId,
                entryPb.getAssetId(),
                ledgerAccountRef,
                entryPb.getAmount(),
                EnumMappers.directionPbMapper.to(entryPb.getDirection())
        );
    }

    private String nextInternalId() {
        return Long.toString(internalIdGenerator.nextId());
    }

    private PostTransactionResult toFailure(IResult<?> result) {
        Objects.requireNonNull(result, "result");
        return PostTransactionResult.failure(
                LedgerBoundaryErrorMapper.toLedgerErrorCode(result), result.getDetail());
    }

    private void releaseOwnedIdempotencySafely(PostTransactionRequestPb req, String token) {
        try {
            String idempV = CommonIdempHelper.idempPendingValue(getReqStableHash(req), token);
            Result<String> releaseResult = idempotencyClient.releaseIdempIfOwned(
                    GlobalServiceId.LEDGER.code, SCOPE, req.getWalletTxnRef(), idempV);
            if (releaseResult.isFailed()) {
                log.error("release ledger idempotency failed, wallet_txn_ref={}, result={}",
                        req.getWalletTxnRef(), releaseResult);
            }
        } catch (RuntimeException cleanupFailure) {
            // Compare-and-delete cleanup is best effort and must not mask the original failure.
            log.error("release ledger idempotency threw, wallet_txn_ref={}",
                    req.getWalletTxnRef(), cleanupFailure);
        }
    }

    private String getReqStableHash(PostTransactionRequestPb req) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.getWalletTxnRef()).append("|")
                .append(req.getDescription()).append("|");
        List<LedgerEntryPb> sorted = new ArrayList<>(req.getEntriesList());
        sorted.sort(Comparator.comparing(LedgerEntryPb::getAccountRef)
                .thenComparing(LedgerEntryPb::getDirection)
                .thenComparing(LedgerEntryPb::getAmount)
                .thenComparing(LedgerEntryPb::getAssetId));
        for (LedgerEntryPb entry : sorted) {
            sb.append(entry.getAccountRef()).append("|")
                    .append(entry.getDirection()).append("|")
                    .append(entry.getAmount()).append("|")
                    .append(entry.getAssetId()).append("|");
        }
        return StableHashHelper.stableHash(sb.toString());
    }

    private record IdempDecision(boolean replay, String txnId, PostTransactionResult.IdempotentSource source) {
        static IdempDecision newTransaction() {
            return new IdempDecision(false, null, null);
        }

        static IdempDecision replay(String txnId, PostTransactionResult.IdempotentSource source) {
            return new IdempDecision(true, txnId, source);
        }

        boolean isReplay() {
            return replay;
        }
    }
}
