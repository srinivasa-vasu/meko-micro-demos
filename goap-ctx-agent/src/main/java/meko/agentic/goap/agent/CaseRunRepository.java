package meko.agentic.goap.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CaseRunRepository {
    private final Map<String, CaseRunState> runs = new ConcurrentHashMap<>();

    public CaseRunState create(String caseId) {
        CaseRunState state = new CaseRunState(caseId);
        runs.put(caseId, state);
        return state;
    }

    public CaseRunState get(String caseId) {
        return runs.get(caseId);
    }
}
