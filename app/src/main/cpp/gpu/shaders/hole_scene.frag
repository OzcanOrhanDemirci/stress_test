#version 450
// Draws the black hole at the scene resolution: linear HDR colour and the
// arc length along each pixel's ray to the nearest thing it met (a disk
// crossing, the horizon, or the stars far out), which the TAA pass
// reprojects with.
#extension GL_GOOGLE_include_directive : require

#include "frame_params.glsl"
#include "hole.glsl"
#include "quality.glsl"

// Samples a pixel takes each frame (HOLE_SAMPLES, one of the scene's own
// constants): each takes its own random samples, so the history converges in
// fewer frames.
layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;
layout(location = 1) out float distance;

void main() {
    // Vulkan's window origin is the top left; the scene's camera math uses bottom left.
    vec2 fragCoord = vec2(gl_FragCoord.x, params.resolution.y - gl_FragCoord.y);
    float depth;
    vec3 c = renderHole(fragCoord, params.resolution, params.time, params.frame * uint(HOLE_SAMPLES),
                        params.jitter + sampleOffset(0, HOLE_SAMPLES), depth);
    for (int s = 1; s < HOLE_SAMPLES; ++s) {
        float ignored;
        c += renderHole(fragCoord, params.resolution, params.time, params.frame * uint(HOLE_SAMPLES) + uint(s),
                        params.jitter + sampleOffset(s, HOLE_SAMPLES), ignored);
    }
    color = vec4(sanitize(c / float(HOLE_SAMPLES)), 1.0);
    distance = depth;
}
