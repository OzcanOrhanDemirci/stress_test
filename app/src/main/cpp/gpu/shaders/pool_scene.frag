#version 450
// Draws the pool reactor at the scene resolution: linear HDR colour and the
// distance along each pixel's ray, which the TAA pass reprojects with.
#extension GL_GOOGLE_include_directive : require

#include "frame_params.glsl"
#include "pool.glsl"

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;
layout(location = 1) out float distance;

void main() {
    // Vulkan's window origin is the top left; the scene's camera math uses bottom left.
    vec2 fragCoord = vec2(gl_FragCoord.x, params.resolution.y - gl_FragCoord.y);
    float depth;
    vec3 c = renderPool(fragCoord, params.resolution, params.time, params.frame, params.jitter, depth);
    color = vec4(sanitize(c), 1.0);
    distance = depth;
}
