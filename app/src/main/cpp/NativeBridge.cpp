#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include "llama.h"

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static std::mutex g_mutex;

static std::string fromJString(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bibgg_aibbgi_NativeBridge_loadModel(JNIEnv *env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    const std::string modelPath = fromJString(env, path);
    if (modelPath.empty()) return JNI_FALSE;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // portable CPU baseline; GPU offload can be added per device
    g_model = llama_model_load_from_file(modelPath.c_str(), mp);
    if (!g_model) return JNI_FALSE;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 512;
    cp.n_threads = 4;
    cp.n_threads_batch = 4;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bibgg_aibbgi_NativeBridge_generate(JNIEnv *env, jobject, jstring promptValue) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) return env->NewStringUTF("पहले Settings से GGUF मॉडल लोड करें।");

    const std::string prompt = fromJString(env, promptValue);
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    const int32_t needed = -llama_tokenize(vocab, prompt.c_str(),
        static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (needed <= 0 || needed > 1800) return env->NewStringUTF("Prompt बहुत लंबा है या tokenize नहीं हुआ।");

    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t count = llama_tokenize(vocab, prompt.c_str(),
        static_cast<int32_t>(prompt.size()), tokens.data(), needed, true, true);
    if (count < 0) return env->NewStringUTF("Prompt tokenize करने में समस्या हुई।");

    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_batch batch = llama_batch_get_one(tokens.data(), count);
    if (llama_decode(g_ctx, batch) != 0) return env->NewStringUTF("Model prompt decode failed.");

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output;
    for (int i = 0; i < 384; ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char piece[512];
        const int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, static_cast<size_t>(n));
        llama_sampler_accept(sampler, token);
        batch = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
        if (llama_decode(g_ctx, batch) != 0) break;
    }
    llama_sampler_free(sampler);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_bibgg_aibbgi_NativeBridge_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}
