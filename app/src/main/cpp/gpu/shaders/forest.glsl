// The forest: the second cinematic scene. Shared by the renderer (Vulkan)
// and the desktop preview (tools/scene_preview.py, OpenGL).
//
// A temperate rain forest after rain: mossy spruce and fir, a few beech, a
// clearing where a low sun breaks through the mist. Every piece of geometry
// is a pure function of its draw's vertex and instance numbers: trees sit
// on a jittered grid, their branches and needle sprays come from seeded
// hashes, nothing is stored. The phone's GPU has arithmetic to spare and
// little memory bandwidth; this spends the one and not the other.
//
// Conventions as in pool.glsl: metres, y up, linear HDR out; the camera
// maps a point to the image through FOCAL, image height = 1.

#include "camera.glsl"
#include "forest_counts.glsl"

const float PI = 3.14159265359;

// ---- light and air ------------------------------------------------------------------------------

const vec3 SUN_DIR = vec3(-0.5197, 0.3969, -0.7559);  // normalised: low, beyond the clearing
const vec3 SUN = vec3(1.00, 0.86, 0.66) * 3.2;
const vec3 SKY_ZENITH = vec3(0.26, 0.31, 0.35);
const vec3 SKY_HORIZON = vec3(0.47, 0.52, 0.53);
const vec3 FOG_COLOUR = vec3(0.33, 0.39, 0.39);
const float FOG_DENSITY = 0.022;

// ---- layout -------------------------------------------------------------------------------------

const float CELL = 3.4;                // one tree per cell, jittered
const int GRID = FOREST_GRID;          // cells a side: an 88 m square round the origin
const int TREES = GRID * GRID;
const vec2 CLEARING = vec2(3.0, -7.0);
const float CLEARING_R = 6.5;

// ---- random numbers and noise -------------------------------------------------------------------

uint hashu(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

uint hash2u(ivec2 c) {
    return hashu(uint(c.x) * 1597334677u ^ hashu(uint(c.y) + 2654435769u));
}

// Next number of the stream `s`, in [0, 1].
float rnd(inout uint s) {
    s = hashu(s);
    return float(s) * (1.0 / 4294967295.0);
}

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1, 0)), f.x), mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; ++i) {
        v += a * vnoise(p);
        p = p * 2.07 + vec2(3.1, 1.7);
        a *= 0.5;
    }
    return v;
}

// ---- ground -------------------------------------------------------------------------------------

float terrain(vec2 xz) {
    float h = 2.4 * vnoise(xz * 0.03) + 0.9 * vnoise(xz * 0.085 + 7.3) + 0.22 * vnoise(xz * 0.31 + 1.7);
    // The clearing lies in a shallow hollow.
    vec2 d = xz - CLEARING;
    h -= 0.7 * exp(-dot(d, d) / 45.0);
    return h - 1.6;
}

vec3 terrainNormal(vec2 xz) {
    const float e = 0.12;
    float hx = terrain(xz + vec2(e, 0.0)) - terrain(xz - vec2(e, 0.0));
    float hz = terrain(xz + vec2(0.0, e)) - terrain(xz - vec2(0.0, e));
    return normalize(vec3(-hx, 2.0 * e, -hz));
}

// ---- trees --------------------------------------------------------------------------------------

struct Tree {
    vec3 base;
    float height;
    float radius;
    float crown;    // reach of the longest branch, metres
    vec2 lean;      // metres of drift per metre of height
    uint seed;
    bool conifer;
    bool alive;
};

Tree treeAt(int index) {
    ivec2 cell = ivec2(index % GRID, index / GRID) - GRID / 2;
    uint s = hash2u(cell);
    Tree t;
    vec2 xz = (vec2(cell) + 0.2 + 0.6 * vec2(rnd(s), rnd(s))) * CELL;
    t.alive = rnd(s) > 0.16 && length(xz - CLEARING) > CLEARING_R;
    t.conifer = rnd(s) < 0.74;
    t.height = t.conifer ? mix(19.0, 31.0, rnd(s)) : mix(15.0, 22.0, rnd(s));
    t.radius = mix(0.24, 0.46, rnd(s)) * t.height / 25.0;
    t.crown = t.conifer ? mix(2.3, 3.4, rnd(s)) : mix(3.2, 4.6, rnd(s));
    t.lean = (vec2(rnd(s), rnd(s)) - 0.5) * 0.05;
    t.base = vec3(xz.x, terrain(xz) - 0.25, xz.y);
    t.seed = s;
    return t;
}

