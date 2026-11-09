package meko.agentic.release.amp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/** A Postgres endpoint on an AMP branch: where to connect, as whom, to which database. */
public record Endpoint(String host, int port, String user, String password, String database) {

    /** From an AMP address, {@code host:port}. */
    public static Endpoint of(String address, String user, String password, String database) {
        int colon = address.lastIndexOf(':');
        return new Endpoint(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)), user, password, database);
    }

    public Endpoint database(String other) {
        return new Endpoint(host, port, user, password, other);
    }

    public String address() {
        return host + ":" + port;
    }

    /**
     * An autocommit connection, named so the rehearsal's own sessions can be told apart in {@code pg_stat_activity}.
     * AMP's external endpoints require TLS. The host is resolved by {@link HostResolver}, so a name that is not
     * published yet fails fast without leaving "not found" in the system's DNS cache.
     */
    public Connection connect(String applicationName) throws Exception {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("sslmode", "require");
        props.setProperty("ApplicationName", applicationName);
        props.setProperty("connectTimeout", "20");
        String target = HostResolver.connectAddress(host);
        Connection connection = DriverManager.getConnection("jdbc:postgresql://" + target + ":" + port + "/" + database, props);
        connection.setAutoCommit(true);
        return connection;
    }
}
