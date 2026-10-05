package meko.agentic.sre.meko;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;

import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MekoMcpConfiguration {

	private final String connectionName;
	private final String apiKey;

	public MekoMcpConfiguration(
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
				request.header("User-Agent", "sre-agent/0.2");
				if (apiKey != null && !apiKey.isBlank()) {
					request.header("Authorization", "Bearer " + apiKey);
				}
			});
		};
	}
}
