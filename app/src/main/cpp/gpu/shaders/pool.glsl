// The pool reactor: the cinematic scene. Shared by the renderer (Vulkan) and
// the desktop preview (tools/scene_preview.py, OpenGL): plain GLSL, inputs as
// arguments, output linear HDR colour (tone mapping and bloom come after).
//
// A research reactor seen from its pool deck: the fuel assembly glows
// Cherenkov blue under clear water, the water's surface bends and mirrors
// it, caustics crawl over the tiled pool walls. Above the water a spiked
// containment ring turns (FurMark's furry donut, grown up), control-rod tubes
// hang into the pool, amber lamps cut shafts through the hall's haze.
//
// Built for temporal accumulation: every frame takes a few random samples
// (area-light shadows, haze scattering) with a new seed; the renderer
// accumulates frames, so noise averages out instead of costing more rays.
// Heavy work stays in arithmetic, not memory: the phone's GPU has ~1 TFLOPS
// but ~16 GB/s.

const float PI = 3.14159265359;

// Palette: cold Cherenkov blue against warm sodium amber.
const vec3 CHERENKOV = vec3(0.10, 0.45, 1.00);
const vec3 CORE_HOT = vec3(0.55, 0.85, 1.00);
const vec3 AMBER = vec3(1.00, 0.55, 0.18);
const vec3 HAZARD = vec3(1.00, 0.70, 0.06);

// Layout (metres). The water surface is y = 0.
const float POOL_R = 2.4;
const float DECK_Y = 0.35;
const float POOL_BOTTOM = -4.2;
const vec3 CORE = vec3(0.0, -3.2, 0.0);
const vec3 RING_CENTRE = vec3(0.0, 1.55, 0.0);
const float RING_R = 1.55;

// Materials.
const float M_DECK = 1.0;
const float M_RING = 2.0;
const float M_TUBE = 3.0;
const float M_RAIL = 4.0;
const float M_COLUMN = 5.0;
const float M_WALL = 6.0;
const float M_POOLWALL = 7.0;
const float M_ROD = 8.0;
const float M_GRID = 9.0;

float gTime;
uint gSeed;

// ---- random numbers and noise ------------------------------------------------

uint pcg(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float rand() {
    gSeed = pcg(gSeed);
    return float(gSeed) * (1.0 / 4294967295.0);
}

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1, 0)), f.x), mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), f.x), f.y);
}

float fbm2(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; ++i) {
        v += a * noise2(p);
        p = p * 2.03 + vec2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

mat2 rot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, -s, s, c);
}

// ---- shapes -------------------------------------------------------------------

float sdCylinderY(vec3 p, float r, float h) {
    vec2 d = abs(vec2(length(p.xz), p.y)) - vec2(r, h);
    return min(max(d.x, d.y), 0.0) + length(max(d, 0.0));
}

float sdBox(vec3 p, vec3 b) {
    vec3 q = abs(p) - b;
    return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0);
}

// Heartbeat of the core.
float pulse() {
    return 1.0 + 0.35 * pow(0.5 + 0.5 * sin(gTime * 2.2), 8.0);
}

// The ring's frame: tilted a little, spinning.
vec3 ringSpace(vec3 p) {
    vec3 q = p - RING_CENTRE;
    q.xy *= rot(0.16 * sin(gTime * 0.23));
    q.xz *= rot(gTime * 0.35);
    return q;
}

// ---- the hall above the water ---------------------------------------------------

