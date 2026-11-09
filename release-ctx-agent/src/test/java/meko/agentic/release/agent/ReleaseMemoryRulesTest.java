package meko.agentic.release.agent;

import meko.agentic.release.amp.AmpDatabase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseMemoryRulesTest {

    /** A chunk as Meko's knowledge base returned it: inline code stripped, list items run together on one line. */
    private static final String CHUNK = "Section: Release policy: the orders table\n\nOwner: release management.\n\n"
            + "REL-01 Rehearsal. Every migration on  is rehearsed.REL-02 Rollback. The rollback is rehearsed on the same branch."
            + "REL-03 Approval. A release is approved only if no checkout insert stalls for more than 3 s.REL-04 Record. Recorded.";

    @Test
    void everyRuleInAJoinedChunkIsFound() {
        var rules = ReleaseMemory.rules(CHUNK, "release-team");
        assertThat(rules).containsOnlyKeys("REL-01", "REL-02", "REL-03", "REL-04");
        assertThat(rules.get("REL-03").text()).startsWith("REL-03 Approval.").contains("more than 3 s").doesNotContain("REL-04");
        assertThat(rules.get("REL-02").datapack()).isEqualTo("release-team");
    }

    @Test
    void theRewrittenDocumentsStateTheirLimitsInPlainText() throws Exception {
        String standard = AmpDatabase.read("kb/zero-downtime-migration-standard.md");
        String policy = AmpDatabase.read("kb/orders-release-policy.md");
        assertThat(standard).doesNotContain("`");
        assertThat(policy).doesNotContain("`");
        assertThat(ReleaseMemory.rules(standard, "x")).containsOnlyKeys("MIG-01", "MIG-02", "MIG-03", "MIG-04", "MIG-05", "MIG-06");
        assertThat(ReleaseMemory.rules(policy, "x")).containsOnlyKeys("REL-01", "REL-02", "REL-03", "REL-04");
        assertThat(standard).containsPattern("lock_timeout to 2s");
        assertThat(policy).containsPattern("stalls for more than 3 s");
    }

    @Test
    void eachLimitIsReadFromTheRuleThatStatesIt() throws Exception {
        var rules = new java.util.HashMap<>(ReleaseMemory.rules(AmpDatabase.read("kb/zero-downtime-migration-standard.md"), "x"));
        rules.putAll(ReleaseMemory.rules(AmpDatabase.read("kb/orders-release-policy.md"), "x"));
        assertThat(ReleaseMemory.stated(rules, "MIG-01", java.util.regex.Pattern.compile("lock_timeout to (\\d+\\s*m?s)"))).isEqualTo("2s");
        assertThat(ReleaseMemory.stated(rules, "MIG-01", java.util.regex.Pattern.compile("up to (\\d+) attempts"))).isEqualTo("10");
        assertThat(ReleaseMemory.stated(rules, "REL-03", java.util.regex.Pattern.compile("more than (\\d+) times the buffers"))).isEqualTo("10");
        rules.remove("REL-03");
        assertThat(ReleaseMemory.stated(rules, "REL-03", java.util.regex.Pattern.compile("more than (\\d+) s"))).isNull();
    }
}
