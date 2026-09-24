#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdlib.h>
#include <string>
#include <unistd.h>

namespace {

std::string quickScan(const std::string& code) {
    int braceDepth = 0;
    int line = 1;
    int column = 1;
    bool inString = false;
    bool escaped = false;

    for (char ch : code) {
        if (ch == '\n') {
            ++line;
            column = 1;
            continue;
        }
        if (ch == '"' && !escaped) inString = !inString;
        escaped = (ch == '\\' && !escaped);
        if (!inString) {
            if (ch == '{') ++braceDepth;
            if (ch == '}') {
                --braceDepth;
                if (braceDepth < 0) {
                    return "ERROR|" + std::to_string(line) + "|" + std::to_string(column) + "|unexpected closing brace";
                }
            }
        }
        ++column;
    }

    if (inString) return "ERROR|" + std::to_string(line) + "|" + std::to_string(column) + "|unterminated string literal";
    if (braceDepth != 0) return "ERROR|" + std::to_string(line) + "|" + std::to_string(column) + "|unbalanced braces";
    return "OK|0|0|native scan passed";
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_avrandroid_NativeAvrEngine_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("avr-android-native/1.0");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_avrandroid_NativeAvrEngine_nativeQuickScan(JNIEnv* env, jobject, jstring code) {
    const char* chars = env->GetStringUTFChars(code, nullptr);
    const std::string source = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(code, chars);
    const std::string result = quickScan(source);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_avrandroid_PtyBridge_nativeOpen(JNIEnv* env, jobject) {
    const int fd = posix_openpt(O_RDWR | O_NOCTTY);
    if (fd < 0 || grantpt(fd) != 0 || unlockpt(fd) != 0) {
        if (fd >= 0) close(fd);
        return nullptr;
    }

    char slavePath[256] = {};
    if (ptsname_r(fd, slavePath, sizeof(slavePath)) != 0) {
        close(fd);
        return nullptr;
    }

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        close(fd);
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(2, stringClass, nullptr);
    if (result == nullptr) {
        close(fd);
        return nullptr;
    }
    const std::string fdText = std::to_string(fd);
    jstring fdString = env->NewStringUTF(fdText.c_str());
    jstring pathString = env->NewStringUTF(slavePath);
    env->SetObjectArrayElement(result, 0, fdString);
    env->SetObjectArrayElement(result, 1, pathString);
    env->DeleteLocalRef(fdString);
    env->DeleteLocalRef(pathString);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_avrandroid_PtyBridge_nativeRead(JNIEnv* env, jobject, jint fd, jbyteArray destination, jint timeoutMs) {
    if (fd < 0 || destination == nullptr) return -1;
    const jsize capacity = env->GetArrayLength(destination);
    if (capacity <= 0) return 0;
    pollfd descriptor{};
    descriptor.fd = fd;
    descriptor.events = POLLIN;
    const int ready = poll(&descriptor, 1, timeoutMs < 0 ? -1 : timeoutMs);
    if (ready <= 0) return ready;
    jbyte buffer[4096];
    const size_t amount = static_cast<size_t>(capacity < static_cast<jsize>(sizeof(buffer)) ? capacity : sizeof(buffer));
    const ssize_t received = read(fd, buffer, amount);
    if (received <= 0) return static_cast<jint>(received);
    env->SetByteArrayRegion(destination, 0, static_cast<jsize>(received), buffer);
    return static_cast<jint>(received);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_avrandroid_PtyBridge_nativeWrite(JNIEnv* env, jobject, jint fd, jbyteArray source, jint length) {
    if (fd < 0 || source == nullptr) return -1;
    const jsize available = env->GetArrayLength(source);
    const jsize count = length < available ? length : available;
    if (count <= 0) return 0;
    jbyte buffer[4096];
    const jsize amount = count < static_cast<jsize>(sizeof(buffer)) ? count : static_cast<jsize>(sizeof(buffer));
    env->GetByteArrayRegion(source, 0, amount, buffer);
    const ssize_t written = write(fd, buffer, static_cast<size_t>(amount));
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_avrandroid_PtyBridge_nativeClose(JNIEnv*, jobject, jint fd) {
    if (fd >= 0) close(fd);
}
