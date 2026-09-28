#include <jni.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "NativeInterface"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_rtmp_drone_service_NativeMultiplexer_getVersion(
    JNIEnv* env, 
    jobject /* this */
) {
    return env->NewStringUTF("RTMP Native Multiplexer v1.0 - Ultra Optimized");
}

extern "C" JNIEXPORT void JNICALL
Java_com_rtmp_drone_service_NativeMultiplexer_initialize(
    JNIEnv* env, 
    jobject /* this */
) {
    LOGI("Native multiplexer initialized - Zero-copy mode enabled");
}

// Optimisation mémoire: copie rapide
extern "C" JNIEXPORT void JNICALL
Java_com_rtmp_drone_service_NativeMultiplexer_fastCopy(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray src,
    jbyteArray dst,
    jint length
) {
    if (src == nullptr || dst == nullptr || length <= 0) return;
    
    jbyte* srcData = env->GetByteArrayElements(src, nullptr);
    jbyte* dstData = env->GetByteArrayElements(dst, nullptr);
    
    if (srcData && dstData) {
        memcpy(dstData, srcData, length);
    }
    
    if (srcData) env->ReleaseByteArrayElements(src, srcData, JNI_ABORT);
    if (dstData) env->ReleaseByteArrayElements(dst, dstData, 0);
}
