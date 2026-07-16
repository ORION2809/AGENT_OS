# Hands-on verification: the two open items from Vision.md section 8

Both verified locally before any further design work, per Vision.md's own instruction.
Raw artifacts live under `research/llama-verify/` and `research/sqlite-vec-verify/`.

## 1. GBNF / grammar-constrained decoding quality (Qwen2.5-1.5B-Instruct)

**Setup**: built `llama.cpp` from source (MSVC + Ninja, CPU-only, commit at time of
verification) and ran `llama-completion.exe --json-schema-file docs/structured_action_plan.schema.json`
against `Qwen/Qwen2.5-1.5B-Instruct-GGUF` (Q4_K_M) with three utterances.

**Mechanical result: CONFIRMED, no caveats.**
- 3/3 outputs were syntactically valid JSON and 100% schema-conformant: closed `enum`
  values, `const schema_version`, `additionalProperties: false` respected (no stray
  fields ever emitted), nullable typed slots, and — most important given the array
  bug found below — **nested arrays-of-objects** (`numeric_constraints`) generated
  correctly with correct field typing on every element.
- Grammar overhead is negligible: ~3-5s wall time per parse on a 16-thread CPU with
  no GPU offload, most of which is normal token generation, not grammar bookkeeping.
- Qwen2.5-1.5B's tokenizer/architecture has no rough edges with llama.cpp's
  JSON-schema-to-grammar converter. This specific model is safe to lock in for the
  reasoning LLM role as far as GBNF support goes.

**Semantic result: NOT confirmed — this is prompt/example design, not a grammar
limitation, but it is real and must be tracked.**
- Zero-shot: wrong goal classification, no slot structure, everything dumped into
  `message_body`.
- 2-shot (one CREATE_REMINDER example, one CREATE_ALARM example): got the exact
  Vision.md flight-alarm example 100% right, including decomposing 3 simultaneous
  numeric constraints correctly.
- Same 2-shot prompt on a novel structurally-different utterance (topic_reference
  case) dropped a slot and hallucinated a spurious constraint.
- Same prompt on an out-of-scope utterance ("what's the weather") was wrongly forced
  into `CREATE_REMINDER` — with no negative/NO_ACTION example shown, the model
  pattern-matches toward the only goals it's seen.

**Implication for the design**: the grammar solves "is the output valid" but not "is
the output right." This is exactly why section 8 already calls for a
rules/tiny-classifier gate *before* the LLM call (so we don't even invoke the
schema-constrained LLM on non-actionable utterances), and why section 7.8's eval
harness isn't optional polish — few-shot prompt coverage will need active,
measured iteration, not a one-time write. Treat prompt/example curation as its own
ongoing workstream, not a solved side detail of the schema.

## 2. SQLite vector extension: combined structured + similarity queries

**Setup**: `sqlite-vec` v0.1.9 (Python bindings) against SQLite 3.50.4. Modeled
directly on Vision.md section 8's own example: "events where participant = Rahul,
ordered by similarity to 'document discussion'."

**Result: CONFIRMED.** A single SQL statement joins a `vec0` virtual table (holding
embeddings) to a normal relational table by shared id, applies a structured `WHERE`
filter, and orders by vector distance — all in one query:

```sql
SELECT e.event_id, e.title, e.event_time, ee.distance
FROM event_embeddings ee
JOIN events e ON e.event_id = ee.event_id
WHERE ee.embedding MATCH ?
  AND e.participant = 'Rahul'
  AND k = 5
ORDER BY ee.distance
```

- Structured filter correctly restricted the KNN candidate set (confirmed by a
  second test where filtering to a participant with no matching embedding produced
  correct-but-different results, not the unfiltered top-K).
- No evidence of needing two separate calls merged in application code — the
  Query Decomposer can be designed around single joined statements per slot type,
  as section 8 recommends.

**Not yet verified** (section 8's third item, lower priority, flagged for before
the encrypted-DB work starts): whether `sqlite-vec` operates cleanly against a
SQLCipher-encrypted connection without an unencrypted export step. This wasn't
blocking for schema/decomposer design and was deferred.

## Model lock-in decision

**Qwen2.5-1.5B-Instruct-GGUF (Q4_K_M) is confirmed usable** for the reasoning LLM
role on GBNF grounds. Its remaining weaknesses (goal misclassification, dropped
slots on novel phrasing) are addressable via prompt/example iteration and the
eval harness, not blockers to the architecture itself.
