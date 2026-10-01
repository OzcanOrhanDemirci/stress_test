#version 450
// Every piece of the forest's geometry, made from nothing but the vertex and
// instance numbers (vertex pulling): params.part says which.
//
//   terrain  instance = row of the ground grid, 6 vertices a quad
//   trunks   instance = tree, a tapered tube of TRUNK_SIDES x TRUNK_RINGS quads
//   cards    instance = tree, CARDS quads: needle sprays on a spruce's whorls
//            of branches, leaf clusters through a beech's crown
#extension GL_GOOGLE_include_directive : require

#include "forest_params.glsl"
#include "forest.glsl"

layout(location = 0) out vec3 world;
layout(location = 1) out vec3 normal;
layout(location = 2) out vec2 uv;          // card or bark coordinates
layout(location = 3) flat out uint material;
layout(location = 4) out float shade;      // light that reaches in here, 0..1
layout(location = 5) flat out float variant;

const float TERRAIN_SIZE = 90.0;  // counts: forest_counts.glsl

// Corner c (0..5) of a quad drawn as two triangles, as (u, v) in {0, 1}.
vec2 quadCorner(int c) {
    return vec2(c == 1 || c == 2 || c == 4 ? 1.0 : 0.0, c == 2 || c == 4 || c == 5 ? 1.0 : 0.0);
}

// Light under the trees: the nearer a trunk, the less sky.
float groundShade(vec2 xz) {
    ivec2 c = ivec2(floor(xz / CELL)) + GRID / 2;
    float nearest = 1e9;
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            ivec2 k = clamp(c + ivec2(x, y), ivec2(0), ivec2(GRID - 1));
            Tree t = treeAt(k.y * GRID + k.x);
            if (t.alive) nearest = min(nearest, length(xz - t.base.xz) - t.radius);
        }
    }
    return 1.0 - 0.5 * exp(-max(nearest, 0.0) / 0.9);
}

void terrainVertex(int row, int v) {
    vec2 q = quadCorner(v % 6);
    vec2 xz = (vec2(float(v / 6) + q.x, float(row) + q.y) / float(TERRAIN_N) - 0.5) * TERRAIN_SIZE;
    world = vec3(xz.x, terrain(xz), xz.y);
    normal = terrainNormal(xz);
    uv = xz;
    material = M_GROUND;
    shade = groundShade(xz);
    variant = 0.0;
}

void trunkVertex(Tree t, int v) {
    int quad = v / 6;
    vec2 q = quadCorner(v % 6);
    float a = (float(quad % TRUNK_SIDES) + q.x) / float(TRUNK_SIDES) * 2.0 * PI;
    // Rings closer together near the root, where the flare curves; a beech's
    // trunk ends where its crown begins.
    float f = pow((float(quad / TRUNK_SIDES) + q.y) / float(TRUNK_RINGS), 1.3) * (t.conifer ? 1.0 : 0.8);
    vec3 n = vec3(cos(a), 0.0, sin(a));
    world = trunkAxis(t, f) + n * trunkRadius(t, f);
    normal = n;
    uv = vec2(a * t.radius, f * t.height);  // metres round and up
    material = t.conifer ? M_BARK : M_SMOOTH_BARK;
    shade = mix(0.5, 1.0, smoothstep(0.0, 0.3, f));
    variant = float(t.seed & 1023u) / 1023.0;
}

// Where a spruce's live crown begins, as a fraction of its height.
float crownStart(Tree t) {
    return 0.24 + 0.12 * float(hashu(t.seed ^ 77u) & 1023u) / 1023.0;
}

