# On-Device Voice Assistant — Vision & Architecture

## 1. Core Idea

A mobile (Android-first) voice assistant, triggered via earbuds, that runs its intelligence pipeline primarily on-device. The core insight: **separate semantic understanding from deterministic reasoning.** Language understanding is handled by a small local LLM, but anything that requires precision — arithmetic, date/time resolution, contact/location resolution — is handed off to deterministic engines instead of trusted to the LLM.

This avoids the classic failure mode of asking an LLM "what time should the alarm be?" (arithmetic errors) and instead has the LLM produce a structured intermediate representation that a deterministic engine resolves exactly.

## 2. Why "NER" Isn't the Right Frame

Classic NER extracts flat entities:

```
Rahul       → PERSON
Hyderabad   → LOCATION
Friday      → DATE
```

What this assistant actually needs is **semantic slot extraction** — structured, task-relevant meaning, not just tagged spans. Example: *"Wake me an hour before I have to leave"* decomposes into:

```
ACTION
    type: CREATE_ALARM

TEMPORAL_RELATION
    offset: -1 hour
    reference: REQUIRED_DEPARTURE_TIME

EVENT
    type: FLIGHT
    start_time: 08:30

TRAVEL_DURATION
    value: 45 minutes

ARRIVAL_BUFFER
    value: 2 hours
```

This is better called a **Semantic Parser** rather than an NER model. The LLM's job is to output a structured intermediate representation — not to directly call Android APIs.

Example structured output:

```json
{
  "goal": "CREATE_ALARM",
  "event": {
    "type": "flight",
    "time": "08:30",
    "date": "tomorrow"
  },
  "constraints": [
    { "type": "arrive_before", "duration_minutes": 120 },
    { "type": "travel_duration", "duration_minutes": 45 },
    { "type": "wake_before_departure", "duration_minutes": 60 }
  ]
}
```

A deterministic **Temporal Reasoning Engine** then calculates the exact alarm time. This is safer and more reliable than asking the LLM directly, since LLMs can make arithmetic mistakes.

The local LLM's role, restated:

```
Human language
     ↓
Understand meaning
     ↓
Resolve references
     ↓
Produce structured constraints
```

NOT:

```
Human language
     ↓
Guess everything
     ↓
Directly perform action
```

## 3. Frozen Intelligence Pipeline

```
Earbud
  │
  ▼
VAD
  │
  ▼
STT
  │
  ▼
┌──────────────────────────────────────┐
│       SEMANTIC UNDERSTANDING         │
│                                       │
│  Small Local LLM                     │
│        +                             │
│  Entity / Slot Extraction            │
└──────────────────┬───────────────────┘
                    │
                    ▼
            QUERY DECOMPOSER
                    │
           "What do I need to know?"
                    │
           ┌────────┴────────┐
           ▼                 ▼
         SQL             VECTOR
       MEMORY           MEMORY
           │                 │
           └────────┬────────┘
                    ▼
              RELEVANT CONTEXT
                    │
                    ▼
               REASONING LLM
                    │
                    ▼
           STRUCTURED ACTION PLAN
                    │
                    ▼
┌──────────────────────────────────────┐
│       DETERMINISTIC ENGINES          │
│                                       │
│ Temporal reasoning                   │
│ Date/time resolution                 │
│ Arithmetic                           │
│ Contact resolution                   │
│ Location resolution                  │
│ Permission validation                │
└──────────────────┬───────────────────┘
                    ▼
              ACTION VALIDATOR
                    │
                    ▼
               Android Action
```

## 4. One Model, Two Inference Passes

An important optimization: the semantic parser and reasoning LLM don't need to be separate models. One small local LLM can serve both roles across two inference passes:

```
                ONE SMALL LLM
                       │
           ┌───────────┴───────────┐
           ▼                       ▼
    First inference           Second inference
"What does this mean?"    "Given retrieved context,
                            what should we do?"
           │                       │
           ▼                       ▼
    Retrieval query           Action plan
```

**Simple commands** may only need one inference pass:

```
"Wake me at seven tomorrow"
        ↓
   Small LLM
        ↓
Structured action
        ↓
Date/time validator
        ↓
     Alarm
```

**Complex commands** need the full loop, e.g. *"Remind me before that meeting with Rahul to bring the document we discussed yesterday"*:

