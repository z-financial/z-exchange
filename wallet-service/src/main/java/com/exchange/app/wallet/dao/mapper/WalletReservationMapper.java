package com.exchange.app.wallet.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.app.wallet.po.transaction.WalletReservation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface WalletReservationMapper extends BaseMapper<WalletReservation> {
    @Insert("<script>" +
            "INSERT INTO wallet_reservations (internal_id, wallet_reservation_ref, initiator, reference_id, wallet_ref, asset_id, total, remaining, consumed, pending_settle, released, reservation_status, reservation_outcome, reserve_wallet_txn_ref) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.internalId}, #{item.walletReservationRef}, #{item.initiator}, #{item.referenceId}, #{item.walletRef}, #{item.assetId}, #{item.total}, #{item.remaining}, #{item.consumed}, #{item.pendingSettle}, #{item.released}, #{item.reservationStatus}, #{item.reservationOutcome}, #{item.reserveWalletTxnRef})" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<WalletReservation> list);
}
