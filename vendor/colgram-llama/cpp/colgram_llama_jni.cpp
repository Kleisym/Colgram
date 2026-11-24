#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-cpu.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"
#include "sampling.h"

#define TAG "ColgramLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct CpuTopology {
    int online_cores = 2;
    std::vector<int> big_cores;
    int decode_threads = 2;
    int batch_threads = 4;
    bool pin_decode_big = false;
    std::string source = "default";
};

struct PrefixSnapshot {
    llama_tokens tokens;
    std::vector<uint8_t> state;

    void clear() {
        tokens.clear();
        state.clear();
        state.shrink_to_fit();
    }

    bool matches_strict_prefix(const llama_tokens & prompt) const {
        return !tokens.empty()
                && !state.empty()
                && tokens.size() < prompt.size()
                && std::equal(tokens.begin(), tokens.end(), prompt.begin());
    }
};

std::mutex g_engine_mutex;
std::mutex g_error_mutex;
std::atomic<bool> g_cancel{false};
std::atomic<bool> g_backend_ready{false};

llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
mtmd_context * g_mtmd = nullptr;
ggml_threadpool_t g_tp_decode = nullptr;
ggml_threadpool_t g_tp_batch = nullptr;
common_chat_templates_ptr g_tmpls;

int g_n_ctx = 2048;
int g_n_batch = 512;
int g_n_threads = 2;
int g_n_threads_batch = 4;
CpuTopology g_topology;
bool g_has_vision = false;
std::string g_mmproj_path;
enum llama_flash_attn_type g_flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

llama_tokens g_cached_tokens;
PrefixSnapshot g_sys_snapshot;
PrefixSnapshot g_turn_snapshot;

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

long read_sysfs_long(const std::string & path) {
    std::ifstream f(path);
    if (!f.is_open()) return -1;
    long v = -1;
    f >> v;
    return f.fail() ? -1 : v;
}

uint64_t get_total_ram_mb() {
    long pages = sysconf(_SC_PHYS_PAGES);
    long page_sz = sysconf(_SC_PAGE_SIZE);
    if (pages > 0 && page_sz > 0) {
        return (static_cast<uint64_t>(pages) * static_cast<uint64_t>(page_sz)) / (1024ULL * 1024ULL);
    }
    return 4096ULL;
}

bool is_arm_little_cpu_part(int part) {
    // Cortex-A53 (0xd03), Cortex-A35 (0xd04), Cortex-A55 (0xd05), Cortex-A510 (0xd46), Cortex-A520 (0xd80)
    return part == 0xd03 || part == 0xd04 || part == 0xd05 || part == 0xd46 || part == 0xd80;
}

