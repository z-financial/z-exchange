package com.exchange.app.ledger.po.ledger;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.exchange.app.ledger.po.enums.ServiceId;
import com.exchange.common.db.po.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
// do not compare create/update time
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@TableName("ledger_transactions")
public class LedgerTxn extends BaseEntity {
    @TableId(value = "internal_id", type = IdType.INPUT)
    @ToString.Exclude
    private Long internalId;
    private String ledgerTxnRef;
    // TODO: Populate and validate the source service when Ledger accepts upstreams other than Wallet.
    private ServiceId sourceServiceId;
    private String sourceTxnRef;
    private String metadata;
    @Version
    private Long version;

    public static LedgerTxn create(long internalId, String ledgerTxnRef, String sourceTxnRef,
                                   String metadata) {
        return new LedgerTxn(internalId, ledgerTxnRef, null, sourceTxnRef, metadata, 0L);
    }

    public static LedgerTxn create(long internalId, String ledgerTxnRef, String sourceTxnRef) {
        return new LedgerTxn(internalId, ledgerTxnRef, null, sourceTxnRef, "{}", 0L);
    }
}
