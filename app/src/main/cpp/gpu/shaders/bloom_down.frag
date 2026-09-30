#version 450
// One step down the bloom chain: the 13-tap filter from the Call of Duty:
// Advanced Warfare post-processing talk (Jimenez, 2014). The first step keeps
// only light above the threshold, with a soft knee.
#extension GL_GOOGLE_include_directive : require

#include "bloom_params.glsl"

layout(set = 0, binding = 0) uniform sampler2D source;
layout(set = 0, binding = 1) uniform sampler2D particles;  // read on the first step only

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;

vec3 at(vec2 uv, vec2 offset) {
    return texture(source, uv + offset * params.sourceTexel).rgb;
}

void main() {
    vec2 uv = gl_FragCoord.xy / params.targetSize;
    vec3 a = at(uv, vec2(-2, -2)), b = at(uv, vec2(0, -2)), c = at(uv, vec2(2, -2));
    vec3 d = at(uv, vec2(-1, -1)), e = at(uv, vec2(1, -1));
    vec3 f = at(uv, vec2(-2, 0)), g = at(uv, vec2(0, 0)), h = at(uv, vec2(2, 0));
    vec3 i = at(uv, vec2(-1, 1)), j = at(uv, vec2(1, 1));
    vec3 k = at(uv, vec2(-2, 2)), l = at(uv, vec2(0, 2)), m = at(uv, vec2(2, 2));
    vec3 sum = (d + e + i + j) * 0.125 + (a + b + f + g) * 0.03125 + (b + c + g + h) * 0.03125 +
               (f + g + k + l) * 0.03125 + (g + h + l + m) * 0.03125;
    if (params.first > 0.5) {
        sum += texture(particles, uv).rgb;
        float luma = max(sum.r, max(sum.g, sum.b));
        float knee = params.threshold * 0.5;
        float soft = clamp(luma - params.threshold + knee, 0.0, 2.0 * knee);
        soft = soft * soft / (4.0 * knee + 1e-4);
        sum *= max(soft, luma - params.threshold) / max(luma, 1e-4);
    }
    color = vec4(sum, 1.0);
}
