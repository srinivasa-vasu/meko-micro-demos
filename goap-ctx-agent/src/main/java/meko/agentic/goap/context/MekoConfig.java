package meko.agentic.goap.context;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MekoProperties.class)
public class MekoConfig {
}
