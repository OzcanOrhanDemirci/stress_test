#version 450
// A rain streak: brightest in its middle, fading to its ends and edges;
// hidden behind whatever the scene has nearer.
#extension GL_GOOGLE_include_directive : require

#include "forest_params.glsl"

layout(set = 0, binding = 1) uniform sampler2D distances;  // R32F: fetched, never filtered

layout(location = 0) in vec3 colour;
layout(location = 1) in vec2 local;
layout(location = 2) in float viewDistance;
layout(location = 0) out vec4 outColour;

void main() {
    if (viewDistance > texelFetch(distances, ivec2(gl_FragCoord.xy), 0).r) discard;
    float ends = smoothstep(0.0, 0.3, local.x) * smoothstep(1.0, 0.7, local.x);
    float edge = 1.0 - local.y * local.y;
    outColour = vec4(colour * ends * edge, 1.0);
}
