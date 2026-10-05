package meko.agentic.goap.agent;

import meko.agentic.goap.api.CaseModels.TimelineEvent;

import java.util.ArrayList;
import java.util.List;

public class CaseRunState {
    private final String caseId;
    private final List<TimelineEvent> timeline = new ArrayList<>();
    private int reworkActions;

    public CaseRunState(String caseId) { this.caseId = caseId; }
    public String caseId() { return caseId; }
    public List<TimelineEvent> timeline() { return List.copyOf(timeline); }
    public int reworkActions() { return reworkActions; }
    public void add(TimelineEvent event) { timeline.add(event); }
    public void addRework(int count) { reworkActions += count; }
}
