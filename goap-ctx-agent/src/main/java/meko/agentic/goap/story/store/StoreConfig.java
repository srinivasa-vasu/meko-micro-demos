package meko.agentic.goap.story.store;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StoreConfig {

    /** Meko stand-in for running without network; seeded with the organizational context at startup. */
    @Bean
    LocalContextStore standInStore(ReceiptLog receipts, OutageSwitch outage) {
        return new LocalContextStore(receipts, outage, ContextStore.Kind.LOCAL_STAND_IN);
    }

    /** The application's own database: never seeded with what other tools and systems wrote. */
    @Bean
    LocalContextStore sharedDbStore(ReceiptLog receipts, OutageSwitch outage) {
        return new LocalContextStore(receipts, outage, ContextStore.Kind.SHARED_DB);
    }

    /** Another system's own store: the vendor chatbot, the payments team's application, the EU ops team's application. */
    @Bean
    LocalContextStore otherSystemDbStore(ReceiptLog receipts, OutageSwitch outage) {
        return new LocalContextStore(receipts, outage, ContextStore.Kind.OTHER_SYSTEM_DB);
    }
}
