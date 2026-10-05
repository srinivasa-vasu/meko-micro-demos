package meko.agentic.sre.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.PlannerType;

/**
 * Two actions, no models, no Meko, no cost methods. Its only job is to settle how THIS
 * version of Embabel links a producer to a consumer, so the incident agent can be built on
 * a verified fact instead of a fourth guess.
 * <p>
 * Run it, then read the planner log. Four outcomes, four different conclusions:
 * <p>
 *   A. Runs end to end
 *      → producer/consumer linking by type works with default bindings, and a named
 *        condition in `pre` is fine even when nothing posts it. The incident agent's problem
 *        is somewhere else entirely — look at the @Cost methods next.
 * <p>
 *   B. Stuck, History (0)
 *      → even this cannot plan. The `post`/`pre` contract is stricter than assumed: comment
 *        out `"probeReady"` from the goal's `pre` and run again. If it then works, every named
 *        condition used in `pre` MUST be posted by some action.
 * <p>
 *   B'. Stuck, History (1) — makeMiddle ran, then nothing
 *      → posting is not enough. A posted name is only an assumption the planner makes; it is
 *        never written to the blackboard, so it must ALSO be computable at execution time via
 *        a @Condition method or a `spel:` prefix. This is the one the incident agent hit.
 * <p>
 *   C. Runs only when ENABLE_BINDINGS is flipped on
 *      → named bindings are required, and every consumer parameter needs a matching name
 *        annotation. Put outputBinding back everywhere and annotate the parameters.
 * <p>
 *   D. Fails at startup
 *      → the annotation surface itself differs in your version. The stack trace will name
 *        which annotation, which is the fastest possible answer.
 * <p>
 * Delete this package once you have the answer.
 */
@Agent(planner = PlannerType.GOAP, description = "Minimal probe: does a two-action chain plan at all?")
public class PlannerProbeAgent {

	public record ProbeInput(String text) {}

	public record ProbeMiddle(String text) {}

	public record ProbeOutput(String text) {}

	@Action(
			description = "Produce the middle value",
			post = {"middleReady"},
			cost = 0.01,
			value = 0.9
	)
	public ProbeMiddle makeMiddle(ProbeInput input) {
		return new ProbeMiddle("middle:" + input.text());
	}

	/**
	 * Consumes ProbeMiddle by type. If this never runs, type-based linking is not what the
	 * planner uses, and the answer is outcome C.
	 */
	@AchievesGoal(description = "Finish the probe")
	@Action(
			// Toggle: remove "probeReady" to test outcome B.
			pre = {"middleReady", "probeReady"},
			post = {"probeDone"},
			cost = 0.01,
			value = 1.0
	)
	public ProbeOutput finish(ProbeMiddle middle) {
		return new ProbeOutput("done:" + middle.text());
	}

	/**
	 * Backs the effect makeMiddle posts. Without this the probe reproduces the incident
	 * agent's original bug rather than testing for it: makeMiddle runs, ProbeMiddle is bound,
	 * and middleReady is still recomputed as FALSE because `post` is a planner assumption,
	 * not a blackboard write.
	 */
	@Condition(name = "middleReady")
	public boolean middleReady(ProbeMiddle middle) {
		return middle != null;
	}

	/**
	 * A named condition that NOTHING posts. If the goal becomes unreachable purely because of
	 * this, that is the rule the incident agent tripped over.
	 */
	@Condition(name = "probeReady")
	public boolean probeReady(ProbeMiddle middle) {
		return middle != null;
	}
}