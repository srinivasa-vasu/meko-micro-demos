package meko.agentic.release.rehearsal;

import meko.agentic.release.amp.Endpoint;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The incident on {@code orders}, and its repair. The nightly job marks every paid order pending; checkout keeps
 * writing. The repair compares main with a fork from before the job and puts back exactly the rows the job changed.
 */
public final class IncidentSql {
    private IncidentSql() {}

    public static final String JOB = "nightly-reprice";
    /** The value the job's defect writes: the status every row it changes ends up with. */
    public static final String JOB_VALUE = "PENDING";
    /** The rows the job's defect selects: every paid order. */
    public static final String JOB_SELECTS = "PAID";

    public record Position(String lsn, String at) {}

    public static Position position(Connection connection) throws Exception {
        try (ResultSet rs = connection.createStatement().executeQuery(
                "SELECT pg_current_wal_lsn()::text, to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS')")) {
            rs.next();
            return new Position(rs.getString(1), rs.getString(2));
        }
    }

    /** The bad job: every paid order to pending. Returns the rows it changed. */
    public static int runBadJob(Connection connection) throws Exception {
        return connection.createStatement().executeUpdate("UPDATE orders SET status = '" + JOB_VALUE + "', updated_at = now() WHERE status = '" + JOB_SELECTS + "'");
    }

    /**
     * Checkout, after the job: new orders, some paid, some pending. They must survive the repair untouched. Returns
     * what was written, by status, as the database reports it.
     */
    public static Map<String, Integer> checkoutContinues(Connection connection, int paid, int pending) throws Exception {
        Map<String, Integer> out = new java.util.TreeMap<>();
        try (ResultSet rs = connection.createStatement().executeQuery(
                "WITH written AS (INSERT INTO orders (customer_id, status, amount) SELECT 1 + (random() * 999)::int, s, 30.00 FROM ("
                        + "SELECT 'PAID' AS s FROM generate_series(1, " + paid + ") UNION ALL SELECT 'PENDING' FROM generate_series(1, " + pending + ")) x "
                        + "RETURNING status) SELECT status, count(*) FROM written GROUP BY status")) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
        }
        return out;
    }

    /** Orders by status: the before-and-after picture that shows a repair touched only what it should. */
    public static Map<String, Integer> statusCounts(Connection connection) throws Exception {
        Map<String, Integer> out = new java.util.TreeMap<>();
        try (ResultSet rs = connection.createStatement().executeQuery("SELECT status, count(*) FROM orders GROUP BY status")) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
        }
        return out;
    }

    public static Map<String, Integer> statusCounts(Endpoint endpoint) throws Exception {
        try (Connection connection = endpoint.connect("recovery:counts")) {
            return statusCounts(connection);
        }
    }

    /** What a critical query returns: each row, its columns joined, in the query's order. */
    public static List<String> rows(Endpoint endpoint, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection connection = endpoint.connect("recovery:check");
             ResultSet rs = connection.createStatement().executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) row.append(i > 1 ? "|" : "").append(rs.getString(i));
                out.add(row.toString());
            }
        }
        return out;
    }

    /** The rows holding the job's value now: the damaged ones among them. */
    public static List<Long> pendingIds(Endpoint endpoint) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Connection connection = endpoint.connect("recovery:diff");
             ResultSet rs = connection.createStatement().executeQuery("SELECT id FROM orders WHERE status = '" + JOB_VALUE + "'")) {
            while (rs.next()) ids.add(rs.getLong(1));
        }
        return ids;
    }

    /** Each of {@code ids} as it was on the fork: absent ids were created after the fork's position. */
    public static Map<Long, String> statusOn(Endpoint fork, List<Long> ids) throws Exception {
        Map<Long, String> out = new LinkedHashMap<>();
        try (Connection connection = fork.connect("recovery:diff");
             PreparedStatement statement = connection.prepareStatement("SELECT id, status FROM orders WHERE id = ANY(?)")) {
            Array array = connection.createArrayOf("bigint", ids.toArray());
            statement.setArray(1, array);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) out.put(rs.getLong(1), rs.getString(2));
            }
        }
        return out;
    }

    /**
     * The repair: each damaged row back to its value before the job, and only while it still holds the job's value
     * (a row someone changed since is left for a person).
     */
    public static int repair(Endpoint endpoint, Map<Long, String> before) throws Exception {
        try (Connection connection = endpoint.connect("recovery:repair");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE orders o SET status = v.status, updated_at = now() FROM unnest(?::bigint[], ?::text[]) AS v(id, status) "
                             + "WHERE o.id = v.id AND o.status = '" + JOB_VALUE + "'")) {
            statement.setArray(1, connection.createArrayOf("bigint", before.keySet().toArray()));
            statement.setArray(2, connection.createArrayOf("text", before.values().toArray()));
            return statement.executeUpdate();
        }
    }

    /** How many of the damaged rows hold their value from before the job. */
    public static int restored(Endpoint endpoint, Map<Long, String> before) throws Exception {
        try (Connection connection = endpoint.connect("recovery:check");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM orders o JOIN unnest(?::bigint[], ?::text[]) AS v(id, status) ON o.id = v.id WHERE o.status = v.status")) {
            statement.setArray(1, connection.createArrayOf("bigint", before.keySet().toArray()));
            statement.setArray(2, connection.createArrayOf("text", before.values().toArray()));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public static int count(Endpoint endpoint, String where) throws Exception {
        try (Connection connection = endpoint.connect("recovery:check");
             ResultSet rs = connection.createStatement().executeQuery("SELECT count(*) FROM orders WHERE " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