// Detect Android ARM64 big.LITTLE topology accurately (including closely-clocked SoCs like
// MediaTek Helio G99 where A76=2.2GHz vs A55=2.0GHz -> 90.9% ratio, easily distinguished by
// cpu_capacity, CPU part, and >min_freq!).
CpuTopology detect_cpu_topology(int requested_threads) {
    CpuTopology topo;
    const int online = std::clamp<int>(
            static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN)), 1, GGML_MAX_N_THREADS);
    topo.online_cores = online;

    std::vector<long> caps(online, -1);
    std::vector<long> freqs(online, -1);
    std::vector<int> parts(online, -1);

    long max_cap = -1, min_cap = -1;
    long max_freq = -1, min_freq = -1;
    for (int i = 0; i < online; ++i) {
        const std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(i);
        long c = read_sysfs_long(base + "/cpu_capacity");
        if (c > 0) {
            caps[i] = c;
            max_cap = (max_cap < 0) ? c : std::max(max_cap, c);
            min_cap = (min_cap < 0) ? c : std::min(min_cap, c);
        }
        long fr = read_sysfs_long(base + "/cpufreq/cpuinfo_max_freq");
        if (fr <= 0) {
            fr = read_sysfs_long(base + "/cpufreq/scaling_max_freq");
        }
        if (fr > 0) {
            freqs[i] = fr;
            max_freq = (max_freq < 0) ? fr : std::max(max_freq, fr);
            min_freq = (min_freq < 0) ? fr : std::min(min_freq, fr);
        }
    }

    // Parse /proc/cpuinfo CPU part per processor as an extra ground-truth signal.
    {
        std::ifstream cpuinfo("/proc/cpuinfo");
        std::string line;
        int cur_cpu = -1;
        while (std::getline(cpuinfo, line)) {
            if (line.rfind("processor", 0) == 0) {
                size_t col = line.find(':');
                if (col != std::string::npos) {
                    try {
                        cur_cpu = std::stoi(line.substr(col + 1));
                    } catch (...) {
                        cur_cpu = -1;
                    }
                }
            } else if (cur_cpu >= 0 && cur_cpu < online && line.rfind("CPU part", 0) == 0) {
                size_t col = line.find(':');
                if (col != std::string::npos) {
                    try {
                        parts[cur_cpu] = std::stoi(line.substr(col + 1), nullptr, 0);
                    } catch (...) {}
                }
            }
        }
    }

    // Priority 1: ARM EAS cpu_capacity (e.g. Helio G99: A76=1024 vs A55=~440; SD 1+3+4: 1024/820 vs 350).
    if (max_cap > 0 && min_cap > 0 && max_cap * 10 >= min_cap * 12) {
        const long thresh = (max_cap * 70) / 100;
        for (int i = 0; i < online; ++i) {
            if (caps[i] >= thresh) {
                topo.big_cores.push_back(i);
            }
        }
        if (!topo.big_cores.empty() && static_cast<int>(topo.big_cores.size()) < online) {
            topo.source = "cpu_capacity";
        } else {
            topo.big_cores.clear();
        }
    }

    // Priority 2: /proc/cpuinfo ARM CPU part heterogeneity (excludes A53/A55/A510/A520 little cores).
    if (topo.big_cores.empty()) {
        bool saw_little = false;
        std::vector<int> non_little;
        for (int i = 0; i < online; ++i) {
            if (parts[i] < 0) continue;
            if (is_arm_little_cpu_part(parts[i])) {
                saw_little = true;
            } else {
                non_little.push_back(i);
            }
        }
        if (saw_little && !non_little.empty()) {
            topo.big_cores = std::move(non_little);
            topo.source = "cpu_part";
        }
    }

    // Priority 3: cpufreq cpuinfo_max_freq cluster split.
    if (topo.big_cores.empty() && max_freq > 0 && min_freq > 0 && max_freq > min_freq) {
        std::vector<int> above_min;
        for (int i = 0; i < online; ++i) {
            if (freqs[i] > min_freq) {
                above_min.push_back(i);
            }
        }
        if (above_min.size() >= 2 && above_min.size() <= 4) {
            topo.big_cores = std::move(above_min);
            topo.source = "max_freq_cluster";
        } else {
            const long thresh = (max_freq * 90) / 100;
            for (int i = 0; i < online; ++i) {
                if (freqs[i] >= thresh) {
                    topo.big_cores.push_back(i);
                }
            }
            if (!topo.big_cores.empty() && static_cast<int>(topo.big_cores.size()) < online) {
                topo.source = "max_freq_90pct";
            } else {
                topo.big_cores.clear();
            }
        }
    }

    // Sort big cores highest capacity & frequency first (Prime -> Big).
    std::sort(topo.big_cores.begin(), topo.big_cores.end(), [&](int a, int b) {
        if (caps[a] != caps[b]) return caps[a] > caps[b];
        if (freqs[a] != freqs[b]) return freqs[a] > freqs[b];
        return a > b;
    });

    if (requested_threads > 0) {
        topo.decode_threads = std::clamp<int>(requested_threads, 1, online);
        topo.pin_decode_big = !topo.big_cores.empty()
                && topo.decode_threads <= static_cast<int>(topo.big_cores.size());
        topo.source += "+override";
    } else if (!topo.big_cores.empty()) {
        // Clamp big-core decode threads to [2..4] (if single prime core, fall back to 2).
        const int big_cnt = static_cast<int>(topo.big_cores.size());
        topo.decode_threads = std::clamp<int>(big_cnt, 2, 4);
        topo.pin_decode_big = (big_cnt >= topo.decode_threads);
    } else {
        // Homogeneous CPU (e.g. emulator / x86_64): use up to 4 threads.
        topo.decode_threads = (online > 4) ? std::clamp<int>(online / 2, 2, 4) : std::clamp<int>(online, 1, 4);
        topo.pin_decode_big = false;
        topo.source = "homogeneous";
    }

    // Prompt prefill is GEMM compute-bound and uses dynamic chunk stealing in ggml_compute_forward_mul_mat:
    // use up to 6 cores for fast TTFT while decode stays strictly on the fast big cores!
    const int max_batch = std::min(online, 6);
    topo.batch_threads = std::clamp<int>(max_batch, topo.decode_threads, std::max(topo.decode_threads, max_batch));
    return topo;
}

struct ThreadpoolPauseGuard {
    ~ThreadpoolPauseGuard() {
        if (g_tp_decode) ggml_threadpool_pause(g_tp_decode);
        if (g_tp_batch) ggml_threadpool_pause(g_tp_batch);
    }
};

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

