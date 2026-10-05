package meko.agentic.sre.service;

import meko.agentic.sre.domain.ModelTier;
import meko.agentic.sre.domain.RoutingObservation;
import meko.agentic.sre.domain.RoutingPolicy;
import meko.agentic.sre.domain.StoredIncident;
import meko.agentic.sre.meko.MekoToolBridge.MekoMemory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses the structured records this demo writes into Meko.
 * <p>
 * The seed writes key=value records rather than prose precisely so that the reuse gate can
 * score a candidate deterministically. If the records were free text, deciding whether to
 * reuse would itself require a model call — which is the cost we are trying to avoid.
 */
@Component
public class MemoryRecordParser {

    private static final Pattern RECORD = Pattern.compile("(?m)^INCIDENT_RECORD\\b.*$");
    private static final Pattern OUTCOME = Pattern.compile("(?m)^ROUTING_OUTCOME\\b.*$");
    private static final Pattern POLICY = Pattern.compile("(?m)^ROUTING_POLICY\\b.*$");

    /**
     * Parse rows that still carry their Meko metadata, so the semantic score and the writing
     * agent survive into the reuse decision. Prefer this over the String overload: flattening
     * to text throws both away, and the score is what the comparison against lexical overlap
     * is built on.
     */
    public List<StoredIncident> parseIncidents(List<MekoMemory> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        var best = new LinkedHashMap<String, StoredIncident>();
        var bestRow = new LinkedHashMap<String, MekoMemory>();
        for (var row : rows) {
            for (var incident : parseIncidents(row.text())) {
                var scored = new StoredIncident(
                        incident.incidentId(), incident.service(), incident.signature(),
                        incident.deployService(), incident.deployVersion(),
                        incident.rootCause(), incident.remediation(),
                        incident.scopeServices(), incident.observedAt(), incident.raw(),
                        row.score(), row.rawScore(), row.writtenBy());
                var key = identity(scored);
                var existing = best.get(key);
                var existingRow = bestRow.get(key);

                boolean newer = existing == null
                        || row.createdAt().compareTo(existingRow.createdAt()) > 0;
                if (newer) {
                    double bestScore = existing == null
                            ? scored.semanticScore()
                            : Math.max(scored.semanticScore(), existing.semanticScore());
                    best.put(key, withScore(scored, bestScore));
                    bestRow.put(key, row);
                } else if (scored.semanticScore() > existing.semanticScore()) {
                    best.put(key, withScore(existing, scored.semanticScore()));
                }
            }
        }
        return List.copyOf(best.values().stream().toList());
    }

    private StoredIncident withScore(StoredIncident incident, double score) {
        return new StoredIncident(
                incident.incidentId(), incident.service(), incident.signature(),
                incident.deployService(), incident.deployVersion(), incident.rootCause(),
                incident.remediation(), incident.scopeServices(), incident.observedAt(),
                incident.raw(), score, incident.rawScore(), incident.writtenBy());
    }

    /**
     * What makes two stored incidents the same incident.
     * <p>
     * Id when there is one. Otherwise service plus signature, because a record written without
     * an id is still a duplicate of another describing the same failure on the same service.
     */
    private String identity(StoredIncident incident) {
        if (incident.incidentId() != null && !incident.incidentId().isBlank()
                && !"unknown".equals(incident.incidentId())) {
            return "id:" + incident.incidentId().toLowerCase(Locale.ROOT);
        }
        return "sig:" + (incident.service() + "|" + incident.signature()).toLowerCase(Locale.ROOT);
    }

    public List<StoredIncident> parseIncidents(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<StoredIncident>();
        var matcher = RECORD.matcher(text);
        while (matcher.find()) {
            var line = matcher.group();
            out.add(new StoredIncident(
                    value(line, "id").orElse("unknown"),
                    value(line, "service").orElse(""),
                    value(line, "signature").orElse(""),
                    value(line, "deployService").orElse(value(line, "service").orElse("")),
                    value(line, "deployVersion").orElse(""),
                    value(line, "rootCause").orElse(""),
                    value(line, "remediation").orElse(""),
                    list(value(line, "scopeServices").orElse("")),
                    value(line, "observedAt").orElse(""),
                    line,
                    0.0,
                    -1.0,
                    "unknown"
            ));
        }
        return List.copyOf(out);
    }

