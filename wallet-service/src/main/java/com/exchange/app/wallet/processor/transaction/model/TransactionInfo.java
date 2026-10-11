package com.exchange.app.wallet.processor.transaction.model;

import com.exchange.app.wallet.po.enums.transaction.ActionType;
import com.exchange.app.wallet.po.transaction.WalletAction;
import com.exchange.app.wallet.po.transaction.WalletReservation;
import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.app.wallet.po.wallet.BalanceSnapshot;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// memory cache, not real time data
@RequiredArgsConstructor
public class TransactionInfo {
    public final WalletTransaction walletTxn;
    public final List<WalletAction> actions;
    public final Map<String, List<WalletAction>> walletRefToPostActions; // only CONSUME, TRANSFER_OUT, TRANSFER_IN
    public final List<WalletReservation> reservations;
    public final List<BalanceSnapshot> snapshots;

    public static TransactionInfo create(WalletTransaction txn, List<WalletAction> actions, List<WalletReservation> reservations, List<BalanceSnapshot> snapshots) {
        Map<String, List<WalletAction>> walletRefToActions = new HashMap<>();
        for (WalletAction action : actions) {
            if (!action.isTransfer() && action.getActionType() != ActionType.CONSUME) {
                continue;
            }
            walletRefToActions.computeIfAbsent(action.getWalletRef(), k -> new ArrayList<>());
            walletRefToActions.get(action.getWalletRef()).add(action);
        }
        return new TransactionInfo(txn, actions, walletRefToActions, reservations, snapshots);
    }

    static TransactionInfo create(WalletTransaction walletTxn) {
        return new TransactionInfo(walletTxn, null, null, null, null);
    }
}
