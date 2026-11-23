#include <android/log.h>
#include <jni.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"
#include "sampling.h"

#define TAG "ColgramLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::mutex g_engine_mutex;
std::mutex g_error_mutex;
std::atomic<bool> g_cancel{false};
std::atomic<bool> g_backend_ready{false};

llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
mtmd_context * g_mtmd = nullptr;
common_chat_templates_ptr g_tmpls;
int g_n_ctx = 4096;
int g_n_batch = 256;
int g_n_threads = 2;
bool g_has_vision = false;
std::string g_mmproj_path;
enum llama_flash_attn_type g_flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
llama_tokens g_last_prompt_tokens;
std::string g_last_error;
std::string g_last_timings;
std::string g_model_desc;

void set_last_error(const std::string & msg) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = msg;
    if (!msg.empty()) {
        LOGE("%s", msg.c_str());
    }
}

std::string get_last_error() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return g_last_error;
}

void colgram_llama_log_cb(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!text) return;
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default:                   prio = ANDROID_LOG_DEBUG; break;
    }
    if (prio >= ANDROID_LOG_INFO) {
        __android_log_print(prio, TAG, "%s", text);
    }
}

bool colgram_abort_cb(void * /*data*/) {
    return g_cancel.load(std::memory_order_relaxed);
}

void ensure_backend_initialized() {
    if (!g_backend_ready.exchange(true)) {
        llama_log_set(colgram_llama_log_cb, nullptr);
        mtmd_helper_log_set(colgram_llama_log_cb, nullptr);
        llama_backend_init();
    }
}

