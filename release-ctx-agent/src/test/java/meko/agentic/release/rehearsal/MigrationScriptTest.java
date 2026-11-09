package meko.agentic.release.rehearsal;

import meko.agentic.release.amp.AmpDatabase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationScriptTest {

    @Test
    void splitsStatementsAndKeepsTheBackfillDirective() throws Exception {
        MigrationScript script = MigrationScript.parse("release-1-safe.sql", AmpDatabase.read("sql/releases/release-1-safe.sql"));
        assertThat(script.steps()).hasSize(10);
        assertThat(script.steps().get(5)).isEqualTo(new MigrationScript.Backfill("orders", "public_ref", "uuidv7()", 20000));
        assertThat(script.steps().getLast()).isInstanceOfSatisfying(MigrationScript.Sql.class, sql -> assertThat(sql.concurrently()).isTrue());
        assertThat(script.steps()).noneMatch(s -> s.text().contains("--") && s instanceof MigrationScript.Sql);
    }

    @Test
    void aMultiLineStatementIsOneStep() throws Exception {
        MigrationScript script = MigrationScript.parse("release-1-as-written.sql", AmpDatabase.read("sql/releases/release-1-as-written.sql"));
        assertThat(script.steps()).hasSize(4);
        assertThat(script.steps().getFirst().text()).contains("CHECK (fulfillment_type IN ('SHIP', 'PICKUP'))").doesNotEndWith(";");
        assertThat(script.steps()).noneMatch(s -> s instanceof MigrationScript.Sql sql && sql.concurrently());
    }
}
