package meko.agentic.release.agent;

import meko.agentic.release.amp.AmpDatabase;
import meko.agentic.release.rehearsal.MigrationScript;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationReviewTest {

    private static ReleaseModels.ReleaseContext knowing(String... rules) {
        return new ReleaseModels.ReleaseContext("run", "PR-1",
                Stream.of(rules).collect(Collectors.toMap(r -> r, r -> new ReleaseModels.Rule(r, r, "test"))),
                null, 0, 0, 0, List.of(), List.of(), List.of());
    }

    private static List<MigrationScript> scripts(String... files) throws Exception {
        return Stream.of(files).map(f -> {
            try {
                return MigrationScript.parse(f, AmpDatabase.read("sql/releases/" + f));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();
    }

    @Test
    void withoutTheStandardNothingIsFound() throws Exception {
        assertThat(MigrationReview.review(scripts(ReleaseRehearsalAgent.AS_WRITTEN.toArray(String[]::new)), knowing())).isEmpty();
    }

    @Test
    void theStandardFindsEveryTrapInThePullRequest() throws Exception {
        var findings = MigrationReview.review(scripts(ReleaseRehearsalAgent.AS_WRITTEN.toArray(String[]::new)),
                knowing("MIG-01", "MIG-02", "MIG-03", "MIG-04", "MIG-05"));
        Map<String, List<Integer>> byRule = findings.stream().filter(f -> f.script().startsWith("release-1"))
                .collect(Collectors.groupingBy(ReleaseModels.Finding::ruleId, Collectors.mapping(ReleaseModels.Finding::statement, Collectors.toList())));
        assertThat(byRule).containsEntry("MIG-02", List.of(2)).containsEntry("MIG-03", List.of(1, 3)).containsEntry("MIG-04", List.of(4))
                .containsEntry("MIG-01", List.of(0));
        Map<String, List<Integer>> release2 = findings.stream().filter(f -> f.script().startsWith("release-2"))
                .collect(Collectors.groupingBy(ReleaseModels.Finding::ruleId, Collectors.mapping(ReleaseModels.Finding::statement, Collectors.toList())));
        assertThat(release2).containsEntry("MIG-03", List.of(1)).containsEntry("MIG-04", List.of(2)).containsEntry("MIG-05", List.of(3));
    }

    @Test
    void theRewrittenMigrationsBreakOnlyTheLockTimeoutRuleTheRunnerApplies() throws Exception {
        var findings = MigrationReview.review(scripts(ReleaseRehearsalAgent.REWRITTEN.toArray(String[]::new)),
                knowing("MIG-02", "MIG-03", "MIG-04", "MIG-05"));
        assertThat(findings).isEmpty();
    }
}
