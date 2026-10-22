# Meko Workshop: Same Agents, Only the Context Changes

A hands-on session (about 2 hours) for 20 or more people. Each participant runs three use cases, adding context to
their own Meko datapack between runs, and watches what the agents do differently. The agents, the planner and the code
never change. No model key is needed.

The demo and the workshop are the same application. The demo is the default; the workshop is a profile with its own
page.

## What participants learn

Each stage adds one kind of context, and participants run the use cases again to see what changes.

| Stage | What is in the datapack | What to look for |
|---|---|---|
| **0 · No context** | Nothing: only what the agents write during the run | What crosses a handoff, and what still cannot be decided |
| **1 · Shared knowledge** | The dispute policies, uploaded as documents | Which steps disappear, and whether the agent picks the right policy |
| **2 · Another desk's reviews** | Last quarter's reviews, written to memory by another desk's agent; then the KYC standard | Who wrote the context the agent used |
| **3 · Collective memory & audit** | Lessons the agents promote from memory themselves | How a lesson reaches the next case; what an auditor gets from one id |

Every run is the same agents with Meko; only the context in the datapack changes.

The three use cases, each the clearest example of one kind of context: *Don't Make Me Explain It Again* (payments:
memory across a handoff, then shared knowledge), *What Changed Since Last Review?* (KYC: another desk's earlier
reviews) and *The Organization Learned* (payments operations: collective memory). Together they touch every stage. For
a longer session, add *Same Proof, Twice* (lending) to `workshop.use-cases` in `application-workshop.yml`.

No figures are stated in this guide on purpose: every count, minute and outcome is measured from the participant's own
run and shown on the page.

## Before the session (facilitator)

