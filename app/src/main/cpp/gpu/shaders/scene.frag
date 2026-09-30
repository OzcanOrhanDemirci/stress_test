#version 450
// The visible pass in scene mode: the reactor, drawn at the scene resolution.
#extension GL_GOOGLE_include_directive : require

layout(push_constant) uniform Params {
    vec2 resolution;
    float time;
    float load;
} params;

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;

#include "reactor.glsl"

void main() {
    // Vulkan's window origin is the top left; the scene is written for a bottom-left origin.
    vec2 fragCoord = vec2(gl_FragCoord.x, params.resolution.y - gl_FragCoord.y);
    color = vec4(reactorScene(fragCoord, params.resolution, params.time), 1.0);
}
