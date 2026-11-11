package meko.agentic.client;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The Meko client for any application that depends on this module: its properties ({@code meko.*}) and one client.
 * Registered through Spring Boot's auto-configuration, so applications in other packages need no scan settings.
 */
@AutoConfiguration
@EnableConfigurationProperties(MekoProperties.class)
public class MekoClientAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public MekoMcpClient mekoMcpClient(MekoProperties properties) {
        return new MekoMcpClient(properties);
    }
}
