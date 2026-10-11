package com.exchange.app.wallet.processor.transaction.util;

import com.exchange.app.wallet.po.enums.BusinessType;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.enums.WalletStatus;
import com.exchange.app.wallet.po.enums.transaction.ActionType;
import com.exchange.app.wallet.po.enums.transaction.TransactionType;
import com.exchange.app.wallet.po.transaction.WalletAction;
import com.exchange.app.wallet.po.transaction.WalletReservation;
import com.exchange.app.wallet.po.wallet.BalanceSnapshot;
import com.exchange.app.wallet.processor.transaction.model.RequestInfo;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.app.wallet.utils.EnumPbMappers;
import com.exchange.app.wallet.utils.WalletTxnHelper;
import com.exchange.common.result.Result;
import com.exchange.proto.wallet.common.OperationTypePb;
import com.exchange.proto.wallet.wallet.TransactionLinePb;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class TransactionValidateHelper {
    // simply precheck, validate simple rules like enum and amount>0
    public static Result<Void> requestPrecheck(RequestInfo requestInfo) {
        ServiceId serviceId = EnumPbMappers.serviceIdPbMapper.to(requestInfo.serviceIdPb);
        if (serviceId == null || serviceId == ServiceId.UNKNOWN) {
            log.error("invalid service id, serviceId: {}", requestInfo.serviceIdPb);
            return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid service id: " + requestInfo.serviceIdPb);
        }
        BusinessType businessType = EnumPbMappers.businessTypePbMapper.to(requestInfo.businessTypePb);
        if (businessType == null || businessType == BusinessType.UNKNOWN) {
            log.error("invalid businessType, businessType: {}", requestInfo.businessTypePb);
            return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid business type: " + requestInfo.businessTypePb);
        }
        if (requestInfo.transactionType == null || requestInfo.transactionType == TransactionType.UNKNOWN) {
            log.error("invalid transactionType, transactionType: {}", requestInfo.transactionType);
            return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid transaction type: " + requestInfo.transactionType);
        }
        for (TransactionLinePb line : requestInfo.linePbs) {
            // validate action type and amount
            if (line.getOperationType() == OperationTypePb.UNRECOGNIZED || line.getOperationType() == OperationTypePb.OperationTypePb_Unknown) {
                log.error("invalid operationType, operationType: {}", line.getOperationType());
                return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid operation type: " + line);
            }
            if (line.getAmount() <= 0) {
                log.error("invalid amount, amount: {}", line.getAmount());
                return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid amount: " + line);
            }
        }
        return Result.success();
    }

    public static Result<Void> validateActionInfo(List<WalletAction> actions, List<BalanceSnapshot> snapshots, List<WalletReservation> reservations) {
        Map<String, BalanceSnapshot> walletRefToSnapshot = Objects.requireNonNullElse(snapshots, new ArrayList<BalanceSnapshot>()).stream()
                .collect(Collectors.toMap(BalanceSnapshot::getWalletRef, snapshot -> snapshot));
        Map<String, WalletReservation> reservationRefToReservation = Objects.requireNonNullElse(reservations, new ArrayList<WalletReservation>()).stream()
                .collect(Collectors.toMap(WalletReservation::getWalletReservationRef, reservation -> reservation));

        if (actions == null || actions.isEmpty()) {
            log.error("empty actions list");
            return Result.failure(WalletServiceErrorCode.WALLET_ACTION_NOT_FOUND, "empty actions list");
        }

        Result<Void> result = validateAssetInfo(actions);
        if (result.isFailed()) {
            log.error("validate asset info failed, {}", result);
            return Result.failure(result);
        }

        for (WalletAction action : actions) {
            BalanceSnapshot snapshot = walletRefToSnapshot.get(action.getWalletRef());
            if (!WalletTxnHelper.actionSnapshotMatched(snapshot, action)) {
                log.error("validate action snapshot mismatch, action: {}, snapshot: {}", action, snapshot);
                return Result.failure(WalletServiceErrorCode.BALANCE_SNAPSHOT_MISMATCH, "action snapshot mismatch");
            }

            if (!Strings.isEmpty(action.getWalletReservationRef())) {
                WalletReservation reservation = reservationRefToReservation.get(action.getWalletReservationRef());
                if (!WalletTxnHelper.actionReservationMatched(action, reservation)) {
                    log.error("validate action reservation mismatch, action: {}, reservation: {}", action, reservation);
                    return Result.failure(WalletServiceErrorCode.WALLET_RESERVATION_MISMATCH, "action reservation mismatch");
                }
            }
        }
        return Result.success();
    }

    public static Result<Void> validateAssetInfo(List<WalletAction> actions) {
        Map<String, Long> inAssetToAmount = new HashMap<>(), outAssetToAmount = new HashMap<>();
        for (WalletAction action : actions) {
            if (action.isTransfer()) {
                Map<String, Long> targetAssetToAmount;
                if (action.getActionType() == ActionType.TRANSFER_OUT) {
                    targetAssetToAmount = outAssetToAmount;
                } else {
                    targetAssetToAmount = inAssetToAmount;
                }
                targetAssetToAmount.putIfAbsent(action.getAssetId(), 0L);
                targetAssetToAmount.put(action.getAssetId(), action.getAmount() + targetAssetToAmount.get(action.getAssetId()));
            }
        }

        if (inAssetToAmount.size() != outAssetToAmount.size()) {
            return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER, "abnormal lines, asset types not match");
        }
        // validate: for each asset, transfer in should match transfer out
        for (Map.Entry<String, Long> entry : inAssetToAmount.entrySet()) {
            String assetCode = entry.getKey();
            Long inAmount = entry.getValue();
            if (!Objects.equals(inAmount, outAssetToAmount.get(assetCode))) {
                return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER, "imbalanced asset: " + assetCode + ", in_amount: " + inAmount + ", out_amount" + outAssetToAmount.get(assetCode));
            }
        }
        return Result.success();
    }

    public static Result<Void> validateSnapshotForAction(BalanceSnapshot snapshot, WalletAction action) {
        if (!Objects.equals(snapshot.getWalletRef(), action.getWalletRef()) || !Objects.equals(snapshot.getAssetId(), action.getAssetId())) {
            return Result.failure(WalletServiceErrorCode.BALANCE_SNAPSHOT_MISMATCH,
                    String.format("action snapshot mismatch, snapshot: %s, action: %s", snapshot, action));
        }
        if (snapshot.getWalletStatus() != WalletStatus.OPEN) {
            return Result.failure(WalletServiceErrorCode.BALANCE_SNAPSHOT_UPDATE_FAILED,
                    String.format("snapshot is not open, snapshot: %s, action: %s", snapshot, action));
        }
        if (action.getAmount() == null || action.getAmount() <= 0) {
            return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER,
                    String.format("invalid action amount, snapshot: %s, action: %s", snapshot, action));
        }
        return Result.success();
    }

}
