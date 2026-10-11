package com.exchange.app.wallet.constant.cache;

public class CacheScope {
    final static public String BALANCE_SNAPSHOT_CACHE = "balance_snapshot";

    final static public String BALANCE_SNAPSHOT_BUSINESS_REF_SCOPE = BALANCE_SNAPSHOT_CACHE + ":business-ref";
    final static public String BALANCE_SNAPSHOT_WALLET_REF_SCOPE = BALANCE_SNAPSHOT_CACHE + ":wallet-ref";

    public static String balanceSnapshotBusinessRefKey(String businessRef) {
        return BALANCE_SNAPSHOT_BUSINESS_REF_SCOPE + ":" + businessRef;
    }

    public static String balanceSnapshotWalletRefKey(String walletRef) {
        return BALANCE_SNAPSHOT_WALLET_REF_SCOPE + ":" + walletRef;
    }
}
