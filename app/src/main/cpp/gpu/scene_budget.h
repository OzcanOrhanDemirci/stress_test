#pragma once

#include "gpu/scene_renderer.h"

namespace stress {

inline constexpr int64_t kSlowSceneNanos = 650'000'000;
inline constexpr double kSceneStepBudgetNanos = 500'000'000.0;

// Use the work at the preceding quality to bound the next draw. The pool
// and white world trace a second ray at High, including its extra shading;
// the hole doubles both the samples and roughly the step count, and so do
// the northern lights (with longer marches). The forest adds taps and light
// samples while most of its geometry is fixed.
constexpr bool sceneFits(SceneRenderer::Kind kind, SceneRenderer::Quality current,
                         SceneRenderer::Quality next, float scaleRatio,
                         int64_t frameNanos) {
    using Kind = SceneRenderer::Kind;
    using Quality = SceneRenderer::Quality;
    double shading = 1.0;
    if (current != next && kind == Kind::Forest) shading = 1.25;
    if (current != next && kind == Kind::Hole && next == Quality::Medium) shading = 104.0 / 64.0;
    if (current != next && kind == Kind::Aurora && next == Quality::Medium) shading = 1.1;
    if (current != next && next == Quality::High) {
        if (kind == Kind::Pool) shading = 3.6;
        if (kind == Kind::White) shading = 2.6;
        if (kind == Kind::Hole) shading = 4.6;
        if (kind == Kind::Aurora) shading = 3.5;
    }
    // One budget for every step: the probe guards against a frame long enough
    // to reset the driver, it does not choose a frame rate. Probe frames can run
    // before the GPU clock ramps up, so a tighter budget would refuse settings
    // the phone draws well once warm (the Honor's hole at Medium was refused at 125 ms).
    return frameNanos > 0 && static_cast<double>(frameNanos) * scaleRatio * scaleRatio * shading <= kSceneStepBudgetNanos;
}

struct SceneDraw {
    SceneRenderer::Quality quality = SceneRenderer::Quality::Low;
    // 10,000 means full screen. Zero is reserved for a scene that has yet to draw.
    uint16_t scale = 0;

    constexpr uint32_t packed() const { return (static_cast<uint32_t>(quality) << 14) | scale; }
    constexpr bool operator==(const SceneDraw&) const = default;
};

constexpr SceneDraw lowestSceneDraw(SceneDraw previous, SceneDraw drawn) {
    return previous.scale == 0 || drawn.packed() < previous.packed() ? drawn : previous;
}

constexpr bool sceneTooSlow(bool settled, SceneRenderer::Quality quality, int64_t probePeak,
                            uint32_t slowFrames) {
    return slowFrames >= 2 || (!settled && (probePeak > kSlowSceneNanos ||
        (quality == SceneRenderer::Quality::Low && probePeak > 180'000'000)));
}

constexpr bool sceneProbeReachedTarget(SceneDraw drawn, SceneDraw target, uint32_t framesLeft, int64_t peak,
                                       uint32_t slowFrames) {
    return framesLeft == 0 && drawn == target && !sceneTooSlow(false, drawn.quality, peak, slowFrames);
}

namespace scene_budget_checks {
// Honor 400 GPU timings from docs/OLCUMLER.md, including the later Medium measurements.
using Kind = SceneRenderer::Kind;
using Quality = SceneRenderer::Quality;
static_assert(sceneFits(Kind::Pool, Quality::Low, Quality::Medium, 45.0f / 30, 20'800'000));
static_assert(sceneFits(Kind::Forest, Quality::Low, Quality::Medium, 45.0f / 30, 25'100'000));
static_assert(sceneFits(Kind::White, Quality::Low, Quality::Medium, 45.0f / 30, 19'700'000));
static_assert(sceneFits(Kind::Hole, Quality::Low, Quality::Medium, 45.0f / 30, 12'100'000));
static_assert(sceneFits(Kind::Pool, Quality::Medium, Quality::High, 65.0f / 45, 47'600'000));
static_assert(sceneFits(Kind::Forest, Quality::Medium, Quality::High, 100.0f / 45, 41'100'000));
static_assert(sceneFits(Kind::White, Quality::Medium, Quality::High, 65.0f / 45, 39'200'000));
static_assert(sceneFits(Kind::Hole, Quality::Medium, Quality::High, 65.0f / 45, 23'700'000));
static_assert(sceneFits(Kind::Aurora, Quality::Low, Quality::Medium, 45.0f / 30, 19'200'000));
static_assert(sceneFits(Kind::Aurora, Quality::Medium, Quality::High, 65.0f / 45, 46'900'000));
static_assert(sceneFits(Kind::Aurora, Quality::Low, Quality::Medium, 45.0f / 30, 3 * 19'200'000));
// The bounded Low starts at about 18.6% (hole) and 28.5% (other scenes) on the Honor.
static_assert(sceneFits(Kind::Hole, Quality::Low, Quality::Low, 30.0f / 18.6f, 12'100'000));
static_assert(sceneFits(Kind::Forest, Quality::Low, Quality::Low, 30.0f / 28.4f, 25'100'000));
static_assert(!sceneTooSlow(false, Quality::High, 295'000'000, 0)); // Pool, the slowest measured High
static_assert(!sceneTooSlow(false, Quality::High, 118'700'000, 0));
static_assert(!sceneTooSlow(false, Quality::High, 336'600'000, 0));  // the northern lights' High
static_assert(!sceneTooSlow(true, Quality::Low, kSlowSceneNanos + 1, 1));
static_assert(sceneTooSlow(true, Quality::High, kSlowSceneNanos + 1, 2));
static_assert(!sceneFits(Kind::Pool, Quality::Low, Quality::Medium, 45.0f / 30, 250'000'000));
// A probe frame three times slower than warm (GPU clock still low) must not refuse the step.
static_assert(sceneFits(Kind::Hole, Quality::Low, Quality::Medium, 45.0f / 30, 3 * 12'900'000));
static_assert(sceneFits(Kind::Pool, Quality::Low, Quality::Medium, 45.0f / 30, 3 * 25'900'000));
static_assert(!sceneFits(Kind::Hole, Quality::Medium, Quality::High, 65.0f / 45, 100'000'000));
static_assert(lowestSceneDraw({Quality::Medium, 4500}, {Quality::Low, 5000}) == SceneDraw{Quality::Low, 5000});
static_assert(lowestSceneDraw({Quality::Low, 3000}, {Quality::Low, 2100}) == SceneDraw{Quality::Low, 2100});
static_assert(!sceneProbeReachedTarget({Quality::Low, 1862}, {Quality::Low, 3000}, 0, 12'100'000, 0));
static_assert(!sceneProbeReachedTarget({Quality::High, 6500}, {Quality::High, 6500}, 1, 118'700'000, 0));
static_assert(sceneProbeReachedTarget({Quality::High, 6500}, {Quality::High, 6500}, 0, 118'700'000, 0));
}  // namespace scene_budget_checks

}  // namespace stress
