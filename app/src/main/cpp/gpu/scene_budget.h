#pragma once

#include "gpu/scene_renderer.h"

namespace stress {

// Use the work at the preceding quality to bound the next draw. The pool
// and white world trace a second ray at High, including its extra shading;
// the hole doubles both the samples and roughly the step count. The
// forest adds taps and light samples while most of its geometry is fixed.
constexpr bool sceneFits(SceneRenderer::Kind kind, SceneRenderer::Quality next, float scaleRatio,
                         int64_t frameNanos) {
    using Kind = SceneRenderer::Kind;
    using Quality = SceneRenderer::Quality;
    double shading = 1.0;
    if (kind == Kind::Forest) shading = 1.25;
    if (kind == Kind::Hole && next == Quality::Medium) shading = 104.0 / 64.0;
    if (next == Quality::High) {
        if (kind == Kind::Pool) shading = 3.6;
        if (kind == Kind::White) shading = 2.6;
        if (kind == Kind::Hole) shading = 4.6;
    }
    const double budget = next == Quality::High ? 500'000'000.0 : 125'000'000.0;
    return frameNanos > 0 && static_cast<double>(frameNanos) * scaleRatio * scaleRatio * shading <= budget;
}

static_assert(sceneFits(SceneRenderer::Kind::Pool, SceneRenderer::Quality::High, 65.0f / 45, 39'600'000));
static_assert(sceneFits(SceneRenderer::Kind::Forest, SceneRenderer::Quality::High, 100.0f / 45, 39'200'000));
static_assert(!sceneFits(SceneRenderer::Kind::Pool, SceneRenderer::Quality::Medium, 45.0f / 30, 208'000'000));
static_assert(!sceneFits(SceneRenderer::Kind::Hole, SceneRenderer::Quality::High, 65.0f / 45, 100'000'000));

}  // namespace stress
