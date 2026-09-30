// JNI surface of the engine. Kotlin counterpart: dev.ozcan.stress.engine.NativeBridge.

#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <sched.h>
#include <time.h>

#include <array>
#include <cstdio>
#include <string>
#include <vector>

#include "cpu/cpu_load.h"
#include "cpu/kernel_table.h"
#include "gpu/gpu_load.h"
#include "sense/sysfs_reader.h"

namespace {

stress::CpuLoad gCpuLoad;
stress::SysfsReader gSensors;
stress::GpuLoad gGpuLoad;

struct PinnedRun {
    int kernel = 0;
    uint64_t iterations = 0;
    int cpu = -1;
    bool pinned = false;
    bool ok = false;
    stress::Digest digest;
};

int64_t monotonicNanos() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000 + ts.tv_nsec;
}

void* pinnedRunMain(void* arg) {
    auto* run = static_cast<PinnedRun*>(arg);
    if (run->cpu >= 0) {
        // The target may be paused by core_ctl. Spinning here is demand that
        // makes it resume the CPU, usually within tens of milliseconds.
        const int64_t deadline = monotonicNanos() + 2'000'000'000;
        while (!(run->pinned = stress::pinCurrentThread(run->cpu)) && monotonicNanos() < deadline) {
            const int64_t until = monotonicNanos() + 2'000'000;
            while (monotonicNanos() < until) {
            }
        }
    }
    run->ok = stress::runKernelOnce(run->kernel, run->iterations, run->digest);
    return nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_kernelTable(JNIEnv* env, jclass) {
    const auto table = stress::kernelTable();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(table.size()), stringClass, nullptr);
    for (size_t i = 0; i < table.size(); ++i) {
        const auto& k = table[i];
        char line[160];
        std::snprintf(line, sizeof(line), "%s|%s|%s|%.17g|%u", k.key, k.code, k.unit, k.opsPerIteration(),
                      k.bufferBytes);
        jstring s = env->NewStringUTF(line);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_cpuStart(JNIEnv* env, jclass, jintArray kernelPerCpu, jint nice,
                                                   jint batchMillis) {
    std::array<int, stress::CpuLoad::kMaxCpus> kernels{};
    kernels.fill(-1);
    const jsize n = env->GetArrayLength(kernelPerCpu);
    std::vector<jint> values(static_cast<size_t>(n));
    env->GetIntArrayRegion(kernelPerCpu, 0, n, values.data());
    for (jsize i = 0; i < n && i < stress::CpuLoad::kMaxCpus; ++i) {
        kernels[static_cast<size_t>(i)] = values[static_cast<size_t>(i)];
    }
    return gCpuLoad.start(kernels, nice, batchMillis);
}

JNIEXPORT void JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_cpuStop(JNIEnv*, jclass) {
    gCpuLoad.stop();
}

JNIEXPORT jint JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_cpuSnapshotStride(JNIEnv*, jclass) {
    return stress::CpuLoad::kSnapshotStride;
}

JNIEXPORT void JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_cpuSnapshot(JNIEnv* env, jclass, jlongArray out) {
    constexpr int kLength = stress::CpuLoad::kMaxCpus * stress::CpuLoad::kSnapshotStride;
    if (env->GetArrayLength(out) < kLength) return;
    std::array<int64_t, kLength> values{};
    gCpuLoad.snapshot(values.data());
    env->SetLongArrayRegion(out, 0, kLength, reinterpret_cast<const jlong*>(values.data()));
}

// Runs one kernel to completion on a fresh thread pinned to `cpu` (-1: not
// pinned). Returns {digest low, digest high, pinned ? 1 : 0}, or null.
JNIEXPORT jlongArray JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_kernelDigest(JNIEnv* env, jclass, jint kernel, jlong iterations,
                                                       jint cpu) {
    PinnedRun run;
    run.kernel = kernel;
    run.iterations = static_cast<uint64_t>(iterations);
    run.cpu = cpu;
    pthread_t thread;
    if (pthread_create(&thread, nullptr, &pinnedRunMain, &run) != 0) return nullptr;
    pthread_join(thread, nullptr);
    if (!run.ok) return nullptr;
    const std::array<jlong, 3> values{static_cast<jlong>(run.digest.lo), static_cast<jlong>(run.digest.hi),
                                      run.pinned ? 1 : 0};
    jlongArray result = env->NewLongArray(3);
    env->SetLongArrayRegion(result, 0, 3, values.data());
    return result;
}

JNIEXPORT jbooleanArray JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_sensorsOpen(JNIEnv* env, jclass, jobjectArray paths) {
    const jsize n = env->GetArrayLength(paths);
    std::vector<std::string> list;
    list.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        const char* chars = env->GetStringUTFChars(s, nullptr);
        list.emplace_back(chars);
        env->ReleaseStringUTFChars(s, chars);
        env->DeleteLocalRef(s);
    }
    const std::vector<bool> readable = gSensors.open(list);
    jbooleanArray result = env->NewBooleanArray(n);
    std::vector<jboolean> flags(readable.size());
    for (size_t i = 0; i < readable.size(); ++i) flags[i] = readable[i] ? JNI_TRUE : JNI_FALSE;
    env->SetBooleanArrayRegion(result, 0, n, flags.data());
    return result;
}

JNIEXPORT void JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_sensorsRead(JNIEnv* env, jclass, jlongArray out) {
    const jsize n = env->GetArrayLength(out);
    std::vector<int64_t> values(static_cast<size_t>(n), stress::SysfsReader::kMissing);
    gSensors.read(values.data(), values.size());
    env->SetLongArrayRegion(out, 0, n, reinterpret_cast<const jlong*>(values.data()));
}

JNIEXPORT void JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_sensorsClose(JNIEnv*, jclass) {
    gSensors.close();
}

JNIEXPORT jobjectArray JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_gpuBurnerTable(JNIEnv* env, jclass) {
    const auto table = stress::gpuBurnerTable();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(table.size()), stringClass, nullptr);
    for (size_t i = 0; i < table.size(); ++i) {
        const auto& b = table[i];
        char line[96];
        std::snprintf(line, sizeof(line), "%s|%s|%s|%d", b.key, b.code, b.unit, b.verified ? 1 : 0);
        jstring s = env->NewStringUTF(line);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return result;
}

// Blocks until Vulkan is set up on the render thread (a few hundred milliseconds).
JNIEXPORT jint JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_gpuStart(JNIEnv* env, jclass, jobject surface, jint burner,
                                                   jint targetFrameMillis, jboolean scene, jint sceneScalePercent) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return stress::GpuLoad::kSetupFailed;
    return gGpuLoad.start(window, burner, targetFrameMillis, scene == JNI_TRUE, sceneScalePercent);
}

JNIEXPORT void JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_gpuStop(JNIEnv*, jclass) {
    gGpuLoad.stop();
}

JNIEXPORT jint JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_gpuSnapshotStride(JNIEnv*, jclass) {
    return stress::GpuLoad::kSnapshotStride;
}

JNIEXPORT void JNICALL
Java_dev_ozcan_stress_engine_NativeBridge_gpuSnapshot(JNIEnv* env, jclass, jlongArray out) {
    constexpr int kLength = stress::GpuLoad::kSnapshotStride;
    if (env->GetArrayLength(out) < kLength) return;
    std::array<int64_t, kLength> values{};
    gGpuLoad.snapshot(values.data());
    env->SetLongArrayRegion(out, 0, kLength, reinterpret_cast<const jlong*>(values.data()));
}

}  // extern "C"
