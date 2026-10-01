#version 450
// The forest's sky, behind everything: grey cloud brightening towards the
// low sun, seen through the canopy and the mist.
#extension GL_GOOGLE_include_directive : require

#include "forest_params.glsl"
#include "forest.glsl"

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 colour;
layout(location = 1) out float distanceOut;

void main() {
#ifdef VULKAN
    // Vulkan's window origin is the top left; the camera math uses bottom left.
    vec2 fragCoord = vec2(gl_FragCoord.x, params.resolution.y - gl_FragCoord.y);
#else
    vec2 fragCoord = gl_FragCoord.xy;
#endif
    vec2 p = (fragCoord + params.jitter - 0.5 * params.resolution) / params.resolution.y;
    vec3 ro, fw, rt, up;
    camera(params.time, ro, fw, rt, up);
    vec3 rd = normalize(p.x * rt + p.y * up + FOCAL * fw);
    // Low in the sky the mist is thicker.
    colour = vec4(mist(sky(rd), 90.0 * (1.0 - 0.75 * max(rd.y, 0.0)), rd), 1.0);
    distanceOut = 1000.0;
}
