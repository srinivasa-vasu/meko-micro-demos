package meko.agentic.client;

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
    private String clientName = "meko-client";
    /**
     * The agent id that owns this application's record conversations. conversation_add_message must use the agent_id
     * that created a conversation, so each application keeps its own (its records then never mix with another's).
     */
    private String conversationOwner = "records";
    /**
     * Per-tool time limits ({@code meko.timeouts.[memory_add]: 90s}), over the client's own defaults. A Meko write
     * runs a server-side extraction and can take well over 30 s; an application that writes in bulk raises it here.
     */
    private java.util.Map<String, java.time.Duration> timeouts = new java.util.HashMap<>();

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
    public java.util.Map<String, java.time.Duration> getTimeouts() { return timeouts; }
    public void setTimeouts(java.util.Map<String, java.time.Duration> timeouts) { this.timeouts = timeouts; }
    public String getConversationOwner() { return conversationOwner; }
    public void setConversationOwner(String conversationOwner) { this.conversationOwner = conversationOwner; }
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