// The trunk's axis at height fraction f, with a slow, uneven bend.
vec3 trunkAxis(Tree t, float f) {
    float y = f * t.height;
    float a = float(t.seed & 255u), b = float((t.seed >> 8u) & 255u);
    vec2 bend = t.lean * y + 0.18 * f * vec2(sin(y * 0.33 + a), cos(y * 0.27 + b));
    return t.base + vec3(bend.x, y, bend.y);
}

// Radius at height fraction f: flared at the root, tapering to the top.
float trunkRadius(Tree t, float f) {
    return t.radius * (1.0 + 0.55 * exp(-f * 45.0)) * pow(max(1.0 - 0.93 * f, 0.0), 0.85);
}

// ---- card outlines -----------------------------------------------------------------------------

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

// ---- the sun's shadow map ------------------------------------------------------------------------

// The sun and the trees stand still, so the shadow map is drawn once. It is
// an orthographic view along the sunlight over the whole forest: wide in the
// sun's horizontal "right", shorter in its tilted "up" (the ground is seen
// at a slant), deep enough for every tree top.
const vec2 SHADOW_HALF = vec2(47.0, 36.0);    // metres either side of the centre
const float SHADOW_DEPTH = 160.0;             // metres along the light
const vec3 SHADOW_CENTRE = vec3(0.0, 6.0, 0.0);

// Shadow-map coordinates of a world point: xy in -1..1, z (depth) in 0..1.
vec3 sunSpace(vec3 p) {
    vec3 forward = -SUN_DIR;
    vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), forward));
    vec3 up = cross(forward, right);
    vec3 q = p - SHADOW_CENTRE;
    return vec3(dot(q, right) / SHADOW_HALF.x, dot(q, up) / SHADOW_HALF.y, dot(q, forward) / SHADOW_DEPTH + 0.5);
}

// ---- camera -------------------------------------------------------------------------------------

// The shot at a moment (camera.glsl). For now four still views to develop
// against, ten seconds each: into the light across the clearing, away from
// it into the trees, low over the ground, up into the crowns.
int shotAt(float time) {
    return int(mod(floor(time / 10.0), 4.0));
}

void framing(float time, out vec3 from, out vec3 target, out vec2 lens) {
    int view = shotAt(time);
    vec2 stand = CLEARING + vec2(3.0, 5.0);
    from = vec3(stand.x, terrain(stand) + 1.7, stand.y);
    if (view == 0) {
        target = from + vec3(SUN_DIR.x, 0.25, SUN_DIR.z) * 10.0;
    } else if (view == 1) {
        target = from + vec3(-SUN_DIR.x, -0.05, -SUN_DIR.z) * 10.0;
    } else if (view == 2) {
        from.y -= 1.35;
        target = from + vec3(SUN_DIR.x + 0.5, -0.12, SUN_DIR.z) * 10.0;
    } else {
        target = from + vec3(SUN_DIR.x * 0.4, 1.0, SUN_DIR.z * 0.4) * 10.0;
    }
    lens = vec2(length(target - from), 0.004);
}

// ---- sky and air --------------------------------------------------------------------------------

vec3 sky(vec3 rd) {
    float up = max(rd.y, 0.0);
    vec3 c = mix(SKY_HORIZON, SKY_ZENITH, sqrt(up));
    float toward = max(dot(rd, SUN_DIR), 0.0);
    // The sun is behind thin cloud: a wide pale glow and a bright core.
    c += SUN * (0.10 * pow(toward, 6.0) + 0.35 * pow(toward, 90.0));
    return c;
}

// Mist between the camera and a point `dist` away along `rd`: the grey
// light of the sky it holds. The sunlight it scatters, broken into beams by
// the canopy, comes from the light pass (forest_light.frag).
vec3 mist(vec3 colour, float dist, vec3 rd) {
    float f = 1.0 - exp(-dist * FOG_DENSITY);
    return mix(colour, FOG_COLOUR, f);
}

// How strongly the mist scatters sunlight towards a viewer looking along
// `rd`: mostly forwards (Henyey-Greenstein, g = 0.6), a little everywhere.
float sunPhase(vec3 rd) {
    const float g = 0.6;
    float c = dot(rd, SUN_DIR);
    float hg = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * c, 1.5);
    return (0.15 + 0.85 * hg) / (4.0 * PI);
}
