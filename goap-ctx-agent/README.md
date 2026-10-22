# Meko × Embabel GOAP: Agents That Don't Lose the Plot

Six relatable agent workflows **across system boundaries**, each run by the **same agents, the same Embabel GOAP
planner and the same code**, with and without **[Meko](https://mekodata.ai/)**. A toggle (*Show the build-it-yourself
comparison*) adds a third lane, **your application's own database**, to show what Meko adds over what you would build
yourself.

> **GOAP decides what to do next. Meko gives every agent the right context to decide it.**

## The stories

Each story states its boundary on screen:

| Story | Boundary | Without | Your own database | With Meko |
|---|---|---|---|---|
| **Don't Make Me Explain It Again** | A vendor's chatbot (its own store) escalates to your payments specialist; only the CRM ticket crosses | 3 customer re-asks · 56 min | 3 · 56 min | 0 · 19 min |
| **Same Proof, Twice** | A vendor's loan-origination system (its own store) refers two applications, each with its file, to your underwriter; the borrowers' explanations do not cross, and a credit-risk ruling on the first is promoted to shared knowledge | 3 things re-explained · 2 rulings requested · 359 min | 3 · 1 ruling · 327 min | 0 · 1 ruling · 132 min |
| **What Changed Since Last Review?** | Last quarter's review was written by the analyst desk's agent | 5 evidence requests · 166 min | 5 · 166 min | 1 · 41 min |
| **The Organization Learned** | Case 1 is solved by the EU payments desk's agent, cases 2-3 by the APAC payments desk's | 3 cases: 264 min | 199 min | 131 min |
| **The Agent Died at 2 AM** | The payments team's on-call agent dies; your platform SRE agent takes over with only the incident id | 2 repeated steps · 80 min | 2 · 80 min | 0 · 50 min |
| **I Was on Vacation. Catch Me Up.** | Decisions and requests were written by Claude Code, the PR bot, the incident bot | 1 missed requirement · 158 min | 1 · 158 min | 0 · 53 min |

