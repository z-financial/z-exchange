package com.exchange.app.wallet.processor.transaction.step;

import com.exchange.app.wallet.config.CustomCacheProperties;
import com.exchange.app.wallet.cronjob.outbox.constant.OutboxConstant;
import com.exchange.app.wallet.dao.repository.*;
import com.exchange.app.wallet.dao.store.BalanceSnapshotStore;
import com.exchange.app.wallet.po.enums.BusinessType;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.enums.transaction.*;
import com.exchange.app.wallet.po.transaction.WalletAction;
import com.exchange.app.wallet.po.transaction.WalletReservation;
import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.app.wallet.po.wallet.BalanceSnapshot;
import com.exchange.app.wallet.po.wallet.WalletAccountMapping;
import com.exchange.app.wallet.processor.transaction.model.RequestInfo;
import com.exchange.app.wallet.processor.transaction.model.TransactionInfo;
import com.exchange.app.wallet.processor.transaction.util.Constant;
import com.exchange.app.wallet.processor.transaction.util.ObjectBuilder;
import com.exchange.app.wallet.processor.transaction.util.TransactionValidateHelper;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.app.wallet.utils.*;
import com.exchange.common.constant.GlobalServiceId;
import com.exchange.common.db.utils.DbTxnExecutor;
import com.exchange.common.identity.EntityIdentityFactory;
import com.exchange.common.identity.EntityRefCodec;
import com.exchange.common.identity.EntityRefType;
import com.exchange.common.identity.GeneratedEntityIdentity;
import com.exchange.common.idgen.InternalIdGenerator;
import com.exchange.common.kafka.producer.IPublisher;
import com.exchange.common.outbox.dao.repository.OutboxRepository;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.outbox.po.enums.OutboxStatus;
import com.exchange.common.redis.idemp.IdempotencyClient;
import com.exchange.common.redis.idemp.utils.CommonIdempHelper;
import com.exchange.common.result.Result;
import com.exchange.common.utils.time.LocalDateTimeHelper;
import com.exchange.proto.wallet.wallet.TransactionLinePb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@EnableConfigurationProperties(CustomCacheProperties.Idemp.class)
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class BeforePostLedgerProcessor {
    private final DbTxnExecutor dbTxnExecutor;
    private final IdempotencyClient idempotencyClient;

    private final CustomCacheProperties.Idemp idempConfig;

    private final BalanceSnapshotRepository balanceSnapshotManager;
    private final BalanceSnapshotStore balanceSnapshotStore;
    private final WalletReservationRepository walletReservationManager;
    private final WalletTransactionRepository walletTransactionManager;
    private final WalletActionRepository walletActionManager;
    private final WalletAccountMappingRepository walletAccountMappingManager;
    private final OutboxRepository outboxManager;
    private final IPublisher postLedgerPublisher;
    private final UpdateReservationProcessor updateReservationProcessor;
    private final UpdateBalanceSnapshotProcessor updateBalanceSnapshotProcessor;
    private final EntityIdentityFactory entityIdentityFactory;
    private final EntityRefCodec entityRefCodec;
    // Event identity remains on the phase-one contract until the outbox identity task.
    private final InternalIdGenerator internalIdGenerator;

    // reserve -> snapshot: -available, +reserved
    // consume -> reservation: -remaining, +pending_settle
    // release -> snapshot, reservation: -reserved, +available | -pending_settle, +released, change status
    // package private
    public Result<TransactionInfo> beforePostingLedger(RequestInfo requestInfo, boolean needPostLedger) {
        try {
            Result<TransactionInfo> result = doBeforePostingLedger(requestInfo, needPostLedger);
            if (result.isFailed()) {
                releaseOwnedIdempotencySafely(requestInfo);
            }
            return result;
        } catch (RuntimeException failure) {
            // Generator and validation exceptions must not strand an owned PENDING claim.
            releaseOwnedIdempotencySafely(requestInfo);
            throw failure;
        }
    }

    private Result<TransactionInfo> doBeforePostingLedger(RequestInfo requestInfo, boolean needPostLedger) {
        Result<Void> identityValidation = validateEntityReferences(requestInfo.linePbs);
        if (identityValidation.isFailed()) {
            return Result.failure(identityValidation);
        }
        WalletTransaction walletTxn = createTxn(requestInfo);

        // complete txn immediately if no need to post ledger
        if (!needPostLedger) {
            walletTxn.setTxnStatus(TransactionStatus.COMPLETED);
        }

        // get snapshot from db
        Set<String> walletRefs = requestInfo.linePbs.stream().distinct()
                .map(TransactionLinePb::getWalletRef)
                .collect(Collectors.toSet());
        List<BalanceSnapshot> balanceSnapshots = balanceSnapshotManager.selectByRefs(walletTxn.getInitiator(), new ArrayList<>(walletRefs));
        Map<String, BalanceSnapshot> walletRefToSnapshot = balanceSnapshots.stream()
                .collect(Collectors.toMap(BalanceSnapshot::getWalletRef, snapshot -> snapshot));

        // get reservation from db
        Set<String> reservationRefs = requestInfo.linePbs.stream()
                .filter((line)-> !Strings.isEmpty(line.getReservationRef()))
                .distinct().map(TransactionLinePb::getReservationRef).collect(Collectors.toSet());
        List<WalletReservation> reservationsInRequest = new ArrayList<>();
        if (!reservationRefs.isEmpty()) {
            reservationsInRequest = walletReservationManager.selectByRefs(walletTxn.getInitiator(), new ArrayList<>(reservationRefs));
            if (reservationsInRequest.size() != reservationRefs.size()) {
                log.error("reservations not found, requested_reservation: {}, reservation_in_db: {}", reservationRefs, reservationsInRequest);
                return Result.failure(WalletServiceErrorCode.WALLET_RESERVATION_NOT_FOUND, "reservations not found");
            }
        }
        Map<String, WalletReservation> refToReservation = reservationsInRequest.stream()
                .collect(Collectors.toMap(WalletReservation::getWalletReservationRef, reservation -> reservation));

        // create action and reservation
        List<WalletAction> actions = new ArrayList<>();
        List<WalletReservation> createdReservations = new ArrayList<>();
        for (TransactionLinePb line : requestInfo.linePbs) {
            BalanceSnapshot snapshot = walletRefToSnapshot.get(line.getWalletRef());
            if (snapshot == null) {
                log.error("snapshot not found, line: {}", line);
                return Result.failure(WalletServiceErrorCode.BALANCE_SNAPSHOT_NOT_FOUND, "snapshot not found");
            }

            WalletReservation reservation = null;
            switch (line.getOperationType()) {
                case OperationTypePb_Reserve:
                case OperationTypePb_Debit:
                    reservation = ObjectBuilder.createReservation(
                            entityIdentityFactory.next(EntityRefType.WALLET_RESERVATION),
                            walletTxn, snapshot.getWalletRef(), line);
                    createdReservations.add(reservation);
                    refToReservation.put(reservation.getWalletReservationRef(), reservation);
                    break;
                case OperationTypePb_Earmark:
                case OperationTypePb_Release:
                    reservation = refToReservation.get(line.getReservationRef());
                    if (reservation == null) {
                        log.error("reservation not found, ref: {}", line);
                        return Result.failure(WalletServiceErrorCode.WALLET_RESERVATION_NOT_FOUND, "reservation not found");
                    }
            }

            String reservationRef = null;
            if (reservation != null) {
                reservationRef = reservation.getWalletReservationRef();
            }
            int actionCount = ObjectBuilder.requiredActionCount(line);
            List<GeneratedEntityIdentity> actionIdentities = new ArrayList<>(actionCount);
            for (int i = 0; i < actionCount; i++) {
                actionIdentities.add(entityIdentityFactory.next(EntityRefType.WALLET_ACTION));
            }
            List<WalletAction> actionsFromLine = ObjectBuilder.createActionsFromLine(
                    walletTxn.getWalletTxnRef(), snapshot.getWalletRef(), reservationRef, line,
                    actionIdentities);
            actions.addAll(actionsFromLine);
        }

        //validate
        Result<Void> result = TransactionValidateHelper.validateActionInfo(actions, balanceSnapshots, new ArrayList<>(refToReservation.values()));
        if (result.isFailed()) {
            log.error("validate action info failed, result: {}", result);
            return Result.failure(result);
        }

        Result<Outbox> outboxResult = createOutboxIfNeeded(walletTxn, actions, needPostLedger);
        if (!outboxResult.isSuccess()) {
            return Result.failure(outboxResult);
        }
        Outbox outbox = outboxResult.getValue();


        Result<List<BalanceSnapshot>> updateResult = updateDbToCreateTxn(walletTxn, actions, balanceSnapshots, reservationsInRequest, createdReservations, outbox);
        if (updateResult.isFailed()) {
            log.error("updateDbToCreateTxn failed: {}", updateResult);
            return Result.failure(updateResult);
        }
        // Cache refresh is best effort and must run only after the DB transaction commits.
        postDbUpdateSnapshots(updateResult.getValue());

        // set idemp to DONE when found wallet txn
        String idempotencyCacheKey = requestInfo.idempotencyCacheKey();
        Result<Void> markDoneResult = idempotencyClient.markIdempDone(
                GlobalServiceId.WALLET.code, Constant.SCOPE, idempotencyCacheKey,
                String.valueOf(requestInfo.getStableHash()), requestInfo.token,
                walletTxn.getWalletTxnRef(), idempConfig.getDoneTtl());
        if (markDoneResult.isFailed()) {
            // The wallet DB commit is authoritative; Redis refresh remains best effort.
            log.warn("mark wallet idempotency done failed after DB commit, result={}", markDoneResult);
        }

        if (needPostLedger) {
            postLedger(outbox);
        }

        return Result.success(TransactionInfo.create(walletTxn, actions, new ArrayList<>(refToReservation.values()), balanceSnapshots));
    }

    private void releaseOwnedIdempotencySafely(RequestInfo requestInfo) {
        try {
            String idempotencyCacheKey = requestInfo.idempotencyCacheKey();
            String idempV = CommonIdempHelper.idempPendingValue(requestInfo.getStableHash(), requestInfo.token);
            Result<String> releaseResult = idempotencyClient.releaseIdempIfOwned(
                    GlobalServiceId.WALLET.code, Constant.SCOPE, idempotencyCacheKey, idempV);
            if (releaseResult.isFailed()) {
                log.error("release wallet idempotency failed, key={}, result={}",
                        idempotencyCacheKey, releaseResult);
            }
        } catch (RuntimeException cleanupFailure) {
            // Cleanup is best effort and must never replace the original business failure.
            log.error("release wallet idempotency threw, key={}",
                    requestInfo.idempotencyCacheKey(), cleanupFailure);
        }
    }

    private WalletTransaction createTxn(RequestInfo requestInfo) {
        ServiceId serviceId = EnumPbMappers.serviceIdPbMapper.to(requestInfo.serviceIdPb);
        BusinessType businessType = EnumPbMappers.businessTypePbMapper.to(requestInfo.businessTypePb);

        // The generator call is deliberately outside updateDbToCreateTxn's transaction callback.
        return ObjectBuilder.createTransaction(
                entityIdentityFactory.next(EntityRefType.WALLET_TRANSACTION), requestInfo.transactionType,
                requestInfo.upstreamRequestRef, serviceId, businessType);
    }

    private Result<Void> validateEntityReferences(List<TransactionLinePb> lines) {
        for (TransactionLinePb line : lines) {
            Result<Void> referenceValidation =
                    entityRefCodec.validate(line.getWalletRef(), EntityRefType.WALLET);
            if (referenceValidation.isFailed()) {
                return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER,
                        referenceValidation.getDetail());
            }
            if (!Strings.isEmpty(line.getReservationRef())) {
                referenceValidation = entityRefCodec.validate(
                        line.getReservationRef(), EntityRefType.WALLET_RESERVATION);
                if (referenceValidation.isFailed()) {
                    return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER,
                            referenceValidation.getDetail());
                }
            }
        }
        return Result.success();
    }

    private Result<List<BalanceSnapshot>> updateDbToCreateTxn(WalletTransaction walletTxn, List<WalletAction> actions, List<BalanceSnapshot> balanceSnapshots,
                                                              List<WalletReservation> reservationsInRequest, List<WalletReservation> createdReservations, Outbox outbox) {

        Map<String, List<WalletAction>> walletRefToActions = new HashMap<>();
        for (WalletAction action : actions) {
            walletRefToActions.computeIfAbsent(action.getWalletRef(), k -> new ArrayList<>());
            walletRefToActions.get(action.getWalletRef()).add(action);
        }

        // immediately claim the outbox and attempt to publish it right after update db
        return dbTxnExecutor.executeWithDefault(() -> {
            Result<Void> reservationResult = updateReservationProcessor.updateReservations(reservationsInRequest,
                    (reservation) -> updateReservationBeforePosting(reservation, walletRefToActions));
            if (reservationResult.isFailed()) {
                log.error("update reservations failed, {}, {}", reservationResult, reservationsInRequest);
                return Result.failure(reservationResult);
            }


            Result<List<BalanceSnapshot>> snapshotResult = updateSnapshotsBeforePosting(walletRefToActions, balanceSnapshots);
            if (snapshotResult.isFailed()) {
                log.error("update snapshots failed, {}, {}", snapshotResult, balanceSnapshots);
                return Result.failure(snapshotResult);
            }

            if (!createdReservations.isEmpty()) {
                if (walletReservationManager.batchInsert(createdReservations) != createdReservations.size()) {
                    log.error("insert reservations failed, {}, {}", snapshotResult, createdReservations);
                    return Result.failure(WalletServiceErrorCode.WALLET_RESERVATION_DUPLICATED, "unexpected reservation duplicated");
                }
            }

            if (walletTransactionManager.insertIgnore(walletTxn) == 0) {
                // TODO(idempotency-db-authority): After this attempt rolls back, load the winning
                //  Wallet transaction and compare its persisted canonical request hash. Replay its
                //  result for the same hash; return HASH_CONFLICT for a different hash. See
                //  design/proposals/redis-idempotency-db-authoritative.md.
                log.error("insert transaction failed, {}, {}", snapshotResult, walletTxn);
                return Result.failure(WalletServiceErrorCode.WALLET_TRANSACTION_DUPLICATED, "unexpected transaction duplicated");
            }
            if (walletActionManager.batchInsert(actions) != actions.size()) {
                log.error("insert actions failed, {}, {}", snapshotResult, actions);
                return Result.failure(WalletServiceErrorCode.WALLET_ACTION_DUPLICATION, "unexpected action duplicated");
            }
            if (outbox != null) {
                // immediately claim the outbox and attempt to publish it right after update db
                if (outboxManager.insertWithClaim(outbox, LocalDateTimeHelper.nowAfterMs(OutboxConstant.BASE_ATTEMPT_INTERVAL_MS))
                        == 0) {
                    log.error("insert outbox failed, {}, {}", snapshotResult, outbox);
                    return Result.failure(WalletServiceErrorCode.WALLET_OUTBOX_DUPLICATED, "unexpected outbox duplicated");
                }
            }

            return Result.success(snapshotResult.getValue());
        });
    }

    private Result<WalletReservation> updateReservationBeforePosting(WalletReservation reservation, Map<String, List<WalletAction>> walletRefToActions) {
        for (WalletAction action : walletRefToActions.get(reservation.getWalletRef())) {
            switch (action.getActionType()) {
                case CONSUME:
                    if (reservation.getRemaining() < action.getAmount()) {
                        return Result.failure(WalletServiceErrorCode.WALLET_RESERVATION_NOT_SATISFIED, String.format("fail to consume, remaining not enough, reservation: %s, action: %s ", reservation, action));
                    }
                    reservation.setRemaining(reservation.getRemaining() - action.getAmount());
                    reservation.setPendingSettle(reservation.getPendingSettle() + action.getAmount());
                    break;
                case RELEASE:
                    if (reservation.getRemaining() < action.getAmount()) {
                        return Result.failure(WalletServiceErrorCode.WALLET_RESERVATION_NOT_SATISFIED, String.format("fail to release, remaining not enough, reservation: %s, action: %s ", reservation, action));
                    }
                    reservation.setRemaining(reservation.getRemaining() - action.getAmount());
                    reservation.setReleased(reservation.getReleased() + action.getAmount());
                    if (WalletReservation.isTotallySettled(reservation)) {
                        reservation.setReservationStatus(ReservationStatus.FINISHED);
                        reservation.setReservationOutcome(WalletReservation.inferOutcome(reservation));
                    }
                    break;
            }
        }
        return Result.success(reservation);
    }

    private Result<List<BalanceSnapshot>> updateSnapshotsBeforePosting(Map<String, List<WalletAction>> walletRefToActions, List<BalanceSnapshot> snapshots) {
        List<BalanceSnapshot> snapshotInOrder = snapshots.stream().sorted(Comparator.comparing(BalanceSnapshot::getId)).collect(Collectors.toList());
        return updateBalanceSnapshotProcessor.updateSnapshots(snapshotInOrder,
                (snapshot) -> doUpdateSnapshotBeforePosting(snapshot, walletRefToActions));
    }

    private Result<BalanceSnapshot> doUpdateSnapshotBeforePosting(BalanceSnapshot snapshot, Map<String, List<WalletAction>> walletRefToActions) {
        for (WalletAction action : walletRefToActions.getOrDefault(snapshot.getWalletRef(), Collections.emptyList())) {
            snapshot.setLastWalletTxnRef(action.getWalletTxnRef());
            switch (action.getActionType()) {
                case RESERVE:
                    Result<Void> reserveValidateResult = TransactionValidateHelper.validateSnapshotForAction(snapshot, action);
                    if (reserveValidateResult.isFailed()) {
                        return Result.failure(reserveValidateResult);
                    }
                    if (snapshot.getAvailable() < action.getAmount()) {
                        return Result.failure(WalletServiceErrorCode.BALANCE_SNAPSHOT_UPDATE_FAILED,
                                String.format("failed to reserve amount, available not enough, snapshot: %s, action: %s", snapshot, action));
                    }
                    snapshot.setAvailable(snapshot.getAvailable() - action.getAmount());
                    snapshot.setReserved(snapshot.getReserved() + action.getAmount());
                    break;
                case RELEASE:
                    Result<Void> releaseValidateResult = TransactionValidateHelper.validateSnapshotForAction(snapshot, action);
                    if (releaseValidateResult.isFailed()) {
                        return Result.failure(releaseValidateResult);
                    }
                    if (snapshot.getReserved() < action.getAmount()) {
                        return Result.failure(WalletServiceErrorCode.BALANCE_SNAPSHOT_UPDATE_FAILED,
                                String.format("failed to release amount, reserved not enough, snapshot: %s, action: %s", snapshot, action));
                    }
                    snapshot.setAvailable(snapshot.getAvailable() + action.getAmount());
                    snapshot.setReserved(snapshot.getReserved() - action.getAmount());
                    break;
            }
        }
        return Result.success(snapshot);
    }

    private void postDbUpdateSnapshots(List<BalanceSnapshot> updatedSnapshots) {
        for (BalanceSnapshot snapshot : updatedSnapshots) {
            balanceSnapshotStore.postDbUpdate(snapshot);
        }
    }

    private Result<Outbox> createOutboxIfNeeded(WalletTransaction walletTxn, List<WalletAction> actions, boolean needPostLedger) {
        Outbox outbox = null;
        if (needPostLedger) {
            List<WalletAction> transferActions = actions.stream()
                    .filter((action) -> action.getActionType() == ActionType.TRANSFER_IN || action.getActionType() == ActionType.TRANSFER_OUT).toList();

            List<String> walletRefs = actions.stream()
                    .map(WalletAction::getWalletRef)
                    .distinct()
                    .collect(Collectors.toList());
            List<WalletAccountMapping> mappings = walletAccountMappingManager.selectInWalletRefs(walletRefs);
            if (mappings.size() != walletRefs.size()) {
                log.error("wallet_account_mapping_not_found, wallet_refs: {}", walletRefs);
                return Result.failure(WalletServiceErrorCode.WALLET_ACCOUNT_MAPPING_NOT_FOUND, "wallet_account_mapping_not_found");
            }
            // Allocate once before the business transaction. The same persisted event ID is then
            // reused by immediate publication and every outbox retry.
            String eventId = Long.toString(internalIdGenerator.nextId());
            Result<Outbox> outboxResult = ObjectBuilder.createOutbox(eventId, walletTxn, actions, mappings);
            if (outboxResult.isFailed()) {
                log.error("createOutbox failed: {}", outboxResult);
                return Result.failure(outboxResult);
            }
            outbox = outboxResult.getValue();
        }
        return Result.success(outbox);
    }

    private void postLedger(Outbox outbox) {
        Result<Void> publishResult = postLedgerPublisher.publish(outbox);
        if (publishResult.isSuccess()) {
            if (outboxManager.updateStatusToFinalize(outbox, OutboxStatus.SENT, outbox.getLastAttemptAt())
                    == 1) {
                outbox.setOutboxStatus(OutboxStatus.SENT);
                outbox.setFinalizedAt(outbox.getLastAttemptAt());
            } else {
                // do not block the main flow
                log.error("finalize outbox failed, {}", outbox);
            }
        } else {
            // publish failed should not block the main flow and should return txn execute successfully
            log.error("post ledger publish failed, outbox: {}, result: {}", outbox, publishResult);
        }
    }

}
