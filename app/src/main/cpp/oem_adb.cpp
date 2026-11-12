#include <android/binder_ibinder.h>
#include <dlfcn.h>
#include <jni.h>
#include <unistd.h>
#include <string>

namespace {
void fail(JNIEnv* env, const std::string& message) {
    env->ThrowNew(env->FindClass("java/io/IOException"), message.c_str());
}
}

// Keep Binder NDK optional: API26 devices must still be able to use Wi-Fi recovery.
extern "C" JNIEXPORT jobject JNICALL
Java_com_byd_extend_OemAdbBridge_findBinder(JNIEnv* env, jclass, jboolean host) {
    const uid_t app_id = getuid() % 100000;
    if (app_id < 10000 || app_id >= 20000) {
        fail(env, "OEM ADB lookup requires ordinary app UID");
        return nullptr;
    }
    static void* library = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        const char* detail = dlerror();
        fail(env, std::string("libbinder_ndk unavailable: ") + (detail ? detail : "dlopen failed"));
        return nullptr;
    }
    using Check = AIBinder* (*)(const char*);
    using ToJava = jobject (*)(JNIEnv*, AIBinder*);
    using Release = void (*)(AIBinder*);
    auto check = reinterpret_cast<Check>(dlsym(library, "AServiceManager_checkService"));
    auto to_java = reinterpret_cast<ToJava>(dlsym(library, "AIBinder_toJavaBinder"));
    auto release = reinterpret_cast<Release>(dlsym(library, "AIBinder_decStrong"));
    if (!check || !to_java || !release) {
        fail(env, "Required Binder NDK lookup/conversion symbols unavailable");
        return nullptr;
    }
    const char* name = host ? "cellsservice_host" : "cellsservice_ivi";
    AIBinder* binder = check(name);
    if (!binder) {
        fail(env, std::string(name) + " unavailable or denied; cause not distinguished");
        return nullptr;
    }
    jobject result = to_java(env, binder);
    release(binder);
    if (!result && !env->ExceptionCheck()) fail(env, std::string(name) + " Java Binder conversion failed");
    return result;
}
