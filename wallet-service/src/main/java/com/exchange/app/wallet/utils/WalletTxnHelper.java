package com.exchange.app.wallet.utils;

import com.exchange.app.wallet.po.transaction.WalletAction;
import com.exchange.app.wallet.po.transaction.WalletReservation;
import com.exchange.app.wallet.po.wallet.BalanceSnapshot;

import java.util.Objects;

public class WalletTxnHelper {
    static public boolean snapshotReservationMatched(BalanceSnapshot snapshot, WalletReservation reservation) {
        if (snapshot == null || reservation == null) {
            return false;
        }
        return Objects.equals(reservation.getWalletRef(), snapshot.getWalletRef()) && Objects.equals(reservation.getAssetId(), snapshot.getAssetId());
    }

    static public boolean actionSnapshotMatched(BalanceSnapshot snapshot, WalletAction action) {
        if (snapshot == null || action == null) {
            return false;
        }
        return Objects.equals(action.getWalletRef(), snapshot.getWalletRef()) && Objects.equals(action.getAssetId(), snapshot.getAssetId());
    }

    static public boolean actionReservationMatched(WalletAction action, WalletReservation reservation) {
        if (action == null || reservation == null) {
            return false;
        }
        return Objects.equals(action.getWalletRef(), reservation.getWalletRef()) && Objects.equals(action.getAssetId(), reservation.getAssetId());
    }
}
