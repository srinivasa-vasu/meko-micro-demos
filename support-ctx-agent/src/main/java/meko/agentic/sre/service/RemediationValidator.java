package meko.agentic.sre.service;

import meko.agentic.sre.domain.Diagnosis;
import meko.agentic.sre.domain.ReuseDecision;
import meko.agentic.sre.domain.ValidationResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks the remediation's stated assumptions against live system state. Zero tokens.
 * <p>
 * This exists because a confidence score is produced by the same model that produced the
 * answer, so it cannot catch the interesting failure: an incident that looks like a known
 * pattern, matches on signature, and is not actually the same incident.
 */
@Service
public class RemediationValidator {

    private static final Pattern VERSION_CLAIM =
            Pattern.compile("(?i)\\b([a-z][a-z0-9-]{2,})\\s+(?:v)?(\\d+\\.\\d+\\.\\d+)\\b");

    private final IncidentDataService incidentData;

    public RemediationValidator(IncidentDataService incidentData) {
        this.incidentData = incidentData;
    }

    public ValidationResult validate(Diagnosis diagnosis) {
        return validate(diagnosis, null);
    }

    /**
     * Validate with the reuse decision in view, so a rollback to the version the gate just ruled
     * stale is rejected.
     * <p>
     * Observed: the gate refused the stored fix because payment-service had moved from 4.7.0 to
     * 5.0.0, and the model — reading that same stale record — answered "roll back to the previous
     * stable release (e.g., 4.7.0)". Refusing to SERVE an answer does not stop the model READING
     * it. Failing validation here demotes confidence below the bar, so the planner escalates with
     * this failure in the prompt, or hands off, instead of finalising a confidently wrong fix.
     */
    public ValidationResult validate(Diagnosis diagnosis, ReuseDecision reuse) {
        if (diagnosis == null) {
            return new ValidationResult(false, java.util.List.of("No diagnosis to validate."), "No diagnosis produced.");
        }

        var failures = new ArrayList<String>();
        for (var assumption : diagnosis.remediationAssumptions()) {
            checkAssumption(assumption, failures);
        }
        // The recommended action itself often names a version; check it too.
        checkAssumption(diagnosis.recommendedAction(), failures);
        checkStaleRollback(diagnosis.recommendedAction(), reuse, failures);

        if (failures.isEmpty()) {
            return ValidationResult.ok();
        }
        return new ValidationResult(false, failures,
                "Remediation rejected: %d assumption(s) do not hold against current state."
                        .formatted(failures.size()));
    }

    private void checkStaleRollback(String action, ReuseDecision reuse, ArrayList<String> failures) {
        if (reuse == null || !reuse.stale() || reuse.match() == null) {
            return;
        }
        var service = reuse.match().deployService();
        var version = reuse.match().deployVersion();
        if (version == null || version.isBlank() || !rollsBackTo(action, version)) {
            return;
        }
        failures.add(("Rolls back to %s, the version stale record %s assumed. %s is deployed now and "
                + "the stored fix was rejected as stale, so that release cannot be assumed to be a "
                + "known-good target.")
                .formatted(version, reuse.match().incidentId(),
                        String.valueOf(incidentData.currentVersion(service))));
    }

    private static final Pattern ROLLBACK =
            Pattern.compile("(?i)\\b(roll(ed|ing)?[\\s-]*back|rollback|revert\\w*|downgrade\\w*)\\b");

    /**
     * Whether an action names this version as where to roll back to: a sentence that both rolls
     * back and names the version, unless it names it as the boundary ("the release before 4.7.0"),
     * which is the correct target. The page's verdict uses the same rule.
     */
    static boolean rollsBackTo(String action, String version) {
        if (action == null || version == null || version.isBlank()) {
            return false;
        }
        var v = Pattern.quote(version);
        var names = Pattern.compile("(?i)(?<![\\w.])v?" + v + "(?![\\w])");
        var boundary = Pattern.compile(
                "(?i)\\b(before|prior to|older than|earlier than|predat\\w*)\\s+(the\\s+)?v?" + v + "(?![\\w])");
        for (var sentence : action.split("(?<=[.;!?])\\s+")) {
            if (ROLLBACK.matcher(sentence).find() && names.matcher(sentence).find()
                    && !boundary.matcher(sentence).find()) {
                return true;
            }
        }
        return false;
    }

    private void checkAssumption(String text, ArrayList<String> failures) {
        if (text == null || text.isBlank()) {
            return;
        }
        var matcher = VERSION_CLAIM.matcher(text);
        while (matcher.find()) {
            var service = matcher.group(1).toLowerCase(Locale.ROOT);
            var version = matcher.group(2);
            if (!incidentData.serviceExists(service)) {
                continue; // not a service we track; nothing to contradict
            }
            var current = incidentData.currentVersion(service);
            if (current != null && !current.equals(version)) {
                failures.add("Claims %s %s, but %s is currently deployed.".formatted(service, version, current));
            }
        }
    }
}
