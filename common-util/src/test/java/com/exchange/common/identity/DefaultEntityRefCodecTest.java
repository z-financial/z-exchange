package com.exchange.common.identity;

import com.exchange.common.result.error.EntityReferenceErrorCode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultEntityRefCodecTest {
    private final EntityRefCodec codec = new DefaultEntityRefCodec();

    @Test
    void fixedVectorsProtectTheWireFormat() {
        assertEquals("WAL-00000000001-3EHznX", codec.encode(EntityRefType.WALLET, 1));
        assertEquals(
                "WTX-0000008M0kX-1WJJKr",
                codec.encode(EntityRefType.WALLET_TRANSACTION, 123_456_789));
        assertEquals(
                "LEN-AzL8n0Y58m7-1QfF8Y",
                codec.encode(EntityRefType.LEDGER_ENTRY, Long.MAX_VALUE));
    }

    @Test
    void everyTypeRoundTripsTheSupportedRange() {
        long[] values = {1, 35, 36, 61, 62, 123_456_789, Long.MAX_VALUE};

        for (EntityRefType type : EntityRefType.values()) {
            for (long value : values) {
                String reference = codec.encode(type, value);
                assertEquals(new DecodedEntityRef(type, value), codec.decode(reference));
                assertEquals(value, codec.decode(reference, type));
            }
        }
    }

    @Test
    void typeCodesKeepEqualIdsUniqueAcrossAllocatorDomains() {
        Set<String> references = new HashSet<>();

        for (EntityRefType type : EntityRefType.values()) {
            references.add(codec.encode(type, 42));
        }

        assertEquals(EntityRefType.values().length, references.size());
        assertNotEquals(
                codec.encode(EntityRefType.WALLET_TRANSACTION, 42),
                codec.encode(EntityRefType.LEDGER_TRANSACTION, 42));
    }

    @Test
    void everyValueUsesTheFixedReferenceLength() {
        assertEquals(
                DefaultEntityRefCodec.REFERENCE_LENGTH,
                codec.encode(EntityRefType.WALLET, 1).length());
        assertEquals(
                DefaultEntityRefCodec.REFERENCE_LENGTH,
                codec.encode(EntityRefType.LEDGER_ENTRY, Long.MAX_VALUE).length());
    }

    @Test
    void malformedAndNonCanonicalReferencesAreControlled() {
        assertCode(EntityReferenceErrorCode.MALFORMED, () -> codec.decode(null));
        assertCode(EntityReferenceErrorCode.MALFORMED, () -> codec.decode(""));
        assertCode(EntityReferenceErrorCode.MALFORMED, () -> codec.decode("wal-00000000001-3EHznX"));
        assertCode(EntityReferenceErrorCode.MALFORMED, () -> codec.decode("WAL-1-19owXm"));
        assertCode(EntityReferenceErrorCode.MALFORMED, () -> codec.decode("WAL-0000000000*-000000"));
    }

    @Test
    void unknownTypeIsControlled() {
        assertCode(EntityReferenceErrorCode.UNKNOWN_TYPE, () -> codec.decode("ZZZ-00000000001-000000"));
        assertCode(EntityReferenceErrorCode.UNKNOWN_TYPE, () -> codec.encode(null, 1));
    }

    @Test
    void invalidChecksumIsControlled() {
        assertCode(EntityReferenceErrorCode.CHECKSUM_INVALID,
                () -> codec.decode("WAL-00000000001-000000"));
    }

    @Test
    void overLengthIsControlledBeforeParsing() {
        assertCode(
                EntityReferenceErrorCode.OVER_LENGTH,
                () -> codec.decode("WAL-AzL8n0Y58m7-1QfF8Y0"));
    }

    @Test
    void unsupportedNumericRangeIsControlled() {
        assertCode(EntityReferenceErrorCode.OUT_OF_RANGE, () -> codec.encode(EntityRefType.WALLET, 0));
        assertCode(EntityReferenceErrorCode.OUT_OF_RANGE, () -> codec.encode(EntityRefType.WALLET, -1));
        assertCode(
                EntityReferenceErrorCode.OUT_OF_RANGE,
                () -> codec.decode("WAL-AzL8n0Y58m8-000000"));
    }

    @Test
    void expectedTypeValidationDoesNotGuessRouting() {
        String walletRef = codec.encode(EntityRefType.WALLET, 99);

        assertCode(
                EntityReferenceErrorCode.UNEXPECTED_TYPE,
                () -> codec.decode(walletRef, EntityRefType.LEDGER_ACCOUNT));
    }

    private static void assertCode(
            EntityReferenceErrorCode expected,
            Runnable operation) {
        EntityReferenceException exception = assertThrows(EntityReferenceException.class, operation::run);
        assertEquals(expected, exception.getCode());
    }
}
