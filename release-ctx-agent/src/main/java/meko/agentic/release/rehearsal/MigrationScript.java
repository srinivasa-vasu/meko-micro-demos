package meko.agentic.release.rehearsal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A migration file as the rehearsal runs it: one step per statement, in order. A statement ends with a semicolon at the
 * end of a line. Comment lines are dropped, except a directive:
 * <pre>-- @backfill orders.public_ref = uuidv7() batch 20000</pre>
 * which fills a column's existing rows in batches by primary key, as a migration tool's data step would.
 */
public record MigrationScript(String name, List<Step> steps) {

    public sealed interface Step permits Sql, Backfill {
        String text();
    }

    public record Sql(String text) implements Step {
        /** Statements PostgreSQL refuses inside a transaction block: they run on their own, never retried mid-way. */
        public boolean concurrently() {
            return text.toUpperCase().matches("(?s).*\\b(CREATE|DROP)\\s+(UNIQUE\\s+)?INDEX\\s+CONCURRENTLY\\b.*");
        }
    }

    public record Backfill(String table, String column, String expression, int batch) implements Step {
        public String text() {
            return "-- backfill " + table + "." + column + " = " + expression + " in batches of " + batch + " rows";
        }
    }

    private static final Pattern BACKFILL = Pattern.compile(
            "--\\s*@backfill\\s+(\\w+)\\.(\\w+)\\s*=\\s*(.+?)\\s+batch\\s+(\\d+)\\s*$");

    public static MigrationScript parse(String name, String sql) {
        List<Step> steps = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.lines().toList()) {
            String trimmed = line.strip();
            Matcher backfill = BACKFILL.matcher(trimmed);
            if (backfill.matches()) {
                steps.add(new Backfill(backfill.group(1), backfill.group(2), backfill.group(3), Integer.parseInt(backfill.group(4))));
                continue;
            }
            if (trimmed.startsWith("--") || trimmed.isEmpty()) continue;
            current.append(current.isEmpty() ? "" : "\n").append(line);
            if (trimmed.endsWith(";")) {
                String statement = current.toString().strip();
                steps.add(new Sql(statement.substring(0, statement.length() - 1).strip()));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) steps.add(new Sql(current.toString().strip()));
        return new MigrationScript(name, List.copyOf(steps));
    }
}
