package com.exchange.app.ledger.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.app.ledger.po.ledger.LedgerEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface LedgerEntryMapper extends BaseMapper<LedgerEntry> {
    @Insert("<script>" +
            "INSERT INTO ledger_entries (internal_id, ledger_entry_ref, ledger_txn_ref, asset_id, ledger_account_ref, amount, direction) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.internalId}, #{item.ledgerEntryRef}, #{item.ledgerTxnRef}, #{item.assetId}, #{item.ledgerAccountRef}, #{item.amount}, #{item.direction})" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<LedgerEntry> list);
}
