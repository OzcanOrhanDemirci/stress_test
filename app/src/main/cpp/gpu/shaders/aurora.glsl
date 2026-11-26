// The northern lights: the fifth cinematic scene. Shared by the renderer
// (Vulkan) and the desktop preview (tools/scene_preview.py, OpenGL): plain
// GLSL, inputs as arguments, output linear HDR colour (tone mapping and bloom
// come after).
//
// A polar night in a fjord: snowy mountains either side, the water between
// them mirror-still, black ice and drifted snow near the shores, and over it
// all green and violet curtains of aurora rippling across the stars. The sky
// is the hero; the mountains and the water frame it and give it back.
//
// The curtains are not a volume sampled at points: each is a thin sheet,
// z = curve(x), and the ray's path through it is integrated in closed form.
// Over one step the ray's distance to the sheet is a straight line, and a
// Gaussian sheet's light along a straight line is a difference of two erf().
// A curtain crossed face-on gives its thickness' worth of light, one seen
// edge-on along a fold the length the ray stays in it: the bright folds of a
// real aurora come out of that, and no step can skip a thin curtain or band
// it. The steps only follow the curve's bends and the curtain's height. The
// striations and the travelling waves along the arc are evaluated where the
// ray meets the sheet, and their fine octaves fade by the pixel's footprint,
// as the mountains' do, so nothing shimmers as the camera drifts.
//
// The mountains are a ridged heightfield marched per pixel, lit by a low moon
// behind the camera (with its soft shadow) and by the aurora's green from
// above; the mist lies low over the water. The water reflects the whole scene
// once; the ice reflects it a little rougher and shows its cracks. Nothing is
// stored and nothing is sampled: every pixel is arithmetic, the compute-heavy
// memory-light rule again.
//
// Units: metres, y up, the water at y = 0, the fjord running north along +z.

// A wide landscape lens: the sky needs height in a portrait frame.
#define CAMERA_FOCAL 0.62
#include "camera.glsl"

// The quality levers, each its own constant id (scene_renderer.cpp pushes all
// four): the samples a pixel takes (0), the curtains' integration steps (1),
// the mountains' shading octaves (2; the march uses three fewer) and the
// terrain march's step budget (3; the moon's shadow takes a sixth of it).
// The water's reflection runs on lighter levers: half the curtain steps, one
// octave less in the march and two in the shading, two thirds of the march.
#ifdef VULKAN
layout(constant_id = 0) const int AURORA_SAMPLES = 1;
layout(constant_id = 1) const int AURORA_STEPS = 16;
layout(constant_id = 2) const int AURORA_DETAIL = 6;
layout(constant_id = 3) const int AURORA_MARCH = 96;
#else
// The preview forces these from --quality.
#ifdef AURORA_SAMPLES_FORCED
const int AURORA_SAMPLES = AURORA_SAMPLES_FORCED;
#else
const int AURORA_SAMPLES = 1;
#endif
#ifdef AURORA_STEPS_FORCED
const int AURORA_STEPS = AURORA_STEPS_FORCED;
#else
const int AURORA_STEPS = 16;
#endif
#ifdef AURORA_DETAIL_FORCED
const int AURORA_DETAIL = AURORA_DETAIL_FORCED;
#else
const int AURORA_DETAIL = 6;
#endif
#ifdef AURORA_MARCH_FORCED
const int AURORA_MARCH = AURORA_MARCH_FORCED;
#else
const int AURORA_MARCH = 96;
#endif
#endif

const float PI = 3.14159265359;

// The scripted sequence: three calm shots, then again. Shot boundaries are
// cuts: the TAA pass sees the shot number change and drops its history.
const float SHOT_SECONDS = 20.0;
const int SHOTS = 3;

// ---- light and colour ---------------------------------------------------------------------------

const vec3 MOON = vec3(-0.50, 0.30, -0.81);       // low, behind the camera's left shoulder
const vec3 MOON_LIGHT = vec3(0.36, 0.42, 0.56);
const vec3 AURORA_DIR = vec3(0.05, 0.80, 0.60);   // where the curtains' light comes from on average
const vec3 SKY_AMBIENT = vec3(0.010, 0.014, 0.024);
const vec3 GREEN = vec3(0.10, 1.00, 0.40);
const vec3 VIOLET = vec3(0.58, 0.16, 1.00);
const vec3 PINK = vec3(1.00, 0.22, 0.48);
const vec3 WATER = vec3(0.0015, 0.003, 0.006);
const float FAR = 60000.0;                        // the sky's distance for the TAA's reprojection

