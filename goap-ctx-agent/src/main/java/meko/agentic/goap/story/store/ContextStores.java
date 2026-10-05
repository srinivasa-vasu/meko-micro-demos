package meko.agentic.goap.story.store;

import meko.agentic.goap.context.MekoMcpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ContextStores {

    /** The three ways a story can run: no durable context, the application's own database, or Meko. */
    public enum Lane {
        WITHOUT, DATABASE, MEKO;

        public static Lane of(String name) {
            return switch (name.toLowerCase()) {
                case "with", "meko" -> MEKO;
                case "db", "database" -> DATABASE;
                default -> WITHOUT;
            };
        }
    }

    private final MekoMcpClient meko;
    private final MekoContextStore live;
    private final LocalContextStore standIn;
    private final LocalContextStore sharedDb;
    private final LocalContextStore otherSystemDb;
    private final NoContextStore none;

    public ContextStores(MekoMcpClient meko, MekoContextStore live,
                         @Qualifier("standInStore") LocalContextStore standIn,
                         @Qualifier("sharedDbStore") LocalContextStore sharedDb,
                         @Qualifier("otherSystemDbStore") LocalContextStore otherSystemDb, NoContextStore none) {
        this.meko = meko;
        this.live = live;
        this.standIn = standIn;
        this.sharedDb = sharedDb;
        this.otherSystemDb = otherSystemDb;
        this.none = none;
    }

    /** Without Meko: no durable context. With Meko: the live datapack, or the stand-in when Meko is not configured. */
    public ContextStore forRun(boolean meko) {
        return forLane(meko ? Lane.MEKO : Lane.WITHOUT);
    }

    public ContextStore forLane(Lane lane) {
        return switch (lane) {
            case WITHOUT -> none;
            case DATABASE -> sharedDb;
            case MEKO -> meko.configured() ? live : standIn;
        };
    }

    public ContextStore byKind(ContextStore.Kind kind) {
        return switch (kind) {
            case NONE -> none;
            case MEKO_LIVE -> live;
            case LOCAL_STAND_IN -> standIn;
            case SHARED_DB -> sharedDb;
            case OTHER_SYSTEM_DB -> otherSystemDb;
        };
    }

    public LocalContextStore standIn() {
        return standIn;
    }
}
