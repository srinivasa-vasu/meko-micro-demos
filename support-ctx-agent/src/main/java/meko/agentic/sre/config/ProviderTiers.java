package meko.agentic.sre.config;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * What any OpenAI-compatible provider has to tell us to serve the three model roles.
 *
 * <p>Each provider gets its own {@code @ConfigurationProperties} record rather than sharing one,
 * because the prefixes, the defaults and the endpoint differ — but the shape they must satisfy is
 * identical, and {@link TieredProviderConfig} builds tiers from this interface alone.
 */
public interface ProviderTiers {

    String baseUrl();

    String apiKey();

    String slmModel();

    String llmModel();

    String frontierModel();

    String llmEffort();

    String frontierEffort();

    /** True when a run can actually reach the provider. Reported, never assumed. */
    default boolean hasApiKey() {
        return apiKey() != null && !apiKey().isBlank();
    }

    /**
     * gpt-oss accepts low, medium and high. It does NOT accept the "none" and "default" that
     * Groq's other reasoning models take, nor OpenRouter's wider "max"/"xhigh"/"minimal" set,
     * which apply to models we do not use here.
     * <p>
     * Checked at startup rather than on first call: a rejected effort is a 400 in the middle of a
     * demo, and the message the provider returns says nothing about which property was wrong.
     */
    Set<String> ALLOWED_EFFORTS = Set.of("low", "medium", "high");

    static String validEffort(String effort, String property) {
        var normalised = effort.toLowerCase(Locale.ROOT);
        if (!ALLOWED_EFFORTS.contains(normalised)) {
            throw new IllegalArgumentException(
                    property + " is '" + effort + "'; gpt-oss accepts only "
                            + String.join(", ", new TreeSet<>(ALLOWED_EFFORTS)));
        }
        return normalised;
    }

    static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