float gTime;

// ---- random numbers and noise -------------------------------------------------------------------

// An integer hash for the stars' cells: a float fract-hash of large cell
// numbers loses its low bits and bands (the black hole learnt it on its stars).
uint pcg(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

// The noise lattices are hashed in floats (Hoskins' hash without sine): the
// Adreno multiplies 32-bit integers at a fraction of its float rate, and the
// land asks for millions of lattice values a frame. Their coordinates stay
// within a few thousand cells, where the float hash keeps its precision.
float lattice(vec2 c) {
    vec3 p = fract(vec3(c.xyx) * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(lattice(i), lattice(i + vec2(1.0, 0.0)), u.x),
               mix(lattice(i + vec2(0.0, 1.0)), lattice(i + vec2(1.0, 1.0)), u.x), u.y);
}

float noise1(float x, int salt) {
    float i = floor(x);
    float f = x - i;
    float u = f * f * (3.0 - 2.0 * f);
    vec2 c = vec2(i, float(salt) * 17.0);
    return mix(lattice(c), lattice(c + vec2(1.0, 0.0)), u);
}

// ---- the land -----------------------------------------------------------------------------------

const float FLOOR = -30.0;   // the fjord's bed
const float PEAKS = 1350.0;  // nothing rises above this: the march's ceiling
const mat2 OCTAVE = mat2(1.62, 1.18, -1.18, 1.62);  // twice the frequency, turned

float fjordCentre(float z) {
    return 150.0 * sin(z * 0.00105) + 60.0 * sin(z * 0.0031 + 1.3);
}

float fjordHalfWidth(float z) {
    return mix(250.0, 560.0, smoothstep(0.0, 5000.0, z));
}

// Height of the land at p (x, z). The fjord's walls rise from its shores and
// a range closes it far to the north; a ridged multifractal carves both.
// Each octave fades out once its cells are smaller than `footprint` (the
// pixel's size where it is looked at), so far ridges do not shimmer.
// Across the fjord from p to its nearest shore, in metres: negative over the
// water's channel, positive up the land.
float shoreAt(vec2 p) {
    return abs(p.x - fjordCentre(p.y)) - fjordHalfWidth(p.y) + 90.0 * (noise(p / 320.0) - 0.5);
}

const float HEAD = 5600.0;  // where the range that closes the fjord begins to rise

// How far the land rises at p: 0 in the fjord's channel, towards 1 up its walls.
float riseAt(vec2 p, out float wall) {
    float shore = shoreAt(p);
    wall = 1.0 - exp(-max(shore, 0.0) / 430.0);
    return max(wall, smoothstep(HEAD, HEAD + 2200.0, p.y));
}

float terrain(vec2 p, int octaves, float footprint) {
    float wall;
    float rise = riseAt(p, wall);
    if (rise <= 0.0) return FLOOR;
    vec2 q = p / 1100.0 + vec2(3.1, 7.7);
    float sum = 0.0;
    float amp = 0.55;
    float weight = 1.0;
    float cell = 1100.0;
    for (int i = 0; i < octaves; ++i) {
        float n = 1.0 - abs(2.0 * noise(q) - 1.0);
        n *= n * weight;
        weight = clamp(n * 1.8, 0.0, 1.0);
        sum += n * amp * smoothstep(1.0, 3.0, cell / max(footprint, 1e-3));
        q = OCTAVE * q;
        amp *= 0.5;
        cell *= 0.5;
    }
    return FLOOR + rise * (200.0 + 1000.0 * sum) + wall * 260.0 * sum * sum;
}

vec3 terrainNormal(vec2 p, float footprint, int octaves) {
    float e = max(1.5, footprint);
    float h = terrain(p, octaves, footprint);
    float hx = terrain(p + vec2(e, 0.0), octaves, footprint);
    float hz = terrain(p + vec2(0.0, e), octaves, footprint);
    return normalize(vec3(h - hx, e, h - hz));
}

// The first point along the ray where it meets the land, before `limit`,
// in at most `budget` steps through `octaves` of the land.
bool marchTerrain(vec3 ro, vec3 rd, float limit, float pixel, int octaves, int budget, out float t) {
    if (rd.y > 0.0) limit = min(limit, (PEAKS - ro.y) / rd.y);
    t = 1.0;
    float previous = t;
    float h = 0.0;
    for (int i = 0; i < budget && t < limit; ++i) {
        vec3 p = ro + rd * t;
        // Over the channel the land is the flat bed: the next thing to meet is
        // a shore or the range ahead, so the ray goes straight to it. Rays
        // skimming the water would otherwise crawl in steps of a few metres.
        float across = -shoreAt(p.xz) - 50.0;
        if (across > 0.0 && p.z < HEAD && p.y > FLOOR + 5.0) {
            float ahead = rd.z > 1e-3 ? (HEAD - p.z) / rd.z : 1e9;
            float skip = min(0.7 * across / max(length(rd.xz), 1e-3), ahead);
            if (skip > 0.003 * t) {
                previous = t;
                t += skip;
                h = 1e3;
                continue;
            }
        }
        h = p.y - terrain(p.xz, octaves, t * pixel);
        if (h < 0.0015 * t) {
            // Halve the last step a few times for a clean silhouette.
            float a = previous;
            float b = t;
            for (int j = 0; j < 4; ++j) {
                float m = 0.5 * (a + b);
                vec3 q = ro + rd * m;
                if (q.y - terrain(q.xz, octaves, m * pixel) < 0.0015 * m) b = m; else a = m;
            }
            t = b;
            return true;
        }
        previous = t;
        t += max(0.45 * h, 0.003 * t);
    }
    // Out of steps right at a slope: call it land. Further off, the sky: a
    // looser rule leaves slivers of land floating over the ridges.
    return t < limit && h < 0.006 * t;
}

// The moon's soft shadow from the land, in `budget` steps.
float moonShadow(vec3 p, float footprint, int budget) {
    float light = 1.0;
    float t = 6.0;
    for (int i = 0; i < budget; ++i) {
        vec3 q = p + MOON * t;
        if (q.y > PEAKS) break;
        float h = q.y - terrain(q.xz, AURORA_DETAIL - 4, footprint + t * 0.003);
        light = min(light, 12.0 * h / t);
        if (light < 0.0) break;
        t += clamp(0.6 * h, 12.0, 350.0);
    }
    return smoothstep(0.0, 1.0, light);
}

// The aurora's light on the land: green from above, breathing with the curtains.
vec3 auroraLight() {
    return vec3(0.022, 0.080, 0.042) * (0.85 + 0.15 * sin(gTime * 0.11));
}

// `mirror`: seen in the water, a little softer and for less work.
vec3 shadeLand(vec3 p, float t, float pixel, bool mirror) {
    float footprint = t * pixel;
    vec3 n = terrainNormal(p.xz, footprint, mirror ? AURORA_DETAIL - 2 : AURORA_DETAIL);
    // Snow holds wherever the slope lets it, more of it higher up; the steep
    // faces stay dark rock.
    float lie = n.y + 0.22 * (noise(p.xz / 55.0) - 0.5) + 0.10 * (noise(p.xz / 13.0) - 0.5) + p.y * 0.00010;
    float snow = smoothstep(0.50, 0.62, lie);
    vec3 albedo = mix(vec3(0.030, 0.031, 0.036), vec3(0.78, 0.83, 0.92), snow);
    float shadow = moonShadow(p + n * 2.0, footprint, mirror ? AURORA_MARCH / 12 : AURORA_MARCH / 6);
    vec3 light = MOON_LIGHT * max(dot(n, MOON), 0.0) * shadow
               + auroraLight() * (0.55 + 0.45 * dot(n, normalize(AURORA_DIR)))
               + SKY_AMBIENT * (0.6 + 0.4 * n.y);
    // The shore's foot sits in its own dark, under the mist.
    light *= mix(0.3, 1.0, smoothstep(0.0, 90.0, p.y));
    return albedo * light;
}

// ---- the sky ------------------------------------------------------------------------------------

// Stars from a hashed grid of the direction itself, as the black hole's:
// shells of cells, a point in some, its colour and size its own; behind them
// the faint band of the galaxy. Thinner and dimmer near the horizon.
// Only the eight cells nearest the direction are looked at: a star sits in
// the middle of its cell, so its light never reaches past the next one.
vec3 starfield(vec3 rd, int shells) {
    vec3 sky = vec3(0.0);
    for (int shell = 0; shell < shells; ++shell) {
        float scale = shell == 0 ? 20.0 : 48.0;
        float glow = shell == 0 ? 1.1 : 0.42;
        vec3 cell = floor(rd * scale);
        vec3 side = step(0.5, rd * scale - cell) * 2.0 - 1.0;
        for (int x = 0; x <= 1; ++x) {
            for (int y = 0; y <= 1; ++y) {
                for (int z = 0; z <= 1; ++z) {
                    vec3 c = cell + side * vec3(float(x), float(y), float(z));
                    uvec3 q = uvec3(c + 512.0);
                    uint h = pcg(q.x * 73856093u ^ q.y * 19349663u ^ q.z * 83492791u ^ uint(shell * 53 + 7));
                    float pick = float(h) * (1.0 / 4294967295.0);
                    if (pick < (shell == 0 ? 0.88 : 0.84)) continue;
                    vec3 spread = vec3(float(pcg(h + 11u)), float(pcg(h + 23u)), float(pcg(h + 37u))) * (1.0 / 4294967295.0);
                    vec3 at = (c + 0.15 + 0.7 * spread) / scale;
                    float d = length(rd - normalize(at));
                    // Soft enough not to snap between pixels with the jitter.
                    float shine = pow(max(0.0, 1.0 - d * scale * mix(3.6, 2.4, fract(pick * 7.7))), 9.0);
                    sky += mix(vec3(0.72, 0.80, 1.00), vec3(1.0, 0.86, 0.70), fract(pick * 91.3)) * shine * glow;
                }
            }
        }
    }
    vec3 axis = normalize(vec3(0.62, 0.45, -0.64));
    float band = exp(-6.0 * abs(dot(rd, axis)));
    vec3 sideA = normalize(cross(axis, vec3(0.0, 1.0, 0.0)));
    vec3 sideB = cross(axis, sideA);
    vec2 b = vec2(dot(rd, sideA), dot(rd, sideB));
    float haze = 0.6 * noise(b * 6.0) + 0.4 * noise(b * 13.0 + 7.0);
    sky += vec3(0.028, 0.032, 0.048) * band * haze;
    return sky * smoothstep(-0.02, 0.22, rd.y);
}

vec3 nightSky(vec3 rd, bool mirror) {
    float up = max(rd.y, 0.0);
    vec3 c = mix(vec3(0.011, 0.018, 0.036), vec3(0.0022, 0.0035, 0.010), pow(up, 0.45));
    // Airglow and the aurora's spill low over the northern horizon.
    c += vec3(0.004, 0.016, 0.009) * exp(-up * 7.0) * (0.6 + 0.4 * smoothstep(-0.5, 0.8, rd.z));
    return c + starfield(rd, mirror ? 1 : 2);
}

// ---- the curtains -------------------------------------------------------------------------------

// Four curtains, near to far. Each runs east-west at its own distance north,
// folds along a sum of slow sines, and has its own thickness, base height,
// brightness and share of violet.
const int CURTAINS = 4;
const float C_NORTH[4] = float[](1500.0, 5200.0, 10500.0, 17500.0);
const float C_FOLD[4] = float[](600.0, 1000.0, 1500.0, 2200.0);
const float C_SPAN[4] = float[](1.0, 1.4, 2.0, 2.8);
const float C_THICK[4] = float[](90.0, 130.0, 190.0, 260.0);
const float C_BASE[4] = float[](2300.0, 2400.0, 2550.0, 2700.0);
const float C_BRIGHT[4] = float[](1.0, 0.9, 0.75, 0.6);
const float C_VIOLET[4] = float[](0.15, 0.55, 0.10, 0.45);
const float C_QUIET[4] = float[](0.85, 0.35, 0.8, 0.6);  // how much of the arc may lie dark

// Where curtain k stands at x (its z) and the slope dz/dx there.
void curtainAt(int k, float x, out float z, out float slope) {
    float kf = float(k);
    float s = 1.0 / (1000.0 * C_SPAN[k]);
    float a = x * s * 1.0 + kf * 1.7 + gTime * 0.045;
    float b = x * s * 2.3 + kf * 0.9 - gTime * 0.07;
    float c = x * s * 5.1 + kf * 2.3 + gTime * 0.11;
    float e = x * s * 11.3 + kf * 4.1 - gTime * 0.17;
    // The last two terms bend faster than the curtain runs: tight folds, where
    // the sheet turns edge-on and the ray runs along it.
    z = C_NORTH[k] + C_FOLD[k] * (0.5 * sin(a) + 0.3 * sin(b) + 0.22 * sin(c) + 0.09 * sin(e));
    slope = C_FOLD[k] * s * (0.5 * cos(a) + 0.69 * cos(b) + 1.122 * cos(c) + 1.017 * cos(e));
}

// The vertical rays of a curtain: a 1-D fractal along it, its fine octaves
// faded where they would be finer than the pixel.
float striations(float s, float footprint, int k) {
    float sum = 0.0;
    float total = 0.0;
    float period = 150.0;
    float amp = 1.0;
    for (int i = 0; i < 3; ++i) {
        float fade = smoothstep(1.5, 4.0, period / max(footprint, 1e-3));
        sum += amp * mix(0.5, noise1(s / period, k * 7 + i), fade);
        total += amp;
        period *= 0.42;
        amp *= 0.6;
    }
    float v = sum / total;
    return 0.25 + 1.5 * v * v;
}

// Light emitted per metre of curtain k at q.
vec3 emission(int k, vec3 q, float footprint) {
    float kf = float(k);
    float span = 1000.0 * C_SPAN[k];
    float h = q.y - (C_BASE[k] + 280.0 * sin(q.x / (1.7 * span) + kf * 2.1));
    float lower = smoothstep(-45.0, 25.0, h);
    float body = exp(-max(h, 0.0) / 1400.0);
    float rim = exp(-max(h, 0.0) / 190.0);
    float rays = striations(q.x + 0.05 * h - gTime * (14.0 + 5.0 * kf), footprint, k);
    // Brightness travelling along the arc, and each curtain's slow breathing.
    float wave = 0.5 + 0.5 * sin(q.x / (0.9 * span) - gTime * 0.32 + kf * 2.0);
    wave = 0.35 + 0.65 * wave * wave;
    float breath = 0.75 + 0.25 * sin(gTime * 0.09 + kf * 1.9);
    // Whole stretches of each arc lie quiet: the sky between stays dark.
    float awake = mix(1.0, smoothstep(0.22, 0.62, noise1(q.x / (2600.0 * C_SPAN[k]) + gTime * 0.012 + kf * 3.0, 40 + k)),
                      C_QUIET[k]);
    float upper = clamp(smoothstep(800.0, 3600.0, h) + C_VIOLET[k] * smoothstep(200.0, 1800.0, h), 0.0, 1.0);
    vec3 colour = mix(GREEN, VIOLET, upper);
    colour = mix(colour, PINK, 0.45 * lower * exp(-max(h + 40.0, 0.0) / 70.0));
    // Violet glows fainter per metre than the green it sits above; it gets a
    // little back so the upper curtain stays a colour, not a fade.
    float intensity = lower * (body * (1.0 + 0.6 * upper) + 2.2 * rim) * rays * wave * breath * awake * C_BRIGHT[k];
    return colour * intensity;
}

// tanh(1.2 x) is erf(x) to about 1%.
float erfApprox(float x) {
    return tanh(1.2025 * x);
}

// The curtains' light along the ray from ro (above the water, upwards), and
// the light-weighted distance at which it was met.
vec3 aurora(vec3 ro, vec3 rd, float pixel, int steps, out float depth) {
    depth = FAR;
    if (rd.y < 0.02) return vec3(0.0);
    float t0 = max((1700.0 - ro.y) / rd.y, 0.0);
    float t1 = min((9500.0 - ro.y) / rd.y, 42000.0);
    // Only the band of the sky where the curtains stand gets steps.
    if (abs(rd.z) > 1e-4) {
        float za = (C_NORTH[0] - 1.2 * C_FOLD[0] - ro.z) / rd.z;
        float zb = (C_NORTH[CURTAINS - 1] + 1.2 * C_FOLD[CURTAINS - 1] - ro.z) / rd.z;
        t0 = max(t0, min(za, zb));
        t1 = min(t1, max(za, zb));
    }
    if (t0 >= t1) return vec3(0.0);
    float L = (t1 - t0) / float(steps);
    vec3 light = vec3(0.0);
    float weight = 0.0;
    float weighted = 0.0;
    for (int i = 0; i < steps; ++i) {
        float mid = t0 + (float(i) + 0.5) * L;
        vec3 p = ro + rd * mid;
        for (int k = 0; k < CURTAINS; ++k) {
            float z, slope;
            curtainAt(k, p.x, z, slope);
            float norm = inversesqrt(1.0 + slope * slope);
            float d = (p.z - z) * norm;               // distance to the sheet
            float a = (rd.z - slope * rd.x) * norm;   // its change per metre along the ray
            float w = max(C_THICK[k], 1.2 * mid * pixel);
            if (abs(d) - abs(a) * 0.5 * L > 3.0 * w) continue;  // too far from this step
            float through;
            if (abs(a) * L > 0.01 * w) {
                through = 0.886227 * w / abs(a) * abs(erfApprox((d + 0.5 * a * L) / w) - erfApprox((d - 0.5 * a * L) / w));
            } else {
                through = L * exp(-d * d / (w * w));
            }
            // A curtain widened to the pixel keeps its light.
            through *= C_THICK[k] / w;
            through = min(through, 14.0 * C_THICK[k]);
            float u = abs(a) > 1e-4 ? clamp(-d / a, -0.5 * L, 0.5 * L) : 0.0;
            vec3 q = p + rd * u;
            vec3 c = emission(k, q, (mid + u) * pixel) * through;
            light += c;
            float lum = dot(c, vec3(0.3, 0.6, 0.1));
            weight += lum;
            weighted += lum * (mid + u);
        }
    }
    if (weight > 1e-5) depth = mix(FAR, weighted / weight, smoothstep(0.0, 0.02, weight * 0.0085));
    return light * 0.0085;
}

// ---- the air ------------------------------------------------------------------------------------

// The night's thin haze, and a mist lying over the water, both lit faintly by
// the aurora; integrated in closed form along the ray.
vec3 haze(vec3 colour, vec3 ro, vec3 rd, float t) {
    const float H = 70.0;
    const float K = 0.0011;
    float base = K * H * exp(-ro.y / H);
    float mist = abs(rd.y) > 1e-3 ? base * (1.0 - exp(-t * rd.y / H)) / (rd.y) : K * exp(-ro.y / H) * t;
    float amount = clamp(1.0 - exp(-t * 0.00007 - max(mist, 0.0)), 0.0, 1.0);
    vec3 air = vec3(0.010, 0.016, 0.028) + auroraLight() * 0.08;
    return mix(colour, air, amount);
}

// ---- the scene ----------------------------------------------------------------------------------

// The land and the sky along a ray that does not meet the water; `mirror`
// when it is the water's reflection, drawn with a lighter hand.
vec3 above(vec3 ro, vec3 rd, float limit, float pixel, bool mirror, out float dist) {
    float t;
    int octaves = mirror ? AURORA_DETAIL - 4 : AURORA_DETAIL - 3;
    int budget = mirror ? AURORA_MARCH * 2 / 3 : AURORA_MARCH;
    if (marchTerrain(ro, rd, limit, pixel, octaves, budget, t)) {
        dist = t;
        return haze(shadeLand(ro + rd * t, t, pixel, mirror), ro, rd, t);
    }
    float ad;
    vec3 c = nightSky(rd, mirror) + aurora(ro, rd, pixel, mirror ? (AURORA_STEPS + 1) / 2 : AURORA_STEPS, ad);
    dist = ad;
    return haze(c, ro, rd, 30000.0);
}

// Ice on the water: floes near the shores and the camera, thinning out into
// open water up the fjord. 0 water, 1 ice; the edge as soft as the pixel.
float iceAt(vec2 p, float footprint) {
    float across = abs(p.x - fjordCentre(p.y)) / fjordHalfWidth(p.y);
    float field = 0.62 * noise(p / 75.0) + 0.38 * noise(p / 21.0 + 7.1);
    field += 0.45 * smoothstep(0.55, 1.0, across) - 0.55 * smoothstep(900.0, 2600.0, p.y);
    float edge = 0.01 + footprint * 0.004;
    return smoothstep(0.62 - edge, 0.62 + edge, field);
}

vec3 onWater(vec3 ro, vec3 rd, float tw, float pixel, out float dist) {
    vec3 p = ro + rd * tw;
    float footprint = tw * pixel / max(-rd.y, 0.05);
    float ice = iceAt(p.xz, footprint);
    // The ice reflects a little rougher: a fixed, gentle tilt, not a jitter.
    vec2 tilt = ice * 0.012 * vec2(noise(p.xz / 6.0) - 0.5, noise(p.xz / 6.0 + 4.3) - 0.5);
    vec3 r = normalize(vec3(rd.x + tilt.x, -rd.y, rd.z + tilt.y));
    float rdist;
    vec3 mirrored = above(p + vec3(0.0, 0.05, 0.0), r, 12000.0, pixel, true, rdist);
    dist = tw + rdist;  // a plane mirror's image lies along the ray, that far
    float cosine = clamp(-rd.y, 0.0, 1.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - cosine, 5.0);
    vec3 open = mix(WATER, mirrored, fresnel);
    // Black ice: darker glass, white cracks of trapped air, drifts of snow.
    float iceFresnel = 0.035 + 0.6 * pow(1.0 - cosine, 5.0);
    float crackDetail = smoothstep(4.0, 1.0, footprint);
    float crack = (1.0 - smoothstep(0.0, 0.02 + 0.02 * footprint, abs(noise(p.xz / 1.6) - 0.5))) * crackDetail
                * smoothstep(0.45, 0.7, noise(p.xz / 11.0 + 2.0));
    float driftEdge = 0.02 + 0.01 * footprint;
    float drift = smoothstep(0.68 - driftEdge, 0.68 + driftEdge,
                             0.62 * noise(p.xz / 9.0) + 0.28 * noise(p.xz / 2.7 + 3.0) + 0.10 * noise(p.xz / 0.9));
    vec3 lit = MOON_LIGHT * MOON.y + auroraLight() + SKY_AMBIENT;
    vec3 iceColour = mix(vec3(0.010, 0.016, 0.022), vec3(0.45, 0.52, 0.60), 0.18 * crack) * lit;
    iceColour = mix(iceColour, mirrored, iceFresnel * (1.0 - 0.3 * crack));
    iceColour = mix(iceColour, vec3(0.62, 0.68, 0.76) * lit, drift);
    vec3 c = mix(open, iceColour, ice);
    return haze(c, ro, rd, tw);
}

vec3 shade(vec3 ro, vec3 rd, float pixel, out float dist) {
    float tw = rd.y < 0.0 ? -ro.y / rd.y : 1e9;
    float t;
    if (rd.y < 0.0 && !marchTerrain(ro, rd, tw, pixel, AURORA_DETAIL - 3, AURORA_MARCH, t)) {
        return onWater(ro, rd, tw, pixel, dist);
    }
    return above(ro, rd, 1e9, pixel, false, dist);
}

// ---- camera -------------------------------------------------------------------------------------

int shotAt(float time) {
    return int(mod(max(time, 0.0), SHOT_SECONDS * float(SHOTS)) / SHOT_SECONDS);
}

// Three calm shots that let the sky move: up the fjord from low over the
// water, the curtains overhead, and the ice by the eastern shore.
void framing(float time, out vec3 from, out vec3 target, out vec2 lens) {
    gTime = time;
    float t = mod(max(time, 0.0), SHOT_SECONDS);
    float u = t / SHOT_SECONDS;
    float ease = u * u * (3.0 - 2.0 * u);
    int shot = shotAt(time);
    lens = vec2(1000.0, 0.0);  // everything sharp: no depth of field
    float yaw;
    float pitch;
    if (shot == 0) {
        float z = mix(-420.0, -330.0, u);
        from = vec3(fjordCentre(z) + 30.0, 3.0, z);
        yaw = mix(0.04, -0.03, ease);
        pitch = 0.27;
    } else if (shot == 1) {
        float z = 350.0;
        from = vec3(fjordCentre(z) - 70.0, 2.0, z);
        yaw = mix(-0.32, -0.10, ease);
        pitch = mix(0.86, 0.80, ease);
    } else {
        float z = mix(150.0, 190.0, u);
        from = vec3(fjordCentre(z) + 175.0, 1.4, z);
        yaw = mix(-0.30, -0.22, ease);
        pitch = 0.25;
    }
    vec3 heading = vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch));
    target = from + heading * 1000.0;
}

// Linear HDR colour of one pixel, as renderPool() (pool.glsl).
vec3 renderAurora(vec2 fragCoord, vec2 resolution, float time, uint frame, vec2 jitter, out float depth) {
    gTime = time;
    vec2 uv = (fragCoord + jitter - 0.5 * resolution) / resolution.y;
    vec3 ro, fw, rt, up;
    camera(time, ro, fw, rt, up);
    vec3 rd = normalize(uv.x * rt + uv.y * up + FOCAL * fw);
    float pixel = 1.0 / (resolution.y * FOCAL);
    return shade(ro, rd, pixel, depth);
}