Counts (re-asks, repeated steps, evidence requests, missed requirements) come from the plans the planner actually
chose. Minutes are estimates; see [How effort is estimated](#how-effort-is-estimated).

### Follow-ups: decision traces, read back later

Three stories have a follow-up in which someone accountable, given only an id, reads the case's record back:

* **Audit this transaction** (*Don't Make Me Explain It Again*). A compliance auditor reviews how the dispute was handled. Every party writes to
  the transaction's conversation, keyed by the transaction id, so with Meko one `conversation_get` returns the
  customer's account, the handoff, every re-ask, the settlement check, each decision and the resolution.
* **Check this review** (*What Changed Since Last Review?*). A model-risk reviewer checks the agent's KYC review against the KYC re-review
  standard: which previous review it compared against and what it passed over, the materiality decision, each evidence
  request and the sign-off.
* **Review this credit call** (*Same Proof, Twice*). A fair-lending reviewer checks both applications' credit calls against
  the underwriting policy: which checks were verified before the call and by whom, whether each condition the policy
  sets on the file (a large deposit sourced, an employment gap explained) was cleared on documented evidence and why
  that evidence was accepted (listed in the policy, or a credit-risk ruling), whether
  the call follows the thresholds in the policy text for the figures on record, and what the borrower had to explain
  again.

Without the record, each piece has to be requested from the party that holds it, and waited for, and what comes back
is the outcome only: a party's own system keeps what was done, not how an agent's decision was made. Decision traces
exist only where an agent wrote them: in Meko, or in your own database for your own application's agents.

| Follow-up | Without | Your own database | With Meko |
|---|---|---|---|
| **Audit this transaction** | 3 records requested · 0 decision traces · 192 min | 2 · 1 decision trace · 162 min | 0 · 2 decision traces · 17 min |
| **Review this credit call** (both applications) | 6 records requested · 0 decision traces · 388 min | 4 · 6 decision traces · 328 min | 0 · 8 decision traces · 38 min |
| **Check this review** | 2 records requested · 0 decision traces · 102 min | 1 · 1 decision trace · 42 min | 0 · 2 decision traces · 22 min |

## Workshop

The same application runs a hands-on workshop for groups: `SPRING_PROFILES_ACTIVE=workshop` opens a workshop page
where each participant adds context to their own empty datapack stage by stage (shared knowledge, then another desk's earlier reviews,
then promotion to collective memory) and reruns three use cases to see what changes. No model key is needed. See
[docs/WORKSHOP.md](docs/WORKSHOP.md): setup, and each use case step by step.

## What you see in the app

Open `http://localhost:8080` and pick a use case from the bar at the top.

* **The use case header** says what the use case is, the system boundary, and which of the four things it is about:
  memory, knowledge, the right context, decision traces. *How this use case works* (collapsed by default) explains the
  challenge, and the without-Meko and with-Meko paths.
* **Agents in this case** (collapsed by default) shows every agent in the case in the order it acts: its id, the system
  it lives in, what it does, whether it acted earlier (its writes are already in shared context), now, or later (the
  follow-up), and what crosses the boundary to the next one. After a run, each agent shows what it actually did in
  each lane: its reads and writes to shared context, the decisions it traced, and how many of its records other agents
  used. Without Meko, the agents across a boundary have nothing in shared context.
* **Run the case** runs the same agents without Meko and with Meko, live and side by side (and in your own database
  too, with the build-it-yourself comparison switched on). The button that started the run stays highlighted while it
  runs and afterwards, so it is clear which action produced the results on screen.
* **The follow-up** (*Audit this transaction*, *Check this review*, *Review this credit call*) becomes available once
  the case has run.
* **Resets** start a story over where a run changes what the next run sees: *Reset learning* (*The Organization
  Learned*), *Start a new review cycle* (*What Changed Since Last Review?*), *Reopen the review request* (*I Was on
  Vacation. Catch Me Up.*), *Reset precedents* (*Same Proof, Twice*: a promoted credit-risk ruling stays
  precedent for later runs until then). Each says what it did.
* **Summary / Full detail.** *Summary* (the default) shows the outcome, the steps, the context used and the cost. *Full
  detail* adds the planner's plans and replans, the decisions at each gate, which model wrote the reply and why, and
  every Meko call with its latency.

Each lane then shows:

* **A summary** of the headline counts, without → with Meko, and a monthly projection at a volume you set.
* **The steps** the planner ran, each with its estimated minutes; hover for the basis of the estimate.
* **Context the agents used, and who wrote it**, and under **Passed over, and why**, what was retrieved and
  deliberately not used. Uploaded policy documents are shown in full.
* **For a follow-up, the audit trail**: one small table per entry of the record, in order, with *Who* wrote it, its
  kind (decision, record, dialogue), *What* was asked or done, *How* it was decided or why, the *Response*, the *Issue*
  the audit flags, and the *Source*: the case's conversation, or which party it had to be requested from.
* **Cost**: model spend at list price, the effort cost of the hands-on minutes, and Meko calls and latency.

The header shows whether Meko and the models are connected, and has two more controls: *Seed datapack* (with live
Meko) and *Simulate Meko outage*.

A slide deck sets the context before the live demo: open [`assets/overview.html`](assets/overview.html) in a browser.
It covers the six use cases and the audit follow-ups, the agents and how they interact (including the
auditor and the model-risk reviewer who read a case back later), the four things a context
engine provides, and the data infrastructure tax behind each. It is one self-contained file with light and dark themes,
navigable with the arrow keys, and prints one slide per page.

## What the demo shows

**Memory → knowledge → the right context → decision traces.** Each story states which of these it exercises: memory
that agents and tools wrote, knowledge people uploaded or agents promoted, the right context for this case, and decision
traces, each gate decision written into the case's conversation with who made it and on how much context. Retrieval is
not reuse: each lane names what it passed over and why.

**Distractors.** The seed adds six look-alikes: an outdated 2025 review of the same customer, a review of a
near-identical customer id, a superseded early decision on the same pull request, an open request on a different pull
request, a look-alike chargeback policy, and a look-alike underwriting policy for self-employed borrowers. The agents pick the right item in each case. Choosing among what was
retrieved is a decision, not a code filter.

**Every story writes its result back.** The payments specialist records its resolution. The incident's conversation
holds the timeline: what each agent did, what was redone and why, the finding and the mitigation. The catch-up agent
records that the reviewer's request was addressed, so the next catch-up knows it is closed. The KYC review records its
decision. Each payment case records its root cause and whether it was diagnosed by reusing a lesson (whose), after
rejecting one (why), or by testing hypotheses. A result that supersedes an agent's own earlier memory updates it in
place; one agent never edits another's. These are plain writes, with no model calls.

**Results written back without personal data.** A KYC review writes back the decision, the risk class and **keyed
fingerprints** of the profile (an HMAC per field, scoped to the customer, keyed with a secret that never leaves the
application), plus a coarse monthly-volume band: never the address, the ownership or a beneficiary's name. The next
review fingerprints the current profile with the same key and diffs it: "address unchanged, one new beneficiary".
The models see the same fingerprints and counts, never the profile, and the review's record refers to other reviews by
date and id only.

**Your own database.** If all your agents live in one application you own, your own database can do the job: within one
application it keeps its own handoffs, checkpoints and lessons (in *The Organization Learned* it learns the lesson again
on case 2, then reuses it on case 3). It never sees what another system wrote. The moment agents cross a boundary (a team, a vendor, a
framework, a tool) you need a shared context layer: build it, or use Meko. The right context needs **reach** (what
other teams, vendors and tools wrote), **meaning** (found without shared keys), **provenance** (who wrote it and when)
and **promotion** (lessons becoming organizational knowledge), for every team. That is the data infrastructure tax Meko
takes on. Every silo pays once; with Meko the organization pays once.

**The right model for the job.** Every story ends with a response writer (a customer reply, a handover note, a review
rationale, a catch-up brief, a resolution note, an audit finding). Which model writes it is a planning decision: rich
context has no gaps and the planner picks the small model; gaps make a larger model the cheapest safe choice.

**Trust.** *A case the pattern doesn't fit* (*The Organization Learned*): Meko retrieves the learned pattern, the agent checks it against
the case's signals and rejects it, and the planner investigates instead of misdiagnosing. *Simulate Meko outage*: every
context-store call fails, "with Meko" runs degrade to the path an agent without memory takes, and every failed call is
visible.

## How it works

Each story is one or more Embabel agents using the GOAP planner. Actions declare preconditions and effects. A "gate"
action (open the case file, restore the checkpoint, look up the prior review) can lead to several outcomes; once it
has run, the planner **replans** against what was actually recovered. When context is missing, the only way to reach
the goal goes through more expensive actions: ask the customer, redo the database check, re-verify every KYC area.

* **The judgement at each gate is made by a model reading only the context the agent has**: which facts are already
  established, how far the dead investigator got, which changes are material, whether a review request is open,
  whether a learned pattern fits. The planner's conditions come from the answer, so the without-Meko behaviour emerges
  from thin context rather than from separate code. The small model decides; if no model is available, a
  deterministic rule decides, and the decision record says which.
* Handoffs and takeovers run as **separate agent processes** that share only what is passed explicitly (a ticket note,
  an incident id) plus whatever is in the context store. The SRE investigator is genuinely terminated mid-plan.
* Counts are measured from the run: actions executed, replans, planner cost, Meko calls and latency, and the business
  effects the actions record.
* The lanes run at the same time and stream to the browser as they go: plans appear, steps complete, replans are
  highlighted, and every Meko call, decision and model call shows up as it happens.

### How effort is estimated

Every minute in the demo is an **illustrative assumption**, not a measurement. No figure comes from a study or
production data. They live in one file, [`estimates.yml`](src/main/resources/effort/estimates.yml), one entry per step
an agent can take, each with:

* `minutes`: human-equivalent minutes for the step (agents act in seconds; this is what the step costs the
  organization when a person does it or waits on it);
* `time`: `hands-on` (someone on your side is working) or `waiting` (on a customer's reply or another team's queue);
* `basis`: one line on why, shown when you hover a step's minutes in the app.

What is measured is **which steps ran**: the planner decides that from the context it had, with the same estimates in
every lane. A story's minutes are the sum of its executed steps; a step that redoes earlier work uses the original
step's estimate. The effort cost is **hands-on minutes** × `HOURLY_RATE_USD` (default $60/h, also an assumption);
waiting minutes are reported but not priced as labour.

| Story | Without Meko | With Meko | Kind of saving |
|---|---|---|---|
| Don't Make Me Explain It Again | 56 (13 hands-on, 43 waiting) | 19 (13 hands-on, 6 waiting) | Customer waiting and time to resolution; your specialist's effort is the same |
| Same Proof, Twice (2 applications) | 359 (64 hands-on, 295 waiting) | 132 (62 hands-on, 70 waiting) | Borrower waiting and time to the credit calls: no conditions sent back for what the borrowers already explained, no policy escalation, and no second ruling once the first is precedent |
| What Changed Since Last Review? | 166 | 41 | Hands-on: full re-verifications replaced by a diff and targeted requests |
| The Organization Learned (3 cases) | 264 | 131 | Hands-on: hypotheses not re-tested on cases 2 and 3 |
| The Agent Died at 2 AM | 80 | 50 | Hands-on: finished investigation steps not redone |
| I Was on Vacation. Catch Me Up. | 158 | 53 | Hands-on: manual digging and a bounced review |

Most of each gap is a few steps that disappear from the plan with Meko (policy escalations, manual digging, a bounced
review, full re-verifications); each one's estimate and basis is in the file. To use your own numbers, edit the file, or override one at startup, for example
`--goap.effort.estimates.payments-specialist.escalate-for-policy.minutes=10`.

## Run

Java 21+, Spring Boot 4.1.1, Embabel Agent 1.5.1.

```bash
mvn spring-boot:run           # offline: "With Meko" uses an in-memory stand-in
```

Open `http://localhost:8080`. A link can run a story on load, for example `/?story=agent-died-2am&run=compare`
(`run` = `compare` | `series` | `mismatch`).

### Live Meko

```bash
export MEKO_ENABLED=true
export MEKO_API_KEY='mko_tkn_...'
export MEKO_DATAPACK_NAME='meko-goap'      # or pin MEKO_DATAPACK_ID
export KYC_FINGERPRINT_KEY='<your secret>' # the key for KYC profile fingerprints; use the same one wherever you compare
mvn spring-boot:run
```

Then:

1. Press **Seed datapack** in the app header (or `POST /api/meko/seed`). It uploads the eight documents in
   `src/main/resources/kb/` to the datapack's knowledge base through Meko's MCP (`knowledgebase_upload_document`;
   Meko then indexes them), and writes what other agents and tools wrote
   (a prior KYC review in fingerprint form, notes from a coding assistant, a PR review bot and an incident bot) and the
   distractors. It is safe to repeat. It also removes any KYC review stored in the datapack as plain text, and reports any it
   could not remove.

The header shows the datapack and how many documents its knowledge base holds.

#### Knowledge base documents

Meko's shared knowledge has two sources, and the demo uses both: **documents** uploaded to the datapack (by the app through Meko's MCP, or by people in the Meko app), and
**lessons** agents promote from memory (the root cause the EU desk's agent promotes in *The Organization Learned*, and the
credit-risk ruling the underwriter promotes in *Same Proof, Twice*). Knowledge search covers
both. The documents are fictional and short:

| Document | Used by |
|---|---|
| `CARD-DISPUTE-07` failed-capture dispute policy | Your payments specialist chooses it (*Don't Make Me Explain It Again*) |
| `CARD-DISPUTE-03` chargeback policy | The look-alike it must pass over (*Don't Make Me Explain It Again*) |
| `SUPPORT-STD-02` payments support handoff standard | Organizational context for *Don't Make Me Explain It Again* |
| `RB-PAY-12` payment API latency runbook | Organizational context for *The Agent Died at 2 AM* |
| `KYC-STD-04` periodic re-review standard | Your KYC agent's materiality decision, and the review check (*What Changed Since Last Review?*) |
| `ADR-194` payment retry idempotency | Your catch-up agent's brief (*I Was on Vacation. Catch Me Up.*) |
| `LEND-UW-05` salaried personal-loan underwriting policy | Your underwriter chooses it and applies its thresholds, and the fair-lending review checks against it (loan story) |
| `LEND-UW-09` self-employed personal-loan underwriting policy | The look-alike it must pass over (loan story) |

The seed uploads each document unless the datapack already holds identical content. Where an upload fails (a storage
quota, say), the seed promotes a one-line version of that document instead, so the stories still work, and says why. A
document is searchable once Meko has indexed it, which takes a moment: re-run a story after the header shows the
documents.

A failed Meko call never stops a run: it is recorded, and the planner takes the path an agent without that context
would take.

### Offline stand-in

Without Meko configured, "With Meko" runs use an in-memory stand-in with the same behaviour (cross-agent memory,
case-scoped recall, promotion into shared knowledge, conversations, artifacts), keyword search instead of semantic
search, and nothing kept across restarts. The app labels these runs as a stand-in. The "your own database" lane uses
the same kind of store with nothing seeded: it holds only what this application's agents wrote. The documents other
teams own (the dispute and lending policies, the KYC standard) are in shared knowledge, not copied into each
application's database, so that lane asks the owning team for them.

### Model tiers

The response writer has three tiers, and the small tier also makes the gate decisions that need judgement. A decision
the context settles exactly (a tagged record is there or not, a policy's scope covers the case, a threshold is met) is
decided by rule, with no model call, and traced the same way; a model's answer that contradicts the context falls back
to the rule. The provider is a Spring
profile:

| Profile | Key | Start with |
|---|---|---|
| `groq` (default) | `GROQ_API_KEY` | `mvn spring-boot:run` |
| `openrouter` | `OPENROUTER_API_KEY` | `SPRING_PROFILES_ACTIVE=openrouter mvn spring-boot:run` |
| `local-llm` | none (Ollama) | add to either: `SPRING_PROFILES_ACTIVE=groq,local-llm` |

| Tier | Default model | Request settings | List price (in / out per 1M, Groq) |
|---|---|---|---|
| `slm` | `openai/gpt-oss-20b` | `reasoning_effort: low` | $0.075 / $0.30 |
| `llm` | `openai/gpt-oss-120b` | `reasoning_effort: low` | $0.15 / $0.60 |
| `frontier` | `openai/gpt-oss-120b` | `reasoning_effort: high` | $0.15 / $0.60 |

Mid and frontier are the same model at different reasoning effort: reasoning tokens are billed as output, so deeper
reasoning costs more. OpenRouter runs the same models, so runs are comparable across providers. The `local-llm`
profile runs the small and mid tiers on Ollama and keeps the frontier tier with the provider.

Override any tier with `SLM_MODEL`/`SLM_BASE_URL`, `LLM_MODEL`/`LLM_BASE_URL`, `FRONTIER_MODEL`/`FRONTIER_BASE_URL`
(any OpenAI-compatible server). `LLM_ENABLED=false` turns model calls off: the planner still routes, gate decisions use
their rules, and the writer uses templates. Without a key, every tier is unavailable and the same happens.

The tier is chosen by the planner: each tier's action is priced at its estimated cost for the prompt plus a risk
penalty per gap in the context (highest for the small model, none for frontier). No gaps → small; one gap → mid; two
→ frontier. A tier that isn't usable right now (no key, endpoint down, rate limit) is priced out, so the plan routes
around it.

## APIs

```text
GET  /api/stories
GET  /api/stories/{id}/stream?mode=compare|with|db|without|mismatch|followup[&db=true]   (server-sent events)
POST /api/stories/{id}/run                     {"meko": true|false}
POST /api/stories/{id}/compare[?db=true]
POST /api/stories/{id}/reset
POST /api/stories/agent-died-2am/start         {"meko": true}
POST /api/stories/agent-died-2am/takeover      {"caseId": "INC-…", "meko": true}
POST /api/stories/organization-learned/series?cases=3[&db=true]
POST /api/stories/organization-learned/case?index=1[&epoch=...][&otherTeam=true]   (one case, with Meko; the workshop's steps)
POST /api/stories/organization-learned/mismatch {"meko": true}
GET  /api/meko/status
POST /api/meko/seed
POST /api/meko/outage?on=true|false
GET  /api/llm/status
POST /api/llm/warmup
```

`mode=followup` runs a story's follow-up in each lane on the case that lane last handled. `/api/cases` is an earlier,
simpler commander flow kept for reference.

## Data

All customer, banking, production and regulatory records are synthetic. KYC profiles are written to Meko only as keyed
fingerprints.
