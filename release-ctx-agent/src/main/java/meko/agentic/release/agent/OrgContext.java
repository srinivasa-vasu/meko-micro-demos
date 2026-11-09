package meko.agentic.release.agent;

import org.springframework.stereotype.Component;

/**
 * The organization's shared datapack the release agent reads besides its own: where other teams' agents (platform SRE,
 * warehouse, checkout) keep what they know. Usually owned by someone else and shared with the release team in the Meko
 * app; reading is enough. Not connected until chosen on the page (or with {@code MEKO_SHARED_DATAPACK}).
 */
@Component
public class OrgContext {
    /** The shared datapack: its id and name, this account's grant on it, and who owns it. */
    public record Shared(String id, String name, String grant, String owner) {
        public boolean owned() {
            return "owner".equals(grant);
        }
    }

    private volatile Shared shared;

    public Shared shared() {
        return shared;
    }

    public void connect(Shared datapack) {
        shared = datapack;
    }

    public void disconnect() {
        shared = null;
    }
}
