package com.exchange.app.ledger.metrics;

public final class LedgerMetricTagValues {
    public static final String NONE = "none";
    public static final String UNKNOWN = "unknown";

    public static final class Operations {
        public static final String POST_TRANSACTION = "post_transaction";
        public static final String GET_LEDGER_TXN_BY_ID = "get_ledger_txn_by_id";
        public static final String GET_LEDGER_TXN_BY_REF = "get_ledger_txn_by_ref";

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
        public static final String FAILURE = "failure";
        public static final String TIMEOUT = "timeout";
        public static final String INVALID_EVENT = "invalid_event";

        private Outcomes() {
        }
    }

    public static final class ErrorCodes {
        public static final String OK = "OK";

        private ErrorCodes() {
        }
    }

    public static final class ErrorTypes {
        public static final String HASH_CONFLICT = "hash_conflict";
        public static final String PARSE_ERROR = "parse_error";
        public static final String RELEASE_FAILED = "release_failed";
        public static final String REDIS_ERROR = "redis_error";
        public static final String UNKNOWN_STATE = "unknown_state";
        public static final String DECODE_ERROR = "decode_error";
        public static final String TIMEOUT = "timeout";
        public static final String PROCESSING_ERROR = "processing_error";
        public static final String DESERIALIZE_ERROR = "deserialize_error";
        public static final String DB_ERROR = "db_error";
        public static final String KAFKA_ERROR = "kafka_error";
        public static final String DUPLICATE = "duplicate";
        public static final String INVALID_EVENT = "invalid_event";
        public static final String UNKNOWN = "unknown";

        private ErrorTypes() {
        }
    }

    public static final class Sources {
        public static final String REDIS = "redis";
        public static final String DB = "db";
        public static final String IMMEDIATE = "immediate";
        public static final String RETRY = "retry";

        private Sources() {
        }
    }

    public static final class CacheTypes {
        public static final String LEDGER_REF = "ledger_ref";
        public static final String LEDGER_TXN = "ledger_txn";
        public static final String IDEMPOTENCY = "idempotency";

        private CacheTypes() {
        }
    }

    public static final class CacheResults {
        public static final String VALUE_HIT = "value_hit";
        public static final String TOMBSTONE_HIT = "tombstone_hit";
        public static final String NEGATIVE_HIT = "negative_hit";
        public static final String MISS = "miss";
        public static final String ERROR = "error";

        private CacheResults() {
        }
    }

    public static final class RedisOperations {
        public static final String CACHE_GET = "cache_get";
        public static final String CACHE_WRITE = "cache_write";
        public static final String CACHE_DELETE = "cache_delete";
        public static final String IDEMP_CLAIM = "idemp_claim";
        public static final String IDEMP_GET = "idemp_get";
        public static final String IDEMP_DONE = "idemp_done";
        public static final String IDEMP_RELEASE = "idemp_release";

        private RedisOperations() {
        }
    }

    public static final class ListenerNames {
        public static final String WALLET_POST = "wallet_post";

        private ListenerNames() {
        }
    }

    public static final class Retriable {
        public static final String TRUE = "true";
        public static final String FALSE = "false";
        public static final String NONE = "none";

        private Retriable() {
        }
    }

    public static final class Topics {
        public static final String WALLET_POST_REPLY = "wallet_post_reply";

        private Topics() {
        }
    }

    public static final class EventTypes {
        public static final String LEDGER_POST_REPLY = "ledger_post_reply";

        private EventTypes() {
        }
    }

    public static final class Lifecycles {
        public static final String CREATED = "created";
        public static final String SENT = "sent";
        public static final String DEAD = "dead";

        private Lifecycles() {
        }
    }

    public static final class Stages {
        public static final String CREATE = "create";
        public static final String CLAIM = "claim";
        public static final String PUBLISH = "publish";
        public static final String FINALIZE = "finalize";
        public static final String MARK_DEAD = "mark_dead";

        private Stages() {
        }
    }

    public static final class DispatchResults {
        public static final String CLAIMED = "claimed";
        public static final String SENT = "sent";
        public static final String FAILED = "failed";

        private DispatchResults() {
        }
    }

    public static final class DbOperations {
        public static final String ACCOUNT_REF_LOOKUP = "account_ref_lookup";
        public static final String LEDGER_TXN_LOOKUP_BY_ID = "ledger_txn_lookup_by_id";
        public static final String LEDGER_TXN_LOOKUP_BY_REF = "ledger_txn_lookup_by_ref";
        public static final String LEDGER_ENTRY_LOOKUP = "ledger_entry_lookup";
        public static final String LEDGER_TXN_INSERT = "ledger_txn_insert";
        public static final String LEDGER_ENTRY_BATCH_INSERT = "ledger_entry_batch_insert";
        public static final String OUTBOX_INSERT = "outbox_insert";
        public static final String OUTBOX_CLAIM = "outbox_claim";
        public static final String OUTBOX_FINALIZE = "outbox_finalize";
        public static final String OUTBOX_BACKLOG_COLLECT = "outbox_backlog_collect";

        private DbOperations() {
        }
    }

    public static final class DbTransactions {
        public static final String LEDGER_POST_TRANSACTION = "ledger_post_transaction";
        public static final String OUTBOX_CLAIM = "outbox_claim";

        private DbTransactions() {
        }
    }

    private LedgerMetricTagValues() {
    }
}
