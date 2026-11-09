// JNI bridge: whisper.cpp transcription for Carnet's "Improve" pass (fully offline).
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <thread>
#include <algorithm>
#include "whisper.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "CarnetWhisper", __VA_ARGS__)
#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_app_carnet_nativeai_NativeLib_##name

struct ProgressCtx { JNIEnv * env; jobject cb; jmethodID m; };

static void on_progress(struct whisper_context *, struct whisper_state *, int progress, void * user) {
    auto * p = static_cast<ProgressCtx *>(user);
    p->env->CallVoidMethod(p->cb, p->m, (jint) progress);
}

static bool on_abort(void * user) {
    // Abort if the Java side asked (via thread interrupt flag on the callback object).
    return false;
}

JNI(jlong, whisperInit)(JNIEnv * env, jclass, jstring jpath, jint threads) {
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;
    whisper_context * ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ctx) LOG("failed to load model");
    return reinterpret_cast<jlong>(ctx);
}

JNI(jobjectArray, whisperTranscribe)(JNIEnv * env, jclass, jlong handle, jfloatArray jpcm, jstring jlang, jobject cb) {
    auto * ctx = reinterpret_cast<whisper_context *>(handle);
    jsize n = env->GetArrayLength(jpcm);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());
    const char * lang = env->GetStringUTFChars(jlang, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    p.beam_search.beam_size = 3;
    p.language = lang;
    p.translate = false;
    p.n_threads = std::max(1, std::min(6, (int) std::thread::hardware_concurrency()));
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.no_context = false;
    p.suppress_blank = true;
    // Teachers dictate slowly with pauses; a French prompt nudges punctuation and spelling.
    p.initial_prompt = "Bonjour à tous. Prenez vos cahiers. Aujourd'hui, nous étudions le chapitre suivant.";

    ProgressCtx pc{env, cb, env->GetMethodID(env->GetObjectClass(cb), "onProgress", "(I)V")};
    p.progress_callback = on_progress;
    p.progress_callback_user_data = &pc;
    p.abort_callback = on_abort;

    int rc = whisper_full(ctx, p, pcm.data(), (int) pcm.size());
    env->ReleaseStringUTFChars(jlang, lang);
    jclass str = env->FindClass("java/lang/String");
    if (rc != 0) return env->NewObjectArray(0, str, nullptr);
    int ns = whisper_full_n_segments(ctx);
    jobjectArray out = env->NewObjectArray(ns, str, nullptr);
    for (int i = 0; i < ns; i++) {
        // t0 is in 10 ms units.
        std::string line = std::to_string(whisper_full_get_segment_t0(ctx, i) * 10) + "\t" + whisper_full_get_segment_text(ctx, i);
        jstring s = env->NewStringUTF(line.c_str());
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

JNI(void, whisperFree)(JNIEnv *, jclass, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}