```
LLM semantic parsing
        ↓
Need: meeting with Rahul / relevant document / reminder time
        ↓
SQL + Vector retrieval
        ↓
Meeting = Friday 3 PM, Document = Phoenix architecture proposal
        ↓
LLM reasoning
        ↓
Structured action
        ↓
Temporal engine + validation
        ↓
Reminder
```

## 5. High-Level System Diagram

```
                 USER SPEECH
                       │
                       ▼
                  LOCAL STT
                       │
                       ▼
            LANGUAGE UNDERSTANDING
                       │
          ┌────────────┴────────────┐
          │                          │
    Entity Extraction         Intent Understanding
          │                          │
          └────────────┬─────────────┘
                       │
                       ▼
                MEMORY RETRIEVAL
               SQL + Vector Search
                       │
                       ▼
                 CONTEXT BUILDER
                       │
                       ▼
                REASONING LLM
                       │
                       ▼
               STRUCTURED PLAN
                       │
                       ▼
          DETERMINISTIC VALIDATION
                       │
                       ▼
                  ACTION ENGINE
```

## 6. Tech Stack

| Layer             | Our choice                   | Runtime/package                         | Why                               |
| ----------------- | ----------------------------- | ---------------------------------------- | ---------------------------------- |
| App               | Kotlin                        | Jetpack Compose                          | Native Android control             |
| Audio capture      | Android native                 | `AudioRecord`                            | Low-level streaming                |
| Earbud trigger     | Android native                 | `MediaSession` APIs                      | Bluetooth/media integration        |
| VAD                | Silero VAD                     | sherpa-onnx                              | Avoid processing silence           |
| STT                | Small multilingual ASR         | sherpa-onnx                              | Strong Android/offline support     |
| Structured memory  | SQLite                         | Room                                     | Stable and lightweight             |
| Vector storage     | SQLite-linked local index      | Native/local vector implementation       | One unified memory system          |
| Embeddings         | EmbeddingGemma 308M            | LiteRT/ONNX-compatible deployment        | Designed for on-device retrieval   |
| Intent routing     | Rules + tiny classifier        | Kotlin + ONNX/LiteRT                     | Avoid LLM for simple commands      |
| NER                | No dedicated model initially   | Reasoning model / deterministic parsers  | Saves RAM                          |
| Context retrieval  | Custom                         | Kotlin                                   | Full control over memory           |
| Reasoning          | Small quantized LLM            | `llama.cpp` initially                    | Broad model compatibility          |
| Model format       | GGUF                           | llama.cpp                                | Easy quantization/model switching  |
| Actions            | Android native                 | Intents + Android APIs                   | Reliable execution                 |
| TTS                | Android TTS                    | `TextToSpeech`                           | Zero bundled model initially       |
| Encryption         | Android Keystore               | Keystore + encrypted DB layer            | Protect personal memory            |

## 7. Open Design Questions in the Core Engine

These are the gaps identified in the intelligence core specifically (deliberately excluding wake-word, multi-turn UX, and other product-level edge cases, which are a separate concern):

### 7.1 Query Decomposer is underspecified — and it's the crux
"What do I need to know?" needs a concrete mapping from slot type → retrieval strategy:
- `person_reference` → SQL contact lookup
- `event_reference` → SQL calendar/event table, filtered by fuzzy time + participant
- `topic_reference` → vector search over conversation history

Not every slot needs both SQL and vector retrieval — conflating a structured lookup ("meeting with Rahul") with a fuzzy semantic one ("the document we discussed") wastes a retrieval pass and risks noise. Prefer rule-based/templated decomposition (slot type → query template) over LLM-driven decomposition wherever possible; reserve the LLM for genuinely ambiguous cases.

### 7.2 Entity linking / reference resolution has no home
"Rahul" → a specific `contact_id`. "That meeting" → a specific `event_id`. This is a distinct step between Semantic Parser output and the SQL query — not just string matching, but disambiguation logic (e.g. multiple contacts named Rahul → most recently interacted with? most frequent?). Currently there's no explicit component for this in the diagram.

### 7.3 No relevance/confidence filter before the Reasoning LLM
Vector search always returns *something*, even when nothing relevant exists. Retrieved context currently flows straight into the Reasoning LLM with no filter — a relevance/confidence threshold is needed before injection, or the reasoning pass will confidently reason over irrelevant context (classic RAG failure mode, but here it produces a wrong *action*, not just a wrong sentence).

