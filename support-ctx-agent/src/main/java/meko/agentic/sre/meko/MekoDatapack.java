package meko.agentic.sre.meko;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves which datapack this demo reads and writes.
 * <p>
 * A datapack id is an opaque UUID. Nobody can tell from
 * {@code 6205bfaf-fdc7-4dd4-b558-003a7237e4b5} which workspace they are about to seed, and on
 * a laptop with several demo datapacks that is how you end up writing an SRE corpus into
 * somebody else's. The name is the thing a human actually knows, so the name is what the demo
 * is configured with; the id is looked up once at startup and cached.
 * <p>
 * An explicit {@code meko.datapack-id} still wins when set — CI and scripted runs want to pin
 * an id and not depend on a name lookup succeeding.
 * <p>
 * Resolution is never fatal. If neither route produces an id, {@link #configured()} is false
 * and the agent plans the cold path, exactly as it does when Meko is switched off.
 */
@Component
public class MekoDatapack {

    private static final Logger log = LoggerFactory.getLogger(MekoDatapack.class);

    private final MekoToolBridge meko;
    private final String configuredId;
    private final String configuredName;

    private volatile String resolvedId;
    /** Kept so /api/demo/meko/status can explain a failure instead of just reporting one. */
    private volatile String diagnosis = "not attempted";

    public MekoDatapack(
            MekoToolBridge meko,
            @Value("${meko.datapack-id:}") String configuredId,
            @Value("${meko.datapack-name:}") String configuredName) {
        this.meko = meko;
        this.configuredId = configuredId == null ? "" : configuredId.trim();
        this.configuredName = configuredName == null ? "" : configuredName.trim();
    }

    @PostConstruct
    void resolve() {
        if (!configuredId.isBlank()) {
            resolvedId = configuredId;
            diagnosis = "pinned by meko.datapack-id";
            log.info("Meko datapack pinned by id: {}", resolvedId);
            return;
        }
        if (configuredName.isBlank()) {
            diagnosis = "neither meko.datapack-name nor meko.datapack-id is set";
            log.warn("Neither meko.datapack-name nor meko.datapack-id is set. "
                    + "The agent will run the cold path on every incident.");
            return;
        }
        attempt("startup");
    }

    /**
     * @return the datapack id, or "" when unresolved. Callers should gate on
     *         {@link #configured()} rather than checking for blank.
     */
    public String id() {
        // Startup ordering is the usual reason this is still null: MekoDatapack's @PostConstruct
        // can run before the MCP client has finished connecting, so getToolCallbacks() is empty
        // and there is nothing to ask. Retry on every use until it succeeds rather than leaving
        // the demo permanently cold because of a slow handshake.
        if (resolvedId == null && !configuredName.isBlank()) {
            synchronized (this) {
                if (resolvedId == null) {
                    attempt("on use");
                }
            }
        }
        return resolvedId == null ? "" : resolvedId;
    }

    private void attempt(String when) {
        if (!meko.available()) {
            diagnosis = "no MCP tool callbacks are registered (" + when
                    + ") — the Meko connection did not come up. Check spring.ai.mcp.client and MEKO_API_KEY.";
            log.warn("Cannot resolve datapack '{}' {}: no MCP tool callbacks are registered. "
                    + "Check spring.ai.mcp.client and MEKO_API_KEY.", configuredName, when);
            return;
        }
        var tools = meko.registeredTools();
        if (tools.stream().noneMatch(t -> t.equals("datapack_list") || t.endsWith("_datapack_list"))) {
            diagnosis = "datapack_list is not among the " + tools.size()
                    + " registered MCP tools, so a name cannot be resolved. Pin meko.datapack-id instead. "
                    + "Registered: " + String.join(", ", tools);
            log.warn("Cannot resolve datapack '{}' {}: datapack_list is not registered. Registered tools: {}",
                    configuredName, when, tools);
            return;
        }
        meko.resolveDatapackId(configuredName).ifPresentOrElse(
                id -> {
                    resolvedId = id;
                    diagnosis = "resolved by name " + when;
                    log.info("Meko datapack '{}' resolved to {} ({})", configuredName, id, when);
                },
                () -> {
                    diagnosis = "datapack_list ran but returned no datapack named '" + configuredName
                            + "'. Visible names are in the logs; check spelling and which key you are using.";
                    log.warn("Meko datapack '{}' was not found {}. The agent will run the cold path. "
                            + "Pin meko.datapack-id to bypass the lookup.", configuredName, when);
                });
    }

    /** Why resolution is in the state it is. For the status endpoint, not for logic. */
    public String diagnosis() {
        return diagnosis;
    }

    public String configuredName() {
        return configuredName;
    }

    public boolean configured() {
        return !id().isBlank();
    }

    /** For display: the name if we have one, otherwise the id. */
    public String label() {
        return configuredName.isBlank() ? id() : configuredName;
    }
}
