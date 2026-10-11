package com.exchange.common.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultEventRefFactoryTest {
    private final EventRefFactory factory = new DefaultEventRefFactory();

    @Test
    void createsCanonicalRequestReferenceForPositiveLongRange() {
        String eventRef = factory.create(EventRefType.WALLET_REQUEST, Long.MAX_VALUE);

        assertTrue(eventRef.startsWith("WER-"));
        assertEquals(ReferenceFormatSupport.REFERENCE_LENGTH, eventRef.length());
        assertTrue(factory.validate(eventRef, EventRefType.WALLET_REQUEST).isSuccess());
    }

    @Test
    void createsIndependentReplyReferencesFromOutboxIdentity() {
        String first = factory.create(EventRefType.LEDGER_REPLY, 42L);
        String second = factory.create(EventRefType.LEDGER_REPLY, 43L);

        assertNotEquals(first, second);
        assertTrue(first.startsWith("LER-"));
        assertTrue(factory.validate(first, EventRefType.LEDGER_REPLY).isSuccess());
        assertEquals(ReferenceFormatSupport.REFERENCE_LENGTH, first.length());
    }

    @Test
    void rejectsCorruptedChecksum() {
        String eventRef = factory.create(EventRefType.WALLET_REQUEST, 7L);
        char replacement = eventRef.endsWith("0") ? '1' : '0';
        String corrupted = eventRef.substring(0, eventRef.length() - 1) + replacement;

        assertTrue(factory.validate(corrupted, EventRefType.WALLET_REQUEST).isFailed());
    }

    @Test
    void rejectsInvalidSourceIdAndUnexpectedType() {
        String replyRef = factory.create(EventRefType.LEDGER_REPLY, 8L);

        assertThrows(EventReferenceException.class,
                () -> factory.create(EventRefType.WALLET_REQUEST, 0L));
        assertTrue(factory.validate(replyRef, EventRefType.WALLET_REQUEST).isFailed());

        String overflowPayload = "LER-AzL8n0Y58m8";
        String overflowRef = overflowPayload + '-' + ReferenceFormatSupport.checksum(overflowPayload);
        assertTrue(factory.validate(overflowRef, EventRefType.LEDGER_REPLY).isFailed());
    }
}