    /**
     * ROUTING_OUTCOME rows, one per line.
     * <p>
     * Handles both shapes deliberately: a per-incident row the agent wrote
     * ({@code escalated=false; validated=true; usd=...}) and a pre-aggregated seed row
     * ({@code attempts=17; escalations=1; successRate=0.94}). See {@link RoutingObservation}
     * for why both exist.
     */
    public List<RoutingObservation> parseRoutingOutcomes(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<RoutingObservation>();
        var matcher = OUTCOME.matcher(text);
        while (matcher.find()) {
            var line = matcher.group();
            var service = value(line, "service").orElse("");
            if (service.isBlank()) {
                continue;
            }
            var tier = tierOf(value(line, "modelTier").orElse(""));
            if (tier == null || tier == ModelTier.NONE) {
                continue; // a short-circuit run says nothing about which tier is sufficient
            }

            int attempts = (int) parseDouble(line, "attempts", 1);
            boolean escalated = flag(line, "escalated");
            int escalations = value(line, "escalations").isPresent()
                    ? (int) parseDouble(line, "escalations", 0)
                    : (escalated ? 1 : 0);

            // "Success" means this tier was sufficient on its own: a validated answer that
            // did not have to escalate. Anything else is evidence the tier was too small.
            int successes;
            if (value(line, "successRate").isPresent()) {
                successes = (int) Math.round(parseDouble(line, "successRate", 0) * attempts);
            } else {
                successes = (!escalated && flag(line, "validated")) ? attempts : 0;
            }

            out.add(new RoutingObservation(
                    value(line, "id").orElse(null),
                    service,
                    tier,
                    attempts,
                    escalations,
                    successes,
                    parseDouble(line, "meanUsd", parseDouble(line, "usd", 0.0)),
                    parseDouble(line, "meanConfidence", parseDouble(line, "confidence", 0.0))
            ));
        }
        return List.copyOf(out);
    }

    /** A curated ROUTING_POLICY line for one service, if the text carries one. */
    public Optional<RoutingPolicy> parseRoutingPolicy(String text, String service, RoutingPolicy.Source source) {
        if (text == null || text.isBlank() || service == null) {
            return Optional.empty();
        }
        var matcher = POLICY.matcher(text);
        while (matcher.find()) {
            var line = matcher.group();
            if (!service.equalsIgnoreCase(value(line, "service").orElse(""))) {
                continue;
            }
            var tier = tierOf(value(line, "preferredTier").orElse(""));
            if (tier == null) {
                continue;
            }
            return Optional.of(new RoutingPolicy(
                    service,
                    tier,
                    parseDouble(line, "confidenceThreshold", 0.75),
                    value(line, "rationale").orElse("Curated routing policy; no rationale recorded."),
                    source,
                    (int) parseDouble(line, "observations", 0),
                    parseDouble(line, "successRate", 0.0),
                    parseDouble(line, "meanUsd", 0.0)
            ));
        }
        return Optional.empty();
    }

    private boolean flag(String line, String key) {
        return value(line, key).map(v -> v.equalsIgnoreCase("true")).orElse(false);
    }

    /** @return null when the text names no tier, so callers can tell "absent" from "LLM". */
    private ModelTier tierOf(String text) {
        var upper = text == null ? "" : text.toUpperCase(Locale.ROOT);
        if (upper.contains("FRONTIER")) return ModelTier.FRONTIER;
        if (upper.contains("SLM")) return ModelTier.SLM;
        if (upper.contains("LLM")) return ModelTier.LLM;
        if (upper.contains("NONE")) return ModelTier.NONE;
        return null;
    }

    // parseTier(String) is gone. It scanned the whole concatenated blob for "preferredTier"
    // and silently defaulted to LLM, so a tier could be picked up from an unrelated record
    // that happened to be in the result window. RoutingPolicyService resolves the tier from a
    // named store instead, and says which store it came from. See tierOf(String) above, which
    // returns null for "absent" rather than guessing.

    public double parseDouble(String text, String key, double fallback) {
        var pattern = Pattern.compile("(?i)" + Pattern.quote(key) + "\\s*[:=]\\s*([0-9]*\\.?[0-9]+)");
        var matcher = pattern.matcher(text == null ? "" : text);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Optional<String> value(String text, String key) {
        var pattern = Pattern.compile("(?i)" + Pattern.quote(key) + "\\s*[:=]\\s*([^;\\n\\r}]+)");
        var matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? Optional.of(matcher.group(1).trim()) : Optional.empty();
    }

    private List<String> list(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
