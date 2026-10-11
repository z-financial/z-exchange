package com.exchange.app.wallet.po.transaction;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.exchange.app.wallet.po.enums.transaction.ActionType;
import com.exchange.app.wallet.po.enums.WalletBucket;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("wallet_actions")
public class WalletAction {
    @TableId(value = "internal_id", type = IdType.INPUT)
    @ToString.Exclude
    private Long internalId;
    private String walletActionRef;
    private String walletTxnRef;
    private String walletRef;
    private String assetId;
    private WalletBucket bucket;
    private ActionType actionType;
    private Long amount;
    private String walletReservationRef;

    public boolean isTransfer() {
        return actionType.isTransfer();
    }

    public static WalletAction create(Long internalId, String walletActionRef, String walletTxnRef,
                                      String walletRef, String assetId, WalletBucket bucket,
                                      ActionType actionType, Long amount, String walletReservationRef) {
        return new WalletAction(internalId, walletActionRef, walletTxnRef, walletRef, assetId, bucket,
                actionType, amount, walletReservationRef);
    }
}
