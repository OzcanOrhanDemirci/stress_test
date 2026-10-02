#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

namespace stress {

struct Digest {
    uint64_t lo = 0;
    uint64_t hi = 0;
    bool operator==(const Digest&) const = default;
};

// Pins the calling thread to `cpu`. Fails while the CPU is paused: on this
// phone Qualcomm's core_ctl pauses idle big cores (at rest only CPUs 0-4 and 6
// are active) and resumes them when demand rises.
bool pinCurrentThread(int cpu);

// Runs `iterations` of kernel `kernelIndex` on the calling thread.
// Returns false when the index is invalid or the operand buffer cannot be allocated.
bool runKernelOnce(int kernelIndex, uint64_t iterations, Digest& out);

// One burner thread per CPU, each pinned to its core and running its kernel in
// batches of roughly `batchMillis`. After every batch the thread compares the
// digest with the one computed during calibration; a mismatch is counted as a
// computation error (the hardware produced a wrong result).
class CpuLoad {
public:
    // Room for every CPU a phone has had so far; the app sizes its own view
    // from the device and reads only the slots it needs.
    static constexpr int kMaxCpus = 32;

    // Snapshot layout: kSnapshotStride values per CPU slot, kMaxCpus slots.
    enum SnapshotField : int {
        kFieldKernel = 0,      // kernel index, -1 when the CPU is idle
        kFieldIterations = 1,  // iterations per batch
        kFieldBatches = 2,     // batches completed
        kFieldErrors = 3,      // batches whose digest did not match
        kFieldLastCpu = 4,     // CPU the thread last ran on (-1 before the first batch)
        kFieldFlags = 5,       // WorkerFlag bits
        kFieldMisplaced = 6,   // batches that ended on another CPU (core_ctl paused ours)
        kSnapshotStride = 7,
    };

    enum WorkerFlag : int {
        kFlagPinned = 1 << 0,    // sched_setaffinity succeeded (retried every batch until it does)
        kFlagNiceSet = 1 << 1,   // setpriority succeeded
        kFlagRunning = 1 << 2,   // setup finished and the thread is burning
        kFlagFailed = 1 << 3,    // setup failed (buffer allocation)
    };

    enum StartResult : int {
        kStarted = 0,
        kAlreadyRunning = 1,
        kInvalidKernel = 2,
        kNoMemory = 3,
        kThreadFailed = 4,
        kSetupTimeout = 5,
    };

    // Defined where Worker is complete.
    CpuLoad();
    ~CpuLoad();
    CpuLoad(const CpuLoad&) = delete;
    CpuLoad& operator=(const CpuLoad&) = delete;

    // kernelPerCpu[i] is a kernel index for CPU i, or -1 to leave it idle. A
    // kernel this CPU cannot run (kernelSupported()) is refused as invalid.
    // Blocks until every worker has built its buffer, then releases them together.
    StartResult start(const std::array<int, kMaxCpus>& kernelPerCpu, int nice, int batchMillis);

    // Stops and joins every worker. Safe to call when nothing runs.
    void stop();

    // Fills kMaxCpus * kSnapshotStride values.
    void snapshot(int64_t* out) const;

private:
    struct Worker;

    static void* threadMain(void* arg);
    void runWorker(Worker& w);
    void stopLocked();

    // control_ serialises start() and stop(); workersMutex_ only guards the
    // slots, so snapshot() never waits for calibration or thread joins.
    std::mutex control_;
    mutable std::mutex workersMutex_;
    std::array<std::unique_ptr<Worker>, kMaxCpus> workers_{};
    std::atomic<bool> go_{false};
    std::atomic<bool> stop_{false};
    std::atomic<int> ready_{0};
};

}  // namespace stress
