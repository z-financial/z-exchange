package com.exchange.common.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.zip.CRC32C;

/** Shared V1 wire-format primitives; semantic type handling remains in each reference codec. */
final class ReferenceFormatSupport {
    static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final int RADIX = ALPHABET.length();
    static final int TYPE_CODE_LENGTH = 3;
    static final int TOKEN_LENGTH = 11;
    static final int CHECKSUM_LENGTH = 6;
    static final int REFERENCE_LENGTH = 22;

    private static final int[] DECODE = buildDecodeTable();

    private ReferenceFormatSupport() {
    }

    static String format(String typeCode, long internalId) {
        String payload = typeCode + '-' + encodePositiveLong(internalId);
        return payload + '-' + checksum(payload);
    }

    static ParsedReferenceParts parseCanonicalParts(String reference) {
        if (reference == null || reference.isBlank() || reference.length() != REFERENCE_LENGTH) {
            return null;
        }

        String[] parts = reference.split("-", -1);
        if (parts.length != 3
                || !isCanonicalTypeCode(parts[0])
                || parts[1].length() != TOKEN_LENGTH
                || parts[2].length() != CHECKSUM_LENGTH
                || !isBase62(parts[1])
                || !isBase62(parts[2])) {
            return null;
        }
        return new ParsedReferenceParts(parts[0], parts[1], parts[2]);
    }

    static String encodePositiveLong(long value) {
        // Eleven Base62 digits cover the complete positive signed-long range.
        char[] encoded = new char[TOKEN_LENGTH];
        Arrays.fill(encoded, ALPHABET.charAt(0));
        int cursor = encoded.length;
        do {
            encoded[--cursor] = ALPHABET.charAt((int) (value % RADIX));
            value /= RADIX;
        } while (value != 0);
        return new String(encoded);
    }

    static OptionalLong decodePositiveLong(String encoded) {
        long value = 0;
        for (int index = 0; index < encoded.length(); index++) {
            int digit = decode(encoded.charAt(index));
            if (digit < 0 || value > (Long.MAX_VALUE - digit) / RADIX) {
                return OptionalLong.empty();
            }
            value = value * RADIX + digit;
        }
        return value > 0 ? OptionalLong.of(value) : OptionalLong.empty();
    }

    static boolean checksumMatches(ParsedReferenceParts parts) {
        String expected = checksum(parts.typeCode() + '-' + parts.encodedId());
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                parts.checksum().getBytes(StandardCharsets.US_ASCII));
    }

    static String checksum(String payload) {
        CRC32C crc = new CRC32C();
        byte[] bytes = payload.getBytes(StandardCharsets.US_ASCII);
        crc.update(bytes, 0, bytes.length);

        long value = crc.getValue();
        char[] encoded = new char[CHECKSUM_LENGTH];
        for (int index = CHECKSUM_LENGTH - 1; index >= 0; index--) {
            encoded[index] = ALPHABET.charAt((int) (value % RADIX));
            value /= RADIX;
        }
        return new String(encoded);
    }

    static boolean isBase62(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (decode(value.charAt(index)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int decode(char value) {
        return value < DECODE.length ? DECODE[value] : -1;
    }

    private static boolean isCanonicalTypeCode(String value) {
        if (value.length() != TYPE_CODE_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 'A' || current > 'Z') {
                return false;
            }
        }
        return true;
    }

    private static int[] buildDecodeTable() {
        int[] table = new int[128];
        Arrays.fill(table, -1);
        for (int index = 0; index < ALPHABET.length(); index++) {
            table[ALPHABET.charAt(index)] = index;
        }
        return table;
    }

    record ParsedReferenceParts(String typeCode, String encodedId, String checksum) {
    }
}
