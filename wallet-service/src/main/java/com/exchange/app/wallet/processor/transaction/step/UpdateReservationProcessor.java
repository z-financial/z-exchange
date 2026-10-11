package com.exchange.app.wallet.processor.transaction.step;

import com.exchange.app.wallet.dao.repository.*;
import com.exchange.app.wallet.po.transaction.WalletReservation;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class UpdateReservationProcessor {
    private final WalletReservationRepository walletReservationManager;

    public Result<Void> updateReservations(List<WalletReservation> reservations, Function<WalletReservation, Result<WalletReservation>> function) {
        if (reservations.isEmpty()) {
            return Result.success();
        }
        List<Long> modifyReservationIds = reservations.stream()
                .map(WalletReservation::getInternalId)
                .collect(Collectors.toList());
        // select for update to lock the rows in PK order
        var reservationFromDb = walletReservationManager.selectInIdForUpdate(modifyReservationIds);
        if (reservationFromDb.size() != modifyReservationIds.size()) {
            return Result.failure(WalletServiceErrorCode.WALLET_RESERVATION_NOT_FOUND, String.format("wallet_reservation_not_found, ids: %s, reservationFromDb: %s", modifyReservationIds, reservationFromDb));
        }

        for (WalletReservation reservation : reservationFromDb) {
            WalletReservation copy = new WalletReservation();
            BeanUtils.copyProperties(reservation, copy);
            Result<WalletReservation> result = function.apply(reservation);
            if (result.isFailed()) {
                return Result.failure(result);
            }
            reservation = result.getValue();

            if (!Objects.equals(reservation, copy)) {
                if (walletReservationManager.updateWithInOptimisticLock(reservation, copy) != 1) {
                    return Result.failure(WalletServiceErrorCode.WALLET_RESERVATION_UPDATE_FAILED, String.format("failed to update reservation, reservation: %s, copy: %s", reservation, copy));
                }
            }
        }
        return Result.success();
    }
}
