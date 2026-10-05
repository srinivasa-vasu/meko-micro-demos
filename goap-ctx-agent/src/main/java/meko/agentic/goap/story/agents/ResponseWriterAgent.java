package meko.agentic.goap.story.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Cost;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PlannerType;
import meko.agentic.goap.model.LlmTierProperties;
import meko.agentic.goap.model.LlmTiers;
import meko.agentic.goap.model.ModelCallLog;
import meko.agentic.goap.story.StoryLedger;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Writes the story's human-facing text (a customer reply, a handover note, a review rationale...) from whatever
 * context the story's agents ended up with. Which model writes it is a planning decision: three alternative
 * actions, one per tier, priced by Embabel cost methods.
 * <p>
 * Cost = price of the tier (its estimated list-price cost for this prompt) + risk. Risk grows with each gap the model would have to reason around instead of
 * restating grounded facts, and small models carry more of it. With rich context there are no gaps and the small
 * model is cheapest; with gaps, a stronger model becomes the cheapest safe choice. A tier that is not usable right
 * now (no key, endpoint down, model not pulled) is priced out, so the planner routes around it.
 */
@Agent(planner = PlannerType.GOAP, description = "Writes the human-facing text for a case, routed to the cheapest sufficient model tier")
public class ResponseWriterAgent {

	/**
	 * @param facts          grounded facts the writer may use
	 * @param gaps           things the writer does not know and must not invent
	 * @param fallbackText   used when model calls are disabled or the model is unavailable
	 */
	public record WritingTask(String caseId, String persona, String purpose, String instructions,
							  List<String> facts, List<String> gaps, String fallbackText) { }

	/**
	 * @param priced      the cost the planner assigned to each tier for this task
	 * @param unavailable the tiers priced out because they are not usable right now
	 */
	public record Draft(String caseId, String tier, String model, String text, boolean generated,
	                    java.util.Map<String, Double> priced, List<String> unavailable) { }

	static final double UNAVAILABLE = 0.95;

	private final StorySupport support;
	private final LlmTiers tiers;

	public ResponseWriterAgent(StorySupport support, LlmTiers tiers) {
		this.support = support;
		this.tiers = tiers;
	}

	@Cost(name = "slmWriteCost")
	public double slmWriteCost(@Nullable WritingTask task) {
		return cost("slm", 0.30, task);
	}

	@Cost(name = "llmWriteCost")
	public double llmWriteCost(@Nullable WritingTask task) {
		return cost("llm", 0.12, task);
	}

	@Cost(name = "frontierWriteCost")
	public double frontierWriteCost(@Nullable WritingTask task) {
		return cost("frontier", 0.0, task);
	}

	/** Price (estimated list-price cost of the call, from LlmTiers) + risk per gap the model must reason around. */
	private double cost(String tier, double riskPerGap, @Nullable WritingTask task) {
		// With model calls disabled the writer uses a template, but routing is still planned on price and risk.
		if (tiers.enabled() && !tiers.available(tier)) return UNAVAILABLE;
		int gaps = task == null ? 0 : task.gaps().size();
		int promptTokens = task == null ? 400 : prompt(task).length() / 4;
		return Math.min(UNAVAILABLE - 0.01, tiers.plannerPrice(tier, promptTokens) + riskPerGap * gaps);
	}

	@AchievesGoal(description = "The case text is written")
	@Action(description = "Write with the small model: enough when every fact is grounded", costMethod = "slmWriteCost", canRerun = false)
	public Draft writeWithSlm(WritingTask task, @Provided Ai ai) {
		return write(task, "slm", ai);
	}

	@AchievesGoal(description = "The case text is written")
	@Action(description = "Write with the mid-size model: some reasoning around gaps", costMethod = "llmWriteCost", canRerun = false)
	public Draft writeWithLlm(WritingTask task, @Provided Ai ai) {
		return write(task, "llm", ai);
	}

	@AchievesGoal(description = "The case text is written")
	@Action(description = "Write with the frontier model: careful reasoning when context is missing", costMethod = "frontierWriteCost", canRerun = false)
	public Draft writeWithFrontier(WritingTask task, @Provided Ai ai) {
		return write(task, "frontier", ai);
	}

	private Draft write(WritingTask task, String tier, Ai ai) {
		StoryLedger ledger = support.ledger(task.caseId());
		LlmTierProperties.Tier config = tiers.tier(tier);
		String label = config.getModel() + " (" + tier + ")";
		String text;
		boolean generated = false;
		if (!tiers.enabled()) {
			text = task.fallbackText();
			ledger.step(task.persona(), "write", "Model calls disabled: used the template for the " + task.purpose() + ".", support.effort("response-writer", "write"), StoryLedger.Kind.WORK);
		}
		else if (!tiers.available(tier)) {
			text = task.fallbackText();
			ledger.step(task.persona(), "write", "No model tier available (" + tiers.status(tier)
					.diagnosis() + "): used the template.", support.effort("response-writer", "write"), StoryLedger.Kind.WORK);
		}
		else {
			try {
				// Small models sometimes echo the attribution tag that leads the prompt.
				text = ai.withLlmByRole(tier).generateText(prompt(task)).strip()
						.replaceFirst("^\\[case [^]]+]\\s*", "");
				generated = !text.isBlank();
				if (!generated) text = task.fallbackText();
				ledger.step(task.persona(), "write", "Wrote the " + task.purpose() + " with " + label + ".", support.effort("response-writer", "write"), StoryLedger.Kind.WORK);
			}
			catch (RuntimeException e) {
				text = task.fallbackText();
				ledger.step(task.persona(), "write", label + " failed (" + e.getMessage() + "): used the template.", support.effort("response-writer", "write"), StoryLedger.Kind.WORK);
			}
		}
		ledger.note(task.persona() + " · " + (generated ? label : "template"), text);
		ledger.count("gapsForWriter", task.gaps().size());
		java.util.Map<String, Double> priced = new java.util.LinkedHashMap<>();
		priced.put("slm", round(slmWriteCost(task)));
		priced.put("llm", round(llmWriteCost(task)));
		priced.put("frontier", round(frontierWriteCost(task)));
		List<String> unavailable = priced.keySet().stream().filter(t -> tiers.enabled() && !tiers.available(t)).toList();
		return new Draft(task.caseId(), tier, config.getModel(), text, generated, priced, unavailable);
	}

	private static double round(double value) {
		return Math.round(value * 100) / 100.0;
	}

	/** Renders {@code prompts/writer.jinja}; the story-specific instructions were rendered from {@code prompts/writer/}. */
	String prompt(WritingTask task) {
		java.util.Map<String, Object> model = new java.util.HashMap<>();
		model.put("caseTag", ModelCallLog.caseTag(task.caseId()));
		model.put("persona", task.persona());
		model.put("purpose", task.purpose());
		model.put("instructions", task.instructions());
		model.put("facts", task.facts());
		model.put("gaps", task.gaps());
		return support.render("writer", model);
	}
}
