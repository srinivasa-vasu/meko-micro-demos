package meko.agentic.release.amp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AmpDatabaseTest {

    @Test
    void seedFileSplitsIntoItsStatements() throws Exception {
        var statements = AmpDatabase.statements(AmpDatabase.read("sql/seed.sql"));
        assertThat(statements).hasSize(4);
        assertThat(statements.getLast()).isEqualTo("ANALYZE customers, stores, orders");
        assertThat(AmpDatabase.statements(AmpDatabase.read("sql/schema.sql"))).hasSize(5);
    }
}
