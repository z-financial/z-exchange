package com.exchange.app.ledger.po.ledger;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.exchange.app.ledger.po.enums.Direction;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ledger_entries")
public class LedgerEntry {
    @TableId(value = "internal_id", type = IdType.INPUT)
    @ToString.Exclude
    private Long internalId;
    private String ledgerEntryRef;
    // TODO should we add a seq field and set (ledger_txn_ref, seq) as a unique key,
    // so that a transaction cannot insert more entries after it has been posted?
    private String ledgerTxnRef;
    private String assetId;
    private String ledgerAccountRef;
    private long amount;
    private Direction direction;

    public static LedgerEntry create(long internalId, String ledgerEntryRef,
                                     String ledgerTxnRef, String assetId,
                                     String ledgerAccountRef, long amount, Direction direction) {
        return new LedgerEntry(internalId, ledgerEntryRef, ledgerTxnRef, assetId,
                ledgerAccountRef, amount, direction);
    }
}
