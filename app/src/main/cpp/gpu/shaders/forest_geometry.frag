#version 450
// The forest's surfaces: wet ground, mossy bark, needle sprays and leaf
// clusters (their outlines drawn here, the cards themselves are plain
// quads), lit by a low sun through the canopy and the grey sky, in mist.
#extension GL_GOOGLE_include_directive : require

#include "forest_params.glsl"
#include "forest.glsl"

layout(location = 0) in vec3 world;
layout(location = 1) in vec3 normalIn;
layout(location = 2) in vec2 uv;
layout(location = 3) flat in uint material;
layout(location = 4) in float shade;
layout(location = 5) flat in float variant;

layout(location = 0) out vec4 colour;
layout(location = 1) out float distanceOut;

layout(set = 0, binding = 0) uniform sampler2DShadow sunDepth;

// Sunlight reaching a surface point: the shadow map read at eight points of a
// small disc (the sun's penumbra a few metres under the canopy), the disc
// turned differently at every pixel and frame so the soft edge builds up as
// frames accumulate. The point is lifted off its surface a little first.
float sunlight(vec3 p, vec3 n) {
    vec3 s = sunSpace(p + n * 0.05);
    if (any(greaterThan(abs(s.xy), vec2(1.0)))) return 1.0;
    uint seed = hashu(uint(gl_FragCoord.x) * 1973u + uint(gl_FragCoord.y) * 9277u + params.frame * 26699u);
    float turn = rnd(seed) * 2.0 * PI;
    const float PENUMBRA = 0.1;  // metres
    vec2 radius = PENUMBRA / SHADOW_HALF * 0.5;
    float lit = 0.0;
    for (int i = 0; i < 8; ++i) {
        float a = turn + float(i) * 2.39996;
        vec2 o = vec2(cos(a), sin(a)) * sqrt((float(i) + 0.5) / 8.0) * radius;
        lit += texture(sunDepth, vec3(s.xy * 0.5 + 0.5 + o, s.z - 0.0003));
    }
    return lit / 8.0;
}

void main() {
    vec3 ro, fw, rt, up;
    camera(params.time, ro, fw, rt, up);
    vec3 toEye = ro - world;
    float dist = length(toEye);
    vec3 v = toEye / dist;
    vec3 n = normalize(normalIn);
    if (dot(n, v) < 0.0) n = -n;  // cards are seen from both sides

    vec3 albedo;
    float translucent = 0.0;
    float gloss = 0.0;  // wet sheen
    float sunlit = sunlight(world, n);
    if (material == M_NEEDLES) {
        if (needleCover(uv, variant) < 0.5) discard;
        albedo = mix(vec3(0.030, 0.050, 0.028), vec3(0.060, 0.085, 0.035), smoothstep(0.55, 1.0, uv.x) + 0.3 * variant);
        translucent = 0.35;
        gloss = 0.3;
    } else if (material == M_LEAVES) {
        if (leafCover(uv, variant) < 0.5) discard;
        albedo = mix(vec3(0.06, 0.09, 0.025), vec3(0.10, 0.12, 0.03), variant);
        translucent = 0.6;
        gloss = 0.4;
    } else if (material == M_BARK) {
        // Grey-brown bark in vertical plates; moss climbs the wetter, north side and the foot.
        float plates = vnoise(vec2(uv.x * 7.0, uv.y * 0.9) + variant * 40.0);
        albedo = mix(vec3(0.045, 0.040, 0.035), vec3(0.11, 0.10, 0.09), smoothstep(0.3, 0.7, plates));
        albedo *= 0.55 + 0.45 * smoothstep(0.15, 0.4, plates);
        float moss = smoothstep(0.35, 0.75, 0.5 + 0.5 * n.z + 0.4 * fbm(uv * vec2(3.0, 0.6)) - uv.y * 0.04);
        albedo = mix(albedo, vec3(0.06, 0.10, 0.025), moss);
        gloss = 0.25;
    } else {
        // Forest floor: dark soil under brown needle litter, moss in patches.
        float litter = fbm(uv * 1.3);
        float moss = smoothstep(0.45, 0.65, fbm(uv * 0.35 + 4.0));
        albedo = mix(vec3(0.035, 0.028, 0.020), vec3(0.090, 0.060, 0.035), litter);
        albedo = mix(albedo, vec3(0.045, 0.075, 0.020), moss);
        gloss = 0.35 * (1.0 - moss);
    }

    vec3 l = SUN_DIR;
    float direct = max(dot(n, l), 0.0);
    // Leaves light up from behind when the sun is beyond them.
    float through = translucent * max(dot(-n, l), 0.0) * (0.4 + 0.6 * max(dot(-v, l), 0.0));
    vec3 sunLight = SUN * sunlit * (direct + through);
    vec3 skyLight = mix(vec3(0.030, 0.038, 0.028), SKY_ZENITH * 1.3, 0.5 + 0.5 * n.y) * shade;
    vec3 h = normalize(l + v);
    float sheen = gloss * pow(max(dot(n, h), 0.0), 60.0) * sunlit * direct;
    vec3 c = albedo * (sunLight + skyLight) + SUN * sheen * 0.5;

    colour = vec4(mist(c, dist, -v), 1.0);
    distanceOut = dist;
}
