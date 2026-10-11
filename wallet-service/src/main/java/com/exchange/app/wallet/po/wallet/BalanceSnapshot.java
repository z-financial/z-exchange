package com.exchange.app.wallet.po.wallet;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.exchange.app.wallet.po.enums.OwnerType;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.enums.WalletStatus;
import com.exchange.common.db.po.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
// do not compare create/update time
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@TableName("balance_snapshots")
public class BalanceSnapshot extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    // Typed Wallet reference; this snapshot intentionally retains its own auto-increment PK.
    private String walletRef;
    private ServiceId serviceId;
    // Caller-owned business reference, not a generated entity identity.
    private String walletBusinessRef;
    private String assetId;
    // consistent with wallet.status
    private WalletStatus walletStatus;
    private OwnerType ownerType;
    private String ownerId;
    private Long available;
    private Long reserved;
    private String lastWalletTxnRef;
    @Version
    private Long version;

    private BalanceSnapshot(String walletRef, ServiceId serviceId, String walletBusinessRef, String assetId,
                            WalletStatus walletStatus, OwnerType ownerType, String ownerId,
                            Long available, Long reserved, String lastWalletTxnRef, Long version) {
        this.walletRef = walletRef;
        this.serviceId = serviceId;
        this.walletBusinessRef = walletBusinessRef;
        this.assetId = assetId;
        this.walletStatus = walletStatus;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.available = available;
        this.reserved = reserved;
        this.lastWalletTxnRef = lastWalletTxnRef;
        this.version = version;
    }

    public static BalanceSnapshot create(String walletRef, ServiceId serviceId, String walletBusinessRef,
                                         String assetId, WalletStatus walletStatus, OwnerType ownerType,
                                         String ownerId, Long available, Long reserved, Long version) {
        return new BalanceSnapshot(walletRef, serviceId, walletBusinessRef, assetId, walletStatus,
                ownerType, ownerId, available, reserved, null, version);
    }
}
