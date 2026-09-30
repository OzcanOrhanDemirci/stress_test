#version 450
// One step up the bloom chain: a 3x3 tent over the smaller level, added
// (blend ONE, ONE) onto the level above.
#extension GL_GOOGLE_include_directive : require

#include "bloom_params.glsl"

layout(set = 0, binding = 0) uniform sampler2D source;

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;

void main() {
    vec2 uv = gl_FragCoord.xy / params.targetSize;
    vec2 t = params.sourceTexel * params.radius;
    vec3 sum = texture(source, uv).rgb * 4.0;
    sum += (texture(source, uv + vec2(-t.x, 0.0)).rgb + texture(source, uv + vec2(t.x, 0.0)).rgb +
            texture(source, uv + vec2(0.0, -t.y)).rgb + texture(source, uv + vec2(0.0, t.y)).rgb) * 2.0;
    sum += texture(source, uv + vec2(-t.x, -t.y)).rgb + texture(source, uv + vec2(t.x, -t.y)).rgb +
           texture(source, uv + vec2(-t.x, t.y)).rgb + texture(source, uv + vec2(t.x, t.y)).rgb;
    color = vec4(sum / 16.0 * params.weight, 1.0);
}
