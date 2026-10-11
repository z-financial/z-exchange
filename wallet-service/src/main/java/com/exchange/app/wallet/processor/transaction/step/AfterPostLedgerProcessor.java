package com.exchange.app.wallet.processor.transaction.step;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exchange.app.wallet.dao.repository.*;
import com.exchange.app.wallet.dao.store.BalanceSnapshotStore;
import com.exchange.app.wallet.po.enums.transaction.ActionType;
import com.exchange.app.wallet.po.enums.transaction.ReservationStatus;
import com.exchange.app.wallet.po.enums.transaction.TransactionStatus;
import com.exchange.app.wallet.po.transaction.WalletAction;
import com.exchange.app.wallet.po.transaction.WalletReservation;
import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.app.wallet.po.wallet.BalanceSnapshot;
import com.exchange.app.wallet.processor.transaction.model.TransactionInfo;
import com.exchange.app.wallet.processor.transaction.util.TransactionValidateHelper;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.common.db.utils.DbTxnExecutor;
import com.exchange.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class AfterPostLedgerProcessor {
    private final DbTxnExecutor dbTxnExecutor;

    private final BalanceSnapshotRepository balanceSnapshotManager;
    private final BalanceSnapshotStore balanceSnapshotStore;
    private final WalletReservationRepository walletReservationManager;
    private final WalletTransactionRepository walletTransactionManager;
    private final WalletActionRepository walletActionManager;
    private final UpdateReservationProcessor updateReservationProcessor;
    private final UpdateBalanceSnapshotProcessor updateBalanceSnapshotProcessor;

    public Result<WalletTransaction> afterPostLedger(String walletTxnRef) {
        Result<TransactionInfo> txnInfoResult = getTxnInfo(walletTxnRef);
        if (txnInfoResult.isFailed()) {
            log.error("failed to find transaction info, wallet_txn_ref={}, result={}", walletTxnRef, txnInfoResult);
            return Result.failure(txnInfoResult);
        }
        TransactionInfo txnInfo = txnInfoResult.getValue();
        if (txnInfo.walletTxn.hasFinalized()) {
            log.info("wallet transaction has finalized, wallet_txn_ref={}", walletTxnRef);
            return Result.success(txnInfo.walletTxn);
        }

        List<BalanceSnapshot> snapshotIdsInOrder = txnInfo.snapshots.stream()
                .sorted(Comparator.comparing(BalanceSnapshot::getId)).collect(Collectors.toList());

        Result<List<BalanceSnapshot>> outResult = dbTxnExecutor.executeWithDefault(() -> {
            // should update all reservations here
            Result<Void> reservationResult = updateReservationProcessor.updateReservations(txnInfo.reservations,
                    (reservation) -> updateReservationAfterPosting(reservation, txnInfo.walletRefToPostActions));
            if (reservationResult.isFailed()) {
                return Result.failure(reservationResult);
            }

            Result<List<BalanceSnapshot>> snapshotResult = updateSnapshotsAfterPosting(txnInfo.walletRefToPostActions, snapshotIdsInOrder);
            if (snapshotResult.isFailed()) {
                return Result.failure(snapshotResult);
            }
            if (walletTransactionManager.updateTransactionStatus(txnInfo.walletTxn,
                    txnInfo.walletTxn.getInternalId(), TransactionStatus.COMPLETED)
                    != 1) {
                log.error("wallet_transaction_update_failed, transaction: {}", txnInfo.walletTxn);
                return Result.failure(WalletServiceErrorCode.WALLET_TRANSACTION_UPDATE_FAILED, "transaction_update_failed");
            }
            return Result.success(snapshotResult.getValue());
        });
        if (outResult.isFailed()) {
            return Result.failure(outResult);
        }
        // Cache refresh is best effort and must run only after the DB transaction commits.
        postDbUpdateSnapshots(outResult.getValue());

        WalletTransaction txn = walletTransactionManager.selectByInternalId(txnInfo.walletTxn.getInternalId());
        if (txn == null) {
            log.error("wallet_transaction_not_found, transaction: {}", txnInfo.walletTxn);
            return Result.failure(WalletServiceErrorCode.WALLET_TRANSACTION_NOT_FOUND, "wallet_transaction_not_found");
        }
        return Result.success(txn);
    }

    private Result<TransactionInfo> getTxnInfo(String walletTxnRef) {
        WalletTransaction txn = walletTransactionManager.selectByWalletTxnRef(walletTxnRef);
        if (txn == null) {
            log.error("getTxnInfo failed, wallet_txn_ref={}", walletTxnRef);
            return Result.failure(WalletServiceErrorCode.WALLET_TRANSACTION_NOT_FOUND,
                    "no transaction found for wallet_txn_ref=" + walletTxnRef);
        }

        List<WalletAction> afterPostingActions = walletActionManager.selectByWalletTxnRef(
                txn.getWalletTxnRef(), new LambdaQueryWrapper<>() {{
                    in(WalletAction::getActionType, ActionType.TRANSFER_IN, ActionType.TRANSFER_OUT, ActionType.CONSUME);
                }});

        List<String> walletRefs = afterPostingActions.stream().map(WalletAction::getWalletRef).distinct().collect(Collectors.toList());
        List<BalanceSnapshot> snapshots = balanceSnapshotManager.selectByWalletRefs(walletRefs);
        if (snapshots.size() != walletRefs.size()) {
            log.error("getTxnInfo failed, snapshot not found, wallet_txn_ref={}, wallet_refs={}",
                    walletTxnRef, walletRefs);
            return Result.failure(WalletServiceErrorCode.BALANCE_SNAPSHOT_NOT_FOUND, "snapshot not found");
        }

        List<WalletAction> consumeActions = afterPostingActions.stream().filter(action -> action.getActionType() == ActionType.CONSUME).collect(Collectors.toList());
        List<WalletReservation> consumeReservations = new ArrayList<>();
        if (!consumeActions.isEmpty()) {
            List<String> consumeReservationRefs = consumeActions.stream()
                    .map(WalletAction::getWalletReservationRef).distinct().collect(Collectors.toList());
            consumeReservations = walletReservationManager.selectByRefs(txn.getInitiator(), consumeReservationRefs);
            if (consumeReservationRefs.size() != consumeReservations.size()) {
                log.error("reservation size mismatch, reservationFromDb: {}, reservationRefs: {}", consumeReservationRefs, consumeReservations);
                return Result.failure(WalletServiceErrorCode.WALLET_RESERVATION_NOT_FOUND, "reservation size mismatch");
            }
        }

        // only validate CONSUME/TRANSFER_IN/TRANSFER_OUT actions, only CONSUME have reservation
        Result<Void> result = TransactionValidateHelper.validateActionInfo(afterPostingActions, snapshots, consumeReservations);
        if (result.isFailed()) {
            log.error("validate action after get txn failed, result: {}", result);
            return Result.failure(result);
        }

        TransactionInfo transactionInfo = TransactionInfo.create(txn, afterPostingActions, consumeReservations, snapshots);
        return Result.success(transactionInfo);
    }

    private Result<List<BalanceSnapshot>> updateSnapshotsAfterPosting(Map<String, List<WalletAction>> walletRefToActions, List<BalanceSnapshot> snapshots) {
        List<BalanceSnapshot> snapshotInOrder = snapshots.stream().sorted(Comparator.comparing(BalanceSnapshot::getId)).collect(Collectors.toList());
        return updateBalanceSnapshotProcessor.updateSnapshots(snapshotInOrder,
                (snapshot) -> doUpdateSnapshotAfterPosting(snapshot, walletRefToActions));
    }

    private Result<BalanceSnapshot> doUpdateSnapshotAfterPosting(BalanceSnapshot snapshot, Map<String, List<WalletAction>> walletRefToActions) {
        for (WalletAction action : walletRefToActions.getOrDefault(snapshot.getWalletRef(), Collections.emptyList())) {
            snapshot.setLastWalletTxnRef(action.getWalletTxnRef());
            switch (action.getActionType()) {
                case TRANSFER_OUT:
                    Result<Void> outValidateResult = TransactionValidateHelper.validateSnapshotForAction(snapshot, action);
                    if (outValidateResult.isFailed()) {
                        return Result.failure(outValidateResult);
                    }
                    if (snapshot.getReserved() < action.getAmount()) {
                        return Result.failure(WalletServiceErrorCode.BALANCE_SNAPSHOT_UPDATE_FAILED,
                                String.format("failed to transfer out amount, reserved not enough, snapshot: %s, action: %s", snapshot, action));
                    }
                    snapshot.setReserved(snapshot.getReserved() - action.getAmount());
                    break;
                case TRANSFER_IN:
                    Result<Void> inValidateResult = TransactionValidateHelper.validateSnapshotForAction(snapshot, action);
                    if (inValidateResult.isFailed()) {
                        return Result.failure(inValidateResult);
                    }
                    snapshot.setAvailable(snapshot.getAvailable() + action.getAmount());
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

    private Result<WalletReservation> updateReservationAfterPosting(WalletReservation reservation, Map<String, List<WalletAction>> walletRefToActions) {
        for (WalletAction action : walletRefToActions.get(reservation.getWalletRef())) {
            if (Objects.requireNonNull(action.getActionType()) == ActionType.CONSUME) {
                if (reservation.getPendingSettle() < action.getAmount()) {
                    // TODO maybe throw exception? this is severe error (overspend risk in reservation)
                    return Result.failure(WalletServiceErrorCode.WALLET_RESERVATION_NOT_SATISFIED, String.format("fail to consume, pending settle not enough, reservation: %s, action: %s ", reservation, action));
                }
                reservation.setPendingSettle(reservation.getPendingSettle() - action.getAmount());
                reservation.setConsumed(reservation.getConsumed() + action.getAmount());
                if (WalletReservation.isTotallySettled(reservation)) {
                    reservation.setReservationStatus(ReservationStatus.FINISHED);
                    reservation.setReservationOutcome(WalletReservation.inferOutcome(reservation));
                }
            }
        }
        return Result.success(reservation);
    }
}
