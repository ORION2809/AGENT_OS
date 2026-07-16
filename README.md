# AGENT_OS — On-Device Voice Assistant

A mobile-first voice assistant (Android) triggered via earbuds, running its intelligence pipeline primarily on-device. The core insight: **separate semantic understanding from deterministic reasoning.**

## Architecture

Language understanding is handled by a small local LLM, but anything requiring precision — arithmetic, date/time resolution, contact/location resolution — is handed off to deterministic engines instead of trusted to the LLM.

### Pipeline

```
User Speech → VAD → STT → Semantic Parser (Small LLM)
  → Query Decomposer → SQL + Vector Memory Retrieval
  → Context Builder → Reasoning LLM (Small LLM)
  → Deterministic Engines → Action Validator → Android Action
```

### One Model, Two Inference Passes

A single small LLM serves both roles:
1. **Pass 1 (Semantic Parser)** — extracts structured slots from utterance
2. **Pass 2 (Reasoning)** — given retrieved context, produces a structured action plan

## Tech Stack

| Layer | Choice |
|---|---|
| App | Kotlin + Jetpack Compose |
| VAD | Silero VAD (sherpa-onnx) |
| STT | Small multilingual ASR (sherpa-onnx) |
| Structured Memory | SQLite (Room) |
| Vector Storage | SQLite-linked (sqlite-vec) |
| Embeddings | EmbeddingGemma 308M |
| Reasoning LLM | Qwen2.5-1.5B-Instruct GGUF (llama.cpp) |
| Model Format | GGUF |
| TTS | Android TTS |

## Repository Structure

```
├── Vision.md                     # Full vision & architecture document
├── docs/
│   ├── query_decomposer_design.md       # Query decomposer spec (resolves Vision.md 7.1)
│   ├── structured_action_plan.schema.json  # v1 schema for LLM output contract
│   └── verification_findings.md         # GBNF + sqlite-vec verification results
├── models/
│   └── qwen2.5-1.5b-instruct-q4_k_m.gguf  # Quantized reasoning model
├── research/
│   ├── eval-harness/
│   │   ├── cases.json              # 5 eval cases (utterance → expected)
│   │   ├── run_eval.py             # Eval runner against llama.cpp
│   │   └── last_run_results.json   # Latest eval results (5/5 pass)
│   ├── llama-verify/
│   │   ├── system_prompt_v1.txt    # Zero-shot prompt (failed)
│   │   ├── system_prompt_v2.txt    # 2-shot prompt (partial)
│   │   └── system_prompt_v3.txt    # 4-shot final prompt (5/5 pass)
│   └── sqlite-vec-verify/
│       └── test_combined_query.py  # Verifies structured + vector join
└── .gitignore
```

## Key Design Decisions

- **Grammar-constrained decoding** (GBNF) forces the LLM to emit valid structured JSON — confirmed working with Qwen2.5-1.5B
- **sqlite-vec** supports combined structured + vector similarity queries in a single SQL statement
- **No dedicated NER model** — slot extraction is a schema-constraint problem handled by the LLM
- **Query Decomposer** maps slot types to retrieval strategies (SQL, vector, or joined)
- **Entity linking** resolves references ("Rahul" → contact_id) with deterministic tie-breaking
- **Relevance filter** applies a distance threshold (not fixed top-K) to vector results before the reasoning pass
- **Empty-retrieval relaxation ladder** tries exact match → wider time window → fuzzy name match before declaring "not found"
