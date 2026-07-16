// JNI bridge to llama.cpp for the Reasoning LLM / Semantic Parser passes (Vision.md sections 3-4).
// Adapted from llama.cpp's own examples/llama.android/lib/src/main/cpp/ai_chat.cpp, trimmed to
// what this project needs (no benchmarking) and extended with grammar-constrained decoding
// support (setGrammar), since the whole point of this pipeline is forcing valid
// StructuredActionPlan JSON out of a small quantized model -- see
// docs/verification_findings.md section 1 and docs/structured_action_plan.schema.json.
#include <android/log.h>
#include <jni.h>
#include <cmath>
#include <string>
#include <unistd.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

constexpr int   N_THREADS_MIN        = 2;
constexpr int   N_THREADS_MAX        = 4;
constexpr int   N_THREADS_HEADROOM   = 2;

constexpr int   DEFAULT_CONTEXT_SIZE = 8192;
constexpr int   OVERFLOW_HEADROOM    = 4;
constexpr int   BATCH_SIZE           = 512;
constexpr float DEFAULT_SAMPLER_TEMP = 0.2f; // matches the temp used during desktop verification

static llama_model             * g_model;
static llama_context            * g_context;
static llama_batch                g_batch;
static common_chat_templates_ptr  g_chat_templates;
static common_sampler            * g_sampler;
static std::string                g_pending_grammar; // set via setGrammar, consumed by prepare()

extern "C"
JNIEXPORT void JNICALL
Java_com_mobileai_core_llm_InferenceEngineImpl_init(JNIEnv *env, jobject, jstring nativeLibDir) {
    llama_log_set(aichat_android_log_callback, nullptr);

    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, nullptr);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_mobileai_core_llm_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    const auto *model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    LOGd("%s: Loading model from: %s", __func__, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}

static common_sampler *new_sampler(float temp, const std::string &grammar);

/**
 * Switches grammar between the two passes of Vision.md section 4's "one model, two passes"
 * design (parse-mode vs. reason-mode) without a full model reload. If the sampler already
 * exists, rebuilds it immediately with the new grammar; otherwise stages the value for
 * prepare() to pick up on first load. Called with null to fall back to unconstrained decoding.
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_mobileai_core_llm_InferenceEngineImpl_nativeSetGrammar(JNIEnv *env, jobject, jstring jgrammar) {
    if (jgrammar == nullptr) {
        g_pending_grammar.clear();
    } else {
        const auto *grammar = env->GetStringUTFChars(jgrammar, nullptr);
        g_pending_grammar.assign(grammar);
        env->ReleaseStringUTFChars(jgrammar, grammar);
    }
    LOGd("%s: grammar set (%zu chars)", __func__, g_pending_grammar.size());

    if (g_sampler != nullptr) {
        common_sampler_free(g_sampler);
        g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP, g_pending_grammar);
    }
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);

    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_init_from_model() returned null", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp, const std::string &grammar) {
    common_params_sampling sparams;
    sparams.temp = temp;
    // COMMON_GRAMMAR_TYPE_USER: raw GBNF text we pre-generated from structured_action_plan.schema.json
    // (see docs/verification_findings.md) -- not the auto-from-JSON-schema path, since the grammar
    // is already generated and bundled as an asset.
    if (!grammar.empty()) {
        sparams.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, grammar);
    }
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_mobileai_core_llm_InferenceEngineImpl_prepare(JNIEnv *, jobject) {
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP, g_pending_grammar);
    return 0;
}

constexpr const char *ROLE_SYSTEM    = "system";
constexpr const char *ROLE_USER      = "user";
constexpr const char *ROLE_ASSISTANT = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;
static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_long_term_states() {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;
    if (g_context) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
}

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);
    chat_msgs.push_back(new_msg);
    return formatted;
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == (int) tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }
        if (llama_decode(context, batch) != 0) {
            LOGe("%s: llama_decode failed", __func__);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_mobileai_core_llm_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env, jobject, jstring jsystem_prompt) {
    reset_long_term_states();
    reset_short_term_states();

    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    std::string formatted_system_prompt(system_prompt);
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                                has_chat_template, has_chat_template);

    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context!", __func__);
        return 1;
    }

    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        return 2;
    }

    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_mobileai_core_llm_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env, jobject, jstring juser_prompt, jint n_predict) {
    reset_short_term_states();

    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    std::string formatted_user_prompt(user_prompt);
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);

    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Truncated.", __func__);
    }

    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        return 2;
    }

    current_position += user_prompt_size;
    stop_generation_position = current_position + n_predict;
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }
    const auto *bytes = (const unsigned char *) string;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) { num = 1; }
        else if ((*bytes & 0xE0) == 0xC0) { num = 2; }
        else if ((*bytes & 0xF0) == 0xE0) { num = 3; }
        else if ((*bytes & 0xF8) == 0xF0) { num = 4; }
        else { return false; }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) { return false; }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_mobileai_core_llm_InferenceEngineImpl_generateNextToken(JNIEnv *env, jobject) {
    if (current_position >= stop_generation_position) {
        return nullptr;
    }

    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }
    current_position++;

    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    jstring result;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        result = env->NewStringUTF("");
    }
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mobileai_core_llm_InferenceEngineImpl_unload(JNIEnv *, jobject) {
    reset_long_term_states();
    reset_short_term_states();
    if (g_sampler) common_sampler_free(g_sampler);
    g_chat_templates.reset();
    if (g_batch.token) llama_batch_free(g_batch);
    if (g_context) llama_free(g_context);
    if (g_model) llama_model_free(g_model);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mobileai_core_llm_InferenceEngineImpl_shutdown(JNIEnv *, jobject) {
    llama_backend_free();
}
