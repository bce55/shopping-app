#include <jni.h>
#include <android/log.h>

#include <string>

#include "whisper.h"

#define TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

whisper_context *as_ctx(jlong ptr) {
    return reinterpret_cast<whisper_context *>(ptr);
}

// Return raw UTF-8 bytes. NewStringUTF expects *modified* UTF-8 and aborts
// on emoji (4-byte sequences), which Whisper does emit now and then.
jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()),
                            reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_vntranscriber_WhisperLib_initContext(JNIEnv *env, jobject, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (ctx == nullptr) LOGE("failed to load model from %s", path);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vntranscriber_WhisperLib_freeContext(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(as_ctx(ptr));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vntranscriber_WhisperLib_fullTranscribe(JNIEnv *env, jobject, jlong ptr,
                                                 jfloatArray audio, jstring language,
                                                 jint threads) {
    whisper_context *ctx = as_ctx(ptr);
    if (ctx == nullptr) return nullptr;

    const char *lang_chars = env->GetStringUTFChars(language, nullptr);
    std::string lang(lang_chars);
    env->ReleaseStringUTFChars(language, lang_chars);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang.c_str();  // "auto", "he", "en", ...
    p.translate = false;
    p.no_context = true;        // each voice note stands alone
    p.no_timestamps = true;
    p.suppress_blank = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;

    const jsize n = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const int rc = whisper_full(ctx, p, samples, n);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        text += whisper_full_get_segment_text(ctx, i);
    }
    return to_bytes(env, text);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_vntranscriber_WhisperLib_detectedLanguage(JNIEnv *env, jobject, jlong ptr) {
    const char *lang = ptr != 0 ? whisper_lang_str(whisper_full_lang_id(as_ctx(ptr))) : nullptr;
    return env->NewStringUTF(lang != nullptr ? lang : "?");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_vntranscriber_WhisperLib_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}
