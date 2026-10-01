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

// Bark relief, a height over (round, up) in metres. A spruce's scaly plates
// split by long furrows; a beech's skin almost smooth.
float barkHeight(vec2 q, bool spruce) {
    if (spruce) {
        float furrow = abs(vnoise(vec2(q.x * 9.0, q.y * 0.7)) * 2.0 - 1.0);
        return 0.6 * smoothstep(0.0, 0.35, furrow) + 0.4 * vnoise(q * vec2(14.0, 6.0));
    }
    return 0.3 * vnoise(q * vec2(6.0, 3.0));
}

// Rain on standing water: in every 0.3 m cell a drop lands now and then at
// a spot of its own and a ring spreads from it, fading. Returns the slope
// the rings give the surface.
vec2 rainRings(vec2 xz, float time) {
    const float CELL = 0.3;
    vec2 slope = vec2(0.0);
    vec2 base = floor(xz / CELL);
    for (int y = 0; y <= 1; ++y) {
        for (int x = 0; x <= 1; ++x) {
            vec2 cell = base + vec2(x, y) - 0.5 + step(0.5, fract(xz / CELL)) - 0.5;
            float h = hash21(cell);
            float age = fract(time * 0.8 + h) / 0.8;
            vec2 d = xz - (cell + 0.2 + 0.6 * vec2(h, fract(h * 13.7))) * CELL;
            float r = length(d) + 1e-4;
            float front = age * 0.32;
            float ring = sin((r - front) * 70.0) * exp(-age * 3.5) * smoothstep(front + 0.05, front, r);
            slope += d / r * ring * 0.25;
        }
    }
    return slope;
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
    float puddle = 0.0;  // standing water: a mirror
    float translucent = 0.0;
    float gloss = 0.0;  // wet sheen
    float sunlit = sunlight(world, n);
    if (material == M_NEEDLES) {
        if (needleCover(uv, variant) < 0.5) discard;
        // Dark blue-green, the year's new growth lighter at the spray's tip.
        albedo = mix(vec3(0.022, 0.042, 0.030), vec3(0.055, 0.080, 0.035), smoothstep(0.6, 1.0, uv.x) * (0.5 + variant));
        translucent = 0.35;
        gloss = 0.3;
    } else if (material == M_FERN) {
        if (fernCover(uv, variant) < 0.5) discard;
        // The brightest green on the floor, lighter towards the tips.
        albedo = mix(vec3(0.045, 0.085, 0.020), vec3(0.085, 0.140, 0.030), uv.x * (0.6 + 0.4 * variant));
        translucent = 0.7;
        gloss = 0.45;
    } else if (material == M_TWIGS) {
        if (twigCover(uv, variant) < 0.5) discard;
        albedo = vec3(0.075, 0.068, 0.060) * (0.8 + 0.4 * variant);
    } else if (material == M_LEAVES) {
        if (leafCover(uv, variant) < 0.5) discard;
        albedo = mix(vec3(0.06, 0.09, 0.025), vec3(0.10, 0.12, 0.03), variant);
        translucent = 0.6;
        gloss = 0.4;
    } else if (material == M_LOG) {
        // A fallen trunk: rotting bark, deep moss over its top.
        float rot = vnoise(uv * vec2(6.0, 1.5) + variant * 20.0);
        albedo = mix(vec3(0.035, 0.027, 0.020), vec3(0.075, 0.058, 0.045), rot);
        float moss = smoothstep(-0.1, 0.5, n.y + 0.4 * (fbm(uv * 2.0) - 0.5));
        albedo = mix(albedo, vec3(0.055, 0.105, 0.022), moss);
        gloss = 0.2;
    } else if (material == M_BARK || material == M_SMOOTH_BARK) {
        // The normal follows the relief: tilted by its slope round and up the trunk.
        bool spruce = material == M_BARK;
        vec2 q = uv + variant * 37.0;
        const float e = 0.01;
        float h = barkHeight(q, spruce);
        vec2 slope = vec2(barkHeight(q + vec2(e, 0.0), spruce) - h, barkHeight(q + vec2(0.0, e), spruce) - h) / e;
        vec3 round = cross(n, vec3(0.0, 1.0, 0.0));
        n = normalize(n - (round * slope.x + vec3(0.0, 1.0, 0.0) * slope.y) * (spruce ? 0.012 : 0.003));
        albedo = spruce ? mix(vec3(0.028, 0.022, 0.018), vec3(0.10, 0.076, 0.060), h)
                        : mix(vec3(0.10, 0.10, 0.095), vec3(0.18, 0.18, 0.17), smoothstep(0.0, 0.3, h));
        // Moss climbs the wetter north side and the foot.
        float moss = smoothstep(0.35, 0.75, 0.5 + 0.5 * n.z + 0.4 * fbm(uv * vec2(3.0, 0.6)) - uv.y * 0.04);
        albedo = mix(albedo, vec3(0.05, 0.09, 0.022), moss);
        gloss = spruce ? 0.15 : 0.3;
    } else {
        // Forest floor: needle litter over dark soil, cushions of moss, and in
        // the hollows rain standing in puddles that mirror the grey sky.
        float moss = smoothstep(0.42, 0.62, fbm(uv * 0.35 + 4.0));
        float grain = vnoise(uv * 9.0) * 0.6 + vnoise(uv * 31.0) * 0.4;
        float litter = fbm(uv * 1.3);
        albedo = mix(vec3(0.030, 0.024, 0.018), vec3(0.085, 0.056, 0.032), litter * (0.6 + 0.6 * grain));
        albedo = mix(albedo, vec3(0.040, 0.075, 0.018) * (0.75 + 0.5 * grain), moss);
        // Relief: litter grain and moss mounds tilt the normal.
        vec2 tilt = vec2(vnoise(uv * 9.0 + 3.1) - 0.5, vnoise(uv * 9.0 + 7.7) - 0.5) * (0.35 + 0.4 * moss);
        n = normalize(n + vec3(tilt.x, 0.0, tilt.y));
        gloss = 0.45 * (1.0 - moss);
        float hollow = smoothstep(0.72, 0.78, vnoise(uv * 0.6 + 11.0)) * (1.0 - moss) * smoothstep(0.85, 0.97, normalIn.y);
        if (hollow > 0.0) {
            n = normalize(mix(n, vec3(0.0, 1.0, 0.0), hollow));
            albedo = mix(albedo, vec3(0.012, 0.012, 0.010), hollow);
            gloss = mix(gloss, 1.0, hollow);
            puddle = hollow;
            vec2 rings = rainRings(uv, params.time);
            n = normalize(n + vec3(rings.x, 0.0, rings.y) * hollow);
        }
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
    if (puddle > 0.0) {
        // The sky in the water, dimmed by the canopy that hangs over most of it.
        float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
        c += sky(reflect(-v, n)) * fresnel * puddle * mix(0.12, 0.6, sunlit);
    }

    colour = vec4(mist(c, dist, -v), 1.0);
    distanceOut = dist;
}
