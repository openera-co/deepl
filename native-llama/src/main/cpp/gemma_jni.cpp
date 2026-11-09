// JNI bridge: TranslateGemma (llama.cpp + mtmd vision) for handwriting and "Improve" (fully offline).
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <thread>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "CarnetGemma", __VA_ARGS__)
#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_app_carnet_nativeai_NativeLib_##name

struct Gemma {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    mtmd_context * mtmd = nullptr;
    int n_ctx = 4096;
};

static void quiet_log(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, "CarnetGemma", "%s", text);
}

JNI(jlong, gemmaInit)(JNIEnv * env, jclass, jstring jmodel, jstring jmmproj, jint threads, jint ctxSize) {
    llama_log_set(quiet_log, nullptr);
    llama_backend_init();
    auto * g = new Gemma();
    g->n_ctx = ctxSize;
    const char * mp = env->GetStringUTFChars(jmodel, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    g->model = llama_model_load_from_file(mp, mparams);
    env->ReleaseStringUTFChars(jmodel, mp);
    if (!g->model) { delete g; return 0; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = ctxSize;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    g->ctx = llama_init_from_model(g->model, cp);
    if (!g->ctx) { llama_model_free(g->model); delete g; return 0; }
    const char * vp = env->GetStringUTFChars(jmmproj, nullptr);
    mtmd_context_params vparams = mtmd_context_params_default();
    vparams.use_gpu = false;
    vparams.n_threads = threads;
    vparams.print_timings = false;
    g->mtmd = mtmd_init_from_file(vp, g->model, vparams);
    env->ReleaseStringUTFChars(jmmproj, vp);
    if (!g->mtmd) LOG("vision projector failed to load; text-only mode");
    return reinterpret_cast<jlong>(g);
}

/** Greedy generation from the current context state until end-of-turn. */
static std::string generate(Gemma * g, llama_pos n_past, int max_tokens) {
    const llama_vocab * vocab = llama_model_get_vocab(g->model);
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    std::string out;
    for (int i = 0; i < max_tokens && n_past < g->n_ctx - 1; i++) {
        llama_token t = llama_sampler_sample(smpl, g->ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        char buf[256];
        int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
        if (n > 0) out.append(buf, n);
        if (out.size() > 12 && out.find("<end_of_turn>") != std::string::npos) { out = out.substr(0, out.find("<end_of_turn>")); break; }
        llama_batch b = llama_batch_get_one(&t, 1);
        if (llama_decode(g->ctx, b) != 0) break;
        n_past++;
    }
    llama_sampler_free(smpl);
    return out;
}

static std::string chat(const std::string & user) {
    return "<start_of_turn>user\n" + user + "<end_of_turn>\n<start_of_turn>model\n";
}

JNI(jstring, gemmaTranslateText)(JNIEnv * env, jclass, jlong handle, jstring jprompt, jint maxTokens) {
    auto * g = reinterpret_cast<Gemma *>(handle);
    const char * pr = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt = chat(pr);
    env->ReleaseStringUTFChars(jprompt, pr);
    llama_memory_clear(llama_get_memory(g->ctx), true);
    const llama_vocab * vocab = llama_model_get_vocab(g->model);
    std::vector<llama_token> toks(prompt.size() + 16);
    int n = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), (int) toks.size(), true, true);
    if (n < 0) return env->NewStringUTF("");
    toks.resize(n);
    for (int i = 0; i < n; i += 512) {
        int m = std::min(512, n - i);
        if (llama_decode(g->ctx, llama_batch_get_one(toks.data() + i, m)) != 0) return env->NewStringUTF("");
    }
    std::string out = generate(g, n, maxTokens);
    return env->NewStringUTF(out.c_str());
}

JNI(jstring, gemmaTranslateImage)(JNIEnv * env, jclass, jlong handle, jbyteArray jimg, jstring jprompt, jint maxTokens) {
    auto * g = reinterpret_cast<Gemma *>(handle);
    if (!g->mtmd) return env->NewStringUTF("");
    jsize len = env->GetArrayLength(jimg);
    std::vector<unsigned char> img(len);
    env->GetByteArrayRegion(jimg, 0, len, reinterpret_cast<jbyte *>(img.data()));
    mtmd_helper_bitmap_wrapper bw = mtmd_helper_bitmap_init_from_buf(g->mtmd, img.data(), img.size(), false, mtmd_helper_init_opt_default());
    if (!bw.bitmap) return env->NewStringUTF("");
    const char * pr = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt = chat(std::string(pr) + mtmd_default_marker());
    env->ReleaseStringUTFChars(jprompt, pr);

    llama_memory_clear(llama_get_memory(g->ctx), true);
    mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bmps[] = {bw.bitmap};
    int rc = mtmd_tokenize(g->mtmd, chunks, &text, bmps, 1);
    std::string out;
    if (rc == 0) {
        llama_pos n_past = 0;
        if (mtmd_helper_eval_chunks(g->mtmd, g->ctx, chunks, 0, 0, 512, true, &n_past) == 0) out = generate(g, n_past, maxTokens);
    } else LOG("mtmd_tokenize failed: %d", rc);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bw.bitmap);
    return env->NewStringUTF(out.c_str());
}

JNI(void, gemmaFree)(JNIEnv *, jclass, jlong handle) {
    auto * g = reinterpret_cast<Gemma *>(handle);
    if (!g) return;
    if (g->mtmd) mtmd_free(g->mtmd);
    if (g->ctx) llama_free(g->ctx);
    if (g->model) llama_model_free(g->model);
    delete g;
}
