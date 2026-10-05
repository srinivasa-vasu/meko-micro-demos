package meko.agentic.goap.story.store;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trust scenario: when on, every context-store call fails as if Meko were unreachable. The agents keep running and the
 * planner takes the path an agent without that context would take; each failed call shows up in the receipts.
 */
@Component
public class OutageSwitch {
    private final AtomicBoolean on = new AtomicBoolean();

    public boolean on() {
        return on.get();
    }

    public void set(boolean value) {
        on.set(value);
    }
}
