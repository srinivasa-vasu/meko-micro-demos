package meko.agentic.goap.context;

import org.springframework.stereotype.Component;

@Component
public class ContextEngines {
    private final MekoProperties properties;
    private final MekoContextEngine meko;
    private final NoMekoContextEngine baseline;

    public ContextEngines(MekoProperties properties, MekoContextEngine meko, NoMekoContextEngine baseline) {
        this.properties = properties;
        this.meko = meko;
        this.baseline = baseline;
    }

    public ContextEngine forMode(boolean requestedMeko) {
        return requestedMeko && properties.configured() ? meko : baseline;
    }

    public boolean mekoAvailable() {
        return properties.configured() && meko.isConnected();
    }
}
