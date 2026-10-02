#version 450
// Draws the pool reactor at the scene resolution: linear HDR colour and the
// distance along each pixel's ray, which the TAA pass reprojects with.
#extension GL_GOOGLE_include_directive : require

#include "frame_params.glsl"

// The simulated water surface (water_surface.comp), in GENERAL layout.
layout(set = 0, binding = 0) uniform sampler2D water;
#define POOL_WATER water

#include "pool.glsl"
#include "quality.glsl"

// Rays a pixel traces each frame: 1 on Low and Medium, 2 on High. Each takes
// its own random samples, so the history converges in fewer frames.
QUALITY_CONSTANT(SAMPLES, 1)

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;
layout(location = 1) out float distance;

void main() {
    // Vulkan's window origin is the top left; the scene's camera math uses bottom left.
    vec2 fragCoord = vec2(gl_FragCoord.x, params.resolution.y - gl_FragCoord.y);
    float depth;
    vec3 c = renderPool(fragCoord, params.resolution, params.time, params.frame * uint(SAMPLES),
                        params.jitter + sampleOffset(0, SAMPLES), depth);
    for (int s = 1; s < SAMPLES; ++s) {
        float ignored;
        c += renderPool(fragCoord, params.resolution, params.time, params.frame * uint(SAMPLES) + uint(s),
                        params.jitter + sampleOffset(s, SAMPLES), ignored);
    }
    color = vec4(sanitize(c / float(SAMPLES)), 1.0);
    distance = depth;
}
