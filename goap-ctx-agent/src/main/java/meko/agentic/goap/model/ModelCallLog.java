package meko.agentic.goap.model;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import meko.agentic.goap.story.RunEvents;

/**
 * Every model call, with the token counts the provider reported. Calls are attributed to a run by the case tag
 * the writer puts at the top of each prompt, because Embabel may run the model call on another thread.
 */
@Component
public class ModelCallLog {
    private static final Pattern CASE_TAG = Pattern.compile("\\[case ([A-Za-z0-9-]+)]");

    /** @param usd list-price cost of the call (free-plan calls are not billed) */
    public record ModelCall(String tier, String provider, String model, Integer promptTokens, Integer completionTokens,
                            double usd, long millis, boolean ok, String error) {}

    private final Map<String, List<ModelCall>> calls = new ConcurrentHashMap<>();
    private final RunEvents events;

    public ModelCallLog(RunEvents events) {
        this.events = events;
    }

    public static String caseTag(String caseId) {
        return "[case " + caseId + "]";
    }

    public void record(String promptText, ModelCall call) {
        Matcher m = CASE_TAG.matcher(promptText);
        String caseId = m.find() ? m.group(1) : "untagged";
        calls.computeIfAbsent(caseId, k -> Collections.synchronizedList(new ArrayList<>())).add(call);
        events.publish(caseId, "model", call);
    }

    public List<ModelCall> forCase(String caseId) {
        List<ModelCall> list = calls.get(caseId);
        if (list == null) return List.of();
        synchronized (list) {
            return List.copyOf(list);
        }
    }
}
