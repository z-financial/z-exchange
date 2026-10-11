package com.exchange.common.identity;

/** Permanent namespaces for durable message references. */
public enum EventRefType {
    WALLET_REQUEST("WER"),
    LEDGER_REPLY("LER");

    private final String code;

    EventRefType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static EventRefType fromCode(String code) {
        for (EventRefType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw EventReferenceException.unknownType(code);
    }
}
