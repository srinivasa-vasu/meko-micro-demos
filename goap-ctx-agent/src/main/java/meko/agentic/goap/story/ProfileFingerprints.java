package meko.agentic.goap.story;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Keyed fingerprints of a customer profile, so what an agent writes to a shared context store can say "the address is
 * unchanged" or "this beneficiary is new" without ever holding the address or the beneficiary's name.
 * <p>
 * Each fingerprint is an HMAC-SHA256 of one field's normalized value, keyed with a secret the bank holds
 * ({@code KYC_FINGERPRINT_KEY}). Plain hashes would not do: an address or a company name is easy to guess and hash.
 * The key never leaves this application: not to Meko, not to a model. The same key must be used by every agent that
 * writes or compares fingerprints, or nothing will match.
 * <p>
 * Monthly volume is kept as a coarse band rather than a fingerprint, so a reviewer can still judge how far it moved.
 */
@Component
public class ProfileFingerprints {
    private static final Logger log = LoggerFactory.getLogger(ProfileFingerprints.class);
    /** Width of a volume band in USD. */
    static final int VOLUME_BAND = 10_000;

    private final SecretKeySpec key;

    public ProfileFingerprints(@Value("${kyc.fingerprint-key}") String key) {
        if (key == null || key.isBlank()) throw new IllegalStateException("Set KYC_FINGERPRINT_KEY: the key for profile fingerprints");
        if (key.startsWith("local-only")) log.warn("Using the local-only KYC fingerprint key: set KYC_FINGERPRINT_KEY for a shared datapack");
        this.key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * The fingerprint of one field's value for one customer, e.g. {@code of("CUST-7788", "address", "12 Harbour Road")}:
     * 8 hex characters. Scoped to the customer, so the same beneficiary or address under two customers gives two
     * fingerprints and a reader of the datapack cannot link customers through them.
     */
    public String of(String customer, String field, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal((customer + ":" + field + ":" + normalize(value)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> beneficiaries(String customer, List<String> names) {
        return names.stream().map(n -> of(customer, "beneficiary", n)).toList();
    }

    /** The volume band a monthly volume falls in, as "40000-50000". */
    public static String volumeBand(int monthlyVolume) {
        int low = Math.floorDiv(monthlyVolume, VOLUME_BAND) * VOLUME_BAND;
        return low + "-" + (low + VOLUME_BAND);
    }

    /** How far {@code monthlyVolume} lies outside a band "low-high", in percent of the nearest bound (0 inside it). */
    public static double percentOutside(String band, int monthlyVolume) {
        String[] bounds = band.split("-");
        int low = Integer.parseInt(bounds[0].trim()), high = Integer.parseInt(bounds[1].trim());
        if (monthlyVolume < low) return (low - monthlyVolume) * 100.0 / low;
        if (monthlyVolume > high) return (monthlyVolume - high) * 100.0 / high;
        return 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