vec2 mapAir(vec3 p) {
    float r = length(p.xz);

    // Deck: a slab around the pool, its edge a raised curb.
    float deck = max(p.y - DECK_Y, POOL_R - r);
    vec2 res = vec2(deck, M_DECK);

    // Ring with spikes along it.
    vec3 q = ringSpace(p);
    float major = atan(q.z, q.x);
    vec2 tube = vec2(length(q.xz) - RING_R, q.y);
    float minor = atan(tube.y, tube.x);
    float spikes = pow(abs(sin(major * 42.0) * sin(minor * 4.0 + major * 3.0)), 7.0);
    float ring = (length(tube) - 0.10 - 0.11 * spikes) * 0.55;
    if (ring < res.x) res = vec2(ring, M_RING);

    // Railing round the pool: posts and two rails.
    float a = atan(p.z, p.x);
    float sector = 2.0 * PI / 28.0;
    float ang = mod(a + sector * 0.5, sector) - sector * 0.5;
    vec3 post = vec3(r * cos(ang) - (POOL_R + 0.25), p.y - (DECK_Y + 0.5), r * sin(ang));
    float rail = sdCylinderY(post, 0.025, 0.5);
    vec2 railRing = vec2(r - (POOL_R + 0.25), p.y - (DECK_Y + 1.0));
    rail = min(rail, length(railRing) - 0.03);
    rail = min(rail, length(vec2(r - (POOL_R + 0.25), p.y - (DECK_Y + 0.55))) - 0.02);
    if (rail < res.x) res = vec2(rail, M_RAIL);

    // Columns further out.
    float cs = 2.0 * PI / 8.0;
    float ca = mod(a + cs * 0.5, cs) - cs * 0.5;
    vec3 c = vec3(r * cos(ca) - 8.2, p.y - 4.0, r * sin(ca));
    float column = sdBox(c, vec3(0.35, 4.0, 0.35)) - 0.03;
    if (column < res.x) res = vec2(column, M_COLUMN);

    // Hall walls and ceiling.
    float wall = min(11.0 - r, 9.0 - p.y);
    if (wall < res.x) res = vec2(wall, M_WALL);
    return res;
}

// ---- the pool below the water ------------------------------------------------------

vec2 mapWater(vec3 p) {
    float r = length(p.xz);
    vec2 res = vec2(min(POOL_R - r, p.y - POOL_BOTTOM), M_POOLWALL);

    // Fuel assembly: a 6 x 6 lattice of rods standing on a grid plate.
    vec3 q = p - CORE;
    vec2 cell = clamp(floor(q.xz / 0.22 + 0.5), vec2(-2.5), vec2(2.5));
    vec2 local = q.xz - cell * 0.22;
    float rods = length(vec2(length(local), 0.0)) - 0.07;
    rods = max(rods, abs(q.y) - 0.75);
    rods = max(rods, max(abs(q.x), abs(q.z)) - 0.62);
    if (rods < res.x) res = vec2(rods, M_ROD);
    float plate = sdBox(q + vec3(0.0, 0.82, 0.0), vec3(0.75, 0.07, 0.75));
    if (plate < res.x) res = vec2(plate, M_GRID);

    // The tubes carry on under water down to the assembly.
    vec3 t = p;
    t.xz = abs(t.xz);
    float tubes = sdCylinderY(t - vec3(0.55, -1.2, 0.55), 0.06, 1.2);
    if (tubes < res.x) res = vec2(tubes, M_TUBE);
    return res;
}

vec3 normalAir(vec3 p) {
    const vec2 k = vec2(1.0, -1.0);
    const float e = 0.0015;
    return normalize(k.xyy * mapAir(p + k.xyy * e).x + k.yyx * mapAir(p + k.yyx * e).x +
                     k.yxy * mapAir(p + k.yxy * e).x + k.xxx * mapAir(p + k.xxx * e).x);
}

vec3 normalWater(vec3 p) {
    const vec2 k = vec2(1.0, -1.0);
    const float e = 0.0015;
    return normalize(k.xyy * mapWater(p + k.xyy * e).x + k.yyx * mapWater(p + k.yyx * e).x +
                     k.yxy * mapWater(p + k.yxy * e).x + k.xxx * mapWater(p + k.xxx * e).x);
}

// ---- light ---------------------------------------------------------------------------

// Amber lamps high on the hall wall, aimed at the pool.
const int LAMPS = 3;
vec3 lampPosition(int i) {
    float a = float(i) * 2.0 * PI / float(LAMPS) + 2.2;
    return vec3(cos(a) * 5.2, 7.0, sin(a) * 5.2);
}

// Glow of the core seen from a point in the water: what lights the pool.
vec3 coreLight(vec3 p) {
    vec3 d = CORE - p;
    return CHERENKOV * 5.0 * pulse() / (0.3 + dot(d, d));
}

// Shadow ray through the hall towards a point on a lamp: 1 lit, 0 blocked.
float airShadow(vec3 p, vec3 target) {
    vec3 d = target - p;
    float maxT = length(d);
    d /= maxT;
    float s = 1.0;
    float t = 0.05;
    for (int i = 0; i < 24; ++i) {
        float h = mapAir(p + d * t).x;
        s = min(s, 12.0 * h / t);
        t += clamp(h, 0.05, 0.6);
        if (s < 0.02 || t > maxT) break;
    }
    return clamp(s, 0.0, 1.0);
}

