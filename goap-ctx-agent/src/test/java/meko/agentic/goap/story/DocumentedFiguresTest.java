package meko.agentic.goap.story;

import meko.agentic.goap.story.store.ContextStore;
import meko.agentic.goap.story.store.ContextStores;
import meko.agentic.goap.story.store.ContextStores.Lane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The README tables and the slide deck quote figures from the stories. They are written by hand, so this runs every story
 * in every lane and fails when a quoted figure no longer matches what the planner and estimates.yml produce.
 */
@SpringBootTest(properties = {"meko.enabled=false", "goap.llm.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentedFiguresTest {

    /** A story, the README's title for it, the deck's case id, and the count it leads with. */
    record Documented(String storyId, String title, String deckId, String count) {}

    static final List<Documented> STORIES = List.of(
            new Documented("dont-make-me-explain", "Don't Make Me Explain It Again", "support", "customerQuestions"),
            new Documented("agent-died-2am", "The Agent Died at 2 AM", "incident", "repeatedSteps"),
            new Documented("what-changed", "What Changed Since Last Review?", "kyc", "evidenceRequests"),
            new Documented("vacation-catch-up", "I Was on Vacation. Catch Me Up.", "catchup", "missedRequirements"),
            new Documented("organization-learned", "The Organization Learned", "learned", null),
            new Documented("loan-underwriting", "Same Proof, Twice", "loan", "borrowerReexplained"));

    static final List<Lane> LANES = List.of(Lane.WITHOUT, Lane.DATABASE, Lane.MEKO);
    static final int SERIES_CASES = 3;

    @Autowired
    StoryService stories;

    @Autowired
    EffortEstimates efforts;

    @Autowired
    ContextStores stores;

    @Autowired
    StoryCatalog catalog;

    /** Each story's follow-up per lane (story id → lane → metrics), run after that lane's case. */
    final Map<String, Map<Lane, Map<String, Integer>>> followUps = new LinkedHashMap<>();

    /** The learning story's last case per lane: the steady state the deck projects from. */
    final Map<Lane, Map<String, Integer>> steadyState = new LinkedHashMap<>();
    /** The learning series in the database lane, case by case: hypotheses each case tested. */
    final List<Integer> databaseHypotheses = new ArrayList<>();

    /** What actually ran: story id → lane → metric → value, summed over the series for the learning story. */
    final Map<String, Map<Lane, Map<String, Integer>>> measured = new LinkedHashMap<>();

    @BeforeAll
    void runEveryStory() {
        // The KYC story writes its result back; the documented figures are a review compared with last quarter's.
        stories.reset("what-changed");
        stories.reset("vacation-catch-up");
        stories.reset("loan-underwriting");
        for (Documented d : STORIES) {
            Map<Lane, Map<String, Integer>> lanes = new LinkedHashMap<>();
            if (d.count() == null) {
                var series = stories.learningSeries(SERIES_CASES, true);
                steadyState.put(Lane.WITHOUT, totals(List.of(series.get("withoutMeko").getLast())));
                steadyState.put(Lane.MEKO, totals(List.of(series.get("withMeko").getLast())));
                lanes.put(Lane.WITHOUT, totals(series.get("withoutMeko")));
                lanes.put(Lane.DATABASE, totals(series.get("database")));
                series.get("database").forEach(r -> databaseHypotheses.add(totals(List.of(r)).getOrDefault("hypothesesTested", 0)));
                lanes.put(Lane.MEKO, totals(series.get("withMeko")));
            } else {
                for (Lane lane : LANES) lanes.put(lane, totals(List.of(stories.run(d.storyId(), lane))));
            }
            measured.put(d.storyId(), lanes);
        }
        for (StoryCatalog.Story story : catalog.all()) {
            if (story.followUp() == null) continue;
            Map<Lane, Map<String, Integer>> lanes = new LinkedHashMap<>();
            for (Lane lane : LANES) {
                if (stories.lastSubject(story.id(), lane).isEmpty()) stories.run(story.id(), lane);
                lanes.put(lane, totals(List.of(stories.followUp(story.id(), lane, stories.newFollowUpCaseId(story.id())))));
            }
            followUps.put(story.id(), lanes);
        }
    }

    @Test
    void readmeFollowUpFiguresMatchTheRuns() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("README.md"));
        assertThat(followUps).isNotEmpty();
        followUps.forEach((storyId, lanes) -> {
            String label = catalog.get(storyId).followUp().label();
            List<String> cells = row(lines, "| **" + label + "**");
            // | follow-up | without | your own database | with Meko |
            for (int i = 0; i < LANES.size(); i++) {
                Map<String, Integer> run = lanes.get(LANES.get(i));
                assertThat(minutesIn(cells.get(1 + i))).as("%s, %s minutes in the README", label, LANES.get(i)).isEqualTo(run.get("minutes"));
                assertThat(firstNumber(cells.get(1 + i))).as("%s, %s records requested in the README", label, LANES.get(i))
                        .isEqualTo(run.getOrDefault("recordsRequested", 0));
                assertThat(number(cells.get(1 + i), "(\\d+) decision trace")).as("%s, %s decision traces in the README", label, LANES.get(i))
                        .isEqualTo(run.getOrDefault("decisionTraces", 0));
            }
        });
    }

    @Test
    void readmeComparisonTableMatchesTheRuns() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("README.md"));
        for (Documented d : STORIES) {
            List<String> cells = row(lines, "| **" + d.title() + "**");
            // | story | boundary | without | your own database | with Meko |
            for (int i = 0; i < LANES.size(); i++) {
                String cell = cells.get(2 + i);
                Map<String, Integer> run = measured.get(d.storyId()).get(LANES.get(i));
                assertThat(minutesIn(cell)).as("%s, %s minutes in the README", d.title(), LANES.get(i)).isEqualTo(run.get("minutes"));
                if (d.count() != null) {
                    assertThat(firstNumber(cell)).as("%s, %s %s in the README", d.title(), LANES.get(i), d.count()).isEqualTo(run.getOrDefault(d.count(), 0));
                }
                // Rulings written into a cell ("2 rulings requested", "1 ruling") are the run's.
                Matcher rulings = Pattern.compile("(\\d+) rulings?").matcher(cell);
                if (rulings.find()) {
                    assertThat(Integer.parseInt(rulings.group(1))).as("%s, %s rulings in the README", d.title(), LANES.get(i)).isEqualTo(run.getOrDefault("rulingsRequested", 0));
                }
            }
        }
    }

    @Test
    void readmeDatabaseLaneLearnsAgainOnCase2ThenReusesOnCase3() throws IOException {
        // "with your own database ... it learns the lesson again on case 2, then reuses it on case 3"
        assertThat(Files.readString(Path.of("README.md"))).contains("learns the lesson again\non case 2, then reuses it on case 3");
        assertThat(databaseHypotheses.get(1)).as("case 2 in the database lane tests hypotheses").isPositive();
        assertThat(databaseHypotheses.get(2)).as("case 3 in the database lane reuses its own lesson").isZero();
    }

    @Test
    void readmeEffortTableMatchesTheRuns() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("README.md"));
        Pattern split = Pattern.compile("(\\d+)(?: \\((\\d+) hands-on, (\\d+) waiting\\))?");
        for (Documented d : STORIES) {
            List<String> cells = row(lines, "| " + d.title() + " ");
            // | story | without | with Meko | kind of saving |
            Lane[] lanes = {Lane.WITHOUT, Lane.MEKO};
            for (int i = 0; i < lanes.length; i++) {
                Map<String, Integer> run = measured.get(d.storyId()).get(lanes[i]);
                Matcher m = split.matcher(cells.get(1 + i));
                assertThat(m.find()).isTrue();
                assertThat(Integer.parseInt(m.group(1))).as("%s, %s minutes in the effort table", d.title(), lanes[i]).isEqualTo(run.get("minutes"));
                if (m.group(2) != null) {
                    assertThat(Integer.parseInt(m.group(2))).as("%s, %s hands-on minutes", d.title(), lanes[i]).isEqualTo(run.get("handsOnMinutes"));
                    assertThat(Integer.parseInt(m.group(3))).as("%s, %s waiting minutes", d.title(), lanes[i]).isEqualTo(run.get("waitingMinutes"));
                }
            }
        }
    }

    @Test
    void deckAuditTraceMatchesARealRecord() throws IOException {
        String deck = Files.readString(Path.of("assets/overview.html"));
        int start = deck.indexOf("const AUDIT_TRACE = [");
        String block = deck.substring(start, deck.indexOf("];", start));
        List<String> writers = new ArrayList<>(), gates = new ArrayList<>();
        Matcher m = Pattern.compile("\\['([^']+)', '(?:[^'\\\\]|\\\\.)*', '([^']*)'\\]").matcher(block);
        while (m.find()) {
            writers.add(m.group(1));
            if (!m.group(2).isEmpty()) gates.add(m.group(2));
        }
        // The record of the last support case and its audit, with Meko: what conversation_get by transaction id returns.
        String transaction = stories.lastSubject("dont-make-me-explain", Lane.MEKO).orElseThrow();
        List<ContextStore.Turn> record = stores.forLane(Lane.MEKO).transcript("figures", "figures", transaction);
        List<String> recordGates = record.stream().map(ContextStore.Turn::decisionGate).flatMap(java.util.Optional::stream).toList();
        assertThat(collapse(writers)).as("writers in the deck's record, in order").isEqualTo(collapse(record.stream().map(ContextStore.Turn::writtenBy).toList()));
        assertThat(gates).as("decision traces in the deck's record, in order").isEqualTo(recordGates);
    }

    /** Consecutive duplicates removed: the deck condenses a party's turns. */
    private static List<String> collapse(List<String> items) {
        List<String> out = new ArrayList<>();
        for (String i : items) if (out.isEmpty() || !out.getLast().equals(i)) out.add(i);
        return out;
    }

    @Test
    void deckFollowUpFiguresMatchTheRuns() throws IOException {
        String deck = Files.readString(Path.of("assets/overview.html"));
        Map<String, Lane> keys = Map.of("without", Lane.WITHOUT, "db", Lane.DATABASE, "with", Lane.MEKO);
        followUps.forEach((storyId, lanes) -> {
            int start = deck.indexOf("'" + storyId + "': {title:");
            assertThat(start).as("deck FOLLOW_UPS entry for %s", storyId).isNotNegative();
            String block = deck.substring(start, deck.indexOf("}}", start) + 2);
            for (var key : keys.entrySet()) {
                Matcher m = Pattern.compile("\\b" + key.getKey() + ": \\{label: '[^']*', requests: (\\d+), traces: (\\d+), min: (\\d+)\\}").matcher(block);
                assertThat(m.find()).as("deck %s %s", storyId, key.getKey()).isTrue();
                Map<String, Integer> run = lanes.get(key.getValue());
                assertThat(Integer.parseInt(m.group(1))).as("%s, %s record requests in the deck", storyId, key.getValue()).isEqualTo(run.getOrDefault("recordsRequested", 0));
                assertThat(Integer.parseInt(m.group(2))).as("%s, %s decision traces in the deck", storyId, key.getValue()).isEqualTo(run.getOrDefault("decisionTraces", 0));
                assertThat(Integer.parseInt(m.group(3))).as("%s, %s minutes in the deck", storyId, key.getValue()).isEqualTo(run.get("minutes"));
            }
        });
    }

    @Test
    void deckFiguresMatchTheRuns() throws IOException {
        String deck = Files.readString(Path.of("assets/overview.html"));
        for (Documented d : STORIES) {
            String block = deckCase(deck, d.deckId());
            Map<Lane, Map<String, Integer>> run = measured.get(d.storyId());
            assertThat(number(block, "db: \\{text: '[^']*', min: (\\d+)")).as("%s, database minutes in the deck", d.deckId())
                    .isEqualTo(run.get(Lane.DATABASE).get("minutes"));
            // The figures written into the database lane's text: its headline count, any rulings, and its minutes.
            Matcher dbText = Pattern.compile("db: \\{text: '([^']*)'").matcher(block);
            assertThat(dbText.find()).isTrue();
            String text = dbText.group(1);
            Map<String, Integer> db = run.get(Lane.DATABASE);
            assertThat(number(text, "^(\\d+)")).as("%s, database lane's leading figure in the deck", d.deckId())
                    .isEqualTo(d.count() == null ? db.get("minutes") : db.getOrDefault(d.count(), 0));
            Matcher rulings = Pattern.compile("(\\d+) rulings?").matcher(text);
            if (rulings.find()) assertThat(Integer.parseInt(rulings.group(1))).as("%s, database rulings in the deck", d.deckId()).isEqualTo(db.getOrDefault("rulingsRequested", 0));
            Matcher dbMin = Pattern.compile("(\\d+) min").matcher(text);
            if (dbMin.find()) assertThat(Integer.parseInt(dbMin.group(1))).as("%s, database minutes in the deck's text", d.deckId()).isEqualTo(db.get("minutes"));
            for (var lane : Map.of("without", Lane.WITHOUT, "with", Lane.MEKO).entrySet()) {
                Matcher m = Pattern.compile("\\b" + lane.getKey() + ": \\{plan: .*?num: '(\\d+)', unit: '([^']*)', min: (\\d+)(?:, wait: (\\d+))?", Pattern.DOTALL).matcher(block);
                assertThat(m.find()).as("%s %s in the deck", d.deckId(), lane.getKey()).isTrue();
                Map<String, Integer> metrics = run.get(lane.getValue());
                assertThat(Integer.parseInt(m.group(3))).as("%s, %s minutes in the deck", d.deckId(), lane.getKey()).isEqualTo(metrics.get("minutes"));
                // Minutes written into the unit label ("customer re-asks · 56 min") are the same run's minutes.
                Matcher unitMin = Pattern.compile("(\\d+) min").matcher(m.group(2));
                if (unitMin.find()) assertThat(Integer.parseInt(unitMin.group(1))).as("%s, %s minutes in the unit label", d.deckId(), lane.getKey())
                        .isEqualTo(metrics.get("minutes"));
                assertThat(Integer.parseInt(m.group(1))).as("%s, %s headline number in the deck", d.deckId(), lane.getKey())
                        .isEqualTo(metrics.getOrDefault(d.count() == null ? "minutes" : d.count(), 0));
                if (m.group(4) != null) {
                    assertThat(Integer.parseInt(m.group(4))).as("%s, %s waiting minutes in the deck", d.deckId(), lane.getKey()).isEqualTo(metrics.get("waitingMinutes"));
                }
            }
        }
    }

    @Test
    void deckStepFiguresMatchTheEstimatesAndRuns() throws IOException {
        String deck = Files.readString(Path.of("assets/overview.html"));
        assertThat(number(deck, "Validate the lesson: (\\d+) min")).isEqualTo(efforts.of("payment-failure", "validate-known-pattern").getMinutes());
        assertThat(number(deck, "Dig through chat and wiki: (\\d+) min")).isEqualTo(efforts.of("catch-up", "reconstruct-manually").getMinutes());
        Map<Lane, Map<String, Integer>> support = measured.get("dont-make-me-explain");
        Matcher note = Pattern.compile("hands-on time is (\\d+) min either way: the saving is the customer.s waiting, (\\d+) → (\\d+) min").matcher(deck);
        assertThat(note.find()).isTrue();
        assertThat(Integer.parseInt(note.group(1))).isEqualTo(support.get(Lane.WITHOUT).get("handsOnMinutes")).isEqualTo(support.get(Lane.MEKO).get("handsOnMinutes"));
        assertThat(Integer.parseInt(note.group(2))).isEqualTo(support.get(Lane.WITHOUT).get("waitingMinutes"));
        assertThat(Integer.parseInt(note.group(3))).isEqualTo(support.get(Lane.MEKO).get("waitingMinutes"));
        assertThat(number(deck, "perCase = \\{learned: (\\d+)\\}")).as("hypothesis tests avoided per case, steady state")
                .isEqualTo(steadyState.get(Lane.WITHOUT).getOrDefault("hypothesesTested", 0) - steadyState.get(Lane.MEKO).getOrDefault("hypothesesTested", 0));
    }

    private static Map<String, Integer> totals(List<StoryRun> runs) {
        Map<String, Integer> sums = new LinkedHashMap<>();
        for (StoryRun run : runs) {
            run.metrics().forEach((k, v) -> {
                if (v instanceof Integer || v instanceof Long) sums.merge(k, ((Number) v).intValue(), Integer::sum);
            });
        }
        return sums;
    }

    private static List<String> row(List<String> lines, String prefix) {
        String line = lines.stream().filter(l -> l.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("README has no row starting with " + prefix));
        List<String> cells = new ArrayList<>();
        for (String cell : line.substring(1).split("\\|")) cells.add(cell.strip());
        return cells;
    }

    private static int minutesIn(String cell) {
        return number(cell, "(\\d+) min");
    }

    private static int firstNumber(String cell) {
        return number(cell, "(\\d+)");
    }

    private static int number(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) throw new AssertionError("No match for " + regex + " in: " + text);
        return Integer.parseInt(m.group(1));
    }

    private static String deckCase(String deck, String id) {
        int start = deck.indexOf("id: '" + id + "'");
        assertThat(start).as("deck case " + id).isNotNegative();
        int end = deck.indexOf("\n    {", start);
        return deck.substring(start, end < 0 ? deck.indexOf("];", start) : end);
    }
}
