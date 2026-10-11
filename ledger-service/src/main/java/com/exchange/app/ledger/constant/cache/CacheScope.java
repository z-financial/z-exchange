package com.exchange.app.ledger.constant.cache;

public class CacheScope {
    // Cache scope for account-related operations
    final static public String ACCOUNT_CACHE = "account";

    // Cache scope for ledger transaction operations
    final static public String LEDGER_TXN_CACHE = "ledger";

    // Cache scope for ledger entry operations
    final static public String LEDGER_ENTRY_CACHE = "entry";

    // scope for ledger_txn_ref -> ledger_txn
    final static public String LEDGER_TXN_REF_SCOPE = LEDGER_TXN_CACHE + ":ledger-ref";

    // scope for source_txn_ref -> ledger_txn_ref
    final static public String SOURCE_TXN_REF_SCOPE = LEDGER_TXN_CACHE + ":source-ref";

    public static String ledgerTxnRefKey(String ledgerTxnRef) {
        return LEDGER_TXN_REF_SCOPE + ":" + ledgerTxnRef;
    }

    public static String sourceTxnRefKey(String sourceTxnRef) {
        return SOURCE_TXN_REF_SCOPE + ":" + sourceTxnRef;
    }

}
