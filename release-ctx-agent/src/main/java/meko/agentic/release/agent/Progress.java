package meko.agentic.release.agent;

import meko.agentic.client.MekoMcpClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where each use case stands in the datapack in use: the runs of its current cycle, each with what the agent found when
 * it ran. Read from the use case's record in Meko the first time it is asked for, so it survives a restart of this
 * application; kept up to date in memory after that.
 * <p>
 * A record runs in cycles. "Start over" writes a {@code cycle} turn, and the faulty job writes an {@code incident} turn
 * (a new occurrence of the incident): runs before the latest of them belong to an earlier cycle and do not count. The
 * record keeps them, for an audit.
 */
@Component
public class Progress {
    /**
     * A run: for a rehearsal, whether the agent found the standard (knowledge) and other teams' incidents or queries
     * (teams); for a recovery, whether it found the runbook (knowledge) and a job marker (teams).
     */
    public record Run(String run, boolean knowledge, boolean teams, String verdict) {}

    /**
     * The occurrence of the incident the current cycle is about: when the job started, its marker's position, the rows
     * it changed, orders by status before the job and after it, and the orders checkout wrote since (by status).
     */
    public record Incident(String startedAt, String lsn, String detectedAt, int changed, Map<String, Integer> before,
                           Map<String, Integer> after, Map<String, Integer> written, String run) {}

    private record State(List<Run> runs, AtomicReference<Incident> incident) {}

    /** An incident turn's output, as {@link #incidentTurn} writes it. */
    private static final Pattern INCIDENT_TURN = Pattern.compile(
            "Cycle (\\S+)\\. Marker position (\\S+)\\. Changed (\\d+) rows\\. Detected at (\\S+)\\.");

    private final MekoMcpClient meko;
    private final ReleaseMemory memory;
    /** Rebuilt in the background the first time a datapack is asked for: a status call never waits on it. */
    private final Map<String, CompletableFuture<State>> byDatapack = new ConcurrentHashMap<>();

    public Progress(MekoMcpClient meko, ReleaseMemory memory) {
        this.meko = meko;
        this.memory = memory;
    }

    public void recorded(String recordId, Run run) throws Exception {
        State state = state(meko.datapack(), recordId);
        state.runs().removeIf(r -> r.run().equals(run.run()));
        state.runs().add(run);
    }

    /** What the faulty job's turn says, so the cycle can be read back from the record (the counts as JSON at its end). */
    public String incidentTurn(Incident incident) {
        return "Cycle " + incident.startedAt() + ". Marker position " + incident.lsn() + ". Changed " + incident.changed()
                + " rows. Detected at " + incident.detectedAt() + ". Counts: "
                + meko.json(Map.of("before", incident.before(), "after", incident.after(), "written", incident.written(), "run", incident.run() == null ? "" : incident.run()));
    }

    /** A new cycle of a record: no runs yet; for the incident, the occurrence it is about (null: none yet). */
    public void newCycle(String recordId, Incident incident) throws Exception {
        State state = state(meko.datapack(), recordId);
        state.runs().clear();
        state.incident().set(incident);
    }

    /** The current cycle's runs (and incident); {@code loading} while the record is still being read back. */
    public Map<String, Object> snapshot(String recordId) throws Exception {
        CompletableFuture<State> pending = future(meko.datapack(), recordId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!pending.isDone()) {
            out.put("loading", true);
            return out;
        }
        State state = pending.join();
        out.put("runs", List.copyOf(state.runs()));
        Incident incident = state.incident().get();
        if (incident != null) {
            out.put("incident", incident);
            out.put("active", state.runs().stream().noneMatch(r -> "RECOVERED".equals(r.verdict())));
        }
        return out;
    }

    /** Whether the current cycle's incident is still unrepaired; false while unknown. */
    public boolean incidentActive(String recordId) throws Exception {
        Object active = snapshot(recordId).get("active");
        return Boolean.TRUE.equals(active);
    }

    private CompletableFuture<State> future(String datapackId, String recordId) {
        return byDatapack.computeIfAbsent(datapackId + "|" + recordId, key -> CompletableFuture.supplyAsync(() -> restore(recordId)));
    }

    /** For the writers (a decision, a new cycle): they run in jobs or on a request, so they may wait. */
    private State state(String datapackId, String recordId) {
        return future(datapackId, recordId).join();
    }

    /** The counts at the end of an incident turn; empty maps when the turn has none (written before they were recorded). */
    private Map<String, Map<String, Integer>> counts(String output) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (String k : List.of("before", "after", "written")) out.put(k, Map.of());
        int at = output.indexOf("Counts: ");
        if (at < 0) return out;
        try {
            tools.jackson.databind.JsonNode json = meko.objectMapper().readTree(output.substring(at + 8));
            for (String k : List.of("before", "after", "written")) {
                Map<String, Integer> m = new java.util.TreeMap<>();
                for (String status : json.path(k).propertyNames()) m.put(status, json.path(k).path(status).asInt());
                out.put(k, m);
            }
        } catch (Exception e) {
            // unreadable: no counts
        }
        return out;
    }

    /** The job's run id in an incident turn's counts, or null. */
    private String runOf(String output) {
        int at = output.indexOf("Counts: ");
        if (at < 0) return null;
        try {
            String run = meko.objectMapper().readTree(output.substring(at + 8)).path("run").asString("");
            return run.isBlank() ? null : run;
        } catch (Exception e) {
            return null;
        }
    }

    /** Rebuilds the current cycle from the record: the turns after its latest cycle or incident turn, run by run. */
    private State restore(String recordId) {
        List<Run> runs = new ArrayList<>();
        Incident incident = null;
        try {
            Map<String, Boolean[]> context = new LinkedHashMap<>();
            Map<String, String> verdicts = new LinkedHashMap<>();
            for (Object o : (List<?>) memory.audit(recordId, false).get("turns")) {
                Map<?, ?> turn = (Map<?, ?>) o;
                String run = String.valueOf(turn.get("run"));
                String output = String.valueOf(turn.get("output"));
                Object event = turn.get("event");
                if ("cycle".equals(event) || "incident".equals(event)) {
                    // A new cycle: what came before it belongs to an earlier one.
                    context.clear();
                    verdicts.clear();
                    incident = null;
                    Matcher m = INCIDENT_TURN.matcher(output);
                    if ("incident".equals(event) && m.find()) {
                        Map<String, Map<String, Integer>> counts = counts(output);
                        incident = new Incident(m.group(1), m.group(2), m.group(4), Integer.parseInt(m.group(3)),
                                counts.get("before"), counts.get("after"), counts.get("written"), runOf(output));
                    }
                } else if ("context".equals(event)) {
                    // A rehearsal's context turn starts "Rules:", a recovery's "Runbook:".
                    boolean knowledge = !output.startsWith("Rules: none") && !output.startsWith("Runbook: none");
                    boolean teams = output.startsWith("Runbook:") ? !output.contains("Marker: none")
                            : !(output.contains("Incidents: [].") && output.contains("Critical queries: []."));
                    context.put(run, new Boolean[]{knowledge, teams});
                } else if ("decision".equals(event)) {
                    verdicts.put(run, output.split("\\.", 2)[0].strip());
                }
            }
            context.forEach((run, flags) -> {
                if (verdicts.containsKey(run)) runs.add(new Run(run, flags[0], flags[1], verdicts.get(run)));
            });
        } catch (Exception e) {
            meko.recordError("Could not read the progress back from the record: " + e.getMessage());
        }
        return new State(new CopyOnWriteArrayList<>(runs), new AtomicReference<>(incident));
    }
}
