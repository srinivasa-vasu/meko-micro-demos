package meko.agentic.sre.domain;

/**
 * One source that contributed to the prompt, and what it cost to include.
 * <p>
 * The demo's ledger answers "what did this run spend". This answers the more important question
 * for a context engine: "what was the model actually given, and where did each part come from".
 * Without it the prompt is a single opaque number and the assembly — the thing Meko does — is
 * invisible.
 */
public record ContextSource(
        String name,
        /* Where it came from, in the user's terms: telemetry, learned memory, curated knowledge. */
        String origin,
        int items,
        long tokens
) {}