// Hold back any trailing bytes that could be the prefix of a special stop tag (<|im_end|>, <|endoftext|>, <|eot_id|>)
// so stop markers NEVER leak into streamed UI text.
size_t holdback_special_tag_len(const std::string & buf) {
    static const char * kStopTags[] = {"<|im_end|>", "<|endoftext|>", "<|eot_id|>", "<|im_start|>"};
    size_t maxHold = 0;
    for (const char * tag : kStopTags) {
        const size_t tlen = std::strlen(tag);
        const size_t checkMax = std::min(buf.size(), tlen - 1);
        for (size_t k = checkMax; k >= 1; --k) {
            if (buf.compare(buf.size() - k, k, tag, k) == 0) {
                maxHold = std::max(maxHold, k);
                break;
            }
        }
    }
    return maxHold;
}

bool contains_stop_tag(const std::string & s, size_t * out_pos) {
    static const char * kStopTags[] = {"<|im_end|>", "<|endoftext|>", "<|eot_id|>"};
    size_t earliest = std::string::npos;
    for (const char * tag : kStopTags) {
        const size_t pos = s.find(tag);
        if (pos != std::string::npos && (earliest == std::string::npos || pos < earliest)) {
            earliest = pos;
        }
    }
    if (earliest != std::string::npos) {
        if (out_pos) *out_pos = earliest;
        return true;
    }
    return false;
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
        llama_detach_threadpool(g_ctx);
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_tp_batch) {
        ggml_threadpool_free(g_tp_batch);
        g_tp_batch = nullptr;
    }
    if (g_tp_decode) {
        ggml_threadpool_free(g_tp_decode);
        g_tp_decode = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_has_vision = false;
    g_mmproj_path.clear();
    g_cached_tokens.clear();
    g_sys_snapshot.clear();
    g_turn_snapshot.clear();
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
    vparams.n_threads = g_n_threads_batch;
    vparams.flash_attn_type = g_flash_attn_type;
    vparams.warmup = false;
    vparams.image_min_tokens = 48;
    vparams.image_max_tokens = 256; // snappy mobile ViT token budget (~2x faster Vision prefill)

    auto mem = mtmd_get_memory_usage(g_mmproj_path.c_str(), vparams);
    const int n_ubatch = static_cast<int>(llama_n_ubatch(g_ctx));
    if (mem.use_non_causal && mem.image_max_tokens > n_ubatch) {
        vparams.image_max_tokens = n_ubatch;
        vparams.image_min_tokens = std::min(vparams.image_min_tokens, n_ubatch);
    }

    LOGI("Lazy-loading Vision mmproj (thr=%d, max_tok=%d): %s",
         vparams.n_threads, vparams.image_max_tokens, g_mmproj_path.c_str());
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
                   size_t offset, size_t count, llama_pos start_pos, int n_batch, bool logits_last) {
    for (size_t i = 0; i < count; i += static_cast<size_t>(n_batch)) {
        if (g_cancel.load(std::memory_order_relaxed)) {
            return false;
        }
        const size_t cur = std::min(count - i, static_cast<size_t>(n_batch));
        batch.clear();
        for (size_t j = 0; j < cur; ++j) {
            const bool want_logit = logits_last && (i + j == count - 1);
            batch.add(tokens[offset + i + j], start_pos + static_cast<llama_pos>(i + j), 0, want_logit);
        }
        const int rc = llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (rc != 0) {
            set_last_error(g_cancel.load() ? "Cancelled" : ("llama_process decode failed rc=" + std::to_string(rc)));
            return false;
        }
    }
    return true;
}

