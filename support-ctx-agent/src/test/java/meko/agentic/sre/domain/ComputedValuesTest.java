package meko.agentic.sre.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import meko.agentic.sre.config.TokenProperties;
import meko.agentic.sre.service.TokenAccounting;
import org.junit.jupiter.api.Test;

/**
 * Values the page shows must come from the run, not from constants. These pin the two places
 * where a computed value replaced an assumed one and a mistake would be silent.
 */
class ComputedValuesTest {

    /**
     * withActionsTaken rebuilds a sixty-field record by hand. A swapped pair of same-typed
     * fields would compile and quietly show one figure under another's label, so every
     * component is given a distinct value and checked to survive the copy.
     */
    @Test
    void attachingTheExecutedActionsChangesNothingElse() throws Exception {
        var components = IncidentReport.class.getRecordComponents();
        var values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            values[i] = distinct(components[i], i);
        }
        var types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new);
        var original = IncidentReport.class.getDeclaredConstructor(types).newInstance(values);

        var copy = original.withActionsTaken(List.of("retrieveMekoContext", "evaluateReuse"));

        for (var c : components) {
            if (c.getName().equals("actionsTaken")) {
                assertEquals(List.of("retrieveMekoContext", "evaluateReuse"), c.getAccessor().invoke(copy));
            } else {
                assertEquals(c.getAccessor().invoke(original), c.getAccessor().invoke(copy),
                        c.getName() + " changed when the actions were attached");
            }
        }
    }

    @Test
    void theCounterfactualUsesTheRunsOwnCompletionLength() {
        var accounting = new TokenAccounting(new TokenProperties(Map.of(), Map.of(),
                new TokenProperties.Reuse(0.85, 0.55), new TokenProperties.Routing(5, 0.85, false),
                new TokenProperties.Projection(2_000)));
        var prompt = "x".repeat(380);   // 100 estimated input tokens

        var observed = new TokenLedger();
        accounting.recordCounterfactual(observed, prompt, "frontier", 1_234L);
        assertEquals(100 + 1_234, observed.counterfactualTokens());

        var servedFromMemory = new TokenLedger();
        accounting.recordCounterfactual(servedFromMemory, prompt, "frontier", null);
        assertEquals(100 + 600, servedFromMemory.counterfactualTokens(),
                "no completion of its own to measure, so the typical length is the fallback");
    }

    private static Object distinct(RecordComponent c, int i) {
        var t = c.getType();
        if (t == String.class) return "s" + i;
        if (t == int.class) return i + 1;
        if (t == long.class) return (long) i + 1;
        if (t == double.class) return i + 0.5;
        if (t == boolean.class) return i % 2 == 0;
        if (t == List.class) return List.of("item" + i);
        if (t.isEnum()) return t.getEnumConstants()[i % t.getEnumConstants().length];
        throw new IllegalStateException("no distinct value for " + c.getName() + " of " + t);
    }
}
