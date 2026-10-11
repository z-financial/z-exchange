package com.exchange.app.wallet.po.transaction;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.exchange.app.wallet.po.enums.BusinessType;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.enums.transaction.TransactionStatus;
import com.exchange.app.wallet.po.enums.transaction.TransactionType;
import com.exchange.common.db.po.BaseEntity;
import lombok.*;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@TableName("wallet_transactions")
public class WalletTransaction extends BaseEntity {
    @TableId(value = "internal_id", type = IdType.INPUT)
    @ToString.Exclude
    private Long internalId;
    private String walletTxnRef;
    private String upstreamRequestRef;
    private ServiceId initiator;
    // TODO(idempotency-db-authority): Persist a canonical requestHash and compare it on
    // replay after the Redis-only equivalence check is promoted into this phase.
    private TransactionStatus txnStatus;
    private TransactionType txnType;
    private BusinessType businessType;

    public boolean hasFinalized() {
        return txnStatus.hasFinalized();
    }

    public static WalletTransaction create(Long internalId, String walletTxnRef,
                                           String upstreamRequestRef, ServiceId initiator,
                                           TransactionStatus txnStatus, TransactionType txnType,
                                           BusinessType businessType) {
        return new WalletTransaction(internalId, walletTxnRef, upstreamRequestRef, initiator,
                txnStatus, txnType, businessType);
    }
}