// Caustics: bright lace where the waves focus light onto the pool's surfaces.
float caustics(vec2 p) {
    vec2 q = p * 2.6;
    float c = 0.0;
    for (int i = 0; i < 3; ++i) {
        q += vec2(sin(q.y * 1.3 + gTime * 0.9), cos(q.x * 1.1 - gTime * 0.7)) * 0.55;
        c += 1.0 / (1.0 + 30.0 * abs(sin(q.x) * sin(q.y)));
    }
    return c / 3.0;
}

// Water surface normal: a few travelling waves and rings spreading from the tubes.
vec3 waterNormal(vec2 xz) {
    vec2 g = vec2(0.0);
    g += 0.018 * vec2(cos(xz.x * 3.1 + gTime * 1.4), 0.0);
    g += 0.014 * vec2(0.0, cos(xz.y * 2.7 - gTime * 1.1));
    g += 0.010 * cos(dot(xz, vec2(4.3, 3.7)) + gTime * 2.0) * vec2(4.3, 3.7) / 5.7;
    for (int i = 0; i < 4; ++i) {
        vec2 source = vec2((i & 1) == 0 ? 0.55 : -0.55, (i & 2) == 0 ? 0.55 : -0.55);
        vec2 d = xz - source;
        float r = length(d);
        float wave = sin(r * 14.0 - gTime * 4.0 + float(i)) * exp(-r * 1.6);
        g += 0.035 * wave * d / max(r, 1e-3);
    }
    return normalize(vec3(-g.x, 1.0, -g.y));
}

// ---- shading ------------------------------------------------------------------------------

// Schlick's (1 - cos)^5, safe for cosines rounded a hair past 1.
float schlick(float cosine) {
    float m = clamp(1.0 - cosine, 0.0, 1.0);
    float m2 = m * m;
    return m2 * m2 * m;
}

vec3 ggx(vec3 n, vec3 v, vec3 l, float roughness, vec3 f0) {
    vec3 h = normalize(v + l);
    float nl = max(dot(n, l), 0.0);
    float nv = max(dot(n, v), 1e-3);
    float nh = max(dot(n, h), 0.0);
    float a2 = roughness * roughness * roughness * roughness;
    float d = a2 / (PI * pow(nh * nh * (a2 - 1.0) + 1.0, 2.0));
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    float g = nl / (nl * (1.0 - k) + k) * nv / (nv * (1.0 - k) + k);
    vec3 f = f0 + (1.0 - f0) * schlick(dot(h, v));
    return d * g * f / (4.0 * nv) ;
}

float trefoil(vec2 p) {
    float r = length(p);
    float a = atan(p.y, p.x) + PI / 2.0;
    float blade = step(0.5, fract(a / (2.0 * PI / 3.0) + 0.6666));
    return max(blade * step(0.32, r) * step(r, 0.95), step(r, 0.2));
}