void cardVertex(Tree t, int card, vec2 q) {
    float u = q.x, v = q.y * 2.0 - 1.0;
    if (t.conifer && card >= CARDS - DEAD_BRANCHES) {
        // Dead branches on the bare trunk: short, drooping, twigs only.
        uint s = hashu(t.seed + uint(card) * 4241u);
        float f = mix(0.06, crownStart(t), rnd(s));
        float az = rnd(s) * 2.0 * PI;
        float len = mix(0.5, 1.4, rnd(s)) * t.crown / 3.0;
        vec3 dir = normalize(vec3(cos(az), -0.2 - 0.4 * rnd(s), sin(az)));
        vec3 across = normalize(vec3(0.0, 1.0, 0.0) - dir * dir.y);
        world = trunkAxis(t, f) + dir * (u * len) + across * (v * len * 0.3);
        normal = normalize(cross(across, dir));
        uv = vec2(u, v);
        material = M_TWIGS;
        shade = 0.5;
        variant = rnd(s);
        return;
    }
    if (t.conifer) {
        // Whorls of branches from the crown's start to the top. Each branch
        // carries SPRAYS needle cards along it, overlapping: an inner and an
        // outer flat spray, and one rolled about the branch, hanging.
        int branch = card / SPRAYS;
        int spray = card % SPRAYS;
        uint s = hashu(t.seed + uint(branch) * 7919u);
        int whorl = branch / PER_WHORL;
        float start = crownStart(t);
        float f = mix(start, 0.98, (float(whorl) + 0.7 * rnd(s)) / float(WHORLS));
        float rise = (f - start) / (1.0 - start);
        float az = (float(branch % PER_WHORL) + 0.5 * rnd(s)) / float(PER_WHORL) * 2.0 * PI + float(whorl) * 2.399;
        float len = t.crown * pow(1.0 - rise, 0.8) * mix(0.75, 1.15, rnd(s)) + 0.3;
        vec3 dir = normalize(vec3(cos(az), mix(-0.45, 0.35, rise) + 0.2 * (rnd(s) - 0.5), sin(az)));
        vec3 side = normalize(cross(vec3(0.0, 1.0, 0.0), dir));
        vec3 lift = cross(dir, side);
        float from = spray == 1 ? 0.35 : (spray == 2 ? 0.15 : 0.0);
        float to = spray == 0 ? 0.65 : 1.0;
        float roll = spray == 2 ? (rnd(s) < 0.5 ? -0.8 : 0.8) : 0.3 * (rnd(s) - 0.5);
        vec3 across = side * cos(roll) + lift * sin(roll);
        float along = mix(from, to, u);  // fraction of the branch
        float width = len * 0.3 * (1.0 - 0.45 * along);
        world = trunkAxis(t, f) + dir * (along * len) + across * (v * width) - vec3(0.0, 0.2 * along * along * len, 0.0);
        normal = normalize(cross(across, dir));
        uv = vec2(u, v);
        material = M_NEEDLES;
        // Inside the crown little light gets through: darker near the trunk and low down.
        shade = mix(0.3, 1.0, along) * mix(0.5, 1.0, rise);
        variant = rnd(s) * 0.5 + 0.5 * float(spray) / float(SPRAYS);
        return;
    }
    // Beech: leaf clusters scattered through an egg-shaped crown, thicker at its surface.
    uint s = hashu(t.seed + uint(card) * 7919u);
    vec3 d = normalize(vec3(rnd(s) - 0.5, rnd(s) - 0.3, rnd(s) - 0.5) + 1e-3);
    float r = pow(rnd(s), 0.4);
    vec3 centre = trunkAxis(t, 0.72) + d * vec3(t.crown, t.crown * 0.8, t.crown) * r;
    vec3 n = normalize(d + (vec3(rnd(s), rnd(s), rnd(s)) - 0.5) * 0.9);
    vec3 a = normalize(cross(n, abs(n.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 b = cross(n, a);
    float size = mix(0.6, 0.95, rnd(s));
    world = centre + (a * (q.x * 2.0 - 1.0) + b * (q.y * 2.0 - 1.0)) * size * 0.5;
    normal = n;
    uv = q * 2.0 - 1.0;
    material = M_LEAVES;
    shade = mix(0.35, 1.0, r);
    variant = rnd(s);
}

// Clip position of a world point: the scene's camera, FOCAL, image height 1,
// plus this frame's sub-pixel jitter.
vec4 project(vec3 p) {
    const float NEAR = 0.05, FAR = 400.0;
    vec3 ro, fw, rt, up;
    camera(params.time, ro, fw, rt, up);
    vec3 v = p - ro;
    float z = dot(v, fw);
    vec4 clip = vec4(dot(v, rt) * FOCAL * 2.0 * params.resolution.y / params.resolution.x, dot(v, up) * FOCAL * 2.0, 0.0, z);
    // Pixel P shows the image point P + jitter (as the sky and pool scenes do): move the geometry the other way.
    clip.xy -= params.jitter * 2.0 / params.resolution * z;
#ifdef VULKAN
    clip.y = -clip.y;                                       // Vulkan's clip space has y down
    clip.z = FAR * (z - NEAR) / (FAR - NEAR);               // depth 0..1
#else
    clip.z = (FAR + NEAR) / (FAR - NEAR) * z - 2.0 * FAR * NEAR / (FAR - NEAR);  // OpenGL: -1..1
#endif
    return clip;
}

// Clip position in the sun's shadow map (sunSpace()), depth 0..1 in either API.
vec4 projectSun(vec3 p) {
    vec3 s = sunSpace(p);
#ifdef VULKAN
    return vec4(s.xy, s.z, 1.0);
#else
    return vec4(s.xy, s.z * 2.0 - 1.0, 1.0);
#endif
}

void main() {
    int v = gl_VertexIndex;
    int instance = gl_InstanceIndex;
    uint part = params.part % PART_SHADOW;
    bool shadow = params.part >= PART_SHADOW;
    if (part == PART_TERRAIN) {
        terrainVertex(instance, v);
    } else {
        Tree t = treeAt(instance);
        if (!t.alive) {
            // No tree in this cell: every vertex outside the view, the triangles vanish.
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            world = vec3(0.0);
            normal = vec3(0.0, 1.0, 0.0);
            uv = vec2(0.0);
            material = M_BARK;
            shade = 0.0;
            variant = 0.0;
            return;
        }
        if (part == PART_TRUNKS) {
            trunkVertex(t, v);
        } else {
            cardVertex(t, v / 6, quadCorner(v % 6));
        }
    }
    gl_Position = shadow ? projectSun(world) : project(world);
}