// Convert valid UTF-8 bytes into Android JNI jstring via UTF-16 so 4-byte emoji/math never abort CheckJNI.
jstring utf8_to_jstring(JNIEnv * env, const std::string & utf8) {
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    const unsigned char * p = reinterpret_cast<const unsigned char *>(utf8.data());
    const size_t n = utf8.size();
    size_t i = 0;
    while (i < n) {
        uint32_t cp = 0;
        unsigned char c = p[i];
        if ((c & 0x80) == 0) {
            cp = c;
            i += 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < n) {
            cp = ((uint32_t)(c & 0x1F) << 6) | (p[i + 1] & 0x3F);
            i += 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < n) {
            cp = ((uint32_t)(c & 0x0F) << 12) | ((uint32_t)(p[i + 1] & 0x3F) << 6) | (p[i + 2] & 0x3F);
            i += 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < n) {
            cp = ((uint32_t)(c & 0x07) << 18) | ((uint32_t)(p[i + 1] & 0x3F) << 12)
               | ((uint32_t)(p[i + 2] & 0x3F) << 6) | (p[i + 3] & 0x3F);
            i += 4;
        } else {
            cp = 0xFFFD;
            i += 1;
        }

        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(utf16.empty() ? nullptr : utf16.data(), static_cast<jsize>(utf16.size()));
}

size_t utf8_complete_prefix_len(const std::string & buf) {
    const size_t n = buf.size();
    size_t i = 0;
    while (i < n) {
        unsigned char c = static_cast<unsigned char>(buf[i]);
        size_t need = 1;
        if ((c & 0x80) == 0) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else {
            // Invalid leading byte: emit replacement cleanly by stepping 1 byte.
            i += 1;
            continue;
        }
        if (i + need > n) {
            break;
        }
        i += need;
    }
    return i;
}

std::string jstring_to_std(JNIEnv * env, jstring jstr) {
    if (!jstr) return "";
    const char * chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return "";
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

void unload_locked() {
    if (g_mtmd) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
    }
    g_tmpls.reset();
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_has_vision = false;
    g_mmproj_path.clear();
    g_last_prompt_tokens.clear();
    g_model_desc.clear();
}

bool ensure_mtmd_loaded_locked() {
    if (g_mtmd != nullptr) {
        return g_has_vision;
    }
    if (!g_model || !g_ctx || g_mmproj_path.empty()) {
        return false;
    }
    mtmd_context_params vparams = mtmd_context_params_default();
    vparams.use_gpu = false;
    vparams.n_threads = g_n_threads;
    vparams.flash_attn_type = g_flash_attn_type;
    vparams.warmup = false;
    vparams.image_min_tokens = 64;
    vparams.image_max_tokens = 384; // bounded visual token budget for snappy mobile inference

    auto mem = mtmd_get_memory_usage(g_mmproj_path.c_str(), vparams);
    const int n_ubatch = static_cast<int>(llama_n_ubatch(g_ctx));
    if (mem.use_non_causal && mem.image_max_tokens > n_ubatch) {
        vparams.image_max_tokens = n_ubatch;
        vparams.image_min_tokens = std::min(vparams.image_min_tokens, n_ubatch);
    }

    LOGI("Lazy-loading Vision mmproj: %s", g_mmproj_path.c_str());
    g_mtmd = mtmd_init_from_file(g_mmproj_path.c_str(), g_model, vparams);
    if (!g_mtmd) {
        LOGW("Vision mmproj failed to load (%s), continuing text-only", g_mmproj_path.c_str());
        g_has_vision = false;
        return false;
    }
    g_has_vision = mtmd_support_vision(g_mtmd);
    LOGI("Vision mmproj ready=%d", g_has_vision ? 1 : 0);
    return g_has_vision;
}

bool decode_tokens(llama_context * ctx, common_batch & batch, const llama_tokens & tokens,
                   llama_pos start_pos, int n_batch, bool logits_last) {
    for (int i = 0; i < static_cast<int>(tokens.size()); i += n_batch) {
        if (g_cancel.load(std::memory_order_relaxed)) {
            return false;
        }
        const int cur = std::min(static_cast<int>(tokens.size()) - i, n_batch);
        batch.clear();
        for (int j = 0; j < cur; ++j) {
            const bool want_logit = logits_last && (i + j == static_cast<int>(tokens.size()) - 1);
            batch.add(tokens[i + j], start_pos + i + j, 0, want_logit);
        }
        const int rc = llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (rc != 0) {
            set_last_error("llama_process decode failed rc=" + std::to_string(rc));
            return false;
        }
    }
    return true;
}

bool ends_with(const std::string & s, const std::string & suffix) {
    return s.size() >= suffix.size() &&
           s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_org_colgram_core_ColgramNativeLlama_nativeGetSystemInfo(JNIEnv * env, jclass /*clazz*/) {
    ensure_backend_initialized();
    const char * info = llama_print_system_info();
    return utf8_to_jstring(env, info ? std::string(info) : std::string("llama.cpp"));
}

JNIEXPORT jint JNICALL
Java_org_colgram_core_ColgramNativeLlama_nativeInit(
        JNIEnv * env,
        jclass /*clazz*/,
        jstring jModelPath,
        jstring jMmprojPath,
        jint nCtx,
        jint nThreads,
        jint kvQuantBits,
        jboolean flashAttn) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    ensure_backend_initialized();
    g_cancel.store(false);
    set_last_error("");
    unload_locked();

    const std::string modelPath = jstring_to_std(env, jModelPath);
    const std::string mmprojPath = jstring_to_std(env, jMmprojPath);
    if (modelPath.empty()) {
        set_last_error("Empty GGUF model path");
        return -1;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    mparams.check_tensors = false;

    LOGI("Loading GGUF model (mmap): %s", modelPath.c_str());
    g_model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (!g_model) {
        set_last_error("Failed to load GGUF model: " + modelPath);
        return -2;
    }

    const int32_t online = std::max(1, static_cast<int32_t>(sysconf(_SC_NPROCESSORS_ONLN)));
    const int threads = nThreads > 0 ? std::clamp<int>(nThreads, 1, std::max(2, online)) : std::clamp<int>(online - 1, 2, 4);
    const int32_t trainCtx = llama_model_n_ctx_train(g_model);
    int requestedCtx = nCtx > 512 ? static_cast<int>(nCtx) : 4096;
    if (trainCtx > 0 && requestedCtx > trainCtx) {
        requestedCtx = trainCtx;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(requestedCtx);
    cparams.n_batch = 256;
    cparams.n_ubatch = 256;
    cparams.n_threads = threads;
    // Match batch threads to performance-core thread budget so prefill doesn't stall on slow Cortex-A55 little cores.
    cparams.n_threads_batch = threads;
    cparams.flash_attn_type = flashAttn ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_AUTO;
    g_flash_attn_type = cparams.flash_attn_type;
    if (kvQuantBits == 4) {
        cparams.type_k = GGML_TYPE_Q4_0;
        cparams.type_v = GGML_TYPE_Q4_0;
    } else if (kvQuantBits == 8) {
        cparams.type_k = GGML_TYPE_Q8_0;
        cparams.type_v = GGML_TYPE_Q8_0;
    }
    cparams.abort_callback = colgram_abort_cb;
    cparams.abort_callback_data = nullptr;
    cparams.no_perf = false;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        set_last_error("Failed to initialize llama_context (try lower context or smaller quant)");
        unload_locked();
        return -3;
    }

    g_n_ctx = static_cast<int>(llama_n_ctx(g_ctx));
    g_n_batch = static_cast<int>(cparams.n_batch);
    g_n_threads = threads;
    g_tmpls = common_chat_templates_init(g_model, "");

    char desc[256] = {};
    llama_model_desc(g_model, desc, sizeof(desc));
    g_model_desc = desc;

    // Lazy-load Vision mmproj on first photo/video turn so text turns initialize fast & stay light on RAM.
    g_mmproj_path = mmprojPath;
    g_has_vision = !mmprojPath.empty();

    LOGI("Initialized model=%s ctx=%d threads=%d vision=%d(lazy) kv=%dbit",
         g_model_desc.c_str(), g_n_ctx, g_n_threads, g_has_vision ? 1 : 0, (int) kvQuantBits);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_org_colgram_core_ColgramNativeLlama_nativeGetModelInfo(JNIEnv * env, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!g_model || !g_ctx) {
        return utf8_to_jstring(env, "");
    }
    std::ostringstream ss;
    ss << g_model_desc
       << " | ctx=" << g_n_ctx
       << " | thr=" << g_n_threads
       << " | mmap=1"
       << " | flash=1"
       << " | vision=" << (g_has_vision ? "ready" : "off");
    return utf8_to_jstring(env, ss.str());
}

static jint generate_internal(
        JNIEnv * env,
        jstring jSystemPrompt,
        jobjectArray jHistoryPairs,
        jstring jUserPrompt,
        jobjectArray jImagePaths,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jint repeatPenalty100,
        jboolean enableThinking,
        jobject jCallback) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!g_model || !g_ctx) {
        set_last_error("Model is not loaded");
        return -1;
    }
    // Keep inference slightly below foreground UI priority (0) without starving CPU throughput.
    setpriority(PRIO_PROCESS, 0, 2);

    g_cancel.store(false);
    set_last_error("");

    jmethodID onTokenMethod = nullptr;
    if (jCallback != nullptr) {
        jclass cbClass = env->GetObjectClass(jCallback);
        if (cbClass != nullptr) {
            onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
            env->DeleteLocalRef(cbClass);
        }
    }

    const std::string systemPrompt = jstring_to_std(env, jSystemPrompt);
    std::string userPrompt = jstring_to_std(env, jUserPrompt);

    std::vector<std::pair<std::string, std::string>> historyTurns;
    if (jHistoryPairs != nullptr) {
        const jsize pairLen = env->GetArrayLength(jHistoryPairs);
        for (jsize i = 0; i + 1 < pairLen; i += 2) {
            jstring jU = static_cast<jstring>(env->GetObjectArrayElement(jHistoryPairs, i));
            jstring jA = static_cast<jstring>(env->GetObjectArrayElement(jHistoryPairs, i + 1));
            std::string uStr = jstring_to_std(env, jU);
            std::string aStr = jstring_to_std(env, jA);
            if (jU) env->DeleteLocalRef(jU);
            if (jA) env->DeleteLocalRef(jA);
            if (!uStr.empty() && !aStr.empty()) {
                historyTurns.emplace_back(std::move(uStr), std::move(aStr));
            }
        }
    }

    std::vector<std::string> imagePaths;
    if (jImagePaths != nullptr) {
        const jsize arrLen = env->GetArrayLength(jImagePaths);
        for (jsize i = 0; i < arrLen; ++i) {
            jstring item = static_cast<jstring>(env->GetObjectArrayElement(jImagePaths, i));
            std::string p = jstring_to_std(env, item);
            if (item) env->DeleteLocalRef(item);
            if (!p.empty()) {
                imagePaths.push_back(std::move(p));
            }
        }
    }

    const bool useVision = !imagePaths.empty() && ensure_mtmd_loaded_locked();
    const std::string marker = mtmd_default_marker();

    std::vector<mtmd_bitmap *> rawBitmaps;
    std::vector<mtmd_helper_video *> rawVideos;
    if (useVision) {
        mtmd_helper_init_opt opt = mtmd_helper_init_opt_default();
        std::string markerPrefix;
        for (const std::string & imgPath : imagePaths) {
            auto w = mtmd_helper_bitmap_init_from_file(g_mtmd, imgPath.c_str(), false, opt);
            if (w.bitmap != nullptr) {
                rawBitmaps.push_back(w.bitmap);
                if (w.video_ctx != nullptr) {
                    rawVideos.push_back(w.video_ctx);
                }
                markerPrefix += marker;
                markerPrefix += "\n";
            }
        }
        if (!rawBitmaps.empty() && userPrompt.find(marker) == std::string::npos) {
            userPrompt = markerPrefix + userPrompt;
        }
    }

    auto cleanupMedia = [&]() {
        for (mtmd_bitmap * b : rawBitmaps) {
            if (b) mtmd_bitmap_free(b);
        }
        rawBitmaps.clear();
        for (mtmd_helper_video * v : rawVideos) {
            if (v) mtmd_helper_video_free(v);
        }
        rawVideos.clear();
    };

    // Build native multi-turn prompt using the model's exact embedded GGUF Jinja chat template
    // (never inject synthetic <think> blocks into historical assistant turns, and preserve Qwen3.5's
    // exact double-newline `\n\n` after `</think>` when non-thinking mode is active).
    std::string formattedPrompt;
    {
        std::vector<common_chat_msg> msgs;
        if (!systemPrompt.empty()) {
            common_chat_msg sys;
            sys.role = "system";
            sys.content = systemPrompt;
            msgs.push_back(std::move(sys));
        }
        for (const auto & turn : historyTurns) {
            common_chat_msg hu;
            hu.role = "user";
            hu.content = turn.first;
            msgs.push_back(std::move(hu));
            common_chat_msg ha;
            ha.role = "assistant";
            ha.content = turn.second;
            msgs.push_back(std::move(ha));
        }
        common_chat_msg usr;
        usr.role = "user";
        usr.content = userPrompt;
        msgs.push_back(std::move(usr));

        if (g_tmpls) {
            common_chat_templates_inputs inputs;
            inputs.messages = msgs;
            inputs.add_generation_prompt = true;
            inputs.use_jinja = true;
            inputs.enable_thinking = enableThinking ? true : false;
            common_chat_params chatParams = common_chat_templates_apply(g_tmpls.get(), inputs);
            formattedPrompt = chatParams.prompt.str();
        } else {
            if (!systemPrompt.empty()) {
                formattedPrompt += "<|im_start|>system\n" + systemPrompt + "<|im_end|>\n";
            }
            for (const auto & turn : historyTurns) {
                formattedPrompt += "<|im_start|>user\n" + turn.first + "<|im_end|>\n";
                formattedPrompt += "<|im_start|>assistant\n" + turn.second + "<|im_end|>\n";
            }
            formattedPrompt += "<|im_start|>user\n" + userPrompt + "<|im_end|>\n<|im_start|>assistant\n";
        }
        if (!enableThinking) {
            const size_t lastAsst = formattedPrompt.rfind("<|im_start|>assistant");
            const bool alreadyClosedThink = lastAsst != std::string::npos
                    && formattedPrompt.find("</think>", lastAsst) != std::string::npos;
            if (!alreadyClosedThink && ends_with(formattedPrompt, "<|im_start|>assistant\n")) {
                formattedPrompt += "<think>\n\n</think>\n\n";
            }
        }
    }

    const auto t0 = std::chrono::steady_clock::now();
    llama_pos n_past = 0;
    size_t reusedPrefixTokens = 0;
    llama_memory_t mem = llama_get_memory(g_ctx);

    if (!rawBitmaps.empty()) {
        g_last_prompt_tokens.clear();
        llama_memory_clear(mem, true);

        std::vector<std::string> segments;
        size_t start = 0;
        size_t found;
        while ((found = formattedPrompt.find(marker, start)) != std::string::npos) {
            segments.push_back(formattedPrompt.substr(start, found - start));
            start = found + marker.size();
        }
        segments.push_back(formattedPrompt.substr(start));

        const size_t nMedia = std::min(rawBitmaps.size(), segments.size() > 0 ? segments.size() - 1 : 0);
        std::vector<mtmd_input_text> texts(segments.size());
        std::vector<mtmd_input_part> parts;
        parts.reserve(segments.size() + nMedia);
        for (size_t i = 0; i < segments.size(); ++i) {
            texts[i] = {segments[i].data(), segments[i].size(), false, true};
            parts.push_back({&texts[i], nullptr});
            if (i < nMedia) {
                parts.push_back({nullptr, rawBitmaps[i]});
            }
        }
        std::vector<const mtmd_input_part *> partPtrs;
        partPtrs.reserve(parts.size());
        for (const auto & p : parts) {
            partPtrs.push_back(&p);
        }

        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        const int32_t tokRc = mtmd_tokenize_from_parts(g_mtmd, chunks, partPtrs.data(), partPtrs.size(), true);
        cleanupMedia();
        if (tokRc != 0) {
            mtmd_input_chunks_free(chunks);
            set_last_error("mtmd_tokenize_from_parts failed rc=" + std::to_string(tokRc));
            return -4;
        }
        const int32_t evalRc = mtmd_helper_eval_chunks(g_mtmd, g_ctx, chunks, 0, 0, g_n_batch, true, &n_past);
        mtmd_input_chunks_free(chunks);
        if (evalRc != 0) {
            set_last_error(g_cancel.load() ? "Cancelled" : ("mtmd_helper_eval_chunks failed rc=" + std::to_string(evalRc)));
            return g_cancel.load() ? -99 : -5;
        }
    } else {
        cleanupMedia();
        llama_tokens promptTokens = common_tokenize(g_ctx, formattedPrompt, true, true);
        const int maxPromptTokens = std::max(128, g_n_ctx - std::min(maxTokens, g_n_ctx / 2) - 16);
        if (static_cast<int>(promptTokens.size()) > maxPromptTokens) {
            const int keepHead = std::min(320, maxPromptTokens / 4);
            const int keepTail = maxPromptTokens - keepHead;
            llama_tokens trimmed;
            trimmed.reserve(maxPromptTokens);
            trimmed.insert(trimmed.end(), promptTokens.begin(), promptTokens.begin() + keepHead);
            trimmed.insert(trimmed.end(), promptTokens.end() - keepTail, promptTokens.end());
            promptTokens.swap(trimmed);
        }

        // Hybrid DeltaNet + Attention prefix reuse:
        // ONLY reuse on exact pure append (no partial rewind on recurrent DeltaNet layers,
        // which otherwise drifts SSM hidden state and causes cross-turn repetition!).
        if (!g_last_prompt_tokens.empty() && promptTokens.size() > g_last_prompt_tokens.size()) {
            size_t commonLen = 0;
            while (commonLen < g_last_prompt_tokens.size()
                    && g_last_prompt_tokens[commonLen] == promptTokens[commonLen]) {
                ++commonLen;
            }
            if (commonLen == g_last_prompt_tokens.size()) {
                reusedPrefixTokens = commonLen;
            } else {
                llama_memory_clear(mem, true);
                g_last_prompt_tokens.clear();
            }
        } else {
            llama_memory_clear(mem, true);
            g_last_prompt_tokens.clear();
        }

        llama_tokens suffixTokens(promptTokens.begin() + static_cast<ptrdiff_t>(reusedPrefixTokens), promptTokens.end());
        common_batch batch(g_ctx);
        if (!decode_tokens(g_ctx, batch, suffixTokens, static_cast<llama_pos>(reusedPrefixTokens), g_n_batch, true)) {
            g_last_prompt_tokens.clear();
            llama_memory_clear(mem, true);
            return g_cancel.load() ? -99 : -6;
        }
        g_last_prompt_tokens = promptTokens;
        n_past = static_cast<llama_pos>(promptTokens.size());
    }

    const auto tPrefillEnd = std::chrono::steady_clock::now();

    // Official Qwen3.5 clean Russian-safe sampling chain:
    // - Qwen3.5 Hybrid DeltaNet MUST use repeat_penalty=1.0 and dry_allowed_length>=5:
    //   stacking multiplicative repeat_penalty + high presence_penalty + dry_allowed_length=2
    //   destroys Cyrillic BPE subwords (where normal Russian words are 2-4 subword tokens!) and
    //   causes gibberish/language-mixing.
    // - Do NOT pre-seed previous assistant turn tokens into the penalty window, which otherwise
    //   bans normal Russian prepositions/particles ("да", "не", "это", "в", "на") on turn 2+.
    common_params_sampling sparams;
    sparams.temp = std::max(0.0f, static_cast<float>(temperature));
    sparams.top_p = std::clamp(static_cast<float>(topP), 0.1f, 1.0f);
    sparams.top_k = 20;
    sparams.min_p = enableThinking ? 0.02f : 0.04f;
    sparams.penalty_repeat = (repeatPenalty100 > 100)
            ? std::clamp(static_cast<float>(repeatPenalty100) / 100.0f, 1.0f, 1.08f)
            : 1.0f;
    sparams.penalty_freq = 0.0f;
    sparams.penalty_present = enableThinking ? 0.15f : 0.25f;
    sparams.penalty_last_n = 128;
    sparams.dry_multiplier = 0.25f;
    sparams.dry_base = 1.75f;
    sparams.dry_allowed_length = 5;
    sparams.dry_penalty_last_n = 256;
    common_sampler * sampler = common_sampler_init(g_model, sparams);
    if (!sampler) {
        set_last_error("Failed to create sampler");
        return -7;
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    common_batch genBatch(g_ctx);
    const int budget = std::min(static_cast<int>(maxTokens), std::max(64, g_n_ctx - static_cast<int>(n_past) - 8));
    // Crisp Adaptive Thinking budget: enough room to verify logic/math/Russian nuance cleanly without stalls:
    // - Tier 0/1 quick questions (budget <= 480): 72-108 tok focused micro-thought (~1.5-2.5s on ARMv8.2 dotprod)
    // - Tier 1 medium reasoning (budget <= 760): 140-200 tok structured CoT
    // - Tier 2 complex math/code/proofs (budget > 760): 240-340 tok deep CoT
    bool sawThinkClose = !enableThinking;
    const int thinkSoftLimit = (budget <= 480) ? 72
            : ((budget <= 760) ? 140 : std::min(240, std::max(160, budget / 4)));
    const int thinkHardLimit = (budget <= 480) ? 108
            : ((budget <= 760) ? 200 : std::min(340, std::max(220, (budget * 3) / 8)));

    std::string pendingUtf8;
    std::string tailWindow;
    int generatedCount = 0;
    bool abortedByCallback = false;

    for (int i = 0; i < budget; ++i) {
        if (g_cancel.load(std::memory_order_relaxed) || n_past >= g_n_ctx - 2) {
            break;
        }

        const llama_token tok = common_sampler_sample(sampler, g_ctx, -1);
        common_sampler_accept(sampler, tok, true);

        if (llama_vocab_is_eog(vocab, tok)) {
            break;
        }

        std::string piece = common_token_to_piece(g_ctx, tok, false);
        if (piece.empty()) {
            const std::string sp = common_token_to_piece(g_ctx, tok, true);
            if (sp == "</think>" || sp == "<think>") {
                piece = sp;
            } else if (sp == "<|im_end|>" || sp == "<|endoftext|>" || sp == "<|eot_id|>") {
                break;
            }
        }
        if (piece == "<|im_end|>" || piece == "<|endoftext|>" || piece == "<|eot_id|>") {
            break;
        }

        pendingUtf8 += piece;
        tailWindow += piece;
        if (tailWindow.size() > 64) {
            tailWindow.erase(0, tailWindow.size() - 64);
        }
        if (!sawThinkClose && tailWindow.find("</think>") != std::string::npos) {
            sawThinkClose = true;
        }

        const size_t ready = utf8_complete_prefix_len(pendingUtf8);
        if (ready > 0) {
            const std::string emit = pendingUtf8.substr(0, ready);
            pendingUtf8.erase(0, ready);
            if (jCallback != nullptr && onTokenMethod != nullptr) {
                jstring jPiece = utf8_to_jstring(env, emit);
                const jboolean keepGoing = env->CallBooleanMethod(jCallback, onTokenMethod, jPiece);
                if (jPiece) env->DeleteLocalRef(jPiece);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    abortedByCallback = true;
                    break;
                }
                if (!keepGoing) {
                    abortedByCallback = true;
                    break;
                }
            }
        }

        generatedCount++;
        if (ends_with(tailWindow, "<|im_end|>")) {
            break;
        }

        genBatch.clear();
        genBatch.add(tok, n_past++, 0, true);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, genBatch.get()) != 0) {
            set_last_error(g_cancel.load() ? "Cancelled" : "Decode step failed");
            break;
        }
        if (rawBitmaps.empty()) {
            g_last_prompt_tokens.push_back(tok);
        }

        // Bounded thinking closure: inject Qwen3.5's exact special </think> transition so final answer begins cleanly.
        if (!sawThinkClose && generatedCount >= thinkSoftLimit) {
            const bool midTag = ends_with(tailWindow, "<") || ends_with(tailWindow, "</")
                    || ends_with(tailWindow, "</t") || ends_with(tailWindow, "</th")
                    || ends_with(tailWindow, "</thi") || ends_with(tailWindow, "</thin")
                    || ends_with(tailWindow, "</think");
            const bool goodBoundary = piece.find('\n') != std::string::npos || piece.find('.') != std::string::npos;
            if (!midTag && (goodBoundary || generatedCount >= thinkHardLimit)) {
                const std::string closeTag = "\n</think>\n\n";
                llama_tokens closeToks = common_tokenize(g_ctx, closeTag, false, true);
                if (!closeToks.empty() && n_past + static_cast<llama_pos>(closeToks.size()) < g_n_ctx - 4) {
                    if (!decode_tokens(g_ctx, genBatch, closeToks, n_past, g_n_batch, true)) {
                        break;
                    }
                    for (llama_token ct : closeToks) {
                        common_sampler_accept(sampler, ct, true);
                        if (rawBitmaps.empty()) {
                            g_last_prompt_tokens.push_back(ct);
                        }
                    }
                    n_past += static_cast<llama_pos>(closeToks.size());
                    generatedCount += static_cast<int>(closeToks.size());
                    tailWindow += closeTag;
                    sawThinkClose = true;
                    if (jCallback != nullptr && onTokenMethod != nullptr) {
                        jstring jClose = utf8_to_jstring(env, closeTag);
                        const jboolean keepGoing = env->CallBooleanMethod(jCallback, onTokenMethod, jClose);
                        if (jClose) env->DeleteLocalRef(jClose);
                        if (env->ExceptionCheck()) {
                            env->ExceptionClear();
                            abortedByCallback = true;
                            break;
                        }
                        if (!keepGoing) {
                            abortedByCallback = true;
                            break;
                        }
                    }
                }
            }
        }
    }

    if (!pendingUtf8.empty() && !abortedByCallback && jCallback != nullptr && onTokenMethod != nullptr) {
        jstring jRem = utf8_to_jstring(env, pendingUtf8);
        env->CallBooleanMethod(jCallback, onTokenMethod, jRem);
        if (jRem) env->DeleteLocalRef(jRem);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    common_sampler_free(sampler);

    const auto tEnd = std::chrono::steady_clock::now();
    const double prefillMs = std::chrono::duration<double, std::milli>(tPrefillEnd - t0).count();
    const double genSec = std::max(0.001, std::chrono::duration<double>(tEnd - tPrefillEnd).count());
    const double tps = generatedCount / genSec;
    std::ostringstream ts;
    ts.precision(1);
    ts << std::fixed << "reuse " << reusedPrefixTokens << "tok | prefill " << prefillMs << "ms | gen " << generatedCount << " tok (" << tps << " tok/s)";
    {
        std::lock_guard<std::mutex> errLock(g_error_mutex);
        g_last_timings = ts.str();
    }
    LOGI("Generate done: %s", ts.str().c_str());

    return (g_cancel.load() || abortedByCallback) ? 1 : 0;
}