// Surface in the hall: materials, amber lamps (area lights sampled one point
// a frame), the blue light rising from the pool, reflections of the lamps.
vec3 shadeAir(vec3 p, vec3 rd, vec3 n, float mat) {
    vec3 albedo = vec3(0.1);
    float roughness = 0.5;
    float metal = 0.0;
    vec3 emission = vec3(0.0);

    if (mat == M_DECK) {
        // Steel grating, a painted trefoil and a hazard band at the pool's edge.
        vec2 g = abs(fract(p.xz * 1.6) - 0.5);
        float seam = step(0.47, max(g.x, g.y));
        float wear = fbm2(p.xz * 1.3);
        float wet = smoothstep(0.45, 0.6, fbm2(p.xz * 0.7 + 3.0));
        albedo = mix(vec3(0.018), vec3(0.05), wear) * (1.0 - 0.6 * seam);
        roughness = mix(0.45, 0.08, wet);
        metal = 0.7;
        float r = length(p.xz);
        float band = step(POOL_R, r) * step(r, POOL_R + 0.18);
        float stripes = step(0.5, fract((atan(p.z, p.x) * 30.0 + r * 4.0) / PI));
        albedo = mix(albedo, mix(vec3(0.015), HAZARD * 0.45, stripes), band);
        vec2 sign = p.xz - vec2(0.0, POOL_R + 1.5);
        float paint = trefoil(sign / 0.9);
        albedo = mix(albedo, HAZARD * 0.5 * (0.75 + 0.25 * wear), paint * (1.0 - band));
        metal = mix(metal, 0.0, max(paint, band));
        roughness = mix(roughness, 0.7, max(paint, band));
    } else if (mat == M_RING) {
        vec3 q = ringSpace(p);
        float tip = smoothstep(0.14, 0.2, length(vec2(length(q.xz) - RING_R, q.y)));
        albedo = vec3(0.62, 0.64, 0.68);
        roughness = 0.22;
        metal = 1.0;
        emission = CHERENKOV * 3.5 * tip * pulse();
    } else if (mat == M_TUBE) {
        albedo = vec3(0.55, 0.56, 0.58);
        roughness = 0.3;
        metal = 1.0;
    } else if (mat == M_RAIL) {
        albedo = HAZARD * 0.6;
        roughness = 0.45;
    } else if (mat == M_COLUMN) {
        albedo = vec3(0.06, 0.065, 0.07);
        roughness = 0.5;
        metal = 0.4;
        float strip = smoothstep(0.03, 0.0, abs(fract(p.y * 0.25) - 0.5) - 0.02);
        emission = AMBER * 0.6 * strip;
    } else if (mat == M_WALL) {
        albedo = vec3(0.025, 0.027, 0.03);
        roughness = 0.8;
        float panel = step(0.03, fract(p.y * 0.5)) * step(0.02, fract(atan(p.z, p.x) * 12.0 / PI));
        albedo *= 0.6 + 0.4 * panel;
    }

    vec3 v = -rd;
    vec3 f0 = mix(vec3(0.04), albedo, metal);
    vec3 diffuse = albedo * (1.0 - metal);
    vec3 color = emission;

    for (int i = 0; i < LAMPS; ++i) {
        // A random point on the lamp's disc: soft shadows once frames accumulate.
        vec3 lamp = lampPosition(i);
        float ra = rand() * 2.0 * PI, rr = sqrt(rand()) * 0.5;
        vec3 target = lamp + vec3(cos(ra) * rr, 0.0, sin(ra) * rr);
        vec3 toLamp = target - p;
        float dist2 = dot(toLamp, toLamp);
        vec3 l = toLamp * inversesqrt(dist2);
        float nl = max(dot(n, l), 0.0);
        if (nl <= 0.0) continue;
        // Spot cone towards the pool.
        float cone = smoothstep(0.6, 0.9, dot(-l, normalize(vec3(0.0, 0.3, 0.0) - lamp)));
        vec3 radiance = AMBER * 110.0 * cone / dist2;
        float shadow = airShadow(p + n * 0.02, target);
        color += (diffuse / PI + ggx(n, v, l, roughness, f0)) * radiance * nl * shadow;
    }

    // Blue light rising from the pool: strongest right above the water.
    vec3 fromPool = vec3(0.0, 0.2, 0.0) - p;
    float poolDist2 = dot(fromPool, fromPool);
    vec3 lp = fromPool * inversesqrt(poolDist2);
    float up = max(dot(n, lp), 0.0);
    vec3 poolGlow = CHERENKOV * 4.0 * pulse() / (1.0 + poolDist2);
    color += (diffuse / PI + ggx(n, v, lp, roughness, f0)) * poolGlow * up;

    color += diffuse * vec3(0.004, 0.006, 0.01);  // faint ambient
    return color;
}

