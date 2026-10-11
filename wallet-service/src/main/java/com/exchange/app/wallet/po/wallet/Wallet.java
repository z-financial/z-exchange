package com.exchange.app.wallet.po.wallet;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.exchange.app.wallet.po.enums.OwnerType;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.enums.WalletStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("wallets")
public class Wallet {
    @TableId(value = "internal_id", type = IdType.INPUT)
    @ToString.Exclude
    private Long internalId;
    private String walletRef;
    private ServiceId serviceId;
    private String walletBusinessRef;
    private String assetId;
    private WalletStatus walletStatus;
    private OwnerType ownerType;
    private String ownerId;

    public static Wallet create(Long internalId, String walletRef, ServiceId serviceId, String walletBusinessRef,
                                String assetId, WalletStatus walletStatus, OwnerType ownerType, String ownerId) {
        return new Wallet(internalId, walletRef, serviceId, walletBusinessRef,
                assetId, walletStatus, ownerType, ownerId);
    }
}
