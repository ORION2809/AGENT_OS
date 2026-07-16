package com.mobileai.core.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * JNI wrapper for llama.cpp (see llm_bridge.cpp), adapted from llama.cpp's own
 * examples/llama.android reference project. All native calls are confined to a single-threaded
 * dispatcher: llama.cpp's C API is not safe to call concurrently from multiple threads, and the
 * reviewer pass on this codebase (see project history) specifically flagged this as the thing to
 * get right before wiring real LLM calls in -- this is that guarantee.
 */
internal class InferenceEngineImpl private constructor(
    private val nativeLibDir: String,
) : InferenceEngine {

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        @Volatile
        private var instance: InferenceEngine? = null

        fun getInstance(context: Context): InferenceEngine =
            instance ?: synchronized(this) {
                instance ?: run {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }
                    try {
                        InferenceEngineImpl(nativeLibDir).also { instance = it }
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "Failed to load native library from $nativeLibDir", e)
                        throw e
                    }
                }
            }
    }

    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    private external fun nativeSetGrammar(gbnfGrammar: String?)
    private external fun prepare(): Int
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun unload()
    private external fun shutdown()

    private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private var readyForSystemPrompt = false

    @Volatile
    private var cancelGeneration = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            check(_state.value is InferenceEngine.State.Uninitialized) {
                "Cannot load native library in ${_state.value.javaClass.simpleName}!"
            }
            _state.value = InferenceEngine.State.Initializing
            System.loadLibrary("mobileai-llm")
            init(nativeLibDir)
            _state.value = InferenceEngine.State.Initialized
            Log.i(TAG, "Native library loaded.")
        }
    }

    override suspend fun loadModel(pathToModel: String) = withContext(llamaDispatcher) {
        check(_state.value is InferenceEngine.State.Initialized) {
            "Cannot load model in ${_state.value.javaClass.simpleName}!"
        }
        try {
            File(pathToModel).let {
                require(it.exists()) { "Model file not found: $pathToModel" }
                require(it.isFile) { "Not a valid file: $pathToModel" }
                require(it.canRead()) { "Cannot read model file: $pathToModel" }
            }

            readyForSystemPrompt = false
            _state.value = InferenceEngine.State.LoadingModel
            if (load(pathToModel) != 0) throw UnsupportedArchitectureException()
            if (prepare() != 0) throw IOException("Failed to prepare inference resources")

            readyForSystemPrompt = true
            cancelGeneration = false
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model: $pathToModel", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }

    override suspend fun setGrammar(gbnfGrammar: String?) = withContext(llamaDispatcher) {
        check(_state.value is InferenceEngine.State.ModelReady) {
            "Cannot set grammar in ${_state.value.javaClass.simpleName}!"
        }
        nativeSetGrammar(gbnfGrammar)
    }

    override suspend fun setSystemPrompt(prompt: String) = withContext(llamaDispatcher) {
        require(prompt.isNotBlank()) { "Cannot process empty system prompt!" }
        check(readyForSystemPrompt) { "System prompt must be set right after model loaded!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
        }

        readyForSystemPrompt = false
        _state.value = InferenceEngine.State.ProcessingSystemPrompt
        val result = processSystemPrompt(prompt)
        if (result != 0) {
            val error = RuntimeException("Failed to process system prompt: $result")
            _state.value = InferenceEngine.State.Error(error)
            throw error
        }
        _state.value = InferenceEngine.State.ModelReady
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        try {
            _state.value = InferenceEngine.State.ProcessingUserPrompt
            val result = processUserPrompt(message, predictLength)
            if (result != 0) {
                Log.e(TAG, "Failed to process user prompt: $result")
                return@flow
            }

            _state.value = InferenceEngine.State.Generating
            while (!cancelGeneration) {
                val token = generateNextToken() ?: break
                if (token.isNotEmpty()) emit(token)
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    override fun cleanUp() {
        cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (_state.value) {
                is InferenceEngine.State.ModelReady, is InferenceEngine.State.Error -> {
                    readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel
                    unload()
                    _state.value = InferenceEngine.State.Initialized
                }
                else -> Unit
            }
        }
    }

    override fun destroy() {
        cancelGeneration = true
        runBlocking(llamaDispatcher) {
            readyForSystemPrompt = false
            when (_state.value) {
                is InferenceEngine.State.Uninitialized -> Unit
                is InferenceEngine.State.Initialized -> shutdown()
                else -> {
                    unload()
                    shutdown()
                }
            }
        }
        llamaScope.cancel()
    }
}