// Surface under the water: tiles and the fuel assembly, lit by the core and caustics.
vec3 shadeWater(vec3 p, vec3 rd, vec3 n, float mat) {
    if (mat == M_ROD) {
        // Dark cladding lit by the glow around it; only the tops burn hot.
        float tip = smoothstep(0.66, 0.75, p.y - CORE.y);
        float flicker = 0.9 + 0.1 * sin(gTime * 11.0 + p.x * 40.0 + p.z * 23.0);
        vec3 lit = vec3(0.12, 0.13, 0.15) * CHERENKOV * 6.0 * (0.4 + 0.6 * max(n.y, 0.0));
        return lit + CORE_HOT * 6.0 * tip * pulse() * flicker;
    }
    vec3 albedo = vec3(0.22, 0.26, 0.3);
    float roughness = 0.3;
    if (mat == M_POOLWALL) {
        // White tiles with dark grout.
        vec2 uv = abs(n.y) > 0.5 ? p.xz * 3.0 : vec2(atan(p.z, p.x) * POOL_R, p.y) * 3.0;
        vec2 g = abs(fract(uv) - 0.5);
        albedo *= 1.0 - 0.7 * step(0.45, max(g.x, g.y));
    } else if (mat == M_GRID) {
        albedo = vec3(0.3);
        roughness = 0.5;
    } else if (mat == M_TUBE) {
        albedo = vec3(0.5);
        roughness = 0.25;
    }
    vec3 light = coreLight(p);
    vec3 toCore = normalize(CORE - p);
    float nl = max(dot(n, toCore), 0.0) * 0.8 + 0.2;
    // Sunlight-like caustics from the lamps above, fading with depth.
    float c = caustics(p.xz + p.y * 0.3) * exp(p.y * 0.35);
    vec3 causticLight = mix(AMBER, vec3(1.0), 0.5) * c * 0.35;
    return albedo * (light * nl + causticLight) / PI + ggx(n, -rd, toCore, roughness, vec3(0.04)) * light;
}

// Water between two points: red absorbed first, the Cherenkov glow scattered in.
vec3 waterVolume(vec3 ro, vec3 rd, float t, out vec3 transmittance) {
    const vec3 sigma = vec3(0.7, 0.2, 0.1);
    transmittance = exp(-sigma * t);
    // Glow gathered along the path: a few random stations, averaged over frames.
    vec3 glow = vec3(0.0);
    const int STATIONS = 4;
    for (int i = 0; i < STATIONS; ++i) {
        float s = (float(i) + rand()) / float(STATIONS) * t;
        vec3 d = CORE - (ro + rd * s);
        float d2 = dot(d, d);
        glow += exp(-sigma * s) * (1.0 / (0.08 + d2 * d2) + 0.02 / (0.5 + d2));
    }
    return CHERENKOV * 0.9 * pulse() * glow * t / float(STATIONS);
}

// ---- tracing ---------------------------------------------------------------------------------

// March the hall; returns material (negative: nothing hit) and the distance.
vec2 traceAir(vec3 ro, vec3 rd, int steps, float maxT) {
    float t = 0.0;
    for (int i = 0; i < steps; ++i) {
        vec2 h = mapAir(ro + rd * t);
        if (h.x < 0.0006 * t + 0.0004) return vec2(h.y, t);
        t += h.x;
        if (t > maxT) break;
    }
    return vec2(-1.0, t);
}

vec2 traceWater(vec3 ro, vec3 rd, int steps) {
    float t = 0.0;
    for (int i = 0; i < steps; ++i) {
        vec2 h = mapWater(ro + rd * t);
        if (h.x < 0.0008 * t + 0.0005) return vec2(h.y, t);
        t += h.x;
        if (t > 12.0) break;
    }
    return vec2(M_POOLWALL, t);
}

// Distance along the ray to the water surface inside the pool, or -1.
float hitWater(vec3 ro, vec3 rd) {
    if (rd.y >= 0.0) return -1.0;
    float t = -ro.y / rd.y;
    vec3 p = ro + rd * t;
    return t > 0.0 && length(p.xz) < POOL_R ? t : -1.0;
}

// Haze in the hall: one random station a frame lit by one random lamp.
vec3 hallHaze(vec3 ro, vec3 rd, float t) {
    float s = rand() * min(t, 16.0);
    vec3 p = ro + rd * s;
    int i = min(int(rand() * float(LAMPS)), LAMPS - 1);
    vec3 lamp = lampPosition(i);
    vec3 toLamp = lamp - p;
    float dist2 = dot(toLamp, toLamp);
    vec3 l = toLamp * inversesqrt(dist2);
    float cone = smoothstep(0.6, 0.9, dot(-l, normalize(vec3(0.0, 0.3, 0.0) - lamp)));
    float density = 0.011 * (0.4 + 1.2 * fbm2(p.xz * 0.6 + vec2(gTime * 0.05, p.y * 0.3)));
    float phase = 0.25 + 0.75 * pow(max(dot(rd, l), 0.0), 6.0);
    vec3 scatter = AMBER * 110.0 * float(LAMPS) * cone / dist2 * airShadow(p, lamp) * phase * density;
    // The pool's blue glow in the air just above it.
    vec3 fromPool = vec3(0.0, 0.3, 0.0) - p;
    scatter += CHERENKOV * 0.9 * pulse() * density * 6.0 / (0.6 + dot(fromPool, fromPool));
    return scatter * min(t, 16.0);
}

