#version 450
// Depth of field, at half the scene's resolution: each pixel gathers the
// history over its own circle of confusion, along a spiral of taps. A tap in
// front of the pixel counts only where its own circle reaches this far, so a
// sharp object keeps its edge instead of smearing into the blurred
// background behind it. final.frag blends this in where the blur is wider
// than a pixel.
#extension GL_GOOGLE_include_directive : require

// SceneRenderer::DofParams
layout(push_constant) uniform Dof {
    vec2 sourceTexel;   // 1 / scene size
    vec2 targetSize;    // this pass, pixels
    float time;         // the lens of the moment
    float sceneHeight;  // scene pixels: the unit of the blur
    float pad0;
    float pad1;
} params;

layout(set = 0, binding = 0) uniform sampler2D history;
layout(set = 0, binding = 1) uniform sampler2D distances;  // R32F: fetched, never filtered

#include "pool.glsl"

layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;

const int TAPS = 28;
const float GOLDEN_ANGLE = 2.39996323;

float distanceAt(vec2 uv) {
    ivec2 size = textureSize(distances, 0);
    return texelFetch(distances, clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1), 0).r;
}

void main() {
    vec2 uv = gl_FragCoord.xy / params.targetSize;
    vec2 lens = lensAt(params.time);
    float depth = distanceAt(uv);
    float radius = blurRadius(lens, depth, params.sceneHeight);
    // The spiral starts next to the centre, which needs no tap of its own: a
    // full one would leave a bright light as a sharp dot inside its blur. The
    // trace of it only keeps the sum defined where no tap counts.
    vec3 sum = texture(history, uv).rgb * 1e-3;
    float weight = 1e-3;
    // The spiral turned by a different angle at every pixel (interleaved
    // gradient noise): a small bright light then blurs into a soft grain,
    // not into a pattern of copies of itself, one a tap.
    float turn = 6.2831853 * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    for (int i = 0; i < TAPS; ++i) {
        float r = sqrt((float(i) + 0.5) / float(TAPS)) * radius;
        float a = float(i) * GOLDEN_ANGLE + turn;
        vec2 at = uv + vec2(cos(a), sin(a)) * r * params.sourceTexel;
        float d = distanceAt(at);
        float w = d < depth ? clamp(blurRadius(lens, d, params.sceneHeight) - r + 1.0, 0.0, 1.0) : 1.0;
        sum += texture(history, at).rgb * w;
        weight += w;
    }
    color = vec4(sum / weight, 1.0);
}
