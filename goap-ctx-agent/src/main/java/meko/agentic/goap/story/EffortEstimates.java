package meko.agentic.goap.story;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The effort estimate for every step an agent can take, from {@code estimates.yml}. They are illustrative
 * assumptions, kept in one place with a stated basis so they can be explained and replaced. Which steps run is decided
 * by the planner; the estimates only put minutes on them.
 */
@Component
@ConfigurationProperties(prefix = "goap.effort")
public class EffortEstimates {

    public enum Time {
        /** Someone on your side is working: priced as labour. */
        HANDS_ON,
        /** Elapsed time waiting on someone else (a customer, another team's queue): counted, not priced. */
        WAITING
    }

    public static class Estimate {
        private int minutes;
        private Time time = Time.HANDS_ON;
        private String basis = "";

        public int getMinutes() { return minutes; }
        public void setMinutes(int minutes) { this.minutes = minutes; }
        public Time getTime() { return time; }
        public void setTime(Time time) { this.time = time; }
        public String getBasis() { return basis; }
        public void setBasis(String basis) { this.basis = basis; }

        public boolean waiting() {
            return time == Time.WAITING;
        }
    }

    /** agent → step → estimate. */
    private Map<String, Map<String, Estimate>> estimates = new LinkedHashMap<>();

    public Map<String, Map<String, Estimate>> getEstimates() { return estimates; }
    public void setEstimates(Map<String, Map<String, Estimate>> estimates) { this.estimates = estimates; }

    /** Fails loudly when a step has no estimate, so a number can never be made up at the call site. */
    public Estimate of(String agent, String step) {
        Estimate estimate = estimates.getOrDefault(agent, Map.of()).get(step);
        if (estimate == null) {
            throw new IllegalStateException("No effort estimate for " + agent + "." + step + " in estimates.yml");
        }
        return estimate;
    }
}
