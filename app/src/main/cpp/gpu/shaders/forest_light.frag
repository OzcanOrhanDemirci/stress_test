#version 450
// Sunbeams: the sunlight the mist scatters towards the camera, wherever the
// canopy lets the sun through. Each pixel walks its ray from the camera to
// the surface it sees (at most LIGHT_REACH), asking the shadow map at a few
// points; the points start at a random offset each frame, so the beams come
// out smooth once frames accumulate. Added onto the scene's colour.
#extension GL_GOOGLE_include_directive : require

#include "forest_params.glsl"
#include "forest.glsl"

layout(set = 0, binding = 0) uniform sampler2DShadow sunDepth;
layout(set = 0, binding = 1) uniform sampler2D distances;  // R32F: fetched, never filtered

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 colour;

const int STATIONS = 12;
const float LIGHT_REACH = 70.0;

float litAt(vec3 p) {
    vec3 s = sunSpace(p);
    if (any(greaterThan(abs(s.xy), vec2(1.0)))) return 1.0;
    return texture(sunDepth, vec3(s.xy * 0.5 + 0.5, s.z - 0.0005));
}

void main() {
#ifdef VULKAN
    vec2 fragCoord = vec2(gl_FragCoord.x, params.resolution.y - gl_FragCoord.y);
#else
    vec2 fragCoord = gl_FragCoord.xy;
#endif
    vec2 p = (fragCoord + params.jitter - 0.5 * params.resolution) / params.resolution.y;
    vec3 ro, fw, rt, up;
    camera(params.time, ro, fw, rt, up);
    vec3 rd = normalize(p.x * rt + p.y * up + FOCAL * fw);
    float span = min(texelFetch(distances, ivec2(gl_FragCoord.xy), 0).r, LIGHT_REACH);

    uint seed = hashu(uint(gl_FragCoord.x) * 1973u + uint(gl_FragCoord.y) * 9277u + params.frame * 26699u);
    float offset = rnd(seed);
    float light = 0.0;
    for (int i = 0; i < STATIONS; ++i) {
        float s = (float(i) + offset) / float(STATIONS) * span;
        // Light scattered at s, dimmed by the mist on its way back to the camera.
        light += litAt(ro + rd * s) * exp(-FOG_DENSITY * s);
    }
    light *= span / float(STATIONS);
    colour = vec4(SUN * sunPhase(rd) * FOG_DENSITY * light, 0.0);
}
