package meko.agentic.sre.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much telemetry the two investigation paths read.
 *
 * <h2>Why this is configurable, and why the default is not</h2>
 * The full sweep has to be genuinely large or the token delta is theatre — 60 lines across 20
 * services is already conservative next to a real 2am incident. That is why {@link #FULL_DEFAULT}
 * is what it is, and why nothing here lowers it on your behalf.
 *
 * <p>It is configurable because a provider rate limit can make the honest number unrunnable. Groq's
 * free tier allows 8,000 tokens per minute; the full sweep sends about 32,000 in one request and
 * comes back 413. That rejection lands on the cold run — half of the comparison the demo exists to
 * make — so without a knob the free tier cannot show the thing at all.
 *
 * <h2>The rule that keeps a reduced sweep honest</h2>
 * Every figure the demo reports is measured from the run that actually happened, so a smaller
 * sweep produces a smaller, still-true saving. The danger is not the arithmetic, it is the
 * caption: a 270-line sweep presented as if it were the 1,200-line one overstates what memory
 * saved. So a reduced sweep is {@link #reduced() reported as reduced}, on screen and in the log,
 * rather than left for the audience to assume.
 *
 * @param linesPerService       lines per service on the full sweep, across all 20 services.
 * @param scopedLinesPerService lines per service once history has named the ones that matter.
 */
@ConfigurationProperties(prefix = "incident.sweep")
public record SweepProperties(Integer linesPerService, Integer scopedLinesPerService) {

    /** Conservative next to a real incident, which is the right direction to err in. */
    public static final int FULL_DEFAULT = 60;

    public static final int SCOPED_DEFAULT = 8;

    public SweepProperties {
        linesPerService = atLeastOne(linesPerService, FULL_DEFAULT, "incident.sweep.lines-per-service");
        scopedLinesPerService = atLeastOne(
                scopedLinesPerService, SCOPED_DEFAULT, "incident.sweep.scoped-lines-per-service");
    }

    public static SweepProperties full() {
        return new SweepProperties(FULL_DEFAULT, SCOPED_DEFAULT);
    }

    /** True when the sweep has been cut to fit something, which the demo has to say out loud. */
    public boolean reduced() {
        return linesPerService < FULL_DEFAULT;
    }

    /**
     * The sentence the UI shows when the sweep is reduced. Written for the room, not the log:
     * it names the real default so nobody has to guess what was given up.
     */
    public String disclosure() {
        if (!reduced()) {
            return "";
        }
        return "Full sweep reduced to " + linesPerService + " lines per service (default "
                + FULL_DEFAULT + ") to fit a provider rate limit. Every figure below is measured "
                + "from the run that actually happened, so the saving is real but smaller than an "
                + "unthrottled run would show.";
    }

    private static int atLeastOne(Integer value, int fallback, String property) {
        if (value == null) {
            return fallback;
        }
        if (value < 1) {
            throw new IllegalArgumentException(property + " is " + value + "; a sweep that reads "
                    + "nothing would make the comparison meaningless rather than cheap");
        }
        return value;
    }
}