### 7.4 Context Builder format and token budget undefined
How retrieved SQL rows + vector chunks + the semantic parse get merged into a single prompt matters more on a small on-device model than on a large cloud model — small models are more sensitive to structured vs. narrative context. Needs a decision: JSON, natural language, or hybrid — plus a token budget ceiling and truncation priority (recency vs. relevance score).

### 7.5 Structured Plan schema needs to be strict and grammar-constrained
Rather than hoping the model emits valid JSON, use grammar-constrained decoding (llama.cpp's GBNF support) to force output into a well-documented, versioned schema. Free-form JSON generation from a small quantized model will produce malformed output more often than expected — validate every output against the schema before it reaches the deterministic engines.

### 7.6 Two-pass handoff contract is undefined
Does pass 2 (reasoning) receive pass 1's (semantic parse) full raw output, or is it re-prompted from scratch with retrieved context plus the original utterance? If pass 2 doesn't see pass 1's full reasoning, nuance may be lost; if it does, context grows. This handoff needs an explicit contract.

### 7.7 Failure mode: retrieval returns nothing
If "meeting with Rahul" doesn't exist in memory, does the Reasoning LLM get empty context and report that, or does the Query Decomposer retry with a relaxed query (fuzzy time window, partial name match) first? This will happen constantly and needs explicit engine-level logic, not just a UX-level clarification prompt.

### 7.8 No evaluation harness for the parser/reasoner
Need a fixed set of (utterance → expected structured output) pairs to regression-test the semantic parser and reasoning LLM independently of the app, so prompt tweaks, model swaps, or quantization changes can be measured rather than guessed at.

**Highest leverage, in order:** (1) Structured plan schema + grammar constraint, (2) Query decomposition logic. Everything else — validators, retrieval calls, reasoning prompts — depends on these being nailed down first.

## 8. How the Chosen Tools Map to the Engine

**llama.cpp + GGUF**
Right choice specifically because llama.cpp supports GBNF grammar-constrained decoding — this is what solves the structured-plan-schema problem (§7.5), not an optional extra. Confirm the specific target model architecture has solid llama.cpp grammar support before locking it in; some architectures handle constrained decoding better than others.

**One model, two passes**
Since llama.cpp keeps the model loaded between calls, the real cost isn't reload time — it's alternating between two prompt *styles* (parse-mode vs. reason-mode) in the same session. Likely needs two distinct system prompts/grammars, and should be tested for quality degradation when the model switches roles turn-to-turn; small quantized models are more sensitive to this than larger cloud models.

**SQLite (Room) + SQLite-linked vector index**
The most important architectural fact: the vector store isn't separate infrastructure — it's SQLite-linked. This means the Query Decomposer doesn't need to explicitly branch between two systems; a single SQL statement can join structured filters (contact_id, event_id) with a vector similarity rank (e.g. via a sqlite-vec-like extension) — "events where participant = Rahul, ordered by similarity to 'document discussion.'" The decomposer should be designed around this joined-query capability rather than treating SQL and vector as separate calls merged afterward.

**EmbeddingGemma 308M**
Good for semantic/topic recall ("the document we discussed"). Not appropriate for entity linking ("Rahul" → contact_id) — that's exact/fuzzy structured matching (SQL, possibly with trigram/fuzzy-match support), not embedding similarity. Don't embed contact names and rely on vector similarity for entity resolution; keep that deterministic.

**Rules + tiny ONNX/LiteRT classifier**
Gates the two-pass LLM by deciding simple-command vs. full-pipeline. Could also absorb lightweight slot-type detection (date, person-reference, event-reference via rules/regex) before invoking the LLM at all, reducing how often the semantic-parsing pass is needed.

**No dedicated NER model**
Viable only if the first LLM pass reliably does slot extraction via a grammar-constrained schema that explicitly includes typed fields (person_reference, event_reference, temporal_expression, etc.) rather than free text. This reframes "no NER" as "NER becomes a schema-constraint problem" — worth stating explicitly rather than assuming implicitly.

**Encrypted DB (Keystore)**
Worth confirming hands-on: if vector search runs against the SQLite-linked index, and the DB is encrypted at rest via Keystore, does the vector extension operate against the decrypted/opened connection without needing an unencrypted export step? SQLite extensions and encryption wrappers (e.g. SQLCipher) don't always play well together.

**Two things to verify hands-on before building further:**
1. GBNF grammar support quality for the specific GGUF model chosen.
2. Whether the chosen SQLite vector extension supports combined structured + similarity queries in one statement — the Query Decomposer design should be built around this if it exists.