bool ends_with(const std::string & s, const std::string & suffix) {
    return s.size() >= suffix.size() &&
           s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

std::string format_messages_prompt(
        const std::string & systemPrompt,
        const std::vector<std::pair<std::string, std::string>> & historyTurns,
        const std::string & userPrompt,
        bool enableThinking) {
    std::string formattedPrompt;
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
        inputs.enable_thinking = enableThinking;
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
    return formattedPrompt;
}

// Compute the exact chat-template-rendered prefix string before the final user message,
// trimmed cleanly to the last turn-end marker so BPE token boundaries match 100% across turns.
std::string extract_prefix_before_user_turn(
        const std::string & systemPrompt,
        const std::vector<std::pair<std::string, std::string>> & historyTurns) {
    if (systemPrompt.empty() && historyTurns.empty()) {
        return "";
    }
    const std::string pA = format_messages_prompt(systemPrompt, historyTurns, "__COLGRAM_A__", false);
    const std::string pB = format_messages_prompt(systemPrompt, historyTurns, "__COLGRAM_B__", false);
    size_t common = 0;
    const size_t lim = std::min(pA.size(), pB.size());
    while (common < lim && pA[common] == pB[common]) {
        ++common;
    }
    std::string pref = pA.substr(0, common);
    // Align cleanly right after the last turn-closing special delimiter (e.g. "<|im_end|>\n").
    static const char * kTurnEnds[] = {"<|im_end|>\n", "<|eot_id|>", "<end_of_turn>\n", "</s>"};
    size_t bestCut = 0;
    for (const char * marker : kTurnEnds) {
        const size_t mlen = std::strlen(marker);
        const size_t pos = pref.rfind(marker);
        if (pos != std::string::npos && pos + mlen > bestCut) {
            bestCut = pos + mlen;
        }
    }
    return bestCut > 0 ? pref.substr(0, bestCut) : "";
}

size_t compute_matching_prefix_len(const llama_tokens & promptTokens, const std::string & prefixStr) {
    if (!g_ctx || prefixStr.empty()) return 0;
    llama_tokens prefToks = common_tokenize(g_ctx, prefixStr, true, true);
    if (prefToks.empty() || prefToks.size() >= promptTokens.size()) return 0;
    if (std::equal(prefToks.begin(), prefToks.end(), promptTokens.begin())) {
        return prefToks.size();
    }
    return 0;
}

void save_snapshot_locked(PrefixSnapshot & dst, const llama_tokens & tokens, size_t prefixLen) {
    if (!g_ctx || prefixLen == 0 || prefixLen > tokens.size()) return;
    if (dst.tokens.size() == prefixLen
            && !dst.state.empty()
            && std::equal(dst.tokens.begin(), dst.tokens.end(), tokens.begin())) {
        return;
    }
    const uint64_t ramMb = get_total_ram_mb();
    const size_t maxSnapBytes = (ramMb >= 4000ULL) ? (48ULL * 1024ULL * 1024ULL) : (26ULL * 1024ULL * 1024ULL);
    const size_t stateSize = llama_state_seq_get_size(g_ctx, 0);
    if (stateSize == 0 || stateSize > maxSnapBytes) {
        return;
    }
    std::vector<uint8_t> buf(stateSize);
    const size_t written = llama_state_seq_get_data(g_ctx, buf.data(), buf.size(), 0);
    if (written == 0) {
        return;
    }
    buf.resize(written);
    dst.tokens.assign(tokens.begin(), tokens.begin() + static_cast<ptrdiff_t>(prefixLen));
    dst.state.swap(buf);
}

bool restore_snapshot_locked(llama_memory_t mem, const PrefixSnapshot & snap) {
    if (!g_ctx || snap.tokens.empty() || snap.state.empty()) return false;
    llama_memory_clear(mem, true);
    const size_t read = llama_state_seq_set_data(g_ctx, snap.state.data(), snap.state.size(), 0);
    if (read == 0) {
        llama_memory_clear(mem, true);
        return false;
    }
    return true;
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
    mparams.use_extra_bufts = true;

    const auto tLoad0 = std::chrono::steady_clock::now();
    LOGI("Loading GGUF model (mmap+repack): %s", modelPath.c_str());
    g_model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (!g_model) {
        set_last_error("Failed to load GGUF model: " + modelPath);
        return -2;
    }

    g_topology = detect_cpu_topology(static_cast<int>(nThreads));
    const int decodeThreads = g_topology.decode_threads;
    const int batchThreads = g_topology.batch_threads;

    const int32_t trainCtx = llama_model_n_ctx_train(g_model);
    int requestedCtx = nCtx >= 512 ? static_cast<int>(nCtx) : 2048;
    if (trainCtx > 0 && requestedCtx > trainCtx) {
        requestedCtx = trainCtx;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(requestedCtx);
    cparams.n_batch = 512;
    cparams.n_ubatch = 256;
    cparams.n_threads = decodeThreads;
    cparams.n_threads_batch = batchThreads;
    cparams.flash_attn_type = flashAttn ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    g_flash_attn_type = cparams.flash_attn_type;

    if (kvQuantBits == 4) {
        cparams.type_k = GGML_TYPE_Q4_0;
        cparams.type_v = GGML_TYPE_Q4_0;
    } else if (kvQuantBits == 16) {
        cparams.type_k = GGML_TYPE_F16;
        cparams.type_v = GGML_TYPE_F16;
    } else {
        // Default Q8_0 KV cache cuts LPDDR4X attention bandwidth in half with lossless quality.
        cparams.type_k = GGML_TYPE_Q8_0;
        cparams.type_v = GGML_TYPE_Q8_0;
    }
    cparams.abort_callback = colgram_abort_cb;
    cparams.abort_callback_data = nullptr;
    cparams.no_perf = true;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        set_last_error("Failed to initialize llama_context (try lower context or smaller quant)");
        unload_locked();
        return -3;
    }

    // Create persistent native ggml threadpools:
    // - g_tp_decode: strictly pinned 1-to-1 to big performance cores at HIGH priority (zero A55 barrier stalls!)
    // - g_tp_batch: multi-core prompt prefill pool (paused to 0% CPU during single-token decode & idle!)
    {
        struct ggml_threadpool_params tpp_dec = ggml_threadpool_params_default(decodeThreads);
        tpp_dec.prio = GGML_SCHED_PRIO_HIGH;
        tpp_dec.poll = 50;
        tpp_dec.paused = true;
        if (g_topology.pin_decode_big) {
            std::memset(tpp_dec.cpumask, 0, sizeof(tpp_dec.cpumask));
            for (int k = 0; k < decodeThreads && k < static_cast<int>(g_topology.big_cores.size()); ++k) {
                const int cid = g_topology.big_cores[k];
                if (cid >= 0 && cid < GGML_MAX_N_THREADS) {
                    tpp_dec.cpumask[cid] = true;
                }
            }
            tpp_dec.strict_cpu = true;
        }
        g_tp_decode = ggml_threadpool_new(&tpp_dec);

        if (batchThreads != decodeThreads) {
            struct ggml_threadpool_params tpp_bat = ggml_threadpool_params_default(batchThreads);
            tpp_bat.prio = GGML_SCHED_PRIO_NORMAL;
            tpp_bat.poll = 50;
            tpp_bat.paused = true;
            tpp_bat.strict_cpu = false;
            g_tp_batch = ggml_threadpool_new(&tpp_bat);
        }
        if (g_tp_decode) {
            llama_attach_threadpool(g_ctx, g_tp_decode, g_tp_batch ? g_tp_batch : g_tp_decode);
        }
    }
    ThreadpoolPauseGuard initPauseGuard;

    g_n_ctx = static_cast<int>(llama_n_ctx(g_ctx));
    g_n_batch = static_cast<int>(cparams.n_batch);
    g_n_threads = decodeThreads;
    g_n_threads_batch = batchThreads;
    g_tmpls = common_chat_templates_init(g_model, "");

    char desc[256] = {};
    llama_model_desc(g_model, desc, sizeof(desc));
    g_model_desc = desc;

    // Lazy-load Vision mmproj on first photo/video turn so text turns initialize fast & stay light on RAM.
    g_mmproj_path = mmprojPath;
    g_has_vision = !mmprojPath.empty();

    std::ostringstream bigList;
    for (size_t i = 0; i < g_topology.big_cores.size(); ++i) {
        if (i > 0) bigList << ',';
        bigList << g_topology.big_cores[i];
    }
    const double loadMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - tLoad0).count();
    LOGI("Initialized model=%s ctx=%d batch=%d/%d decode_thr=%d batch_thr=%d big=[%s] pin=%d src=%s kv=Q%d flash=%d vision=%d(lazy) load_ms=%.1f",
         g_model_desc.c_str(), g_n_ctx, g_n_batch, static_cast<int>(cparams.n_ubatch),
         g_n_threads, g_n_threads_batch, bigList.str().c_str(), g_topology.pin_decode_big ? 1 : 0,
         g_topology.source.c_str(), (kvQuantBits == 4 || kvQuantBits == 16) ? (int) kvQuantBits : 8,
         flashAttn ? 1 : 0, g_has_vision ? 1 : 0, loadMs);
    return 0;
}

