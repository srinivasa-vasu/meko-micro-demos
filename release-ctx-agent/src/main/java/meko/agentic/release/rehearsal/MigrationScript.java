package meko.agentic.release.rehearsal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A migration file as the rehearsal runs it: one step per statement, in order. A statement ends with a semicolon at the
 * end of a line. Comment lines are dropped, except a directive:
 * <pre>-- @backfill orders.public_ref = uuidv7() batch 20000</pre>
 * which fills a column's existing rows in batches by primary key, as a migration tool's data step would; and
 * <pre>-- @replaces 2</pre>
 * in a rewritten migration: the statements that follow (to the next such line) replace statement 2 of the migration as
 * written, so a review can show each statement next to what it became.
 */
public record MigrationScript(String name, List<Step> steps) {

    public sealed interface Step permits Sql, Backfill {
        String text();

        /** The statement of the migration as written that this step replaces (1-based), or 0. */
        int replaces();
    }

    public record Sql(String text, int replaces) implements Step {
        public Sql(String text) {
            this(text, 0);
        }

        /** Statements PostgreSQL refuses inside a transaction block: they run on their own, never retried mid-way. */
        public boolean concurrently() {
            return text.toUpperCase().matches("(?s).*\\b(CREATE|DROP)\\s+(UNIQUE\\s+)?INDEX\\s+CONCURRENTLY\\b.*");
        }
    }

    public record Backfill(String table, String column, String expression, int batch, int replaces) implements Step {
        public Backfill(String table, String column, String expression, int batch) {
            this(table, column, expression, batch, 0);
        }

        public String text() {
            return "-- backfill " + table + "." + column + " = " + expression + " in batches of " + batch + " rows";
        }
    }

    private static final Pattern BACKFILL = Pattern.compile(
            "--\\s*@backfill\\s+(\\w+)\\.(\\w+)\\s*=\\s*(.+?)\\s+batch\\s+(\\d+)\\s*$");
    private static final Pattern REPLACES = Pattern.compile("--\\s*@replaces\\s+(\\d+)\\s*$");

    public static MigrationScript parse(String name, String sql) {
        List<Step> steps = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int replaces = 0;
        for (String line : sql.lines().toList()) {
            String trimmed = line.strip();
            Matcher replacing = REPLACES.matcher(trimmed);
            if (replacing.matches()) {
                replaces = Integer.parseInt(replacing.group(1));
                continue;
            }
            Matcher backfill = BACKFILL.matcher(trimmed);
            if (backfill.matches()) {
                steps.add(new Backfill(backfill.group(1), backfill.group(2), backfill.group(3), Integer.parseInt(backfill.group(4)), replaces));
                continue;
            }
            if (trimmed.startsWith("--") || trimmed.isEmpty()) continue;
            current.append(current.isEmpty() ? "" : "\n").append(line);
            if (trimmed.endsWith(";")) {
                String statement = current.toString().strip();
                steps.add(new Sql(statement.substring(0, statement.length() - 1).strip(), replaces));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) steps.add(new Sql(current.toString().strip(), replaces));
        return new MigrationScript(name, List.copyOf(steps));
    }
}
