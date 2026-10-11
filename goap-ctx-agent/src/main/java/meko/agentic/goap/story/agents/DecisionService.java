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
import java.util.Optional;
import java.util.function.Predicate;
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

    /** What a decision read, as kept with it: enough to tell which retrieved items were in its context. */
    static String excerpt(String context) {
        return context == null ? "" : context.length() <= 4000 ? context : context.substring(0, 4000);
    }

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
        return decide(caseId, persona, template, vars, context, type, rule, a -> true, ai);
    }

    /**
     * As {@link #decide(String, String, String, Map, String, Class, Supplier, Ai)}, with a check of the model's answer
     * against the context: an answer that fails it (a fact not in the context, a verdict the record contradicts) is
     * not used; the rule decides instead, and the decision records why.
     */
    public <T> T decide(String caseId, String persona, String template, Map<String, Object> vars, String context,
                        Class<T> type, Supplier<T> rule, Predicate<T> valid, Ai ai) {
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
                if (!valid.test(answer)) {
                    T ruled = rule.get();
                    ledger.decision(new StoryLedger.Decision(persona, gate, question, json.writeValueAsString(ruled),
                            "rule (" + model + "'s answer did not match the context: " + abbreviate(json.writeValueAsString(answer)) + ")", contextSummary, true, excerpt(context)));
                    return ruled;
                }
                ledger.decision(new StoryLedger.Decision(persona, gate, question, json.writeValueAsString(answer), model, contextSummary, true, excerpt(context)));
                return answer;
            } catch (RuntimeException e) {
                T answer = rule.get();
                ledger.decision(new StoryLedger.Decision(persona, gate, question, json.writeValueAsString(answer),
                        "rule (" + model + " failed: " + abbreviate(e.getMessage()) + ")", contextSummary, false, excerpt(context)));
                return answer;
            }
        }
        T answer = rule.get();
        ledger.decision(new StoryLedger.Decision(persona, gate, question, json.writeValueAsString(answer),
                tiers.enabled() ? "rule (" + tiers.status(DECIDER_TIER).diagnosis() + ")" : "rule", contextSummary, false, excerpt(context)));
        return answer;
    }

    /**
     * A decision a deterministic check settles: decided by the rule, with no model call, and recorded like any other
     * decision (the question, the answer and that a rule decided it), so it is traced the same way.
     */
    public <T> T decideByRule(String caseId, String persona, String template, Map<String, Object> vars, String context,
                              Supplier<T> rule, String check) {
        String prompt = prompt(templates, caseId, template, vars, context);
        String contextSummary = context.isBlank() ? "no context" : (context.length() / 4) + " tokens of context";
        T answer = rule.get();
        support.ledger(caseId).decision(new StoryLedger.Decision(persona, template.substring(template.lastIndexOf('/') + 1), question(prompt),
                json.writeValueAsString(answer), "rule (" + check + ")", contextSummary, false, excerpt(context)));
        return answer;
    }

    /**
     * Rule first: when {@code clear} settles the decision it is decided by rule, with no model call; only when it cannot
     * (an ambiguous case) does the model decide, as in {@link #decide}.
     */
    public <T> T decideUnlessClear(String caseId, String persona, String template, Map<String, Object> vars, String context,
                                   Class<T> type, Supplier<Optional<T>> clear, String check, Supplier<T> rule, Ai ai) {
        return decideUnlessClear(caseId, persona, template, vars, context, type, clear, check, rule, a -> true, ai);
    }

    public <T> T decideUnlessClear(String caseId, String persona, String template, Map<String, Object> vars, String context,
                                   Class<T> type, Supplier<Optional<T>> clear, String check, Supplier<T> rule, Predicate<T> valid, Ai ai) {
        Optional<T> settled = clear.get();
        return settled.isPresent() ? decideByRule(caseId, persona, template, vars, context, settled::get, check)
                : decide(caseId, persona, template, vars, context, type, rule, valid, ai);
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
