package com.exchange.common.identity;

import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Permanent protocol registry for externally visible entity references.
 *
 * <p>Codes are persisted and cross service boundaries. Never rename or reuse a
 * code after it has been issued. Each type has exactly one allocator domain in
 * an environment so equal numeric IDs from different domains cannot collide.</p>
 */
@Getter
public enum EntityRefType {
    WALLET("WAL"),
    WALLET_TRANSACTION("WTX"),
    WALLET_RESERVATION("WRS"),
    WALLET_ACTION("WAC"),
    LEDGER_ACCOUNT("LAC"),
    LEDGER_TRANSACTION("LTX"),
    LEDGER_ENTRY("LEN");

    private static final Map<String, EntityRefType> BY_CODE;

    static {
        Map<String, EntityRefType> byCode = new HashMap<>();
        for (EntityRefType type : values()) {
            EntityRefType previous = byCode.put(type.code, type);
            if (previous != null) {
                throw new ExceptionInInitializerError("duplicate entity reference type code: " + type.code);
            }
        }
        BY_CODE = Collections.unmodifiableMap(byCode);
    }

    private final String code;

    EntityRefType(String code) {
        this.code = code;
    }

    public static EntityRefType fromCode(String code) {
        EntityRefType type = BY_CODE.get(code);
        if (type == null) {
            throw EntityReferenceException.unknownType(code);
        }
        return type;
    }
}
