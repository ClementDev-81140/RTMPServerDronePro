#include <jni.h>
#include <cstring>
#include <vector>
#include <memory>
#include <android/log.h>

#define LOG_TAG "RTMPNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Multiplexer RTMP zero-copy optimisé
extern "C" JNIEXPORT jint JNICALL
Java_com_rtmp_drone_service_NativeMultiplexer_distributePacket(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray packet,
    jint packetSize,
    jobjectArray outputStreams
) {
    if (packet == nullptr || outputStreams == nullptr) {
        LOGE("Null parameters");
        return -1;
    }
    
    // Obtenir pointeur direct vers données
    jbyte* packetData = env->GetByteArrayElements(packet, nullptr);
    if (!packetData) {
        LOGE("Failed to get packet data");
        return -1;
    }
    
    jsize streamCount = env->GetArrayLength(outputStreams);
    int successCount = 0;
    
    // Distribuer vers tous les streams actifs
    for (jsize i = 0; i < streamCount; i++) {
        jobject stream = env->GetObjectArrayElement(outputStreams, i);
        if (stream != nullptr) {
            jclass streamClass = env->GetObjectClass(stream);
            jmethodID writeMethod = env->GetMethodID(streamClass, "write", "([BII)V");
            
            if (writeMethod) {
                env->CallVoidMethod(stream, writeMethod, packet, 0, packetSize);
                if (!env->ExceptionCheck()) {
                    successCount++;
                } else {
                    env->ExceptionClear();
                }
            }
            env->DeleteLocalRef(stream);
            env->DeleteLocalRef(streamClass);
        }
    }
    
    env->ReleaseByteArrayElements(packet, packetData, JNI_ABORT);
    
    return successCount;
}

// Détection type de paquet RTMP
extern "C" JNIEXPORT jint JNICALL
Java_com_rtmp_drone_service_NativeMultiplexer_getPacketType(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray packet
) {
    if (packet == nullptr) return -1;
    
    jsize len = env->GetArrayLength(packet);
    if (len < 1) return -1;
    
    jbyte* data = env->GetByteArrayElements(packet, nullptr);
    if (!data) return -1;
    
    // Type RTMP: 8=audio, 9=video, 18=metadata
    int type = data[0] & 0x1F;
    
    env->ReleaseByteArrayElements(packet, data, JNI_ABORT);
    
    return type;
}

// Calcul CRC32 rapide pour vérification intégrité
static const uint32_t crc32_table[256] = {
    0x00000000, 0x77073096, 0xEE0E612C, 0x990951BA,
    // ... (table complète omise pour brièveté)
};

extern "C" JNIEXPORT jint JNICALL
Java_com_rtmp_drone_service_NativeMultiplexer_calculateCRC32(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray data,
    jint length
) {
    if (data == nullptr || length <= 0) return 0;
    
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return 0;
    
    uint32_t crc = 0xFFFFFFFF;
    for (int i = 0; i < length; i++) {
        crc = crc32_table[(crc ^ bytes[i]) & 0xFF] ^ (crc >> 8);
    }
    
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    
    return ~crc;
}
