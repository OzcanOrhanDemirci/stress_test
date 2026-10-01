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

// The scripted sequence: shots of SHOT_SECONDS each, over and over, as in a
// benchmark demo. Shot boundaries are cuts: the TAA pass sees the shot
// number change and drops its history.
const float SHOT_SECONDS = 14.0;
const int SHOTS = 4;

// A TRIGA pulse, once a cycle, half way through the descent onto the core:
// the reactor's power jumps a thousandfold for milliseconds, the pool
// flashes blue and the glow dies away over a second or so.
const float PULSE_AT = 3.0 * SHOT_SECONDS + 7.0;

// The flash's brightness on top of the steady glow, 0 outside it.
float flash(float time) {
    float s = mod(time, SHOT_SECONDS * float(SHOTS)) - PULSE_AT;
    return s < 0.0 ? 0.0 : 7.0 * smoothstep(0.0, 0.06, s) * exp(-s / 0.45);
}

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
const float M_PIPE = 10.0;

float gTime;
uint gSeed;
// Values that change only with time, set once a pixel by beginFrame().
float gPulse;
mat2 gTilt;
mat2 gSpin;

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

// Cone with rounded ends, in its own plane: q = (distance from the axis,
// height along it); radius r1 at height 0, r2 at height h.
float sdRoundCone(vec2 q, float r1, float r2, float h) {
    float b = (r1 - r2) / h;
    float a = sqrt(1.0 - b * b);
    float k = dot(q, vec2(-b, a));
    if (k < 0.0) return length(q) - r1;
    if (k > a * h) return length(q - vec2(0.0, h)) - r2;
    return dot(q, vec2(a, b)) - r1;
}

// Union with a fillet of about `k` where the shapes meet.
float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

void beginFrame(float time) {
    gTime = time;
    gPulse = 1.0 + 0.35 * pow(0.5 + 0.5 * sin(time * 2.2), 8.0) + flash(time);
    gTilt = rot(0.16 * sin(time * 0.23));
    gSpin = rot(time * 0.35);
}

// Heartbeat of the core.
float pulse() {
    return gPulse;
}

// The ring's frame: tilted a little, spinning.
vec3 ringSpace(vec3 p) {
    vec3 q = p - RING_CENTRE;
    q.xy *= gTilt;
    q.xz *= gSpin;
    return q;
}

// ---- the hall above the water ---------------------------------------------------

// The ring's spikes: cones standing out of the tube, four round it in each
// of 48 slices along the ring, every other slice turned by 45 degrees.
const float SPIKE_SLICES = 48.0;
const float SPIKE_FROM = 0.07;  // cone base, from the tube's axis (inside the tube)
const float SPIKE_TO = 0.27;    // tip
const float TUBE_R = 0.10;

// The nearest spike of slice `k` to the ring-space point `q`, seen at tube angle `minor`.
float spikeIn(vec3 q, float minor, float k) {
    float major = k * (2.0 * PI / SPIKE_SLICES);
    float offset = mod(k, 2.0) * 0.25 * PI;
    float around = floor((minor - offset) / (0.5 * PI) + 0.5) * (0.5 * PI) + offset;
    vec3 radial = vec3(cos(major), 0.0, sin(major));
    vec3 axis = cos(around) * radial + vec3(0.0, sin(around), 0.0);
    vec3 v = q - RING_R * radial;
    float along = dot(v, axis);
    float across = sqrt(max(dot(v, v) - along * along, 0.0));
    return sdRoundCone(vec2(across, along - SPIKE_FROM), 0.042, 0.004, SPIKE_TO - SPIKE_FROM);
}

// A point's position within one of `count` equal sectors round the y axis,
// the sector's centre line along +x.
vec2 sectorLocal(vec3 p, float r, float angle, float count) {
    float size = 2.0 * PI / count;
    float a = mod(angle + size * 0.5, size) - size * 0.5;
    return r * vec2(cos(a), sin(a));
}

// Every detailed shape sits inside a simple ring-shaped region round the y
// axis. Far from that region its distance stands in for the shape's: a
// true lower bound, so the march stays safe, and it skips the shape's
// trigonometry for most of the steps a ray takes.
const float DETAIL = 0.06;
const float RAIL_R = POOL_R + 0.25;

