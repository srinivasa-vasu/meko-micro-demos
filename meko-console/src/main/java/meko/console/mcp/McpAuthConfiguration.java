package meko.console.mcp;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Attaches the bearer token to the Meko MCP connection.
 * <p>
 * The customizer's name predicate MUST match the connection key in application.yml, and it has
 * to target the same transport the yml declares. Get either wrong and the header is silently
 * never sent — no error, just a connection that authenticates as nobody.
 */
@Configuration
public class McpAuthConfiguration {

    private final String connectionName;
    private final String apiKey;

    public McpAuthConfiguration(
            @Value("${meko.connection-name:mekodata}") String connectionName,
            @Value("${meko.api-key:}") String apiKey) {
        this.connectionName = connectionName;
        this.apiKey = apiKey;
    }

    @Bean
    McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mekoAuthCustomizer() {
        return (name, builder) -> {
            if (!connectionName.equals(name)) {
                return;
            }
            builder.httpRequestCustomizer((request, method, endpoint, body, context) -> {
                request.header("User-Agent", "meko-console/0.1");
                if (apiKey != null && !apiKey.isBlank()) {
                    request.header("Authorization", "Bearer " + apiKey);
                }
            });
        };
    }
}
