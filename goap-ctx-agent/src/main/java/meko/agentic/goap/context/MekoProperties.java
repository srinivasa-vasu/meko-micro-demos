package meko.agentic.goap.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "meko")
public class MekoProperties {
    private String apiKey;
    /** Pins the datapack. Wins over {@link #datapackName} when set. */
    private String datapackId;
    /** Human-readable datapack name, resolved to an id through datapack_list on first use. */
    private String datapackName;
    private String mcpUrl = "https://mcp.mekodata.ai/mcp";
    private boolean enabled;
    /** How this application names itself to the Meko MCP server (spring.application.name). */
    private String clientName = "goap-ctx-agent";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getDatapackId() { return datapackId; }
    public void setDatapackId(String datapackId) { this.datapackId = datapackId; }
    public String getDatapackName() { return datapackName; }
    public void setDatapackName(String datapackName) { this.datapackName = datapackName; }
    public String getMcpUrl() { return mcpUrl; }
    public void setMcpUrl(String mcpUrl) { this.mcpUrl = mcpUrl; }
    public boolean isEnabled() { return enabled; }
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** A key is set, so a datapack can be created or chosen at run time. */
    public boolean keyed() {
        return enabled && notBlank(apiKey);
    }

    public boolean configured() {
        return enabled && notBlank(apiKey) && (notBlank(datapackId) || notBlank(datapackName));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
