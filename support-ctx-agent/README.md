# Incident commander — Embabel planning, Meko memory, and a bill you can audit

An AI SRE agent that investigates production incidents. Spring Boot is the application,
[Embabel](https://github.com/embabel/embabel-agent) plans, and [Meko](https://mekodata.ai)
remembers.

The idea it demonstrates:

> **Memory changes the plan, and the plan changes the bill.**

The agent consults organizational memory before it spends anything. What memory returns
decides how much telemetry is read, which model is used, and whether a model is needed at all.
Every run reports what it cost, where the figures came from, and what it would have cost
without memory, so the claim can be checked rather than taken on trust.

---

## How it works

### Memory first

Retrieval is the first and cheapest action in the plan, with no preconditions. One recall
step runs five Meko reads in parallel:

- prior incidents, searched by record type and by symptom
- runbook chunks from the datapack's knowledge base
- spend and routing policy
- routing outcomes from earlier runs

Telemetry is gathered afterwards, only in the shape the recovered context calls for.

### Reuse is a ladder, not a switch

A deterministic reuse gate scores historical incidents against the new one, using zero tokens,
and picks one of three modes:

| Mode | When | What follows |
|---|---|---|
| `SHORT_CIRCUIT` | Strong signature match, and the deployment the stored fix assumes is still live | The stored remediation is served. No model call. |
| `SCOPED` | A usable match, or a strong match whose fix is out of date | Telemetry is narrowed to the services the match names, then a model diagnoses. |
| `FULL` | Nothing in memory is about this incident | The full telemetry sweep, then a model diagnoses. |

Thresholds are `token.reuse.short-circuit-threshold` (0.85) and `token.reuse.scoped-threshold`
(0.55).

**Staleness is checked before reuse.** A stored remediation can be right about the diagnosis
and wrong about the fix, because the system moved on. Every stored incident records the
deployment its fix depends on (`deployService`, `deployVersion`). The gate checks that pair
against the current deployment state, and refuses to serve a fix whose version is no longer
deployed. It then uses the match only to narrow the investigation.

**A run never matches itself.** Every finished run writes its incident back under its own
ID. A record carrying the ID being investigated is treated as an echo:
- the gate ignores it
- it's kept out of the model's prompt, so a re-run is never shown its own earlier answer as
  history

A recurrence arrives under a new incident ID, and that run *can* match the earlier one.

### Answers are validated, not just scored

The model states the checkable assumptions its fix depends on, and a deterministic validator
checks them against live state:

- **Version claims:** a claimed version must match what's deployed.
- **Stale rollback targets:** a rollback to the version an out-of-date record assumed is
  rejected, because refusing to serve a stale fix doesn't stop the model reading it.

A failed validation demotes confidence below the threshold. The planner then escalates, with
the specific failure in the next prompt, or hands off. Confidence alone can't catch this,
because the same model produced both the answer and the score.

### Routing, escalation and hand-off

Model tier is chosen per action. Planner costs are estimated dollars against the severity's
budget, and the budget comes from a spend policy in Meko, with configuration as a fallback.
A routing policy learned from past outcomes can steer the first tier, upward as well as
downward.

**Escalation only goes up.** The balanced tier is only reachable from a lower one; the
frontier tier from any.

**Two goals compete:**
- **Finalize:** needs a validated diagnosis above the confidence bar.
- **Hand off to a human:** available whenever a diagnosis attempt exists.

The planner weighs another escalation against stopping. A hand-off stores no remediation,
because an answer nobody accepted must not become reusable history. It still records a routing
outcome, since "this tier couldn't close this incident" is evidence routing needs.

### Write-back

| Outcome | Written to Meko |
|---|---|
| Finalized | `INCIDENT_RECORD` (diagnosis, remediation, scope, deployed version) and `ROUTING_OUTCOME`, upserted on incident ID |
| Handed off | `ROUTING_OUTCOME` only, marked `handedOff=true` |
| Memory off | Nothing |

Once a tier has enough successful observations, the derived routing policy can optionally be
promoted to the datapack's shared knowledge base (`ROUTING_PROMOTE`).

### The plan graph

```
IncidentRequest (id, service, symptom, severity)
        │
        ▼
  retrieveMekoContext            ← first, cheapest, no preconditions
  incidents · runbooks · spend policy · routing outcomes
        │
        ▼
  evaluateReuse                  ← deterministic, zero tokens
  signature scoring · staleness check · echo filter
        │
   ┌────┴───────────────┬────────────────────┐
   ▼                    ▼                    ▼
SHORT_CIRCUIT         SCOPED                FULL
   │                    │                    │
collectScopedEvidence collectScopedEvidence collectFullEvidence
   │                    │                    │
reuseStored        ┌────┴────────────────────┴────┐
Remediation        │ diagnoseWithSlm       [SLM]  │
   │               │ diagnoseWithLlm       [LLM]  │
   │               │ diagnoseWithFrontier  [F]    │
   │               └──────────────┬───────────────┘
   └───────────┬──────────────────┘
               ▼
      validate assumptions       ← zero tokens
               │
      confidence ≥ bar ?
        │             │
       yes            no ──► escalateWithLlm (from below LLM only)
        │                    escalateWithFrontier
        │                    or handOffToHuman
        ▼
  finalizeIncident ──► write-back: INCIDENT_RECORD + ROUTING_OUTCOME
```

### Simulated estate

Telemetry and deployment state are simulated in-process by `IncidentDataService`, so the agent
runs without real infrastructure:

- **Two incident families**, payment timeouts and search index staleness, with metrics, logs
  and a change log for each.
- **A 20-service estate** for the full sweep. Only the services involved in the incident log
  failures; the rest log healthy traffic.
- **A queryable deployment state**, which is what staleness is checked against.

Memory, knowledge, routing and spend policy are real Meko calls.

---

## The web app

`http://localhost:8080/` drives everything:

- **Scenarios.** Each one sets up a known world state in a single call (seed memory, set the
  deployment state) and states a prediction before the run:

  | Scenario | Incident | Prediction |
  |---|---|---|
  | We have seen this before | `INC-4821` | Matches the seeded `INC-1842`, deployment unchanged: answered from memory, zero model calls |
  | It looks familiar. The system moved. | `INC-5107` | Same match, but the release moved on: rejected as stale, scoped investigation, no rollback to the stale version |
  | Memory has nothing, and says so | `INC-7730` | A search incident with no relevant history: full investigation |

  After the run, the prediction is checked row by row against the report: incident, reuse
  mode, matched record, model calls, stale rejection, memory reads completed, rollback target.
  A failure names which row failed and why.
- **Your own incident.** Any investigable service and symptom, under a fresh incident ID
  (`INC-` plus five digits). A note under the form reads Meko and says what incident records
  memory currently holds for the service. Use **New ID** to run a recurrence.
- **Compare.** The same incident twice, with memory off and on, run concurrently. Both runs use
  the same planner and telemetry access; the cold run never touches Meko, so neither can see
  the other's effects.
- **Ablate.** The same incident three times: telemetry only, plus learned memory, plus curated
  knowledge. These run in order, because each run's write-back is the next one's memory.
- **Live runs.** Results stream over Server-Sent Events. Each run gets a lane showing the
  planner's steps as they start and finish (planned steps dashed). It also shows what memory
  returned, the gate's decision, how much telemetry was read and skipped, and every model call
  with its token count.
- **Context claims.** Facts the context should put into the answer, checked against both runs.
  A claim counts only when it appears with context and not without:
  - **Scenarios** declare their claims in advance.
  - **Custom incidents** get claims derived after the run from the matched record's
    remediation steps. A step counts as present when an answer takes the same action and
    shares most of its key words.
- **Honest labelling.**
  - An answer the agent handed off is marked "not accepted · handed to a human", with the
    validation failure.
  - A run in which Meko reads failed is marked "partial recall", because empty-because-failed
    is not the same as nothing in memory.

`assets/overview.html` is a standalone explainer page: why an agentic application needs a
context engine, and what building one yourself involves. Open it directly; it needs no server.

---

## Running it

**Requirements**
- **Java 21 or later**, and Maven.
- **A Meko API key and datapack.**
- **A key for one model provider:** OpenRouter (the default), Groq or Gemini.

```bash
export MEKO_API_KEY=mko_tkn_...
export MEKO_DATAPACK_ID=...            # or MEKO_DATAPACK_NAME (default: change-risk)
export OPENROUTER_API_KEY=...
mvn spring-boot:run
open http://localhost:8080/
```

### Knowledge base

The runbooks, postmortem, service map and on-call policy in `src/main/resources/kb/` are meant
to be uploaded to the datapack's knowledge base through the Meko app. Upload every `.md` file
in that folder except its `README.md`, which explains why structured records and prose live in
different stores. Structured history (incident records, routing outcomes, spend and routing
policy) is written by the app's seed, not uploaded.

### Model providers

The provider is a **Spring profile**. Each profile carries both the model role map and the
per-token prices for those models, so the two can't drift apart.

```bash
mvn spring-boot:run                                       # openrouter (default)
mvn spring-boot:run -Dspring-boot.run.profiles=groq       # or APP_PROFILE=groq
mvn spring-boot:run -Dspring-boot.run.profiles=gemini
```

**`openrouter`** — needs `OPENROUTER_API_KEY`:

| Role | Registered name | Model | Reasoning effort |
|---|---|---|---|
| `slm` | `openrouter-gpt-oss-20b` | `openai/gpt-oss-20b` | default |
| `llm` | `openrouter-gpt-oss-120b-low` | `openai/gpt-oss-120b` | `low` |
| `frontier` | `openrouter-gpt-oss-120b-high` | `openai/gpt-oss-120b` | `high` |

OpenRouter reads reasoning effort as `{"reasoning": {"effort": "high"}}`. It silently ignores
OpenAI's `reasoning_effort`, which would leave both 120b tiers at the router's default.
`ReasoningOptionsConverter` sends the right shape per provider, and `OpenRouterRequestShapeTest`
asserts it. Prices on this profile are approximate: OpenRouter's rate varies by the provider it
routes to.

**`groq`** — needs `GROQ_API_KEY`:

| Role | Registered name | Groq model | Reasoning effort |
|---|---|---|---|
| `slm` | `groq-gpt-oss-20b` | `openai/gpt-oss-20b` | default |
| `llm` | `groq-gpt-oss-120b-low` | `openai/gpt-oss-120b` | `low` |
| `frontier` | `groq-gpt-oss-120b-high` | `openai/gpt-oss-120b` | `high` |

The last two rows are one model at two reasoning efforts. Embabel keys a model by the name it
was registered under, so the tiers are registered under tier names, and `GroqOptionsConverter`
sends the real model ID. Groq namespaces open-weight models under their origin
(`openai/gpt-oss-120b`); dropping the prefix is a 404 on the first call. Every ID and effort can
be overridden with the `GROQ_*` variables.

Groq's free tier caps tokens per minute below what one full sweep sends. So this profile sets
`incident.sweep.lines-per-service: 10`; the startup log warns and the results page says so.
Figures are still measured from the run that happened, just from a smaller sweep. On a paid key,
set `SWEEP_LINES_PER_SERVICE=60`.

**`gemini`** — needs `GEMINI_API_KEY`:

| Role | Model |
|---|---|
| `slm` | `gemini-3.1-flash-lite` |
| `llm` | `gemini-3.5-flash` |
| `frontier` | `gemini-3.1-pro-preview` |

Three different models, so a genuine three-step price ladder.

Each provider's configuration is `@Profile`-scoped. `AI_SLM_MODEL`, `AI_LLM_MODEL` and
`AI_FRONTIER_MODEL` override the active profile's choices. Set the `PRICE_*` variables to the
published prices for whatever you use. Reported dollars are only as accurate as those prices,
so verify the shipped rates against your own account.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `APP_PROFILE` | `openrouter` | Model provider profile |
| `MEKO_API_KEY` | — | Meko API key |
| `MEKO_DATAPACK_ID` / `MEKO_DATAPACK_NAME` | — / `change-risk` | Datapack; a pinned ID skips the name lookup |
| `MEKO_MCP_URL` | `https://mcp.mekodata.ai/mcp` | Meko MCP endpoint |
| `MEKO_ENABLED` | `true` | MCP client on/off |
| `MEKO_AGENT_ID` | `sre-agent` | Agent identity for memory written by runs |
| `MEKO_DEPLOY_BOT_AGENT_ID` | `platform-deploy-bot` | Identity the seed writes its deploy record under, to show cross-agent recall |
| `MEKO_READ_TIMEOUT` | `30s` | How long a Meko read is waited for before the run continues without it (reported as partial recall) |
| `MEKO_WRITE_TIMEOUT` | `90s` | MCP request timeout, which bounds writes. Meko writes run a server-side extraction and can exceed 30s. |
| `SWEEP_LINES_PER_SERVICE` | `60` | Log lines per service in the full sweep |
| `SWEEP_SCOPED_LINES_PER_SERVICE` | `8` | Log lines per service in a scoped sweep |
| `PRICE_SLM_IN` … `PRICE_FRONTIER_OUT` | per profile | USD per million tokens, per role |
| `PRICE_EMBEDDING_IN` | `0.02` | USD per million embedded tokens, used to price Meko calls |
| `ROUTING_MIN_OBSERVATIONS` | `5` | Observations before a learned routing policy is acted on |
| `ROUTING_TARGET_SUCCESS` | `0.85` | Success rate a tier must clear to be routed to first |
| `ROUTING_PROMOTE` | `false` | Promote a confirmed routing policy to shared knowledge. One-way; needs owner or maintainer rights. |
| `PROJECTED_INCIDENTS_PER_MONTH` | `2000` | Volume used for the monthly and annual projection |

Spend ceilings by severity (`token.budget-usd`) are fallbacks; a `SPEND_POLICY` record in Meko
overrides them.

---

## Reading the numbers

- **Measured, not estimated.** Model token counts are the provider-reported usage Embabel
  records for each call, including data-binding retries and reasoning tokens (billed as
  completion tokens). If a provider returns no usage, that entry falls back to an estimate and
  the run is marked as estimated.
- **Meko's own cost is included.** Each search and write embeds text, priced from what was
  actually sent: the queries issued and the records written. Meko reports no usage, so these
  figures are always estimates.
- **The comparison is two real runs.** The delta compares the cold and warm runs' totals,
  model plus Meko, with the same planner and telemetry and memory the only difference. It
  doesn't compare against a hypothetical.
- **The counterfactual is reported separately.** Each report also carries what the full sweep
  through the frontier tier would have cost. It uses this run's own completion length, falling
  back to a typical length only when the answer was served from memory.
- **Tokens are split by input and output.** Input dominates this workload, and a blended
  number would hide where the saving is.
- **Projection.** Per-incident dollars are small, so the comparison also extrapolates the delta
  to `PROJECTED_INCIDENTS_PER_MONTH`. It's a straight line from one incident, not a forecast.
- **Same-model tiers.** On the `openrouter` and `groq` profiles, `llm` and `frontier` are the
  same model at different reasoning effort. An escalation between them costs only the extra
  (mostly reasoning) tokens it produces.

---

## API

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/incidents/investigate` | One run. Body: `incidentId`, `service`, `symptom`, `useMeko`, `severity` |
| `POST /api/v1/compare` | Cold and warm runs, the delta, and context-claim results |
| `POST /api/v1/ablate` | Three runs: telemetry only, + memory, + knowledge |
| `POST /api/v1/incidents/investigate/stream` | As above, streamed as Server-Sent Events |
| `POST /api/v1/compare/stream` | As above, streamed |
| `POST /api/v1/ablate/stream` | As above, streamed |
| `GET /api/v1/scenarios` | The scenarios and their predictions |
| `POST /api/v1/scenarios/{id}/arm` | Seed memory and set the deployment state for a scenario |
| `GET /api/v1/services` | Investigable services, their deployed version and default symptom |
| `GET /api/v1/history?service=&incidentId=` | Incident records memory currently holds for a service |
| `GET /api/v1/state` | Current deployment state, which staleness is checked against |
| `GET /api/v1/pin` | The deployment the seeded history depends on, and whether it is still live |
| `GET /api/v1/sweep` | Full-sweep size, and whether it has been reduced |
| `GET /api/v1/incidents/checkout` | A sample incident request |
| `GET /api/v1/meko/status` | MCP connection, registered tools and datapack resolution |
| `POST /api/v1/meko/seed` | Seed structured history (idempotent; unchanged records are not rewritten) |
| `POST /api/v1/meko/prune` | Remove duplicate seeded and written records (`?includePromoted=true` for shared rows) |
| `POST /api/v1/meko/advance-deployment` | Ship the next release of the pinned service, making stored fixes stale |
| `POST /api/v1/meko/reset-deployment` | Roll back to the version the seeded history assumes |

**Streaming frames.** Each frame is JSON, with a `run` label where it belongs to one run:

| Frame | Contents |
|---|---|
| `plan` | The planner's current route: remaining steps |
| `step` | An action started or finished, with duration |
| `context` | What Meko recalled, plus any failed reads |
| `reuse` | The gate's decision, score, matched record and staleness |
| `evidence` | Log lines read and skipped, and the scope |
| `diagnosis` | Each attempt: tier, confidence, validation result |
| `llm` | One provider call and its token usage |
| `replan` | The planner changed course |
| `result` | The same body the JSON endpoint returns; the stream then closes |
| `error` | The run failed; the stream then closes |

---

## Project layout

```
src/main/java/meko/agentic/sre/
  agent/     IncidentCommanderAgent — the GOAP agent: actions, conditions, costs, write-back
  api/       REST and streaming controllers, scenarios, event routing to live runs
  config/    Provider profiles, tier registration, reasoning-effort request shapes
  domain/    Requests, reports, ledger, reuse decisions, context claims
  meko/      MekoToolBridge (deterministic adapter over Meko's MCP tools), datapack resolution
  service/   Reuse gate, validator, routing policy, accounting, seed, claim derivation,
             simulated telemetry
src/main/resources/
  prompts/   Jinja prompt templates (diagnosis, counterfactual)
  kb/        Knowledge-base documents to upload to the datapack
  static/    The web app
```

Run the tests with `mvn test`. Provider request shapes are asserted against local stubs, so
no keys are needed.

---

Versions targeted: Java 21+, Spring Boot 4.1.1, Spring AI 2.0.1, Embabel Agent 1.5.1.
