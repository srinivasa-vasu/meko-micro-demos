package meko.agentic.goap.story.agents;

import com.embabel.agent.api.common.Ai;
import com.embabel.common.textio.template.TemplateRenderer;
import meko.agentic.goap.model.LlmTiers;
import meko.agentic.goap.model.ModelCallLog;
import meko.agentic.goap.story.StoryLedger;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The judgement calls at the gates of each story ("which facts does the specialist already know?", "does this learned
 * pattern apply?") are made by a model reading only the context the agent actually has. The planner's conditions are
 * derived from the answer, so what the agents do next follows from what Meko returned rather than from hand-written
 * branches.
 * <p>
 * Prompts are Jinja templates: {@code prompts/decision.jinja} is the shared wrapper and each gate's question and
 * answer shape live in {@code prompts/decisions/<gate>.jinja}, which extends it.
 * <p>
 * The decision runs on the small tier. When no model is available, or its answer cannot be parsed, a deterministic
 * rule decides instead, and the decision record says so.
 */
@Component
public class DecisionService {
    static final String DECIDER_TIER = "slm";
    private static final Pattern QUESTION = Pattern.compile("Question: (.*?)\\s*Context:", Pattern.DOTALL);

    private final LlmTiers tiers;
    private final StorySupport support;
    private final TemplateRenderer templates;
    private final ObjectMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public DecisionService(LlmTiers tiers, StorySupport support, TemplateRenderer templates) {
        this.tiers = tiers;
        this.support = support;
        this.templates = templates;
    }

    /**
     * @param template  the gate's template, e.g. "decisions/support-known-facts"
     * @param vars      variables the gate's template uses (beyond caseTag, decisionId and context)
     * @param context   everything the agent knows at this point, and nothing else
     * @param rule      deterministic fallback over the same context
     */
    public <T> T decide(String caseId, String persona, String template, Map<String, Object> vars, String context,
                        Class<T> type, Supplier<T> rule, Ai ai) {
        StoryLedger ledger = support.ledger(caseId);
        String prompt = prompt(templates, caseId, template, vars, context);
        String question = question(prompt);
        String gate = template.substring(template.lastIndexOf('/') + 1);
        String contextSummary = context.isBlank() ? "no context" : (context.length() / 4) + " tokens of context";
        if (tiers.enabled() && tiers.available(DECIDER_TIER) && ai != null) {
            String model = tiers.tier(DECIDER_TIER).getModel();
            try {
                String reply = ai.withLlmByRole(DECIDER_TIER).generateText(prompt);
                T answer = json.readValue(extractJson(reply), type);
                ledger.decision(new StoryLedger.Decision(persona, gate, question, json.writeValueAsString(answer), model, contextSummary, true));
                return answer;
            } catch (RuntimeException e) {
                T answer = rule.get();
                ledger.decision(new StoryLedger.Decision(persona, gate, question, json.writeValueAsString(answer),
                        "rule (" + model + " failed: " + abbreviate(e.getMessage()) + ")", contextSummary, false));
                return answer;
            }
        }
        T answer = rule.get();
        ledger.decision(new StoryLedger.Decision(persona, gate, question, json.writeValueAsString(answer),
                "rule (" + (tiers.enabled() ? tiers.status(DECIDER_TIER).diagnosis() : "model calls disabled") + ")", contextSummary, false));
        return answer;
    }

    static String prompt(TemplateRenderer templates, String caseId, String template, Map<String, Object> vars, String context) {
        Map<String, Object> model = new HashMap<>(vars);
        model.put("caseTag", ModelCallLog.caseTag(caseId));
        model.put("decisionId", template.substring(template.lastIndexOf('/') + 1));
        model.put("context", context.isBlank() ? "(nothing)" : context);
        return templates.renderLoadedTemplate(template, model);
    }

    /** The question as rendered, for the decision record shown in the UI. */
    static String question(String prompt) {
        Matcher m = QUESTION.matcher(prompt);
        return m.find() ? m.group(1).strip() : "";
    }

    /** The JSON object in a reply that may carry prose or code fences around it. */
    static String extractJson(String reply) {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("no JSON object in reply: " + abbreviate(reply));
        return reply.substring(start, end + 1);
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }
}
