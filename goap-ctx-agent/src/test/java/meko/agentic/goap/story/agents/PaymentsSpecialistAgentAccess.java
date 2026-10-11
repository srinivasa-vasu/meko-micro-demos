package meko.agentic.goap.story.agents;

/** Test access to the specialist's scope rule (package-private), for tests in other packages. */
public final class PaymentsSpecialistAgentAccess {
    private PaymentsSpecialistAgentAccess() {}

    public static boolean covers(String policyText, PaymentsSpecialistAgent.SettlementCheck check) {
        return PaymentsSpecialistAgent.covers(policyText, check);
    }
}
