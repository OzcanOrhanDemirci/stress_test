#pragma once

#include <android/native_window.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <span>

namespace stress {

// One GPU burner: what a dispatch does and how its work is counted.
struct GpuBurnerSpec {
    const char* key;   // stable identifier shared with the app
    const char* code;  // short label (G1, ...)
    const char* unit;  // "FLOP", "TEXEL", "B" or "PIXEL"
    bool verified;     // whether its results are checked against a reference
};

std::span<const GpuBurnerSpec> gpuBurnerTable();

// Renders to a window on a thread of its own. Every frame runs the chosen
// burner as many times as fit in `targetFrameMillis` of GPU time, measured with
// timestamp queries, then draws the visible pass. Frames are kept in flight so
// the GPU never waits for the CPU. Burners that compute (G1-G4) write one
// digest per workgroup; each is compared with the first dispatch's, and a
// mismatch counts as a computation error.
class GpuLoad {
public:
    enum SnapshotField : int {
        kFieldState = 0,             // State
        kFieldFrames = 1,            // frames completed
        kFieldDispatches = 2,        // burner dispatches completed
        kFieldWork = 3,              // work completed, in the burner's unit
        kFieldGpuNanos = 4,          // sum of per-frame GPU time
        kFieldLastFrameNanos = 5,    // GPU time of the last completed frame
        kFieldDispatchesPerFrame = 6,
        kFieldErrors = 7,            // dispatches whose digests did not match
        kFieldChecks = 8,            // dispatches whose digests were compared
        kFieldWidth = 9,
        kFieldHeight = 10,
        kFieldBurnerNanos = 11,      // sum of GPU time spent in burner work
        kFieldVisibleNanos = 12,     // sum of GPU time spent drawing what the screen shows
        kSnapshotStride = 13,
    };

    enum State : int { kStateIdle = 0, kStateRunning = 1, kStateDeviceLost = 2, kStateFailed = 3 };

    enum StartResult : int {
        kStarted = 0,
        kAlreadyRunning = 1,
        kInvalidBurner = 2,
        kSetupFailed = 3,
    };

    GpuLoad();
    ~GpuLoad();
    GpuLoad(const GpuLoad&) = delete;
    GpuLoad& operator=(const GpuLoad&) = delete;

    // Takes ownership of `window` (released on stop or failure). `burner` is an
    // index into gpuBurnerTable(), or -1 to draw the visible pass alone. The
    // visible pass is a scene (`sceneKind`: 0 the pool, 1 the forest) at
    // `sceneScalePercent` of the screen's resolution, or with `scene` false a
    // cheap preview ring.
    StartResult start(ANativeWindow* window, int burner, int targetFrameMillis, bool scene, int sceneScalePercent,
                      int sceneKind);

    // Stops the render thread and releases every Vulkan object. Safe to call when idle.
    void stop();

    void snapshot(int64_t* out) const;

    struct Renderer;

private:
    mutable std::mutex control_;
    std::unique_ptr<Renderer> renderer_;
    std::atomic<int> lastState_{kStateIdle};
};

}  // namespace stress
