package com.mobileai.core.llm

import kotlinx.coroutines.flow.Flow

/**
 * Adapted from llama.cpp's own examples/llama.android/lib/src/main/java/com/arm/aichat/InferenceEngine.kt,
 * trimmed to what this project's two-pass pipeline needs (Vision.md section 4). Notably adds
 * [setGrammar] -- grammar-constrained decoding is not optional here (docs/verification_findings.md
 * section 1), so it's a first-class part of the load sequence rather than an extension.
 */
interface InferenceEngine {
    sealed interface State {
        data object Uninitialized : State
        data object Initializing : State
        data object Initialized : State
        data object LoadingModel : State
        data object ModelReady : State
        data object ProcessingSystemPrompt : State
        data object ProcessingUserPrompt : State
        data object Generating : State
        data object UnloadingModel : State
        data class Error(val cause: Throwable) : State
    }

    val state: kotlinx.coroutines.flow.StateFlow<State>

    suspend fun loadModel(pathToModel: String)

    /**
     * GBNF grammar text (or null to clear) to constrain the *next* prompt's decoding. Must be
     * called before [loadModel] finishes preparing the sampler -- see llm_bridge.cpp's
     * setGrammar/prepare ordering.
     */
    suspend fun setGrammar(gbnfGrammar: String?)

    suspend fun setSystemPrompt(prompt: String)

    fun sendUserPrompt(message: String, predictLength: Int): Flow<String>

    fun cleanUp()

    fun destroy()
}

class UnsupportedArchitectureException :
    Exception("Model architecture is not supported by this build of llama.cpp")
