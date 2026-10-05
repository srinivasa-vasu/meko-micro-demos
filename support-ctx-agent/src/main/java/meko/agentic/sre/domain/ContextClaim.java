package meko.agentic.sre.domain;

import java.util.List;

/**
 * A checkable claim about what CONTEXT did to the answer.
 * <p>
 * The scenarios already make the reuse decision falsifiable, and that verdict caught a bug that
 * would otherwise have been demoed. This applies the same discipline to the harder claim: that
 * the model produced a better answer because it was given better material.
 * <p>
 * CHECKED COMPARATIVELY, and that is the whole design. An absolute "the warm answer contains
 * 4.7.0" proves nothing — a model can guess a version, and a reader has no way to tell whether
 * it did. The claim that means something is "present with context, absent without it", checked
 * against two real runs of the same incident. It is self-calibrating: if the cold run says it
 * too, the claim reports itself as not distinctive rather than passing on a technicality.
 */
public record ContextClaim(
        /* What this demonstrates, in one line, for the room. */
        String label,
        /* Any of these appearing in the answer counts as present. Matched case-insensitively. */
        List<String> anyOf,
        /*
          None of these may appear. The "did not do the wrong thing" claim.

          Needed because the clearest thing a runbook does is RULE OPTIONS OUT. Observed in the
          ablation: the memory-only run offered "roll back, or increase the timeout to >5s" while
          the run with the runbook offered only the rollback — because the runbook says not to fix
          forward. No positive token catches that; the difference is an absence.
         */
        List<String> noneOf,
        /*
          May appear, but not before the first anyOf match. The "this first, that only as a
          fallback" claim.

          Needed because a rule that permits something conditionally cannot be checked by
          banning it. The runbook says not to fix forward UNLESS a rollback is unavailable; an
          answer that rolls back and names the hotfix as the fallback follows it exactly, and a
          noneOf failed it for mentioning the hotfix at all. Order is what separates "roll back;
          if you cannot, raise the timeout" from "raise the timeout, or roll back".
         */
        List<String> notBefore,
        /* Which context source should have supplied it. */
        String source) {

    public ContextClaim {
        anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
        noneOf = noneOf == null ? List.of() : List.copyOf(noneOf);
        notBefore = notBefore == null ? List.of() : List.copyOf(notBefore);
    }

    /** A positive-only claim, which is most of them. */
    public ContextClaim(String label, List<String> anyOf, String source) {
        this(label, anyOf, List.of(), List.of(), source);
    }

    /** Present-and-absent, with no ordering. */
    public ContextClaim(String label, List<String> anyOf, List<String> noneOf, String source) {
        this(label, anyOf, noneOf, List.of(), source);
    }

    public boolean presentIn(String answer) {
        if (answer == null || (anyOf.isEmpty() && noneOf.isEmpty())) {
            return false;
        }
        var haystack = answer.toLowerCase(java.util.Locale.ROOT);
        var required = anyOf.isEmpty()
                || anyOf.stream().anyMatch(t -> haystack.contains(t.toLowerCase(java.util.Locale.ROOT)));
        var forbidden = noneOf.stream().anyMatch(t -> haystack.contains(t.toLowerCase(java.util.Locale.ROOT)));
        return required && !forbidden && ordered(haystack);
    }

    /** True when nothing in notBefore appears ahead of the first anyOf match. */
    private boolean ordered(String haystack) {
        if (notBefore.isEmpty()) {
            return true;
        }
        int early = firstIndex(haystack, notBefore);
        if (early < 0) {
            return true;
        }
        int wanted = firstIndex(haystack, anyOf);
        return wanted >= 0 && wanted < early;
    }

    private static int firstIndex(String haystack, List<String> terms) {
        return terms.stream()
                .mapToInt(t -> haystack.indexOf(t.toLowerCase(java.util.Locale.ROOT)))
                .filter(i -> i >= 0)
                .min()
                .orElse(-1);
    }

    /** The outcome of checking one claim across both runs. */
    public record Result(ContextClaim claim, boolean inWarm, boolean inCold) {
        /** Context supplied something the run without it did not have. */
        public boolean distinctive() {
            return inWarm && !inCold;
        }

        public String verdict() {
            if (inWarm && !inCold) {
                return "only with context";
            }
            if (inWarm) {
                return "both runs — not distinctive";
            }
            return inCold ? "only without context" : "neither run";
        }
    }
}
