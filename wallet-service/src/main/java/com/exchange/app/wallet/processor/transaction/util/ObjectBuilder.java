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
import com.exchange.common.identity.GeneratedEntityIdentity;
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
    public static WalletTransaction createTransaction(GeneratedEntityIdentity identity,
                                                      TransactionType transactionType,
                                                      String referenceId, ServiceId serviceId,
                                                      String idempotencyKey, BusinessType businessType) {
        return WalletTransaction.create(
                identity.internalId(),
                identity.reference(),
                referenceId,
                serviceId,
                idempotencyKey,
                TransactionStatus.PENDING,
                transactionType,
                businessType
        );
    }

    public static WalletReservation createReservation(GeneratedEntityIdentity identity,
                                                      WalletTransaction walletTransaction,
                                                      String walletRef,
                                                      TransactionLinePb line) {
        switch (line.getOperationType()) {
            case OperationTypePb_Debit:
                // in Atomic txn, reservation asset goes into pending settle after created
                return  WalletReservation.create(
                        identity.internalId(),
                        identity.reference(),
                        walletTransaction.getInitiator(),
                        walletTransaction.getReferenceId() + ":" + walletRef,
                        walletRef,
                        line.getAssetCode(),
                        line.getAmount(),
                        0L,
                        0L,
                        line.getAmount(),
                        0L,
                        ReservationStatus.ACTIVE,
                        ReservationOutcome.NOT_DONE,
                        walletTransaction.getWalletTxnRef()
                );
            case OperationTypePb_Reserve:
                return  WalletReservation.create(
                        identity.internalId(),
                        identity.reference(),
                        walletTransaction.getInitiator(),
                        walletTransaction.getReferenceId() + ":" + walletRef,
                        walletRef,
                        line.getAssetCode(),
                        line.getAmount(),
                        line.getAmount(),
                        0L,
                        0L,
                        0L,
                        ReservationStatus.ACTIVE,
                        ReservationOutcome.NOT_DONE,
                        walletTransaction.getWalletTxnRef()
                );
            default: throw WalletExceptionFactory.invalidEnum("OperationType_Reserve not supported: " + line.getOperationType());
        }
    }

    public static List<WalletAction> createActionsFromLine(String walletTxnRef, String walletRef,
                                                            String walletReservationRef,
                                                            TransactionLinePb line,
                                                            List<GeneratedEntityIdentity> actionIdentities) {
        int requiredCount = requiredActionCount(line);
        if (actionIdentities.size() != requiredCount) {
            throw WalletExceptionFactory.invalidValue(
                    "expected " + requiredCount + " action identities but got " + actionIdentities.size());
        }
        int actionIndex = 0;
        List<WalletAction> actions = new ArrayList<>();
        switch (line.getOperationType()) {
            case OperationTypePb_Reserve:
                actions.add(createAction(actionIdentities.get(actionIndex), walletTxnRef, walletRef, null, WalletBucket.AVAILABLE, ActionType.RESERVE, line));
                break;
            case OperationTypePb_Earmark:
                actions.add(createAction(actionIdentities.get(actionIndex++), walletTxnRef, walletRef, walletReservationRef, WalletBucket.RESERVED, ActionType.CONSUME, line));
                actions.add(createAction(actionIdentities.get(actionIndex), walletTxnRef, walletRef, null, WalletBucket.RESERVED, ActionType.TRANSFER_OUT, line));
                break;
            case OperationTypePb_Release:
                actions.add(createAction(actionIdentities.get(actionIndex), walletTxnRef, walletRef, walletReservationRef, WalletBucket.RESERVED, ActionType.RELEASE, line));
                break;
            case OperationTypePb_Debit:
                actions.add(createAction(actionIdentities.get(actionIndex++), walletTxnRef, walletRef, null, WalletBucket.AVAILABLE, ActionType.RESERVE, line));
                actions.add(createAction(actionIdentities.get(actionIndex++), walletTxnRef, walletRef, walletReservationRef, WalletBucket.RESERVED, ActionType.CONSUME, line));
                actions.add(createAction(actionIdentities.get(actionIndex), walletTxnRef, walletRef, null, WalletBucket.RESERVED, ActionType.TRANSFER_OUT, line));
                break;
            case OperationTypePb_Credit:
                actions.add(createAction(actionIdentities.get(actionIndex), walletTxnRef, walletRef, null, WalletBucket.AVAILABLE, ActionType.TRANSFER_IN, line));
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

    public static WalletAction createAction(GeneratedEntityIdentity identity, String walletTxnRef,
                                            String walletRef, String walletReservationRef,
                                            WalletBucket bucket, ActionType actionType,
                                            TransactionLinePb line) {
        return WalletAction.create(
                identity.internalId(),
                identity.reference(),
                walletTxnRef,
                walletRef,
                line.getAssetCode(),
                bucket,
                actionType,
                line.getAmount(),
                walletReservationRef
        );
    }

    public static Result<Outbox> createOutbox(String eventId, WalletTransaction txn, List<WalletAction> actions, List<WalletAccountMapping> mappings) {
        Map<String, String> walletToAccount = mappings.stream().collect(
                Collectors.toMap(WalletAccountMapping::getWalletRef, WalletAccountMapping::getLedgerAccountRef));

        List<LedgerEntryPb> entries = new ArrayList<>();
        for (WalletAction action : actions) {
            LedgerEntryPb.Builder builder = LedgerEntryPb.newBuilder();
            builder.setAccountRef(walletToAccount.get(action.getWalletRef())).setAmount(action.getAmount()).setAssetId(action.getAssetId());
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
                .setWalletTxnRef(txn.getWalletTxnRef())
                .addAllEntries(entries).build();
        Outbox outbox = OutboxHelper.fromPostTransactionRequest(eventId, req, txn);
        return Result.success(outbox);
    }
}
