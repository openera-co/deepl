// JNI bridge: CTranslate2 translation for Carnet. Tokenisation stays in Kotlin (MarianText), so the
// model sees exactly the pieces the app has always produced.
#include <jni.h>
#include <android/log.h>
#include <ctranslate2/translator.h>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "CarnetCt2", __VA_ARGS__)
#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_app_carnet_nativeai_Ct2Lib_##name

// OpenMP's default is to keep idle threads spinning, which on 4 ARM cores made 4 threads slower
// than 2 (3.2 vs 11.6 lines/s); with passive waiting 4 threads give 14.3 (engine workflow). Must be
// set before the OpenMP runtime starts, i.e. here.
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    setenv("OMP_WAIT_POLICY", "PASSIVE", 1);
    return JNI_VERSION_1_6;
}

static void throwJava(JNIEnv* env, const char* msg) {
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), msg);
}

JNI(jlong, init)(JNIEnv* env, jclass, jstring jdir, jint threads) {
    const char* dir = env->GetStringUTFChars(jdir, nullptr);
    std::string path(dir);
    env->ReleaseStringUTFChars(jdir, dir);
    try {
        ctranslate2::ReplicaPoolConfig pool;
        pool.num_threads_per_replica = threads;
        pool.max_queued_batches = 0;
        auto* t = new ctranslate2::Translator(path, ctranslate2::Device::CPU, ctranslate2::ComputeType::INT8, {0}, false, pool);
        return reinterpret_cast<jlong>(t);
    } catch (const std::exception& e) {
        LOG("load failed: %s", e.what());
        throwJava(env, e.what());
        return 0;
    }
}

// batch: one byte[] per sentence, UTF-8 source pieces separated by 0x1F (raw bytes: JNI's
// "modified UTF-8" strings would corrupt emoji and other 4-byte characters). Same format back.
JNI(jobjectArray, translate)(JNIEnv* env, jclass, jlong handle, jobjectArray batch, jint beam, jint maxLen) {
    auto* t = reinterpret_cast<ctranslate2::Translator*>(handle);
    jsize n = env->GetArrayLength(batch);
    std::vector<std::vector<std::string>> src(n);
    for (jsize i = 0; i < n; i++) {
        auto b = (jbyteArray) env->GetObjectArrayElement(batch, i);
        jsize len = env->GetArrayLength(b);
        std::string all(len, '\0');
        env->GetByteArrayRegion(b, 0, len, reinterpret_cast<jbyte*>(&all[0]));
        env->DeleteLocalRef(b);
        size_t p = 0;
        while (p <= all.size()) {
            size_t q = all.find('\x1f', p);
            if (q == std::string::npos) q = all.size();
            if (q > p) src[i].emplace_back(all.substr(p, q - p));
            p = q + 1;
        }
    }
    std::vector<ctranslate2::TranslationResult> res;
    try {
        ctranslate2::TranslationOptions opt;
        opt.beam_size = beam;
        opt.max_decoding_length = maxLen;
        res = t->translate_batch(src, opt);
    } catch (const std::exception& e) {
        LOG("translate failed: %s", e.what());
        throwJava(env, e.what());
        return nullptr;
    }
    jobjectArray out = env->NewObjectArray(n, env->FindClass("[B"), nullptr);
    for (jsize i = 0; i < n; i++) {
        std::string joined;
        const auto& toks = res[i].output();
        for (size_t k = 0; k < toks.size(); k++) { if (k) joined += '\x1f'; joined += toks[k]; }
        jbyteArray b = env->NewByteArray((jsize) joined.size());
        env->SetByteArrayRegion(b, 0, (jsize) joined.size(), reinterpret_cast<const jbyte*>(joined.data()));
        env->SetObjectArrayElement(out, i, b);
        env->DeleteLocalRef(b);
    }
    return out;
}

JNI(void, free)(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<ctranslate2::Translator*>(handle);
}
