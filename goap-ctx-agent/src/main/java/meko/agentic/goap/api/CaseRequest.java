package meko.agentic.goap.api;

public record CaseRequest(Boolean mekoEnabled, Boolean simulateTakeover) {
    public boolean useMeko() {
        return Boolean.TRUE.equals(mekoEnabled);
    }

    public boolean simulateTakeoverRequested() {
        return simulateTakeover == null || simulateTakeover;
    }
}
