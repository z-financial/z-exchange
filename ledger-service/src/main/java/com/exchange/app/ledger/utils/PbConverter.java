package com.exchange.app.ledger.utils;

import com.exchange.app.ledger.po.ledger.LedgerEntry;
import com.exchange.app.ledger.po.ledger.LedgerTxn;
import com.exchange.proto.ledger.post.LedgerEntryPb;
import com.exchange.proto.ledger.post.LedgerTransactionPb;

import java.util.List;
import java.util.stream.Collectors;

public class PbConverter {
    public static LedgerTransactionPb convertToLedgerTransactionPb(LedgerTxn txn) {
        if (txn == null) {
            return LedgerTransactionPb.newBuilder().build();
        }
        return LedgerTransactionPb.newBuilder()
                .setLedgerTxnRef(txn.getLedgerTxnRef())
                .setSourceTxnRef(txn.getSourceTxnRef())
                .setExtraData(txn.getMetadata())
                .build();
    }

    public static Iterable<LedgerEntryPb> convertToLedgerEntryPbs(List<LedgerEntry> entries) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
                .map(entry -> LedgerEntryPb.newBuilder()
                        .setLedgerEntryRef(entry.getLedgerEntryRef())
                        .setLedgerAccountRef(entry.getLedgerAccountRef())
                        .setDirection(EnumMappers.directionPbMapper.from(entry.getDirection()))
                        .setAmount(entry.getAmount())
                        .setAssetId(entry.getAssetId())
                        .build())
                .collect(Collectors.toList());
    }
}
