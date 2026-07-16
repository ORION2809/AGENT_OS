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

A single small LLM serves both roles across two inference passes:
1. **Pass 1 (Semantic Parser)** — extracts structured slots from utterance
2. **Pass 2 (Reasoning)** — given retrieved context, produces a structured action plan

## Status

| Component | Status |
|---|---|
| **Vision & Architecture** (`Vision.md`) | Complete |
| **Structured Action Plan Schema** (`docs/structured_action_plan.schema.json`) | v1 final |
| **Query Decomposer Design** (`docs/query_decomposer_design.md`) | Complete |
| **GBNF Grammar** (`app/src/main/assets/structured_action_plan.v1.gbnf`) | Generated & bundled |
| **Semantic Parser System Prompt** (`app/src/main/assets/semantic_parser_system_prompt.v1.txt`) | 4-shot, 5/5 eval pass |
| **Desktop Verifications** (`docs/verification_findings.md`) | GBNF + sqlite-vec combo confirmed |
| **Android Verifications** | requery/sqlite-vec works on-device, 2 bugs fixed |
| **Evaluation Harness** (`research/eval-harness/`) | 5 cases, 5/5 pass |
| **Android App Scaffold** | Room DB, sqlite-vec, llama.cpp JNI bridge |
| **Data Layer** (`app/.../data/`) | Entities, DAOs, EmbeddingStore (joined structured+vector queries) |
| **QueryDecomposer** (`app/.../decomposer/`) | Full implementation: entity linking, time windows, relaxation ladder |
| **InferenceEngine** (`app/.../llm/`) | llama.cpp JNI bridge with grammar-constrained decoding |
| **PlanValidator** (`app/.../plan/`) | Runtime validation beyond GBNF (ranges, hallucination detection) |
| **Tests** | PlanValidatorTest (unit), QueryDecomposerTest & InferenceEngineTest (instrumented) |

## Tech Stack

| Layer | Choice |
|---|---|
| App | Kotlin + Jetpack Compose |
| VAD | Silero VAD (sherpa-onnx) |
| STT | Small multilingual ASR (sherpa-onnx) |
| Structured Memory | SQLite via Room |
| Vector Storage | sqlite-vec (Android via requery/sqlite-android) |
| Embeddings | EmbeddingGemma 308M (not yet wired) |
| Reasoning LLM | Qwen2.5-1.5B-Instruct GGUF (llama.cpp) |
| Model Format | GGUF |
| SQLite Backend | requery/sqlite-android (extension loading enabled) |
| TTS | Android TTS |

## Repository Structure

```
├── Vision.md                              # Full vision & architecture
├── docs/
│   ├── query_decomposer_design.md         # Query decomposer spec
│   ├── structured_action_plan.schema.json # v1 JSON Schema
│   └── verification_findings.md           # GBNF + sqlite-vec desktop & Android verification
├── models/
│   └── qwen2.5-1.5b-instruct-q4_k_m.gguf # Quantized reasoning model (gitignored)
├── research/
│   ├── eval-harness/                      # Python eval runner (5 cases, 5/5 pass)
│   ├── llama-verify/                      # System prompt iterations (v1→v3)
│   ├── sqlite-vec-verify/                 # Desktop Python verification
│   └── sqlite-vec-android/                # Prebuilt vec0.so for arm64 + x86_64
├── app/
│   ├── build.gradle.kts                   # Android app module (Compose, Room, KSP)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/
│       │   │   ├── semantic_parser_system_prompt.v1.txt
│       │   │   └── structured_action_plan.v1.gbnf
│       │   ├── cpp/
│       │   │   ├── CMakeLists.txt         # Builds llama.cpp via add_subdirectory
│       │   │   ├── llm_bridge.cpp         # JNI bridge with grammar switching (2-pass)
│       │   │   └── logging.h
│       │   ├── jniLibs/{arm64-v8a,x86_64}/
│       │   │   └── libvec0.so             # Prebuilt sqlite-vec extensions
│       │   └── java/com/mobileai/core/
│       │       ├── MainActivity.kt        # On-device sqlite-vec verifier
│       │       ├── data/
│       │       │   ├── AppDatabase.kt     # Room DB + sqlite-vec extension loading
│       │       │   ├── Entities.kt        # ContactEntity, EventEntity
│       │       │   ├── Daos.kt            # ContactDao, EventDao (fuzzy match + time windows)
│       │       │   └── EmbeddingStore.kt  # Combined structured+vector queries
│       │       ├── decomposer/
│       │       │   └── QueryDecomposer.kt # Entity linking, retrieval, relaxation ladder
│       │       ├── llm/
│       │       │   ├── InferenceEngine.kt       # Interface + state machine
│       │       │   └── InferenceEngineImpl.kt   # JNI wrapper with single-threaded dispatcher
│       │       └── plan/
│       │           ├── StructuredActionPlan.kt  # Kotlin serialization mirror of schema
│       │           └── PlanValidator.kt         # Runtime semantic validation
│       ├── test/.../plan/PlanValidatorTest.kt   # Unit tests (6 cases)
│       └── androidTest/
│           ├── decomposer/QueryDecomposerTest.kt # Instrumented (real sqlite-vec)
│           └── llm/InferenceEngineTest.kt        # End-to-end LLM on-device
├── third_party/
│   └── llama.cpp/                       # Git subtree (see CMakeLists.txt)
├── build.gradle.kts                     # Root Gradle (AGP 8.5.2, Kotlin 1.9.24)
├── settings.gradle.kts                  # jitpack.io for requery/sqlite-android
├── gradle.properties
├── gradlew / gradlew.bat
└── .gitignore
```

## Key Design Decisions

- **Grammar-constrained decoding** (GBNF) forces the LLM to emit valid `StructuredActionPlan` JSON — confirmed with Qwen2.5-1.5B
- **sqlite-vec** supports combined structured + vector similarity queries in a single SQL JOIN
- **requery/sqlite-android** replaces Android's stock SQLite (which has extension loading compiled out)
- **No dedicated NER model** — slot extraction is a schema-constraint problem handled by the LLM
- **Query Decomposer** maps slot types to retrieval strategies with deterministic entity linking, relevance filters, and an empty-retrieval relaxation ladder
- **Two bugs found on-device** that desktop testing missed: wrong sqlite-vec entry point symbol and missing Room `@ColumnInfo` annotations
- **PlanValidator** catches what GBNF cannot: numeric range violations and hallucinated empty-slot actions

## Running Tests

```bash
# Unit tests (JVM)
./gradlew test

# Instrumented tests (emulator/device)
# Push model first:
adb push models/qwen2.5-1.5b-instruct-q4_k_m.gguf /data/local/tmp/
./gradlew connectedAndroidTest

# Desktop eval harness (requires llama.cpp build)
cd research/eval-harness
python run_eval.py
```
