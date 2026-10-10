package com.exchange.common.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.CRC32C;

/** Canonical implementation of the version-one entity reference contract. */
public final class DefaultEntityRefCodec implements EntityRefCodec {
    public static final String ENCODING_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    public static final int ENCODED_ID_LENGTH = 11;
    public static final int CHECKSUM_LENGTH = 6;
    public static final int REFERENCE_LENGTH = 22;

    private static final int RADIX = ENCODING_ALPHABET.length();
    private static final int[] DECODE = buildDecodeTable();

    @Override
    public String encode(EntityRefType type, long internalId) {
        if (type == null) {
            throw EntityReferenceException.unknownType(null);
        }
        if (internalId <= 0) {
            throw EntityReferenceException.outOfRange();
        }

        String encodedId = encodePositiveLong(internalId);
        String payload = type.getCode() + '-' + encodedId;
        return payload + '-' + checksum(payload);
    }

    @Override
    public DecodedEntityRef decode(String reference) {
        if (reference == null || reference.isBlank()) {
            throw EntityReferenceException.malformed();
        }
        if (reference.length() > REFERENCE_LENGTH) {
            throw EntityReferenceException.overLength(reference.length(), REFERENCE_LENGTH);
        }

        String[] parts = reference.split("-", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw EntityReferenceException.malformed();
        }

        if (!isCanonicalTypeCode(parts[0])) {
            throw EntityReferenceException.malformed();
        }
        EntityRefType type = EntityRefType.fromCode(parts[0]);
        if (parts[1].length() != ENCODED_ID_LENGTH
                || !isCanonicalBase62(parts[1])
                || parts[2].length() != CHECKSUM_LENGTH
                || !isCanonicalBase62(parts[2])) {
            throw EntityReferenceException.malformed();
        }
        long internalId = decodePositiveLong(parts[1]);

        String payload = parts[0] + '-' + parts[1];
        String expectedChecksum = checksum(payload);
        if (!MessageDigest.isEqual(
                expectedChecksum.getBytes(StandardCharsets.US_ASCII),
                parts[2].getBytes(StandardCharsets.US_ASCII))) {
            throw EntityReferenceException.checksumInvalid();
        }

        return new DecodedEntityRef(type, internalId);
    }

    private static String encodePositiveLong(long value) {
        char[] buffer = new char[ENCODED_ID_LENGTH];
        java.util.Arrays.fill(buffer, ENCODING_ALPHABET.charAt(0));
        int cursor = buffer.length;
        do {
            buffer[--cursor] = ENCODING_ALPHABET.charAt((int) (value % RADIX));
            value /= RADIX;
        } while (value != 0);
        return new String(buffer);
    }

    private static long decodePositiveLong(String encoded) {
        long value = 0;
        for (int index = 0; index < encoded.length(); index++) {
            char current = encoded.charAt(index);
            int digit = current < DECODE.length ? DECODE[current] : -1;
            if (digit < 0) {
                throw EntityReferenceException.malformed();
            }
            if (value > (Long.MAX_VALUE - digit) / RADIX) {
                throw EntityReferenceException.outOfRange();
            }
            value = value * RADIX + digit;
        }
        if (value <= 0) {
            throw EntityReferenceException.outOfRange();
        }
        return value;
    }

    private static String checksum(String payload) {
        CRC32C crc = new CRC32C();
        byte[] bytes = payload.getBytes(StandardCharsets.US_ASCII);
        crc.update(bytes, 0, bytes.length);

        long value = crc.getValue();
        char[] result = new char[CHECKSUM_LENGTH];
        for (int index = CHECKSUM_LENGTH - 1; index >= 0; index--) {
            result[index] = ENCODING_ALPHABET.charAt((int) (value % RADIX));
            value /= RADIX;
        }
        return new String(result);
    }

    private static boolean isCanonicalTypeCode(String value) {
        if (value.length() != 3) {
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

    private static boolean isCanonicalBase62(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current >= DECODE.length || DECODE[current] < 0) {
                return false;
            }
        }
        return true;
    }

    private static int[] buildDecodeTable() {
        int[] table = new int[128];
        java.util.Arrays.fill(table, -1);
        for (int index = 0; index < ENCODING_ALPHABET.length(); index++) {
            table[ENCODING_ALPHABET.charAt(index)] = index;
        }
        return table;
    }
}