// `spiky` false: the ring as a bare tube, a little thinner than the real
// one, for shadow rays. The lamps are big and far, so a spike's shadow
// blurs away to nothing; the tube's shadow stays, and the thinner tube
// never shadows the ring's own surface.
vec2 mapHall(vec3 p, bool spiky) {
    float r = length(p.xz);

    // Deck: a slab around the pool, its edge a raised curb.
    float deck = max(p.y - DECK_Y, POOL_R - r);
    vec2 res = vec2(deck, M_DECK);

    // Ring with spikes along it; every spike stays within SPIKE_TO of the tube's axis.
    vec3 q = ringSpace(p);
    vec2 tube = vec2(length(q.xz) - RING_R, q.y);
    float ring = spiky ? length(tube) - SPIKE_TO : length(tube) - (TUBE_R - 0.01);
    if (spiky && ring < DETAIL) {
        // Own slice and the neighbour on the near side: the neighbour's
        // spikes are turned, so they can be the nearer ones.
        float slice = atan(q.z, q.x) / (2.0 * PI / SPIKE_SLICES);
        float k = floor(slice + 0.5);
        float minor = atan(tube.y, tube.x);
        float spikes = min(spikeIn(q, minor, k), spikeIn(q, minor, k + (slice > k ? 1.0 : -1.0)));
        ring = smin(length(tube) - TUBE_R, spikes, 0.025);
    }
    if (ring < res.x) res = vec2(ring, M_RING);

    // Railing round the pool: two rails, and posts every 1/28 of the way round.
    float rail = length(vec2(r - RAIL_R, p.y - (DECK_Y + 1.0))) - 0.03;
    rail = min(rail, length(vec2(r - RAIL_R, p.y - (DECK_Y + 0.55))) - 0.02);
    float posts = length(max(vec2(abs(r - RAIL_R) - 0.025, abs(p.y - (DECK_Y + 0.5)) - 0.5), 0.0));
    // Columns further out, eight of them; pipes up the wall, thirty-six.
    float columns = max(7.82 - r, r - 8.59);
    float pipes = 10.57 - r;
    if (min(posts, min(columns, pipes)) < DETAIL) {
        float angle = atan(p.z, p.x);
        if (posts < DETAIL) {
            vec2 s = sectorLocal(p, r, angle, 28.0);
            posts = sdCylinderY(vec3(s.x - RAIL_R, p.y - (DECK_Y + 0.5), s.y), 0.025, 0.5);
        }
        if (columns < DETAIL) {
            vec2 s = sectorLocal(p, r, angle, 8.0);
            columns = sdBox(vec3(s.x - 8.2, p.y - 4.0, s.y), vec3(0.35, 4.0, 0.35)) - 0.03;
        }
        if (pipes < DETAIL) {
            vec2 s = sectorLocal(p, r, angle, 36.0);
            pipes = length(vec2(s.x - 10.7, s.y)) - 0.13;
        }
    }
    rail = min(rail, posts);
    if (rail < res.x) res = vec2(rail, M_RAIL);
    if (columns < res.x) res = vec2(columns, M_COLUMN);

    // Hall walls and ceiling.
    float wall = min(11.0 - r, 9.0 - p.y);
    if (wall < res.x) res = vec2(wall, M_WALL);
    if (pipes < res.x) res = vec2(pipes, M_PIPE);
    return res;
}

vec2 mapAir(vec3 p) {
    return mapHall(p, true);
}

// ---- the pool below the water ------------------------------------------------------

