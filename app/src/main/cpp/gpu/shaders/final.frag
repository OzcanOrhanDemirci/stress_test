#version 450
// The picture on the screen: the accumulated scene scaled up with a
// Catmull-Rom filter (sharper than bilinear), the depth of field blended in
// where the lens blurs, bloom added, then the film finish: ACES tone curve,
// a touch of lens colour fringing, vignette, grain.
#extension GL_GOOGLE_include_directive : require

layout(set = 0, binding = 0) uniform sampler2D scene;
layout(set = 0, binding = 1) uniform sampler2D bloom;
layout(set = 0, binding = 2) uniform sampler2D particles;
layout(set = 0, binding = 3) uniform sampler2D dof;
layout(set = 0, binding = 4) uniform sampler2D distances;  // R32F: fetched, never filtered

layout(push_constant) uniform Final {
    vec2 resolution;   // screen, pixels
    float time;
    float bloomStrength;
} params;

#include "scene.glsl"

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;

// Catmull-Rom in 9 bilinear taps.
vec3 catmullRom(vec2 uv) {
    vec2 size = vec2(textureSize(scene, 0));
    vec2 pos = uv * size;
    vec2 center = floor(pos - 0.5) + 0.5;
    vec2 f = pos - center;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 tc0 = (center - 1.0) / size;
    vec2 tc3 = (center + 2.0) / size;
    vec2 tc12 = (center + w2 / w12) / size;
    vec3 c = vec3(0.0);
    c += texture(scene, vec2(tc0.x, tc0.y)).rgb * w0.x * w0.y;
    c += texture(scene, vec2(tc12.x, tc0.y)).rgb * w12.x * w0.y;
    c += texture(scene, vec2(tc3.x, tc0.y)).rgb * w3.x * w0.y;
    c += texture(scene, vec2(tc0.x, tc12.y)).rgb * w0.x * w12.y;
    c += texture(scene, vec2(tc12.x, tc12.y)).rgb * w12.x * w12.y;
    c += texture(scene, vec2(tc3.x, tc12.y)).rgb * w3.x * w12.y;
    c += texture(scene, vec2(tc0.x, tc3.y)).rgb * w0.x * w3.y;
    c += texture(scene, vec2(tc12.x, tc3.y)).rgb * w12.x * w3.y;
    c += texture(scene, vec2(tc3.x, tc3.y)).rgb * w3.x * w3.y;
    return max(c, vec3(0.0));
}

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    vec2 uv = gl_FragCoord.xy / params.resolution;
    vec3 c = catmullRom(uv);
    // Colour fringing towards the edges, like a real lens.
    vec2 shift = (uv - 0.5) * 0.004;
    c.r = mix(c.r, texture(scene, uv + shift).r, 0.6);
    c.b = mix(c.b, texture(scene, uv - shift).b, 0.6);
    ivec2 size = textureSize(distances, 0);
    float depth = texelFetch(distances, clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1), 0).r;
    float blur = blurRadius(lensAt(params.time), depth, float(size.y));
    c = mix(c, texture(dof, uv).rgb, smoothstep(0.5, 2.0, blur));
    c += texture(particles, uv).rgb;
    c += texture(bloom, uv).rgb * params.bloomStrength;

    c = aces(c * 0.9);
    c *= 0.35 + 0.65 * pow(16.0 * uv.x * uv.y * (1.0 - uv.x) * (1.0 - uv.y), 0.22);
    c += (hash(gl_FragCoord.xy + fract(params.time) * 97.0) - 0.5) * 0.015;
    color = vec4(pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
}
