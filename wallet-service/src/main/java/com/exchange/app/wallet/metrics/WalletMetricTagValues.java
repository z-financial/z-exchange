package com.exchange.app.wallet.metrics;

public final class WalletMetricTagValues {
    public static final String UNKNOWN = "unknown";

    public static final class Operations {
        public static final String CREATE_WALLET = "create_wallet";
        public static final String ATOMIC_TRANSACTION = "atomic_transaction";
        public static final String RESERVE_TRANSACTION = "reserve_transaction";
        public static final String APPLY_RESERVATION_TRANSACTION = "apply_reservation_transaction";
        public static final String GET_SNAPSHOT_BY_WALLET_REF = "get_snapshot_by_wallet_ref";
        public static final String GET_SNAPSHOT_BY_BUSINESS_REF = "get_snapshot_by_business_ref";
        public static final String COMPLETE_LEDGER_TRANSACTION = "complete_ledger_transaction";

        private Operations() {
        }
    }

    public static final class Ingress {
        public static final String GRPC = "grpc";
        public static final String KAFKA = "kafka";

        private Ingress() {
        }
    }

    public static final class Outcomes {
        public static final String SUCCESS = "success";
        public static final String ERROR = "error";

        private Outcomes() {
        }
    }

    public static final class ErrorCodes {
        public static final String OK = "OK";

        private ErrorCodes() {
        }
    }

    public static final class CacheTypes {
        public static final String BALANCE_REF_NORMAL = "balance_ref_normal";
        public static final String BALANCE_REF_HOT = "balance_ref_hot";
        public static final String BALANCE_SNAPSHOT_NORMAL = "balance_snapshot_normal";
        public static final String BALANCE_SNAPSHOT_HOT = "balance_snapshot_hot";

        private CacheTypes() {
        }
    }

    private WalletMetricTagValues() {
    }
}
