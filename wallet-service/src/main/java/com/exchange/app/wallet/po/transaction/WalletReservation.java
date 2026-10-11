package com.exchange.app.wallet.po.transaction;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.exchange.app.wallet.po.enums.transaction.ReservationOutcome;
import com.exchange.app.wallet.po.enums.transaction.ReservationStatus;
import com.exchange.app.wallet.po.enums.ServiceId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.Objects;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("wallet_reservations")
public class WalletReservation {
    @TableId(value = "internal_id", type = IdType.INPUT)
    @ToString.Exclude
    private Long internalId;
    private String walletReservationRef;
    private ServiceId initiator;
    private String walletReservationBusinessRef;
    private String walletRef;
    private String assetId;
    private Long total;
    private Long remaining;
    private Long consumed;
    private Long pendingSettle;
    private Long released;
    private ReservationStatus reservationStatus;
    private ReservationOutcome reservationOutcome;
    private String reserveWalletTxnRef;

    static public boolean isTotallySettled(WalletReservation reservation) {
        return reservation.total == reservation.consumed + reservation.released;
    }

    static public ReservationOutcome inferOutcome(WalletReservation reservation) {
        if (isTotallySettled(reservation)) {
            if (Objects.equals(reservation.consumed, reservation.total)) {
                return ReservationOutcome.CONSUMED;
            } else if (Objects.equals(reservation.released, reservation.total)) {
                return ReservationOutcome.RELEASED;
            } else {
                return ReservationOutcome.PARTIAL;
            }
        } else {
            return ReservationOutcome.NOT_DONE;
        }
    }

    static public WalletReservation create(Long internalId, String walletReservationRef,
                                           ServiceId initiator, String walletReservationBusinessRef,
                                           String walletRef,
                                           String assetId, Long total, Long remaining, Long consumed,
                                           Long pendingSettle, Long released,
                                           ReservationStatus reservationStatus,
                                           ReservationOutcome reservationOutcome, String reserveWalletTxnRef) {
        return new WalletReservation(internalId, walletReservationRef, initiator,
                walletReservationBusinessRef, walletRef,
                assetId, total, remaining, consumed, pendingSettle, released, reservationStatus,
                reservationOutcome, reserveWalletTxnRef);
    }
}
