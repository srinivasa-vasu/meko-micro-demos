package meko.agentic.release.amp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "amp")
public class AmpProperties {
    /** Uses this cluster of the datapack in use, if set; otherwise one is chosen or created from the page. */
    private String clusterId;
    /** The cluster's admin password, when it was not created by this application (AMP shows it only once). */
    private String password;
    /** The database the checkout schema lives in. */
    private String database = "checkout";
    private int seedCustomers = 100_000;
    private int seedOrders = 1_000_000;
    /** How long to wait for a new branch endpoint to answer. */
    private int readySeconds = 420;

    public String getClusterId() { return clusterId; }
    public void setClusterId(String clusterId) { this.clusterId = clusterId; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public int getSeedCustomers() { return seedCustomers; }
    public void setSeedCustomers(int seedCustomers) { this.seedCustomers = seedCustomers; }
    public int getSeedOrders() { return seedOrders; }
    public void setSeedOrders(int seedOrders) { this.seedOrders = seedOrders; }
    public int getReadySeconds() { return readySeconds; }
    public void setReadySeconds(int readySeconds) { this.readySeconds = readySeconds; }
}