1. **Meko accounts.** Each participant needs their own Meko account ([cloud.mekodata.ai/signup](https://cloud.mekodata.ai/signup)) and an API
   key. Free accounts work. The workshop
   page creates each participant's own **empty datapack** in step 1: memory, uploads and promotions are per datapack,
   so a shared one would mix everyone's stages. Its creator owns it, which promotion (stage 3) needs.
2. **Environment.** Ask participants to clone and build before the day (see *Setup* below), or hand out the prebuilt
   jar (`target/goap-ctx-agent-0.1.0.jar`, from `mvn -DskipTests package`): with the jar, only Java 21 is needed.
3. **Meko capacity.** Twenty participants running at once make bursts of calls: let the Meko team know the date.
4. **Dry run** the whole session once. Starting over is easy: *Start over with a new datapack* creates another fresh
   one (promotion is one way, so a used datapack cannot be emptied).

## Prerequisites

| What | Why | Check |
|---|---|---|
| **Java 21 or later** | Runs the application | `java -version` |
| **Maven** | Builds it (not needed with a prebuilt jar) | `mvn -version` |
| **Git** | Gets the code (not needed with a prebuilt jar) | `git --version` |
| **A Meko account and API key** | Each participant's own: the workshop creates their datapack in it. Free accounts work | Sign up at [cloud.mekodata.ai/signup](https://cloud.mekodata.ai/signup), then create an API key in the Meko app |
| **A browser, and port 8080 free** | The workshop page runs at `http://localhost:8080` (another port can be set) | |
| **Network access** to `github.com`, Maven Central and `mcp.mekodata.ai` | The clone, the first build's dependencies, and Meko | |

No model key is needed: the workshop runs without model calls. For the *shared datapack* option of *The Organization
Learned*, two participants pair up, each with their own Meko account.

## Setup (participants, 15 minutes)

1. **Get a Meko API key**, if you don't have one yet: sign up at [cloud.mekodata.ai/signup](https://cloud.mekodata.ai/signup), then create an
   API key in the Meko app.
2. **Get the code.**

   ```bash
   git clone https://github.com/srinivasa-vasu/meko-micro-demos.git
   cd meko-micro-demos/goap-ctx-agent
   ```

3. **Build it.** The first build downloads the dependencies (do this before the day: conference Wi-Fi and Maven do
   not mix).

   ```bash
   mvn -DskipTests package
   ```

   This produces `target/goap-ctx-agent-0.1.0.jar`. `-DskipTests` only saves time; plain `mvn package` runs the tests too.
4. **Run the workshop.**

   ```bash
   MEKO_API_KEY=<your key> SPRING_PROFILES_ACTIVE=workshop java -jar target/goap-ctx-agent-0.1.0.jar
   ```

   or, from the source: `MEKO_API_KEY=<your key> SPRING_PROFILES_ACTIVE=workshop mvn spring-boot:run`. The workshop
   connects to Meko at `https://mcp.mekodata.ai/mcp`; set `MEKO_MCP_URL` only to use another endpoint.
5. **Open `http://localhost:8080`.** It opens the workshop page, which guides you step by step. (To use another
   port, add `--server.port=8081` after the jar, or set `SERVER_PORT=8081`.)
6. **Create your datapack** (step 1 of every use case). With *New datapack* selected, the page suggests a unique name
   (`meko-workshop-<date>-<code>`); keep it or type your own, and press **Create and use**. Everything after this is
   written to that datapack. *Use an existing one* lists the datapacks in your account instead: workshop datapacks
   first, datapacks shared with you marked as such, and others marked *has other content*, since they already hold
   context and the stages would not start clean.

One datapack serves all three use cases. Each use case adds only its own context, at its own step, so the others'
earlier stages stay as they were.

Without a Meko key the workshop runs on an in-memory stand-in that starts empty: every step works the same way, but
nothing is kept across a restart. Useful for practice; the live datapack is the point of the session.

## How the steps work

* **The page.** The use cases are tabs at the top; under them, the selected use case's story, its system boundary,
  and what your datapack holds for it right now. The steps are below: each use case shows only the steps it needs,
  numbered from 1.
* **In order.** A step's button stays disabled until the earlier steps of that use case are done, and the page says
  which one to finish first. **Next step** lights up once the current step is done.
* **Run** runs the use case with Meko, once per stage, and only while the datapack holds exactly that stage's context:
  an earlier stage cannot be reproduced once later context is in the datapack (for example, *No context* after the
  documents are uploaded). To run a stage again, *Start over with a new datapack*.
* **Documents.** Press **Upload the documents**: the app uploads the files the step lists, from
  `src/main/resources/kb/`, to your datapack through Meko's MCP (`knowledgebase_upload_document`), and Meko indexes
  them. Press **Check my datapack** until each shows *uploaded* (meanwhile it shows *indexing*). You can also upload
  them yourself in the Meko app. If an upload fails, the page says why; *Upload failed? Add one-line versions* then
  promotes a short version of that use case's documents instead. Those are facts promoted from memory, so they show
  under *Shared knowledge* as collective memory, not as documents.

The output has two parts:

* **Stage by stage**: one card per stage, with the context the run actually used (from what it found), its outcome
  (decided, or handed to a person, as the agents recorded it), its numbers and the change from the stage before.
* **The selected stage's run**, as in the demo. Pick a stage's card to see its run.
  * What Meko provided: **Memory** (what an agent recalled, and who wrote it; also what the run wrote for later),
    **Shared knowledge** (uploaded documents, and collective memory: facts promoted from memory, including what the run
    itself promoted), **Right context** (what was retrieved but set aside, and why), and **Decision traces** (each
    decision's question and answer, the retrieved items it rested on, and whether a rule or a model decided it).
  * The conversation, as chat bubbles (where nobody speaks, as in *The Organization Learned*, the records the agents
    wrote to the case's conversation in Meko); what the agents did (asks and redone work highlighted); and every Meko
    call: what was sent and how much came back.

Ask participants to say what they expect before each run. Runs stay in the browser, per datapack.

## The use cases, step by step

### 1. Don't Make Me Explain It Again (BFSI · Payments, about 20 minutes)

**The story.** A customer explains a failed card payment to a vendor's support chatbot and is escalated to your
payments specialist, who usually asks everything again.

**Boundary.** The chatbot is a vendor's product with its own store; the specialist is your agent. Only the CRM ticket
crosses between them.

**Context it reads.** Memory written during the run (the handoff), and the dispute policies as shared knowledge.

| Step | What you do | What happens | What to look for |
|---|---|---|---|
| 1. Create your datapack | Create or choose it | | |
| 2. Run with no context | Press **Run** | The chatbot writes what the customer established to Meko during the run; the specialist recalls it. No policy is in reach, so the case goes to payments operations, undecided. | Under *Memory*: who wrote what the specialist knew. Was the customer asked again? Why is nothing decided? |
| 3. Add the documents | Press **Upload the documents** (`card-dispute-07-failed-capture-policy.md` and `card-dispute-03-chargeback-policy.md`), then **Check my datapack** | The step is done when both show *uploaded* (or *one-line version*). | |
| 4. Run with shared knowledge | Press **Run** | The specialist finds the policies, picks the one whose scope covers this case, and decides by that policy's own rule on a provisional refund. | Which steps disappeared? Did the outcome change? Under *Right context*: the look-alike chargeback policy, and why it was set aside. |
| 5. Audit | Press **Audit** | A compliance auditor, given only the transaction id, reads the whole record back from the conversation and checks it against the policy. | How many records did it have to request? Was the settlement checked before the decision? |

### 2. What Changed Since Last Review? (BFSI · KYC, about 20 minutes)

**The story.** A periodic KYC re-review of a business customer. Last quarter's review was done by another desk.

**Boundary.** Last quarter's review was written by the analyst desk's agent, in the analyst desk's system.

**Context it reads.** Another desk's earlier reviews in memory, and the KYC standard as shared knowledge. The standard
alone changes nothing (with no earlier review there is nothing to compare with), so both are added before one run.

| Step | What you do | What happens | What to look for |
|---|---|---|---|
| 1. Create your datapack | Create or choose it | | |
| 2. Run with no context | Press **Run** | With no earlier review to compare with, the review re-verifies everything and leaves the risk class to the reviewer. | How many evidence requests? Under *What the agents did*: which work is redone. |
| 3. Add last quarter's reviews | Press **Add their reviews** | Another desk's agent reviewed this customer last quarter, in its own application, and wrote the reviews to memory. They go into your datapack as that agent wrote them: the right one to compare with, and two look-alikes. | |
| 4. Add the documents | Press **Upload the documents** (`kyc-periodic-review-standard.md`), then **Check my datapack** | | |
| 5. Run with the standard and their reviews | Press **Run** | The review compares with the latest review of exactly this customer, and judges each change by the standard's own rules: what is material, and what to request for it. | Under *Memory*: who wrote the review it compared with. Under *Right context*: the older review and the other customer's review, and why each was set aside. Under *Shared knowledge*: the rules it judged by. Compare the evidence requests with step 2. |
| 6. Audit | Press **Audit** | A model-risk reviewer, given only the review id, reads the record back and re-checks the materiality judgement against the standard's text. | Did the review compare against the latest review? Were the requests proportionate? |

Each KYC run starts a new review cycle, so a review never compares with its own previous run.

### 3. The Organization Learned (BFSI · Payments ops, about 20 minutes)

**The story.** The same payment failure keeps arriving from different customers. Two payments desks in the same
organization, each with its own application: the **EU desk** sees the failure first; the **APAC desk** sees it next.

**Boundary.** Case 1: the EU desk's agent, in the EU desk's application. Cases 2 and 3: the APAC desk's agent, in
the APAC desk's application.

**Context it reads.** Collective memory: the root cause the EU desk's agent promotes from memory.

The use case can be run two ways. Pick it with the toggle on the use case's steps:

| Option | Where the lesson lives | Who plays |
|---|---|---|
| **One org-wide datapack** (default) | Both desks write to one datapack for the whole organization | You play both desks |
| **Shared datapack**, as the EU desk or as the APAC desk | Each desk has its own datapack; the EU desk shares its datapack with the APAC desk | Two people, two Meko accounts |

#### One org-wide datapack

| Step | What you do | What happens | What to look for |
|---|---|---|---|
| 1. Create your datapack | Create or choose it | | |
| 2. Run without the lesson | Press **Run** | A failed payment reaches the APAC desk's investigator with nothing promoted. It tests hypotheses until one holds. This is the baseline: what it finds is not promoted. | How many hypotheses does it test? |
| 3. The EU desk solves case 1 | Press **Run** | The EU desk's agent investigates case 1 the hard way, then promotes the root cause from memory to collective memory. | Under *Shared knowledge*: what was promoted, and by whom. |
| 4. Run with the lesson | Press **Run** | The same case as step 2, for the APAC desk's investigator again. It finds the lesson, checks the conditions the pattern states against this case, and validates instead of investigating. | Compare with the *Without the lesson* card: the same case and agent; only the context changed. Under *Decision traces*: what the pattern-fit decision rested on. |

Each of the first two runs starts a new learning epoch (a rerun on a used datapack must not see an older lesson, and
promotion is one way), and the last reads the epoch of case 1, so what it reuses is exactly what case 1 promoted.

#### Shared datapack (two people, optional)

Each person picks their role on the toggle. The two work at the same time.

| When | EU desk | APAC desk |
|---|---|---|
| First | **Create your datapack**, then **Solve case 1**: your agent promotes the root cause in your datapack | **Create your own datapack**, then **Run without the lesson** in it: nothing anyone else learned is in reach, and nothing is promoted |
| Then | **Share it with your partner**: in the Meko app, share the datapack with the APAC desk's account (reading is enough), press *I've shared it*, and tell your partner its name | **Switch to the shared datapack**: pick it from the list (it shows as *shared with you*; press *Refresh* if it is not there yet). Your baseline stays on the page |
| Last | | **Run with the lesson**: the same case, now in the EU desk's datapack |

What to look for (APAC desk): under *Shared knowledge*, the lesson promoted by the EU desk's curator, written from
another person's account; compared with your baseline card, the same case and agent, where only the datapack differs.
The only thing connecting the two desks is the share.

*Run with the lesson* finds the lesson from the shared datapack itself, not from the EU desk's browser. It stays
disabled ("Waiting for your partner's lesson") until the EU desk's case 1 is there; press *Check my datapack* to look
again. Sharing is done in the Meko app: Meko's tools do not grant access.

### To close

In the Meko app, open the datapack's memories and collective memory: what did the agents write, and under which agent
ids?

## Exercises (15 minutes, pick any)

1. **Change a policy.** In `card-dispute-07-failed-capture-policy.md`, change the scope so the policy applies when
   the merchant capture "was declined by the acquirer" instead of "timed out at the gateway". Restart the workshop (it
   reads the documents when it starts; with the jar, rebuild it first), create a fresh datapack, upload the documents,
   and run *Don't Make Me Explain It Again*. Before you run: what will the specialist decide? The decision
   follows the document's text, not the code.
2. **Break Meko.** In the demo view (`/index.html`), press *Simulate Meko outage* and run a use case: the Meko lane
   falls back to what an agent without context does, and every failed call is listed.
3. **Read the record.** In the demo view, run a use case's follow-up with *Full detail* on, and read the audit trail
   entry by entry: who wrote what, and which decisions were made by rule and which by a model.

## Facilitator notes

* **Read numbers off the screen.** Every count and minute is measured from the run; this guide states none on purpose.
  Minutes are estimates (see `effort/estimates.yml`); counts are what the planner did.
* **Common problems.**
  * *Meko: no datapack yet*: create one at step 1. If the datapack in use was deleted in the Meko app, step 1 says so:
    create a new one. (`MEKO_DATAPACK_NAME` is not needed for the workshop.)
  * *A document stays at indexing*: indexing takes a moment; press *Check my datapack* again.
  * *An upload fails with a storage limit*: the Meko account's knowledge-base quota is full; free space or upgrade, or
    use *Add one-line versions*.
  * *Exercise 1 (a changed policy)*: changed content is a new upload, so it goes into a fresh datapack.
  * *No context does not look empty*: the datapack is not new. Use a fresh one.
  * *Promotion failed*: the key is not the datapack's owner or a maintainer.
  * *Run with the lesson keeps waiting (shared datapack)*: the EU desk has not run case 1 yet, or has not shared the
    datapack; press *Check my datapack* once it has.
* **Timing.** Intro 15 min, setup 15, each use case about 20, exercises 15, wrap-up 10. A run takes from a few seconds
  up to about a minute against live Meko.
* **Starting over.** A datapack keeps everything written to it, across restarts, and promotion is one way: a clean
  start is a new datapack (*Start over with a new datapack*).
