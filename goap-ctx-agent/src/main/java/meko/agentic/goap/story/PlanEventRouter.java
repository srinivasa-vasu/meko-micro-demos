package meko.agentic.goap.story;

import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embabel 1.5.1 sends plan, goal and stuck events only to platform-level listeners (Spring beans), not to
 * the per-process listeners in ProcessOptions. This bean receives them and routes each to the recorder
 * registered for its process.
 */
@Component
public class PlanEventRouter implements AgenticEventListener {
    private final Map<String, PlanRecorder> recorders = new ConcurrentHashMap<>();

    public void register(String processId, PlanRecorder recorder) {
        recorders.put(processId, recorder);
    }

    public void unregister(String processId) {
        recorders.remove(processId);
    }

    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        PlanRecorder recorder = recorders.get(event.getProcessId());
        if (recorder != null) recorder.onProcessEvent(event);
    }
}
