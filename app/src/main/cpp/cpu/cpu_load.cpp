#include "cpu/cpu_load.h"

#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <thread>
#include <vector>

#include "common/log.h"
#include "cpu/kernel_table.h"
#include "cpu/operands.h"

namespace stress {
namespace {

int64_t nowNanos() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000 + ts.tv_nsec;
}

struct Calibration {
    uint64_t iterations = 0;
    Digest golden;
};

// Finds how many iterations take about `batchMillis` on the calling thread and
// computes the reference digest for that count.
bool calibrate(const KernelSpec& spec, int batchMillis, Calibration& out) {
    OperandBuffer buffer = makeOperands(spec);
    if (!buffer) return false;

    Digest digest;
    uint64_t iterations = 1;
    int64_t elapsed = 0;
    constexpr int64_t kProbeNanos = 3'000'000;
    for (;;) {
        const int64_t t0 = nowNanos();
        spec.fn(iterations, buffer.get(), spec.bufferBytes, &digest);
        elapsed = nowNanos() - t0;
        if (elapsed >= kProbeNanos || iterations >= (1ULL << 40)) break;
        iterations *= 2;
    }
    const double scale = static_cast<double>(batchMillis) * 1e6 / static_cast<double>(std::max<int64_t>(elapsed, 1));
    out.iterations = std::max<uint64_t>(1, static_cast<uint64_t>(static_cast<double>(iterations) * scale));
    spec.fn(out.iterations, buffer.get(), spec.bufferBytes, &out.golden);
    return true;
}

}  // namespace

bool pinCurrentThread(int cpu) {
    cpu_set_t set;
    CPU_ZERO(&set);
    CPU_SET(cpu, &set);
    return sched_setaffinity(0, sizeof(set), &set) == 0;
}

bool runKernelOnce(int kernelIndex, uint64_t iterations, Digest& out) {
    const KernelSpec* spec = kernelAt(kernelIndex);
    if (spec == nullptr || iterations == 0) return false;
    OperandBuffer buffer = makeOperands(*spec);
    if (!buffer) return false;
    spec->fn(iterations, buffer.get(), spec->bufferBytes, &out);
    return true;
}

struct CpuLoad::Worker {
    CpuLoad* owner = nullptr;
    int cpu = -1;
    int kernelIndex = -1;
    const KernelSpec* spec = nullptr;
    Calibration calibration;
    int nice = 0;
    pthread_t thread{};
    bool threadStarted = false;

    std::atomic<uint64_t> batches{0};
    std::atomic<uint64_t> errors{0};
    std::atomic<int32_t> lastCpu{-1};
    std::atomic<int32_t> flags{0};
};

CpuLoad::CpuLoad() = default;

CpuLoad::~CpuLoad() { stop(); }

void* CpuLoad::threadMain(void* arg) {
    auto* worker = static_cast<Worker*>(arg);
    worker->owner->runWorker(*worker);
    return nullptr;
}

void CpuLoad::runWorker(Worker& w) {
    int flags = 0;
    if (pinCurrentThread(w.cpu)) flags |= kFlagPinned;
    if (setpriority(PRIO_PROCESS, static_cast<id_t>(gettid()), w.nice) == 0) flags |= kFlagNiceSet;

    OperandBuffer buffer = makeOperands(*w.spec);
    if (!buffer) {
        w.flags.store(flags | kFlagFailed, std::memory_order_release);
        ready_.fetch_add(1, std::memory_order_acq_rel);
        return;
    }
    w.flags.store(flags, std::memory_order_release);
    ready_.fetch_add(1, std::memory_order_acq_rel);

    while (!go_.load(std::memory_order_acquire)) {
        if (stop_.load(std::memory_order_acquire)) return;
        std::this_thread::sleep_for(std::chrono::microseconds(200));
    }
    w.flags.store(flags | kFlagRunning, std::memory_order_release);

    const KernelSpec& spec = *w.spec;
    Digest digest;
    while (!stop_.load(std::memory_order_relaxed)) {
        // A paused CPU refuses the pin; the load itself makes core_ctl resume
        // it, so keep asking until it is ours.
        if ((flags & kFlagPinned) == 0 && pinCurrentThread(w.cpu)) {
            flags |= kFlagPinned;
            w.flags.store(flags | kFlagRunning, std::memory_order_release);
        }
        spec.fn(w.calibration.iterations, buffer.get(), spec.bufferBytes, &digest);
        if (!(digest == w.calibration.golden)) {
            w.errors.fetch_add(1, std::memory_order_relaxed);
        }
        w.batches.fetch_add(1, std::memory_order_relaxed);
        w.lastCpu.store(sched_getcpu(), std::memory_order_relaxed);
    }
    w.flags.store(flags, std::memory_order_release);
}

