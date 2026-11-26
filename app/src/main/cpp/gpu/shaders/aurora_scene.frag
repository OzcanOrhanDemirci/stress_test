#version 450
// Draws the northern lights at the scene resolution: linear HDR colour and
// the distance along each pixel's ray to what it shows (the land, the
// curtains, a reflection's image behind the water), which the TAA pass
// reprojects with.
#extension GL_GOOGLE_include_directive : require

#include "frame_params.glsl"
#include "aurora.glsl"
#include "quality.glsl"

// Samples a pixel takes each frame (AURORA_SAMPLES, one of the scene's own
// constants), on a rotated grid around the frame's jitter.
layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;
layout(location = 1) out float distance;

void main() {
    // Vulkan's window origin is the top left; the scene's camera math uses bottom left.
    vec2 fragCoord = vec2(gl_FragCoord.x, params.resolution.y - gl_FragCoord.y);
    float depth;
    vec3 c = renderAurora(fragCoord, params.resolution, params.time, params.frame * uint(AURORA_SAMPLES),
                          params.jitter + sampleOffset(0, AURORA_SAMPLES), depth);
    for (int s = 1; s < AURORA_SAMPLES; ++s) {
        float ignored;
        c += renderAurora(fragCoord, params.resolution, params.time, params.frame * uint(AURORA_SAMPLES) + uint(s),
                          params.jitter + sampleOffset(s, AURORA_SAMPLES), ignored);
    }
    color = vec4(sanitize(c / float(AURORA_SAMPLES)), 1.0);
    distance = depth;
}