JNIEXPORT jint JNICALL
Java_org_colgram_core_ColgramNativeLlama_nativeWarmUpPrompt(
        JNIEnv * env,
        jclass /*clazz*/,
        jstring jSystemPrompt) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!g_model || !g_ctx || g_cancel.load(std::memory_order_relaxed)) {
        return 0;
    }
    const std::string systemPrompt = jstring_to_std(env, jSystemPrompt);
    if (systemPrompt.empty()) {
        return 0;
    }
    const std::string sysPrefix = extract_prefix_before_user_turn(systemPrompt, {});
    if (sysPrefix.empty()) {
        return 0;
    }
    llama_tokens sysTokens = common_tokenize(g_ctx, sysPrefix, true, true);
    if (sysTokens.empty() || static_cast<int>(sysTokens.size()) >= g_n_ctx / 2) {
        return 0;
    }
    if (g_sys_snapshot.tokens == sysTokens && !g_sys_snapshot.state.empty()) {
        return static_cast<jint>(sysTokens.size());
    }

    ThreadpoolPauseGuard pauseGuard;
    const auto t0 = std::chrono::steady_clock::now();
    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_memory_clear(mem, true);
    g_cached_tokens.clear();

    common_batch batch(g_ctx);
    if (!decode_tokens(g_ctx, batch, sysTokens, 0, sysTokens.size(), 0, g_n_batch, false)) {
        llama_memory_clear(mem, true);
        g_cached_tokens.clear();
        return -1;
    }
    g_cached_tokens = sysTokens;
    save_snapshot_locked(g_sys_snapshot, sysTokens, sysTokens.size());
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    LOGI("Warm-up prefilled static system prompt: %zu tok in %.1f ms (snap=%zu KB)",
         sysTokens.size(), ms, g_sys_snapshot.state.size() / 1024);
    return static_cast<jint>(sysTokens.size());
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
       << " | thr=" << g_n_threads << "/" << g_n_threads_batch
       << (g_topology.pin_decode_big ? "(big)" : "")
       << " | mmap=1"
       << " | flash=" << (g_flash_attn_type == LLAMA_FLASH_ATTN_TYPE_ENABLED ? "1" : "0")
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

    ThreadpoolPauseGuard pauseGuard;
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

    const bool thinkingOn = enableThinking ? true : false;
    const std::string formattedPrompt = format_messages_prompt(systemPrompt, historyTurns, userPrompt, thinkingOn);

    const auto t0 = std::chrono::steady_clock::now();
    llama_pos n_past = 0;
    size_t reusedPrefixTokens = 0;
    size_t evaluatedDeltaTokens = 0;
    llama_memory_t mem = llama_get_memory(g_ctx);

    if (!rawBitmaps.empty()) {
        g_cached_tokens.clear();
        g_turn_snapshot.clear();
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
        evaluatedDeltaTokens = static_cast<size_t>(std::max<llama_pos>(0, n_past));
    } else {
        cleanupMedia();
        llama_tokens promptTokens = common_tokenize(g_ctx, formattedPrompt, true, true);
        const int maxPromptTokens = std::max(128, g_n_ctx - std::min(static_cast<int>(maxTokens), g_n_ctx / 2) - 16);
        bool promptTrimmed = false;
        if (static_cast<int>(promptTokens.size()) > maxPromptTokens) {
            const int keepHead = std::min(256, maxPromptTokens / 4);
            const int keepTail = maxPromptTokens - keepHead;
            llama_tokens trimmed;
            trimmed.reserve(maxPromptTokens);
            trimmed.insert(trimmed.end(), promptTokens.begin(), promptTokens.begin() + keepHead);
            trimmed.insert(trimmed.end(), promptTokens.end() - keepTail, promptTokens.end());
            promptTokens.swap(trimmed);
            promptTrimmed = true;
        }

        // Compute exact common prefix with currently live KV/SSM cache.
        size_t commonLen = 0;
        const size_t minLive = std::min(g_cached_tokens.size(), promptTokens.size());
        while (commonLen < minLive && g_cached_tokens[commonLen] == promptTokens[commonLen]) {
            ++commonLen;
        }
        // Always evaluate at least the final token so logits are freshly available.
        if (commonLen == promptTokens.size() && commonLen > 0) {
            --commonLen;
        }

        bool stateReady = false;
        // Fast path 1: Exact pure append onto live cache (0ms, zero memory copy).
        if (!g_cached_tokens.empty() && commonLen == g_cached_tokens.size() && commonLen < promptTokens.size()) {
            reusedPrefixTokens = commonLen;
            stateReady = true;
        }
        // Fast path 2: In-place partial rewind via llama_memory_seq_rm (succeeds on pure Attention
        // models and within recurrent rollback window on Hybrid DeltaNet without corrupting SSM state!).
        if (!stateReady && commonLen > 0 && commonLen < g_cached_tokens.size()) {
            if (llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(commonLen), -1)) {
                reusedPrefixTokens = commonLen;
                g_cached_tokens.resize(commonLen);
                stateReady = true;
            }
        }
        // Fast path 3: Exact Hybrid KV+SSM snapshot restore (eliminates full re-prefill on Qwen3.5
        // multi-turn conversations where Jinja strips <think></think> from prior assistant turns!).
        if (!stateReady) {
            const PrefixSnapshot * bestSnap = nullptr;
            if (g_turn_snapshot.matches_strict_prefix(promptTokens)) {
                bestSnap = &g_turn_snapshot;
            }
            if (g_sys_snapshot.matches_strict_prefix(promptTokens)) {
                if (!bestSnap || g_sys_snapshot.tokens.size() > bestSnap->tokens.size()) {
                    bestSnap = &g_sys_snapshot;
                }
            }
            if (bestSnap != nullptr && restore_snapshot_locked(mem, *bestSnap)) {
                reusedPrefixTokens = bestSnap->tokens.size();
                g_cached_tokens = bestSnap->tokens;
                stateReady = true;
            }
        }
        // Fallback: cold clear.
        if (!stateReady) {
            llama_memory_clear(mem, true);
            g_cached_tokens.clear();
            reusedPrefixTokens = 0;
        }

        // Identify exact checkpoint boundaries for system prompt and history prefix so future turns reuse 90%+.
        size_t sysBoundary = 0;
        size_t histBoundary = 0;
        if (!promptTrimmed) {
            if (!systemPrompt.empty()) {
                const std::string sysPref = extract_prefix_before_user_turn(systemPrompt, {});
                sysBoundary = compute_matching_prefix_len(promptTokens, sysPref);
            }
            if (!historyTurns.empty() && get_total_ram_mb() >= 4000ULL) {
                const std::string histPref = extract_prefix_before_user_turn(systemPrompt, historyTurns);
                histBoundary = compute_matching_prefix_len(promptTokens, histPref);
            }
        }

        common_batch batch(g_ctx);
        size_t curPos = reusedPrefixTokens;

        // Checkpoint A: evaluate & snapshot system prompt prefix if not yet cached.
        if (sysBoundary > curPos && sysBoundary < promptTokens.size()) {
            if (!decode_tokens(g_ctx, batch, promptTokens, curPos, sysBoundary - curPos,
                               static_cast<llama_pos>(curPos), g_n_batch, false)) {
                g_cached_tokens.clear();
                llama_memory_clear(mem, true);
                return g_cancel.load() ? -99 : -6;
            }
            curPos = sysBoundary;
            save_snapshot_locked(g_sys_snapshot, promptTokens, sysBoundary);
        }

        // Checkpoint B: evaluate & snapshot multi-turn history prefix so Turn N+1 reuses Turns 1..N in ~2ms!
        if (histBoundary > curPos && histBoundary < promptTokens.size()) {
            if (!decode_tokens(g_ctx, batch, promptTokens, curPos, histBoundary - curPos,
                               static_cast<llama_pos>(curPos), g_n_batch, false)) {
                g_cached_tokens.clear();
                llama_memory_clear(mem, true);
                return g_cancel.load() ? -99 : -6;
            }
            curPos = histBoundary;
            save_snapshot_locked(g_turn_snapshot, promptTokens, histBoundary);
        }

        // Final delta: evaluate remaining user turn + assistant open tokens with logits on the last token.
        if (curPos < promptTokens.size()) {
            if (!decode_tokens(g_ctx, batch, promptTokens, curPos, promptTokens.size() - curPos,
                               static_cast<llama_pos>(curPos), g_n_batch, true)) {
                g_cached_tokens.clear();
                llama_memory_clear(mem, true);
                return g_cancel.load() ? -99 : -6;
            }
        }

        evaluatedDeltaTokens = promptTokens.size() - reusedPrefixTokens;
        g_cached_tokens = promptTokens;
        n_past = static_cast<llama_pos>(promptTokens.size());
    }

    const auto tPrefillEnd = std::chrono::steady_clock::now();

    // Official Qwen3.5 clean Russian-safe sampling chain:
    // repeat_penalty=1.0 + dry_allowed_length>=5 preserves multi-subword Cyrillic fluency cleanly.
    common_params_sampling sparams;
    sparams.temp = std::max(0.0f, static_cast<float>(temperature));
    sparams.top_p = std::clamp(static_cast<float>(topP), 0.1f, 1.0f);
    sparams.top_k = 20;
    sparams.min_p = thinkingOn ? 0.02f : 0.04f;
    sparams.penalty_repeat = (repeatPenalty100 > 100)
            ? std::clamp(static_cast<float>(repeatPenalty100) / 100.0f, 1.0f, 1.08f)
            : 1.0f;
    sparams.penalty_freq = 0.0f;
    sparams.penalty_present = thinkingOn ? 0.15f : 0.25f;
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

    // Compact bounded thinking closure when explicit deep reasoning is enabled (never stalls for 30s+).
    bool sawThinkClose = !thinkingOn;
    const int thinkSoftLimit = (budget <= 420) ? 56 : ((budget <= 680) ? 96 : 140);
    const int thinkHardLimit = (budget <= 420) ? 84 : ((budget <= 680) ? 140 : 200);

    std::string pendingUtf8;
    std::string tailWindow;
    int generatedCount = 0;
    bool abortedByCallback = false;
    bool recordedFirstVisible = false;
    auto tFirstVisible = tPrefillEnd;

    auto emit_chunk = [&](const std::string & chunk) -> bool {
        if (chunk.empty() || jCallback == nullptr || onTokenMethod == nullptr) return true;
        if (!recordedFirstVisible && (sawThinkClose || !thinkingOn)) {
            recordedFirstVisible = true;
            tFirstVisible = std::chrono::steady_clock::now();
        }
        jstring jPiece = utf8_to_jstring(env, chunk);
        const jboolean keepGoing = env->CallBooleanMethod(jCallback, onTokenMethod, jPiece);
        if (jPiece) env->DeleteLocalRef(jPiece);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            abortedByCallback = true;
            return false;
        }
        if (!keepGoing) {
            abortedByCallback = true;
            return false;
        }
        return true;
    };

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
            } else if (sp == "<|im_end|>" || sp == "<|endoftext|>" || sp == "<|eot_id|>" || sp == "<|im_start|>") {
                break;
            }
        }
        if (piece == "<|im_end|>" || piece == "<|endoftext|>" || piece == "<|eot_id|>" || piece == "<|im_start|>") {
            break;
        }

        pendingUtf8 += piece;
        tailWindow += piece;
        if (tailWindow.size() > 64) {
            tailWindow.erase(0, tailWindow.size() - 64);
        }

        // Catch any stop marker that formed across multiple pieces BEFORE emitting to UI!
        size_t stopPos = std::string::npos;
        if (contains_stop_tag(pendingUtf8, &stopPos)) {
            const std::string beforeStop = pendingUtf8.substr(0, stopPos);
            pendingUtf8.clear();
            const size_t safeUtf8 = utf8_complete_prefix_len(beforeStop);
            if (safeUtf8 > 0) {
                emit_chunk(beforeStop.substr(0, safeUtf8));
            }
            break;
        }

        if (!sawThinkClose && tailWindow.find("</think>") != std::string::npos) {
            sawThinkClose = true;
        }

        const size_t holdBack = holdback_special_tag_len(pendingUtf8);
        const size_t avail = (pendingUtf8.size() > holdBack) ? (pendingUtf8.size() - holdBack) : 0;
        const size_t ready = avail > 0 ? utf8_complete_prefix_len(pendingUtf8.substr(0, avail)) : 0;
        if (ready > 0) {
            const std::string emit = pendingUtf8.substr(0, ready);
            pendingUtf8.erase(0, ready);
            if (!emit_chunk(emit)) {
                break;
            }
        }

        generatedCount++;

        genBatch.clear();
        genBatch.add(tok, n_past++, 0, true);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, genBatch.get()) != 0) {
            set_last_error(g_cancel.load() ? "Cancelled" : "Decode step failed");
            g_cached_tokens.clear();
            llama_memory_clear(mem, true);
            break;
        }
        if (rawBitmaps.empty()) {
            g_cached_tokens.push_back(tok);
        }

        // Bounded thinking closure: inject Qwen3.5's exact </think> transition so final answer begins cleanly.
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
                    if (!decode_tokens(g_ctx, genBatch, closeToks, 0, closeToks.size(), n_past, g_n_batch, true)) {
                        break;
                    }
                    for (llama_token ct : closeToks) {
                        common_sampler_accept(sampler, ct, true);
                        if (rawBitmaps.empty()) {
                            g_cached_tokens.push_back(ct);
                        }
                    }
                    n_past += static_cast<llama_pos>(closeToks.size());
                    generatedCount += static_cast<int>(closeToks.size());
                    tailWindow += closeTag;
                    sawThinkClose = true;
                    if (!emit_chunk(closeTag)) {
                        break;
                    }
                }
            }
        }
    }

    if (!pendingUtf8.empty() && !abortedByCallback && !g_cancel.load(std::memory_order_relaxed)) {
        size_t stopPos = std::string::npos;
        if (contains_stop_tag(pendingUtf8, &stopPos)) {
            pendingUtf8.resize(stopPos);
        }
        const size_t safeRem = utf8_complete_prefix_len(pendingUtf8);
        if (safeRem > 0) {
            emit_chunk(pendingUtf8.substr(0, safeRem));
        }
    }

    common_sampler_free(sampler);

    const auto tEnd = std::chrono::steady_clock::now();
    const double prefillMs = std::chrono::duration<double, std::milli>(tPrefillEnd - t0).count();
    const double ttftMs = std::chrono::duration<double, std::milli>(
            (recordedFirstVisible ? tFirstVisible : tEnd) - t0).count();
    const double genSec = std::max(0.001, std::chrono::duration<double>(tEnd - tPrefillEnd).count());
    const double tps = generatedCount / genSec;

    std::ostringstream ts;
    ts.precision(1);
    ts << std::fixed
       << "reuse " << reusedPrefixTokens << "tok"
       << " | prefill " << prefillMs << "ms"
       << " | ttft " << ttftMs << "ms"
       << " | gen " << generatedCount << " tok (" << tps << " tok/s)";
    {
        std::lock_guard<std::mutex> errLock(g_error_mutex);
        g_last_timings = ts.str();
    }
    LOGI("Generate done: prompt_eval_ms=%.1f ttft_ms=%.1f eval_tok_per_s=%.2f kv_reused_tokens=%zu prompt_delta_tokens=%zu gen_tokens=%d decode_thr=%d batch_thr=%d",
         prefillMs, ttftMs, tps, reusedPrefixTokens, evaluatedDeltaTokens, generatedCount, g_n_threads, g_n_threads_batch);

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
    g_cached_tokens.clear();
    g_turn_snapshot.clear();
    // Preserve g_sys_snapshot so starting a fresh chat still reuses the prefilled system prompt in ~2ms!
    if (g_ctx) {
        llama_memory_t mem = llama_get_memory(g_ctx);
        if (mem) {
            llama_memory_clear(mem, true);
        }
    }
    LOGI("Cleared active KV/recurrent turn memory (kept sys snapshot=%zu tok)", g_sys_snapshot.tokens.size());
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