vec2 mapWater(vec3 p) {
    float r = length(p.xz);
    vec2 res = vec2(min(POOL_R - r, p.y - POOL_BOTTOM), M_POOLWALL);

    // Fuel assembly: a 6 x 6 lattice of rods standing on a grid plate. Rod
    // centres sit at odd multiples of half the pitch; outside the lattice
    // the nearest edge rod still gives a true distance.
    vec3 q = p - CORE;
    vec2 cell = clamp(floor(q.xz / 0.22), vec2(-3.0), vec2(2.0)) + 0.5;
    vec2 local = q.xz - cell * 0.22;
    float rods = max(length(local) - 0.07, abs(q.y) - 0.75);
    rods = min(rods, sdCylinderY(vec3(local.x, q.y - 0.78, local.y), 0.022, 0.03));  // lifting pin
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

// Cherenkov light is made in the water among the rods, where the fission
// is: the assembly's box glows evenly, a halo spreads round it. Brightness
// per metre of path through the water.
const vec3 CORE_HALF = vec3(0.66, 0.75, 0.66);
const float CORE_GLOW = 8.0;

float coreBoxDistance(vec3 p) {
    vec3 d = max(abs(p - CORE) - CORE_HALF, 0.0);
    return length(d);
}

float haloDensity(vec3 p) {
    float d = coreBoxDistance(p) / 0.2;
    return CORE_GLOW * 0.25 / (1.0 + d * d * d);
}

// The flux is highest in the middle of the core and falls towards its edges.
float coreProfile(vec3 p) {
    vec3 q = p - CORE;
    float across = 0.5 + 0.5 * cos(PI * min(length(q.xz) / 0.93, 1.0));
    float along = cos(0.5 * PI * clamp(q.y / CORE_HALF.y, -1.0, 1.0));
    return (0.25 + 0.75 * across) * (0.55 + 0.45 * along);
}

// The glow of the ray's stretch [0, t] inside the assembly's box: the exact
// integral of exp(-sigma s) over it, scaled by the flux at its middle.
vec3 coreSegment(vec3 ro, vec3 rd, float t, vec3 sigma) {
    vec3 q = ro - CORE;
    vec3 inv = 1.0 / (abs(rd) + 1e-6) * sign(rd + 1e-9);
    vec3 a = (-CORE_HALF - q) * inv;
    vec3 b = (CORE_HALF - q) * inv;
    vec3 lo = min(a, b), hi = max(a, b);
    float enter = max(max(lo.x, lo.y), max(lo.z, 0.0));
    float leave = min(min(hi.x, hi.y), min(hi.z, t));
    if (leave <= enter) return vec3(0.0);
    float profile = coreProfile(ro + rd * (0.5 * (enter + leave)));
    return profile * (exp(-sigma * enter) - exp(-sigma * leave)) / sigma;
}

// Shadow ray through the hall towards a point on a lamp: 1 lit, 0 blocked.
// A plain hit test: the point on the lamp moves every frame, so penumbrae
// come from accumulating frames, and the march may take whole free
// distances as steps.
float airShadow(vec3 p, vec3 target) {
    vec3 d = target - p;
    float maxT = length(d);
    d /= maxT;
    float t = 0.03;
    for (int i = 0; i < 24; ++i) {
        float h = mapHall(p + d * t, false).x;
        if (h < 0.002) return 0.0;
        t += h;
        if (t > maxT) return 1.0;
    }
    return 1.0;
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

// The simulated surface (water_step.comp, water_surface.comp): slope,
// curvature and height at a point. The renderer binds it as POOL_WATER;
// shaders that include this file without it see still water.
vec4 waterSurface(vec2 xz) {
#ifdef POOL_WATER
    return textureLod(POOL_WATER, xz / (2.0 * POOL_R) + 0.5, 0.0);
#else
    return vec4(0.0);
#endif
}

// Water surface normal: a slow swell of travelling waves, and the simulated
// ripples on top.
vec3 waterNormal(vec2 xz) {
    vec2 g = vec2(0.0);
    g += 0.018 * vec2(cos(xz.x * 3.1 + gTime * 1.4), 0.0);
    g += 0.014 * vec2(0.0, cos(xz.y * 2.7 - gTime * 1.1));
    g += 0.010 * cos(dot(xz, vec2(4.3, 3.7)) + gTime * 2.0) * vec2(4.3, 3.7) / 5.7;
    g += waterSurface(xz).xy;
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
// `primary`: seen straight from the camera, not in a reflection.
vec3 shadeAir(vec3 p, vec3 rd, vec3 n, float mat, bool primary) {
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
        float tip = smoothstep(0.17, SPIKE_TO, length(vec2(length(q.xz) - RING_R, q.y)));
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
        float angle = atan(p.z, p.x);
        float panel = step(0.03, fract(p.y * 0.5)) * step(0.02, fract(angle * 12.0 / PI));
        albedo *= 0.6 + 0.4 * panel;
        // Indicator lamps in rows at eye height: green, amber, the odd red, some blinking.
        vec2 grid = vec2(angle * 11.0 * 6.0, p.y * 6.0);
        vec2 cell = floor(grid);
        vec2 f = fract(grid) - 0.5;
        float band = step(1.2, p.y) * step(p.y, 2.4);
        float h = hash21(cell);
        float on = step(0.35, h) * (h > 0.9 ? step(0.5, fract(gTime * (0.5 + h) + h * 7.0)) : 1.0);
        vec3 hue = h > 0.97 ? vec3(1.0, 0.1, 0.05) : (h > 0.7 ? AMBER : vec3(0.2, 1.0, 0.35));
        emission += hue * 2.5 * band * on * smoothstep(0.22, 0.12, length(f));
    } else if (mat == M_PIPE) {
        albedo = vec3(0.12, 0.11, 0.1);
        roughness = 0.35;
        metal = 0.9;
    }

    vec3 v = -rd;
    vec3 f0 = mix(vec3(0.04), albedo, metal);
    vec3 diffuse = albedo * (1.0 - metal);
    vec3 color = emission;

    // Seen directly, a surface takes all the lamps; seen in a reflection, one
    // lamp picked at random and weighted up to match: the same light on
    // average, a third of the shadow rays.
    int first = primary ? 0 : min(int(rand() * float(LAMPS)), LAMPS - 1);
    int last = primary ? LAMPS : first + 1;
    float share = primary ? 1.0 : float(LAMPS);
    for (int i = first; i < last; ++i) {
        // A random point on the lamp's disc: soft shadows once frames accumulate.
        vec3 lamp = lampPosition(i);
        float ra = rand() * 2.0 * PI, rr = sqrt(rand()) * 0.5;
        vec3 target = lamp + vec3(cos(ra) * rr, 0.0, sin(ra) * rr);
        vec3 toLamp = target - p;
        float dist2 = dot(toLamp, toLamp);
        vec3 l = toLamp * inversesqrt(dist2);
        float nl = max(dot(n, l), 0.0);
        // Spot cone towards the pool; outside it, no shadow ray is needed.
        float cone = smoothstep(0.6, 0.9, dot(-l, normalize(vec3(0.0, 0.3, 0.0) - lamp)));
        if (nl * cone <= 0.0) continue;
        vec3 radiance = AMBER * 110.0 * share * cone / dist2;
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
        // Steel cladding bathed in the glow between the rods; the end
        // fittings on top face away from it and stay dark, so from above the
        // core reads as dark discs in blue light.
        float inside = 1.0 / (1.0 + pow(coreBoxDistance(p) / 0.22, 2.0));
        float facing = n.y > 0.5 ? 0.12 : 1.0;
        vec3 glow = CHERENKOV * CORE_GLOW * 0.3 * inside * facing * pulse();
        float spacer = 1.0 - 0.5 * step(0.92, fract((p.y - CORE.y) * 2.5));  // thin dark rings
        return vec3(0.35, 0.37, 0.4) * spacer * glow / PI + ggx(n, -rd, vec3(0.0, 1.0, 0.0), 0.35, vec3(0.5)) * glow * 0.05;
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
    // Sunlight-like caustics from the lamps above, fading with depth. Where
    // the simulated surface bulges like a lens it gathers the light below
    // it: brighter under a crest, darker under a trough.
    float focus = clamp(1.0 + 0.012 * p.y * waterSurface(p.xz).z, 0.25, 3.0);
    float c = caustics(p.xz + p.y * 0.3) * exp(p.y * 0.35) * focus;
    vec3 causticLight = mix(AMBER, vec3(1.0), 0.5) * c * 0.35;
    return albedo * (light * nl + causticLight) / PI + ggx(n, -rd, toCore, roughness, vec3(0.04)) * light;
}

// Water between two points: red absorbed first, the Cherenkov glow gathered.
vec3 waterVolume(vec3 ro, vec3 rd, float t, out vec3 transmittance) {
    const vec3 sigma = vec3(0.7, 0.2, 0.1);
    transmittance = exp(-sigma * t);
    // Inside the assembly the glow is even: integrated exactly, so the light
    // between the rods comes out clean.
    vec3 glow = CORE_GLOW * coreSegment(ro, rd, t, sigma);
    // The halo and the pool's faint glow fall off round the core: a few
    // stations placed equi-angularly as seen from the core (dense where the
    // ray passes it), averaged over frames.
    float along = dot(CORE - ro, rd);
    // (Any positive `miss` gives a valid sampling; a floor keeps rays straight
    // through the core from spreading their stations too thin.)
    float miss = max(length(ro + rd * along - CORE), 0.05);
    float from = atan(-along, miss);
    float to = atan(t - along, miss);
    const int STATIONS = 3;
    vec3 halo = vec3(0.0);
    for (int i = 0; i < STATIONS; ++i) {
        float angle = mix(from, to, (float(i) + rand()) / float(STATIONS));
        float s = along + miss * tan(angle);
        vec3 x = ro + rd * s;
        vec3 d = CORE - x;
        float density = haloDensity(x) + 0.12 / (0.5 + dot(d, d));
        // Divided by the pdf of s: miss / ((to - from) * |x - core|^2).
        halo += exp(-sigma * s) * density * (miss * miss + (s - along) * (s - along)) / miss;
    }
    glow += halo * (to - from) / float(STATIONS);
    return CHERENKOV * pulse() * glow;
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
    float span = min(t, 10.0);
    float s = rand() * span;
    vec3 p = ro + rd * s;
    int i = min(int(rand() * float(LAMPS)), LAMPS - 1);
    vec3 lamp = lampPosition(i);
    vec3 toLamp = lamp - p;
    float dist2 = dot(toLamp, toLamp);
    vec3 l = toLamp * inversesqrt(dist2);
    float cone = smoothstep(0.6, 0.9, dot(-l, normalize(vec3(0.0, 0.3, 0.0) - lamp)));
    // Haze settles: thick over the deck, thinning towards the roof.
    float settle = exp(-max(p.y - DECK_Y, 0.0) / 1.6);
    float density = 0.011 * settle * (0.4 + 1.2 * fbm2(p.xz * 0.6 + vec2(gTime * 0.05, p.y * 0.3)));
    float phase = 0.25 + 0.75 * pow(max(dot(rd, l), 0.0), 6.0);
    vec3 scatter = AMBER * 110.0 * float(LAMPS) * cone / dist2 * airShadow(p, lamp) * phase * density;
    // The pool's blue glow in the air just above it.
    vec3 fromPool = vec3(0.0, 0.3, 0.0) - p;
    scatter += CHERENKOV * 0.9 * pulse() * density * 6.0 / (0.6 + dot(fromPool, fromPool));
    return scatter * span;
}

// Steam over the warm pool: a thin layer just above the water, curling in
// wisps, lit from below by the glow. One random station a frame within the
// part of the ray that crosses the layer.
vec3 poolMist(vec3 ro, vec3 rd, float t) {
    const float TOP = 0.9;
    float enter = 0.0, leave = t;
    if (abs(rd.y) > 1e-4) {
        float a = -ro.y / rd.y, b = (TOP - ro.y) / rd.y;
        enter = max(min(a, b), 0.0);
        leave = min(max(a, b), t);
    } else if (ro.y < 0.0 || ro.y > TOP) {
        return vec3(0.0);
    }
    if (leave <= enter) return vec3(0.0);
    vec3 p = ro + rd * mix(enter, leave, rand());
    float r = length(p.xz);
    float over = 1.0 - smoothstep(POOL_R - 0.5, POOL_R + 0.5, r);
    float wisps = smoothstep(0.55, 0.8, fbm2(p.xz * 1.6 + vec2(gTime * 0.11, -gTime * 0.08) + p.y * 1.5));
    float density = 0.2 * over * wisps * exp(-p.y / 0.25);
    vec3 glow = CHERENKOV * pulse() * (1.0 + 2.5 * exp(-r * r / 1.2));
    return glow * density * (leave - enter);
}

vec3 radiance(vec3 ro, vec3 rd, out float depth) {
    vec2 air = traceAir(ro, rd, 110, 30.0);
    float tw = hitWater(ro, rd);
    bool water = tw > 0.0 && (air.x < 0.0 || tw < air.y);
    depth = water ? tw : air.y;
    vec3 color = hallHaze(ro, rd, depth) + poolMist(ro, rd, depth);

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
        vec3 above = up.x < 0.0 ? vec3(0.0) : shadeAir(p + rr * up.y, rr, normalAir(p + rr * up.y), up.x, false);

        color += mix(below, above, fresnel);
    } else if (air.x >= 0.0) {
        vec3 p = ro + rd * air.y;
        vec3 n = normalAir(p);
        color += shadeAir(p, rd, n, air.x, true);
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
                seen = shadeAir(p2, rr, normalAir(p2), bounce.x, false);
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

int shotAt(float time) {
    return int(mod(floor(time / SHOT_SECONDS), float(SHOTS)));
}

void lookAt(vec3 from, vec3 target, out vec3 position, out vec3 forward, out vec3 right, out vec3 up) {
    position = from;
    forward = normalize(target - from);
    right = normalize(cross(vec3(0.0, 1.0, 0.0), forward));
    up = cross(forward, right);
}

// Distance along a ray from `from` towards `target` to the water's surface:
// where a shot looking into the pool is focused, since the distance the
// renderer keeps for a pixel ends at the surface.
float toSurface(vec3 from, vec3 target) {
    return from.y / max(-normalize(target - from).y, 0.05);
}

// The shot at a moment: where the camera stands, what it looks at, and its
// lens, (focus distance, aperture). The aperture is how far a point at
// infinity blurs, as a fraction of the image height.
void framing(float time, out vec3 from, out vec3 target, out vec2 lens) {
    int shot = shotAt(time);
    float t = fract(time / SHOT_SECONDS);   // 0..1 through the shot
    float e = t * t * (3.0 - 2.0 * t);      // eased
    if (shot == 0) {
        // Establishing, a crane shot: from deck height behind the railing,
        // across the pool to the hall beyond, rising to look down into the water.
        float a = 0.2 + 0.9 * e;
        float r = mix(6.0, 5.0, e);
        from = vec3(sin(a) * r, mix(1.3, 5.2, e), cos(a) * r);
        target = mix(vec3(0.0, 1.4, 0.0), vec3(0.0, -1.2, 0.0), e);
        lens = vec2(length(target - from), 0.006);
    } else if (shot == 1) {
        // Low over the water, inside the pool, gliding under the ring; the surface mirrors everything.
        from = mix(vec3(0.5, 0.6, 2.15), vec3(-0.1, 0.35, 0.9), e);
        target = vec3(-0.3, -1.4, -1.0);
        lens = vec2(toSurface(from, target), 0.008);
    } else if (shot == 2) {
        // Close round the spikes of the ring: a macro lens, the hall melts away behind.
        float a = 1.9 + 0.7 * e;
        from = vec3(cos(a) * 2.7, 2.2 - 0.3 * e, sin(a) * 2.7);
        target = RING_CENTRE + vec3(cos(a - 0.35) * RING_R, 0.0, sin(a - 0.35) * RING_R);
        lens = vec2(length(target - from), 0.03);
    } else {
        // Straight down onto the core, sinking towards the water; the ring passes
        // blurred in front. The pulse shakes the camera for a moment.
        from = vec3(0.35 * sin(t * 2.0), mix(6.5, 3.4, e), 0.6 + 0.2 * cos(t * 2.0));
        from += vec3(sin(time * 47.0), sin(time * 53.0 + 1.0), sin(time * 41.0 + 2.0)) * 0.006 * flash(time);
        target = vec3(0.0, -3.0, 0.0);
        lens = vec2(toSurface(from, target), 0.012);
    }
}

void camera(float time, out vec3 position, out vec3 forward, out vec3 right, out vec3 up) {
    vec3 from, target;
    vec2 lens;
    framing(time, from, target, lens);
    lookAt(from, target, position, forward, right, up);
}

// Depth of field (dof.frag, final.frag): the lens at a moment, and how far a
// point `dist` along its ray blurs through it, in pixels of an image
// `height` tall.
const float MAX_BLUR = 0.02;

vec2 lensAt(float time) {
    vec3 from, target;
    vec2 lens;
    framing(time, from, target, lens);
    return lens;
}

float blurRadius(vec2 lens, float dist, float height) {
    return min(lens.y * abs(1.0 - lens.x / max(dist, 1e-3)), MAX_BLUR) * height;
}

const float FOCAL = 1.3;

// Linear HDR colour of one pixel. `jitter` moves the sample inside the pixel
// and `frame` reseeds the random samples; `depth` is the distance along the ray.
vec3 renderPool(vec2 fragCoord, vec2 resolution, float time, uint frame, vec2 jitter, out float depth) {
    beginFrame(time);
    gSeed = pcg(uint(fragCoord.x) * 1973u + uint(fragCoord.y) * 9277u + frame * 26699u);
    vec2 uv = (fragCoord + jitter - 0.5 * resolution) / resolution.y;
    vec3 ro, fw, rt, up;
    camera(time, ro, fw, rt, up);
    vec3 rd = normalize(uv.x * rt + uv.y * up + FOCAL * fw);
    return radiance(ro, rd, depth);
}