vec3 radiance(vec3 ro, vec3 rd, out float depth) {
    vec2 air = traceAir(ro, rd, 110, 30.0);
    float tw = hitWater(ro, rd);
    bool water = tw > 0.0 && (air.x < 0.0 || tw < air.y);
    depth = water ? tw : air.y;
    vec3 color = hallHaze(ro, rd, depth);

    if (water) {
        vec3 p = ro + rd * tw;
        vec3 n = waterNormal(p.xz);
        float fresnel = 0.02 + 0.98 * schlick(dot(-rd, n));

        // Below: refract, march the pool, fog it with water.
        vec3 rt = refract(rd, n, 1.0 / 1.33);
        vec2 under = traceWater(p, rt, 72);
        vec3 q = p + rt * under.y;
        vec3 transmittance;
        vec3 inscatter = waterVolume(p, rt, under.y, transmittance);
        vec3 below = shadeWater(q, rt, normalWater(q), under.x) * transmittance + inscatter;

        // Above: the hall mirrored.
        vec3 rr = reflect(rd, n);
        vec2 up = traceAir(p + n * 0.01, rr, 60, 30.0);
        vec3 above = up.x < 0.0 ? vec3(0.0) : shadeAir(p + rr * up.y, rr, normalAir(p + rr * up.y), up.x);

        color += mix(below, above, fresnel);
    } else if (air.x >= 0.0) {
        vec3 p = ro + rd * air.y;
        vec3 n = normalAir(p);
        color += shadeAir(p, rd, n, air.x);
        // Polished metal: one mirror bounce.
        if (air.x == M_RING || air.x == M_TUBE || air.x == M_DECK) {
            float gloss = air.x == M_DECK ? 0.12 : 0.04;
            vec3 wobble = normalize(vec3(rand(), rand(), rand()) - 0.5) * gloss;
            vec3 rr = normalize(reflect(rd, n) + wobble);
            if (dot(rr, n) < 0.0) rr = reflect(rr, n);
            float weight = air.x == M_DECK ? 0.3 : 0.55;
            float tw2 = hitWater(p + n * 0.01, rr);
            vec2 bounce = traceAir(p + n * 0.01, rr, 48, 20.0);
            vec3 seen;
            if (tw2 > 0.0 && (bounce.x < 0.0 || tw2 < bounce.y)) {
                seen = CHERENKOV * 1.2 * pulse();  // the glowing pool
            } else if (bounce.x >= 0.0) {
                vec3 p2 = p + n * 0.01 + rr * bounce.y;
                seen = shadeAir(p2, rr, normalAir(p2), bounce.x);
            } else {
                seen = vec3(0.0);
            }
            float fres = weight + (1.0 - weight) * schlick(dot(-rd, n));
            color += seen * fres * weight;
        }
    }
    return color;
}

// ---- camera ------------------------------------------------------------------------------------

// A slow drift round the pool, looking down into it.
void camera(float time, out vec3 position, out vec3 forward, out vec3 right, out vec3 up) {
    float a = 0.35 * sin(time * 0.09) + 0.4;
    float dist = 6.0 + 0.4 * sin(time * 0.13);
    position = vec3(sin(a) * dist, 5.6 + 0.4 * sin(time * 0.17), cos(a) * dist);
    vec3 target = vec3(0.0, -1.0, 0.0);
    forward = normalize(target - position);
    right = normalize(cross(vec3(0.0, 1.0, 0.0), forward));
    up = cross(forward, right);
}

const float FOCAL = 1.3;

// Linear HDR colour of one pixel. `jitter` moves the sample inside the pixel
// and `frame` reseeds the random samples; `depth` is the distance along the ray.
vec3 renderPool(vec2 fragCoord, vec2 resolution, float time, uint frame, vec2 jitter, out float depth) {
    gTime = time;
    gSeed = pcg(uint(fragCoord.x) * 1973u + uint(fragCoord.y) * 9277u + frame * 26699u);
    vec2 uv = (fragCoord + jitter - 0.5 * resolution) / resolution.y;
    vec3 ro, fw, rt, up;
    camera(time, ro, fw, rt, up);
    vec3 rd = normalize(uv.x * rt + uv.y * up + FOCAL * fw);
    return radiance(ro, rd, depth);
}
