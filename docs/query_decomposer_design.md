# Query Decomposer Design v1

Resolves Vision.md open question 7.1 ("the crux"), and necessarily touches 7.2
(entity linking), 7.3 (relevance filter), and 7.7 (empty-retrieval fallback) since
decomposer output is the direct input to all three. Context Builder format/budget
(7.4) is deliberately **not** decided here — it consumes this component's output
and deserves its own pass once real retrieval payloads exist to measure against.

Built directly on the confirmed capability from `verification_findings.md`: a
single SQL statement can join a structured filter to a vector-similarity `ORDER BY`
against `sqlite-vec`. The decomposer is designed around that joined-query
capability rather than branching into two separate subsystems, per Vision.md
section 8's explicit guidance.

## 1. Input contract

The decomposer consumes the `slots` object from a Semantic-Parser-pass
`StructuredActionPlan` (see `structured_action_plan.schema.json`). It runs
**between** pass 1 (semantic parse) and pass 2 (reasoning) — this is the missing
box in Vision.md's pipeline diagram (section 3/5).

## 2. Slot type -> retrieval strategy table (rule-based, no LLM)

| Slot type | Strategy | Why not the alternative |
|---|---|---|
| `person_reference` | SQL exact/fuzzy lookup on `contacts` table (trigram/fuzzy match on name) | Names are structured data; embedding similarity on names is unreliable per Vision.md section 8 ("don't embed contact names") |
| `event_reference` (no `topic_reference` alongside it) | SQL lookup on `events` table, filtered by fuzzy time window (from `fuzzy_time_hint`) + resolved `person_reference` if present | Purely structured: participant + time window is a WHERE clause, not a semantic search |
| `event_reference` + `topic_reference` together | **Single joined query**: SQL filter (participant, time window) JOINed to vector ORDER BY (topic embedding similarity) — this is the exact pattern verified in section 8 | Conflating them into two separate calls wastes a retrieval pass and risks noise (Vision.md 7.1's explicit warning) |
| `topic_reference` alone (no event/person anchor) | Vector search over conversation-history embeddings only, unfiltered | No structured anchor exists yet to filter by |
| `temporal_expression` | **Not retrieved** — passed through as raw text to the Temporal Reasoning Engine after pass 2. Never a retrieval trigger itself. | Vision.md section 2's core principle: temporal arithmetic is deterministic, not retrieved or LLM-computed |
| `numeric_constraints` | **Not retrieved** — passed through verbatim to deterministic engines | Same principle: arithmetic is never retrieval or LLM work |
| `message_body` | **Not retrieved** | Free text destined for an action (e.g. SEND_MESSAGE), not a query |

Default: if a slot is `null`, no retrieval call is generated for it. The
decomposer only ever issues as many retrieval calls as there are non-null
structured/topic slots — never a blanket "search everything" pass.

## 3. Entity linking (resolves 7.2)

Runs as a **sub-step inside the SQL strategy above**, not a separate pipeline
stage — it's the disambiguation logic behind "SQL lookup on contacts", not new
architecture.

```
resolve_person(raw_text: String, ContactsDb) -> ContactId?  {
  candidates = ContactsDb.fuzzyMatch(raw_text)   // trigram/edit-distance, SQL-side
  when (candidates.size) {
    0 -> null                                     // triggers 7.7 fallback
    1 -> candidates[0].id
    else -> disambiguate(candidates)              // multiple "Rahul"s
  }
}

disambiguate(candidates) -> ContactId {
  // deterministic tie-break order, no LLM judgment call:
  1. most recent interaction_timestamp (calls/messages/calendar co-occurrence)
  2. highest interaction_frequency over trailing 90 days
  3. if still tied: surface for clarification (clarification_needed), don't guess
}
```

Same shape for `event_reference` -> `EventId` (disambiguate by time proximity
to `fuzzy_time_hint` instead of interaction recency).

## 4. Relevance/confidence filter before the Reasoning LLM (resolves 7.3)

Applied **after** retrieval, **before** context is handed to pass 2:

- SQL results: presence is binary (a row matched the WHERE clause or it didn't) —
  no threshold needed, they're already "relevant" by construction.
- Vector results: apply a **distance threshold**, not a fixed top-K cutoff. A
  fixed K always returns *something* even when nothing is relevant (Vision.md
  7.3's exact failure mode). Concretely: drop any vector hit whose distance
  exceeds a calibrated ceiling (to be set empirically per embedding model via
  the eval harness — not guessed at design time).
- If a joined query's vector leg produces zero results under the threshold but
  the SQL leg matched, pass the SQL rows through **without** topic context
  rather than dropping the whole retrieval (partial relevance is still useful:
  "found the meeting with Rahul, no document reference found").

## 5. Empty-retrieval fallback (resolves 7.7)

Rule-based relaxation ladder, attempted in order, stopping at first non-empty
result — never silently escalates to an LLM-driven retry (keeps this
deterministic/cheap, consistent with "prefer rule-based decomposition" in 7.1):

1. Exact structured filter (as decomposed above).
2. Relax time window (e.g. `fuzzy_time_hint` "yesterday" -> +/- 1 day; "that
   meeting" with no time hint -> trailing 14 days).
3. Relax name match (exact -> fuzzy/trigram, if step 1 used exact).
4. If still empty after all relaxations: return empty context explicitly (not
   silently) — `retrieved_context_used: []` — and let pass 2 set
   `clarification_needed` rather than confidently reasoning over nothing. This
   is the deterministic guardrail against the classic RAG failure mode Vision.md
   7.3 warns about, extended to the "found literally nothing" case.

## 6. Worked example (traces Vision.md section 4's own complex-command case)

Utterance: *"Remind me before that meeting with Rahul to bring the document we
discussed yesterday"*

```
slots = {
  person_reference: {raw_text: "Rahul"},
  event_reference: {raw_text: "that meeting", fuzzy_time_hint: null},
  topic_reference: {raw_text: "the document we discussed yesterday"}
}
```

Decomposer output (one joined call, per row 3 of the table above):

```sql
SELECT e.event_id, e.title, e.event_time, ee.distance
FROM event_embeddings ee
JOIN events e ON e.event_id = ee.event_id
WHERE ee.embedding MATCH :topic_query_vec
  AND e.participant_contact_id = :resolved_rahul_id   -- from entity linking, step 3
  AND e.event_time BETWEEN :now AND :now+14d          -- relaxation ladder step 2,
                                                       -- since fuzzy_time_hint was null
  AND k = 5
ORDER BY ee.distance
```

`resolved_rahul_id` comes from entity linking (section 3) run against
`person_reference` first; the joined query only runs once that resolves. If
entity linking itself returns zero contacts named "Rahul," short-circuit straight
to the empty-retrieval fallback (section 5) rather than running a doomed query.

## 7. Explicitly deferred (not solved here)

- Context Builder serialization format + token budget (7.4) — needs real
  retrieval payloads from this design to measure against; premature to fix now.
- Two-pass handoff contract (7.6) — whether pass 2 sees pass 1's full raw slots
  or a fresh prompt — is independent of decomposer internals and should be
  decided alongside Context Builder format.
- Calibrating the actual vector-distance threshold in section 4 — placeholder
  until the eval harness has real (utterance, embedding) pairs to tune against.
