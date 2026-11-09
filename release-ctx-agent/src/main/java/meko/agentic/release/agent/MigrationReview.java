package meko.agentic.release.agent;

import meko.agentic.release.rehearsal.MigrationScript;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the pull request's statements against the rules the knowledge base returned. A rule the agent did not find
 * is not checked: without the standard, nothing here fires.
 */
public final class MigrationReview {
    private MigrationReview() {}

    private record Check(String ruleId, Pattern pattern, String problem) {}

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private static final List<Check> CHECKS = List.of(
            new Check("MIG-02", Pattern.compile("ADD\\s+COLUMN\\b.*\\bDEFAULT\\s+(uuidv7|gen_random_uuid|random|clock_timestamp)\\s*\\(", FLAGS),
                    "a volatile default: every existing row gets its own value, so the whole table is rewritten under ACCESS EXCLUSIVE"),
            new Check("MIG-03", Pattern.compile("ADD\\s+COLUMN\\b.*\\b(CHECK\\s*\\(|REFERENCES\\b)", FLAGS),
                    "a constraint declared inline: it is checked against every row while the table is locked"),
            new Check("MIG-03", Pattern.compile("ADD\\s+(CONSTRAINT\\s+\\w+\\s+)?(FOREIGN\\s+KEY|CHECK)\\b(?!.*NOT\\s+VALID)", FLAGS),
                    "a constraint added without NOT VALID: every row is checked while writes wait"),
            new Check("MIG-03", Pattern.compile("ALTER\\s+COLUMN\\s+\\w+\\s+SET\\s+NOT\\s+NULL", FLAGS),
                    "SET NOT NULL scans every row under ACCESS EXCLUSIVE"),
            new Check("MIG-04", Pattern.compile("CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(?!CONCURRENTLY)", FLAGS),
                    "an index built without CONCURRENTLY: writes are blocked for the whole build"),
            new Check("MIG-04", Pattern.compile("ADD\\s+CONSTRAINT\\s+\\w+\\s+UNIQUE\\s*\\(", FLAGS),
                    "a unique constraint that builds its index while writes are blocked"),
            new Check("MIG-05", Pattern.compile("DROP\\s+INDEX\\s+(?!CONCURRENTLY)", FLAGS),
                    "an index dropped without CONCURRENTLY, and with no proof that no critical query needs it"));

    public static List<ReleaseModels.Finding> review(List<MigrationScript> scripts, ReleaseModels.ReleaseContext context) {
        List<ReleaseModels.Finding> findings = new ArrayList<>();
        for (MigrationScript script : scripts) {
            if (context.knows("MIG-01") && script.steps().stream().noneMatch(s -> s.text().toLowerCase().contains("lock_timeout"))) {
                findings.add(new ReleaseModels.Finding(script.name(), 0, "(the whole script)", "MIG-01",
                        "no lock timeout: any statement may wait for its lock indefinitely, and everything queues behind it"));
            }
            int number = 0;
            for (MigrationScript.Step step : script.steps()) {
                number++;
                for (Check check : CHECKS) {
                    if (context.knows(check.ruleId()) && check.pattern().matcher(step.text()).find()) {
                        findings.add(new ReleaseModels.Finding(script.name(), number, step.text(), check.ruleId(), check.problem()));
                    }
                }
            }
        }
        return findings;
    }
}