JNIEXPORT jint JNICALL
Java_org_colgram_core_ColgramNativeLlama_nativeGenerate(
        JNIEnv * env,
        jclass /*clazz*/,
        jstring jSystemPrompt,
        jstring jUserPrompt,
        jobjectArray jImagePaths,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jint repeatPenalty100,
        jboolean enableThinking,
        jobject jCallback) {
    return generate_internal(env, jSystemPrompt, nullptr, jUserPrompt, jImagePaths,
                             maxTokens, temperature, topP, repeatPenalty100, enableThinking, jCallback);
}

JNIEXPORT jint JNICALL
Java_org_colgram_core_ColgramNativeLlama_nativeGenerateWithHistory(
        JNIEnv * env,
        jclass /*clazz*/,
        jstring jSystemPrompt,
        jobjectArray jHistoryPairs,
        jstring jUserPrompt,
        jobjectArray jImagePaths,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jint repeatPenalty100,
        jboolean enableThinking,
        jobject jCallback) {
    return generate_internal(env, jSystemPrompt, jHistoryPairs, jUserPrompt, jImagePaths,
                             maxTokens, temperature, topP, repeatPenalty100, enableThinking, jCallback);
}

JNIEXPORT void JNICALL
Java_org_colgram_core_ColgramNativeLlama_nativeClearMemory(JNIEnv * /*env*/, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_last_prompt_tokens.clear();
    if (g_ctx) {
        llama_memory_t mem = llama_get_memory(g_ctx);
        if (mem) {
            llama_memory_clear(mem, true);
        }
    }
    LOGI("Cleared native KV/recurrent memory for clean session isolation");
}

JNIEXPORT void JNICALL
Java_org_colgram_core_ColgramNativeLlama_nativeCancel(JNIEnv * /*env*/, jclass /*clazz*/) {
    g_cancel.store(true);
}

JNIEXPORT void JNICALL
Java_org_colgram_core_ColgramNativeLlama_nativeUnload(JNIEnv * /*env*/, jclass /*clazz*/) {
    g_cancel.store(true);
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    unload_locked();
}

JNIEXPORT jstring JNICALL
Java_org_colgram_core_ColgramNativeLlama_nativeGetLastError(JNIEnv * env, jclass /*clazz*/) {
    return utf8_to_jstring(env, get_last_error());
}

JNIEXPORT jstring JNICALL
Java_org_colgram_core_ColgramNativeLlama_nativeGetLastTimings(JNIEnv * env, jclass /*clazz*/) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return utf8_to_jstring(env, g_last_timings);
}

} // extern "C"