CpuLoad::StartResult CpuLoad::start(const std::array<int, kMaxCpus>& kernelPerCpu, int nice, int batchMillis) {
    std::lock_guard control(control_);
    {
        std::lock_guard lock(workersMutex_);
        for (const auto& w : workers_) {
            if (w) return kAlreadyRunning;
        }
    }

    // Calibrate each distinct kernel once, before any worker starts, so the
    // timing is not disturbed by the load itself.
    const size_t kernelCount = kernelTable().size();
    std::vector<Calibration> calibrations(kernelCount);
    std::vector<bool> calibrated(kernelCount, false);
    for (int k : kernelPerCpu) {
        if (k < 0) continue;
        const KernelSpec* spec = kernelAt(k);
        if (spec == nullptr) return kInvalidKernel;
        if (calibrated[static_cast<size_t>(k)]) continue;
        if (!calibrate(*spec, batchMillis, calibrations[static_cast<size_t>(k)])) return kNoMemory;
        calibrated[static_cast<size_t>(k)] = true;
        LOGI("calibrated %s: %llu iterations per %d ms batch", spec->code,
             static_cast<unsigned long long>(calibrations[static_cast<size_t>(k)].iterations), batchMillis);
    }

    go_.store(false);
    stop_.store(false);
    ready_.store(0);

    int launched = 0;
    for (int cpu = 0; cpu < kMaxCpus; ++cpu) {
        const int k = kernelPerCpu[static_cast<size_t>(cpu)];
        if (k < 0) continue;
        auto w = std::make_unique<Worker>();
        w->owner = this;
        w->cpu = cpu;
        w->kernelIndex = k;
        w->spec = kernelAt(k);
        w->calibration = calibrations[static_cast<size_t>(k)];
        w->nice = nice;
        if (pthread_create(&w->thread, nullptr, &CpuLoad::threadMain, w.get()) != 0) {
            stopLocked();
            return kThreadFailed;
        }
        w->threadStarted = true;
        {
            std::lock_guard lock(workersMutex_);
            workers_[static_cast<size_t>(cpu)] = std::move(w);
        }
        ++launched;
    }

    // Wait for every worker to pin itself and build its buffer, then release
    // them together so the load starts as one step.
    const int64_t deadline = nowNanos() + 10'000'000'000LL;
    while (ready_.load(std::memory_order_acquire) < launched) {
        if (nowNanos() > deadline) {
            stopLocked();
            return kSetupTimeout;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    bool anyFailed = false;
    {
        std::lock_guard lock(workersMutex_);
        for (const auto& w : workers_) {
            if (w && (w->flags.load(std::memory_order_acquire) & kFlagFailed) != 0) anyFailed = true;
        }
    }
    if (anyFailed) {
        stopLocked();
        return kNoMemory;
    }
    go_.store(true, std::memory_order_release);
    return kStarted;
}

void CpuLoad::stop() {
    std::lock_guard control(control_);
    stopLocked();
}

// Requires control_. Joins outside workersMutex_ so snapshots keep flowing
// while the last batches finish.
void CpuLoad::stopLocked() {
    stop_.store(true, std::memory_order_release);
    std::array<std::unique_ptr<Worker>, kMaxCpus> finished;
    {
        std::lock_guard lock(workersMutex_);
        finished = std::move(workers_);
        workers_ = {};
    }
    for (auto& w : finished) {
        if (w && w->threadStarted) pthread_join(w->thread, nullptr);
    }
    go_.store(false);
}

void CpuLoad::snapshot(int64_t* out) const {
    std::lock_guard lock(workersMutex_);
    for (int cpu = 0; cpu < kMaxCpus; ++cpu) {
        int64_t* slot = out + cpu * kSnapshotStride;
        const auto& w = workers_[static_cast<size_t>(cpu)];
        if (!w) {
            slot[kFieldKernel] = -1;
            slot[kFieldIterations] = 0;
            slot[kFieldBatches] = 0;
            slot[kFieldErrors] = 0;
            slot[kFieldLastCpu] = -1;
            slot[kFieldFlags] = 0;
            continue;
        }
        slot[kFieldKernel] = w->kernelIndex;
        slot[kFieldIterations] = static_cast<int64_t>(w->calibration.iterations);
        slot[kFieldBatches] = static_cast<int64_t>(w->batches.load(std::memory_order_relaxed));
        slot[kFieldErrors] = static_cast<int64_t>(w->errors.load(std::memory_order_relaxed));
        slot[kFieldLastCpu] = w->lastCpu.load(std::memory_order_relaxed);
        slot[kFieldFlags] = w->flags.load(std::memory_order_acquire);
    }
}

}  // namespace stress
