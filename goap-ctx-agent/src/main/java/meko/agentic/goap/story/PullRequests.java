package meko.agentic.goap.story;

import meko.agentic.goap.story.store.ContextStore;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The simulated pull-request system of story 4: whether a reviewer's request on a pull request is still open. It is
 * the ground truth the simulated reviewer checks; agents learn about it only through what is written to context.
 * Kept per lane (by the lane's context store), because each lane is its own copy of the world and lanes run together.
 */
@Component
public class PullRequests {
    private final Set<String> addressed = ConcurrentHashMap.newKeySet();

    public boolean requestOpen(ContextStore.Kind lane, String pr) {
        return !addressed.contains(lane + "|" + pr);
    }

    public void address(ContextStore.Kind lane, String pr) {
        addressed.add(lane + "|" + pr);
    }

    /** Reopens every request, for a new round of the story. */
    public void reopenAll() {
        addressed.clear();
    }
}
