package com.exchange.app.wallet.processor.transaction.util;

import com.exchange.app.wallet.po.enums.BusinessType;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.enums.WalletBucket;
import com.exchange.app.wallet.po.enums.transaction.*;
import com.exchange.app.wallet.po.transaction.WalletAction;
import com.exchange.app.wallet.po.transaction.WalletReservation;
import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.app.wallet.po.wallet.WalletAccountMapping;
import com.exchange.app.wallet.exception.WalletExceptionFactory;
import com.exchange.app.wallet.utils.OutboxHelper;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.result.Result;
import com.exchange.proto.ledger.common.LedgerDirectionPb;
import com.exchange.proto.ledger.post.LedgerEntryPb;
import com.exchange.proto.ledger.post.PostTransactionRequestPb;
import com.exchange.proto.wallet.wallet.TransactionLinePb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ObjectBuilder {
    public static WalletTransaction createTransaction(String transactionId, TransactionType transactionType, String referenceId, ServiceId serviceId, String idempotencyKey, BusinessType businessType) {
        return WalletTransaction.create(
                transactionId,
                referenceId,
                serviceId,
                idempotencyKey,
                TransactionStatus.PENDING,
                transactionType,
                businessType
        );
    }

    public static WalletReservation createReservation(String reservationId, WalletTransaction walletTransaction, String walletId, TransactionLinePb line) {
        switch (line.getOperationType()) {
            case OperationTypePb_Debit:
                // in Atomic txn, reservation asset goes into pending settle after created
                return  WalletReservation.create(
                        reservationId,
                        walletTransaction.getInitiator(),
                        walletTransaction.getReferenceId() + ":" + walletId,
                        walletId,
                        line.getWalletRef(),
                        line.getAssetCode(),
                        line.getAmount(),
                        0L,
                        0L,
                        line.getAmount(),
                        0L,
                        ReservationStatus.ACTIVE,
                        ReservationOutcome.NOT_DONE,
                        walletTransaction.getTxnId()
                );
            case OperationTypePb_Reserve:
                return  WalletReservation.create(
                        reservationId,
                        walletTransaction.getInitiator(),
                        walletTransaction.getReferenceId() + ":" + walletId,
                        walletId,
                        line.getWalletRef(),
                        line.getAssetCode(),
                        line.getAmount(),
                        line.getAmount(),
                        0L,
                        0L,
                        0L,
                        ReservationStatus.ACTIVE,
                        ReservationOutcome.NOT_DONE,
                        walletTransaction.getTxnId()
                );
            default: throw WalletExceptionFactory.invalidEnum("OperationType_Reserve not supported: " + line.getOperationType());
        }
    }

    public static List<WalletAction> createActionsFromLine(String txnId, String walletId, String reservationId, TransactionLinePb line, List<String> actionIds) {
        int requiredCount = requiredActionCount(line);
        if (actionIds.size() != requiredCount) {
            throw WalletExceptionFactory.invalidValue(
                    "expected " + requiredCount + " action IDs but got " + actionIds.size());
        }
        int actionIndex = 0;
        List<WalletAction> actions = new ArrayList<>();
        switch (line.getOperationType()) {
            case OperationTypePb_Reserve:
                actions.add(createAction(actionIds.get(actionIndex), txnId, walletId, null, WalletBucket.AVAILABLE, ActionType.RESERVE, line));
                break;
            case OperationTypePb_Earmark:
                actions.add(createAction(actionIds.get(actionIndex++), txnId, walletId, reservationId, WalletBucket.RESERVED, ActionType.CONSUME, line));
                actions.add(createAction(actionIds.get(actionIndex), txnId, walletId, null, WalletBucket.RESERVED, ActionType.TRANSFER_OUT, line));
                break;
            case OperationTypePb_Release:
                actions.add(createAction(actionIds.get(actionIndex), txnId, walletId, reservationId, WalletBucket.RESERVED, ActionType.RELEASE, line));
                break;
            case OperationTypePb_Debit:
                actions.add(createAction(actionIds.get(actionIndex++), txnId, walletId, null, WalletBucket.AVAILABLE, ActionType.RESERVE, line));
                actions.add(createAction(actionIds.get(actionIndex++), txnId, walletId, reservationId, WalletBucket.RESERVED, ActionType.CONSUME, line));
                actions.add(createAction(actionIds.get(actionIndex), txnId, walletId, null, WalletBucket.RESERVED, ActionType.TRANSFER_OUT, line));
                break;
            case OperationTypePb_Credit:
                actions.add(createAction(actionIds.get(actionIndex), txnId, walletId, null, WalletBucket.AVAILABLE, ActionType.TRANSFER_IN, line));
                break;
            default:
                throw WalletExceptionFactory.invalidEnum("invalid operation type: " + line.getOperationType());
        }
        return actions;
    }

    public static int requiredActionCount(TransactionLinePb line) {
        return switch (line.getOperationType()) {
            case OperationTypePb_Reserve, OperationTypePb_Release, OperationTypePb_Credit -> 1;
            case OperationTypePb_Earmark -> 2;
            case OperationTypePb_Debit -> 3;
            default -> throw WalletExceptionFactory.invalidEnum("invalid operation type: " + line.getOperationType());
        };
    }

    public static WalletAction createAction(String actionId, String txnId, String walletId, String reservationId, WalletBucket bucket, ActionType actionType, TransactionLinePb line) {
        return WalletAction.create(
                actionId,
                txnId,
                walletId,
                line.getAssetCode(),
                bucket,
                actionType,
                line.getAmount(),
                reservationId
        );
    }

    public static Result<Outbox> createOutbox(WalletTransaction txn, List<WalletAction> actions, List<WalletAccountMapping> mappings) {
        Map<String, String> walletToAccount = mappings.stream().collect(Collectors.toMap(WalletAccountMapping::getWalletId, WalletAccountMapping::getAccountRefId));

        List<LedgerEntryPb> entries = new ArrayList<>();
        for (WalletAction action : actions) {
            LedgerEntryPb.Builder builder = LedgerEntryPb.newBuilder();
            builder.setAccountRef(walletToAccount.get(action.getWalletId())).setAmount(action.getAmount()).setAssetId(action.getAssetId());
            switch (action.getActionType()) {
                case TRANSFER_OUT:
                    builder.setDirection(LedgerDirectionPb.LedgerDirection_Debit);
                    break;
                case TRANSFER_IN:
                    builder.setDirection(LedgerDirectionPb.LedgerDirection_Credit);
                    break;
                default:
                    continue;
            }
            entries.add(builder.build());
        }

        PostTransactionRequestPb req = PostTransactionRequestPb.newBuilder()
                .setReferenceId(txn.getTxnId())
                .addAllEntries(entries).build();
        Outbox outbox = OutboxHelper.fromPostTransactionRequest(req, txn);
        return Result.success(outbox);
    }
}
