#include <jni.h>
#include <android/log.h>

#if defined(__aarch64__)
#include <map>
#include <mutex>
#include "camera_surface_imports.h"

namespace {
using namespace byd_camera;
constexpr char TAG[] = "CameraInputQueue";
constexpr char TIMEOUT[] = "_ZN7android7Surface17setDequeueTimeoutEl";
constexpr char CONSTRUCTOR[] = "_ZN7android7SurfaceC1ERKNS_2spINS_22IGraphicBufferProducerEEEbRKNS1_INS_7IBinderEEE";
using Timeout = int(*)(void*, int64_t);
using Constructor = void(*)(void*, const void*, bool, const void*);
using Count = int(*)(void*, int);
std::mutex lock;
Timeout originalTimeout = nullptr;
Constructor originalConstructor = nullptr;
Count setCount = nullptr;
bool attempted = false, enabled = false;
struct Input { uint64_t token; int index; };
struct Registration { Input input{}; void* surface = nullptr; bool ambiguous = false; };
thread_local Registration pending;
uint64_t nextToken = 0;
std::map<void*, Input> sources;

void constructorHook(void* surface, const void* producer, bool controlled, const void* handle) {
    originalConstructor(surface, producer, controlled, handle);
    std::lock_guard<std::mutex> guard(lock);
    // Also forget a tracked address reused by an unrelated OEM output.
    sources.erase(surface);
    if (!enabled || pending.input.token == 0 || pending.ambiguous) return;
    if (pending.surface) {
        sources.erase(pending.surface);
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                "ordinary input mapping ambiguous index=%d; retaining OEM capacity", pending.input.index);
        pending.ambiguous = true;
        return;
    }
    pending.surface = surface;
    sources[surface] = pending.input;
}

int timeoutHook(void* surface, int64_t timeout) {
    int result = originalTimeout(surface, timeout);
    std::lock_guard<std::mutex> guard(lock);
    auto found = sources.find(surface);
    if (!enabled || found == sources.end()) return result;
    if (result != 0 || timeout != 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                "input capacity skipped index=%d timeout=%lld timeout_rc=%d",
                found->second.index, static_cast<long long>(timeout), result);
        return result;
    }
    // targetInit runs on the OEM worker after addPreviewSurface, just before its first dequeue.
    int countResult = setCount(surface, 3);
    __android_log_print(countResult == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, TAG,
            "input capacity index=%d slots=3 set_rc=%d", found->second.index, countResult);
    return result;
}

bool install() {
    if (attempted) return enabled;
    attempted = true;
    Import timeout{TIMEOUT}, constructor{CONSTRUCTOR};
    dl_iterate_phdr(findImport, &timeout);
    dl_iterate_phdr(findImport, &constructor);
    if (timeout.count != 1 || !timeout.slot || !*timeout.slot
            || constructor.count != 1 || !constructor.slot || !*constructor.slot) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                "input capacity unavailable: OEM imports timeout=%d constructor=%d; retaining OEM capacity",
                timeout.count, constructor.count);
        return false;
    }
    GuiSymbols<3> gui{{TIMEOUT, CONSTRUCTOR, "_ZN7android7Surface14setBufferCountEi"}};
    gui.anchor = *timeout.slot;
    dl_iterate_phdr(findLoadedGui<3>, &gui);
    if (gui.matches != 1 || !gui.error.empty() || gui.values[0] != *timeout.slot
            || gui.values[1] != *constructor.slot || !gui.values[2]) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
                "input capacity unavailable: libgui owners=%d timeout_match=%d constructor_match=%d setter=%d path=%s cause=%s",
                gui.matches, gui.values[0] == *timeout.slot, gui.values[1] == *constructor.slot,
                gui.values[2] != nullptr, gui.path.c_str(), gui.error.c_str());
        return false;
    }
    originalTimeout = reinterpret_cast<Timeout>(*timeout.slot);
    originalConstructor = reinterpret_cast<Constructor>(*constructor.slot);
    setCount = reinterpret_cast<Count>(gui.values[2]);
    int a = replace(constructor.slot, reinterpret_cast<void*>(constructorHook));
    int b = a ? 0 : replace(timeout.slot, reinterpret_cast<void*>(timeoutHook));
    if (a || b) {
        int restoreTimeout = replace(timeout.slot, reinterpret_cast<void*>(originalTimeout));
        int restoreConstructor = replace(constructor.slot, reinterpret_cast<void*>(originalConstructor));
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                "input capacity install failed constructor_rc=%d timeout_rc=%d rollback_timeout_rc=%d rollback_constructor_rc=%d",
                a, b, restoreTimeout, restoreConstructor);
        return false;
    }
    enabled = true;
    return true;
}
}
#endif

extern "C" JNIEXPORT jlong JNICALL Java_com_byd_extend_CameraInputQueue_beforeAdd(
        JNIEnv*, jclass, jint index) {
#if defined(__aarch64__)
    if (index < 1 || index > 4) return 0;
    std::lock_guard<std::mutex> guard(lock);
    if (!install()) return 0;
    if (pending.input.token != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                "nested OEM input registration previous=%d next=%d; retaining OEM capacity", pending.input.index, index);
        sources.erase(pending.surface);
        pending.ambiguous = true;
        return 0;
    }
    pending = {{++nextToken, index}, nullptr, false};
    return static_cast<jlong>(pending.input.token);
#else
    (void)index;
    return 0;
#endif
}

extern "C" JNIEXPORT void JNICALL Java_com_byd_extend_CameraInputQueue_afterAdd(
        JNIEnv*, jclass, jlong token, jboolean added) {
#if defined(__aarch64__)
    std::lock_guard<std::mutex> guard(lock);
    if (pending.input.token != static_cast<uint64_t>(token)) return;
    if (!added) sources.erase(pending.surface);
    else if (!pending.surface)
        __android_log_print(ANDROID_LOG_WARN, TAG,
                "OEM input Surface not observed index=%d; retaining OEM capacity", pending.input.index);
    pending = {};
#else
    (void)token;
    (void)added;
#endif
}

extern "C" JNIEXPORT void JNICALL Java_com_byd_extend_CameraInputQueue_removed(
        JNIEnv*, jclass, jlong token) {
#if defined(__aarch64__)
    std::lock_guard<std::mutex> guard(lock);
    for (auto i = sources.begin(); i != sources.end();)
        if (i->second.token == static_cast<uint64_t>(token)) i = sources.erase(i); else ++i;
#else
    (void)token;
#endif
}

extern "C" JNIEXPORT void JNICALL Java_com_byd_extend_CameraInputQueue_closed(JNIEnv*, jclass) {
#if defined(__aarch64__)
    std::lock_guard<std::mutex> guard(lock);
    sources.clear();
    pending = {};
#endif
}
