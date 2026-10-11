package com.exchange.app.wallet.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.app.wallet.po.transaction.WalletAction;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface WalletActionMapper extends BaseMapper<WalletAction> {
    @Insert("<script>" +
            "INSERT INTO wallet_actions (internal_id, wallet_action_ref, wallet_txn_ref, wallet_ref, asset_id, bucket, action_type, amount, wallet_reservation_ref) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.internalId}, #{item.walletActionRef}, #{item.walletTxnRef}, #{item.walletRef}, #{item.assetId}, #{item.bucket}, #{item.actionType}, #{item.amount}, #{item.walletReservationRef})" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<WalletAction> list);
}
