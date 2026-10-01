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

// A spruce spray seen flat: a stem, side twigs leaving it forwards in a
// herringbone, needles thick along each twig; the outline tapers to the tip.
// uv: along the branch 0..1, across -1..1.
float needleCover(vec2 p, float seed) {
    float halfWidth = smoothstep(0.0, 0.14, p.x) * (1.0 - 0.82 * p.x * p.x);
    float edge = abs(p.y) / max(halfWidth, 1e-3);
    if (edge > 1.0) return 0.0;
    float clumps = vnoise(vec2(p.x * 55.0, p.y * 22.0) + seed * 31.0);
    float twig = abs(fract(p.x * 12.0 + abs(p.y) * 1.7 + seed * 5.0) - 0.5);
    float cover = smoothstep(0.46, 0.2, twig + 0.28 * clumps) * smoothstep(1.0, 0.75, edge + 0.25 * clumps);
    return max(cover, step(abs(p.y), 0.03 * (1.0 - p.x)));
}

// A beech twig: six leaves round a centre, each a pointed ellipse. uv in -1..1.
float leafCover(vec2 p, float seed) {
    float cover = 0.0;
    for (int i = 0; i < 6; ++i) {
        float a = seed * 6.283 + float(i) * 1.047 + 0.35 * sin(float(i) * 7.1 + seed * 19.0);
        vec2 dir = vec2(cos(a), sin(a));
        vec2 q = p - dir * 0.47;
        vec2 l = vec2(dot(q, dir), dot(q, vec2(-dir.y, dir.x)));
        float width = 0.17 * (1.0 - smoothstep(0.0, 0.42, l.x) * 0.7);
        cover = max(cover, step((l.x * l.x) / 0.16 + (l.y * l.y) / (width * width), 1.0));
    }
    return cover;
}

// Until the shadow map (phase O2): sunlight gets through where the canopy
// opens. Follow the sun up from the point to the crowns' height; inside the
// clearing up there it is open, elsewhere only scattered flecks.
float canopyOpening(vec3 p) {
    vec3 up = p + SUN_DIR * ((terrain(p.xz) + 17.0 - p.y) / SUN_DIR.y);
    float gap = 1.0 - smoothstep(CLEARING_R - 2.5, CLEARING_R + 1.5, length(up.xz - CLEARING));
    float flecks = smoothstep(0.66, 0.82, vnoise(up.xz * 0.8));
    return max(gap, 0.55 * flecks);
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
    float sunlit = canopyOpening(world);
    if (material == M_NEEDLES) {
        if (needleCover(uv, variant) < 0.5) discard;
        albedo = mix(vec3(0.030, 0.050, 0.028), vec3(0.060, 0.085, 0.035), smoothstep(0.55, 1.0, uv.x) + 0.3 * variant);
        translucent = 0.35;
        gloss = 0.3;
        sunlit = max(sunlit, 0.35 * shade);
    } else if (material == M_LEAVES) {
        if (leafCover(uv, variant) < 0.5) discard;
        albedo = mix(vec3(0.06, 0.09, 0.025), vec3(0.10, 0.12, 0.03), variant);
        translucent = 0.6;
        gloss = 0.4;
        sunlit = max(sunlit, 0.45 * shade);
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
