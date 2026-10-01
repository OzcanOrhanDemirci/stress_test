// The white world: the third cinematic scene, about clarity. Shared by the
// renderer (Vulkan) and the desktop preview (OpenGL).
//
// An endless white space with a polished floor that mirrors everything. The
// camera flies on without a cut through four zones that repeat: an open void
// of floating cubes, a glass corridor, a ribbed glass tunnel, a room walled
// with stacked cubes. A lit frame marks every zone's door. Through the glass
// the vast empty white world stays in view. Everything is boxes and rings,
// so it is cheap and exact for ray marching: sharp edges, true reflections,
// glass seen through; the finish adds no blur.
//
// Conventions as in pool.glsl: metres, y up, linear HDR out.

#include "camera.glsl"

const float PI = 3.14159265359;

// ---- layout -------------------------------------------------------------------------------------

const float ZONE = 48.0;               // metres a zone runs along z
const int ZONES = 4;
const float PERIOD = ZONE * float(ZONES);
const float MARGIN = 5.0;              // a zone's own objects keep this far from its ends
const float SPEED = 3.6;               // metres a second along the path

const float CORRIDOR_W = 2.3;          // half width
const float CORRIDOR_H = 3.3;
const vec2 TUNNEL_CENTRE = vec2(0.0, 2.0);
const float TUNNEL_R = 2.6;
const float ROOM_W = 4.4;              // the cube walls start this far either side

// ---- light --------------------------------------------------------------------------------------

const vec3 KEY_DIR = vec3(-0.346, 0.865, 0.3633);   // normalised: high, a little to the side
const vec3 KEY = vec3(1.0, 0.98, 0.95) * 1.7;
const vec3 VOID = vec3(0.90, 0.93, 0.97);           // the white world, far off
const float HAZE = 0.006;                           // how fast distance turns to white
const vec3 GLASS_TINT = vec3(0.86, 0.95, 0.96);  // what a pane passes, straight through: a cool green-blue

// Materials.
const float M_WHITE = 1.0;
const float M_FRAME = 2.0;
const float M_LIGHT = 3.0;
const float M_FLOOR = 4.0;

// Each zone's light colour: the strips, the rings, its door's edge.
vec3 zoneTint(int zone) {
    if (zone == 1) return vec3(0.35, 0.82, 1.00);
    if (zone == 2) return vec3(1.00, 0.62, 0.28);
    if (zone == 3) return vec3(0.72, 0.56, 1.00);
    return vec3(0.55, 0.95, 0.85);
}

// ---- helpers ------------------------------------------------------------------------------------

uint wSeed;

uint whash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float wrand() {
    wSeed = whash(wSeed);
    return float(wSeed) * (1.0 / 4294967295.0);
}

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float sdBox(vec3 p, vec3 b) {
    vec3 q = abs(p) - b;
    return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0);
}

float schlick(float cosine) {
    float m = clamp(1.0 - cosine, 0.0, 1.0);
    float m2 = m * m;
    return m2 * m2 * m;
}

// The zone a point is in and its place along it, 0..ZONE.
int zoneOf(float z, out float along) {
    float w = mod(z, PERIOD);
    int zone = int(floor(w / ZONE));
    along = w - float(zone) * ZONE;
    return min(zone, ZONES - 1);
}

// How far a point is from the stretch of its zone where the zone's objects
// stand; past the end, the next zone's start is as far again.
float outsideZone(float along) {
    return max(MARGIN - along, along - (ZONE - MARGIN));
}

// ---- the world ----------------------------------------------------------------------------------

float gTime;

// Tall white slabs far out either side, in every zone: the world's scale.
float monoliths(vec3 p) {
    vec2 cell = vec2(80.0, 64.0);
    vec2 q = vec2(abs(p.x) - 70.0, p.z);
    // None stands nearer the path than the first row of cells, less a slab's half width.
    if (q.x < -8.0) return -q.x - 7.5;
    vec2 c = floor(q / cell);
    vec2 local = q - (c + 0.5) * cell;
    float h = hash21(c + 31.0);
    vec3 size = vec3(mix(3.0, 7.0, h), mix(18.0, 46.0, fract(h * 7.7)), mix(3.0, 9.0, fract(h * 3.1)));
    float d = sdBox(vec3(local.x, p.y - size.y, local.y), size);
    // A neighbour's slab is no nearer than this cell's edge plus the clearance
    // every slab keeps from its cell's sides (at least 23 m).
    vec2 edge = cell * 0.5 - abs(local);
    return min(d, max(min(edge.x, edge.y), 0.0) + 20.0);
}

// A lit frame at every zone's door, round the path.
vec2 portal(vec3 p) {
    float z = mod(p.z + 0.5 * ZONE, ZONE) - 0.5 * ZONE;
    vec3 q = vec3(p.x, p.y - 3.1, z);
    float outer = sdBox(q, vec3(3.6, 3.1, 0.45));
    float inner = sdBox(q, vec3(2.75, 2.55, 0.6));
    float frame = max(outer, -inner);
    // A light line round the opening.
    float glow = max(sdBox(q, vec3(2.8, 2.6, 0.06)), -sdBox(q, vec3(2.75, 2.55, 0.2)));
    return glow < frame ? vec2(glow, M_LIGHT) : vec2(frame, M_FRAME);
}

// Zone 0, the void: white cubes floating round the path and over it,
// bobbing slowly; those over the path hang above the camera's height.
vec2 zoneVoid(vec3 q) {
    vec2 cell = vec2(4.5, 5.0);
    vec2 c = floor(q.xz / cell);
    vec2 local = q.xz - (c + 0.5) * cell;
    // A neighbour's cube is no nearer than this cell's edge plus the clearance
    // every cube keeps from its cell's sides (half a metre and more).
    vec2 edge = cell * 0.5 - abs(local);
    float d = max(min(edge.x, edge.y), 0.0) + 0.45;
    float h = hash21(c + 17.0);
    float mid = (c.y + 0.5) * cell.y;
    if (h > 0.38 && mid > MARGIN + 2.0 && mid < ZONE - MARGIN - 2.0) {
        float size = mix(0.35, 1.7, fract(h * 7.3));
        bool overPath = abs((c.x + 0.5) * cell.x) < 4.0;
        float low = overPath ? 6.5 + size : 0.8 + size;
        float height = mix(low, 12.0, fract(h * 13.1)) + 0.3 * sin(gTime * 0.5 + h * 30.0);
        d = min(d, sdBox(vec3(local.x, q.y - height, local.y), vec3(size)) - 0.015);
    }
    return vec2(d, M_WHITE);
}

// Zone 1, the glass corridor: white frames every 2.4 m, light strips along
// the ceiling's edges. Its panes are in glassCorridor().
vec2 zoneCorridor(vec3 q) {
    float ends = outsideZone(q.z);
    float x = abs(q.x);
    float bay = mod(q.z - MARGIN, 2.4) - 1.2;
    float post = sdBox(vec3(x - CORRIDOR_W, q.y - 0.5 * CORRIDOR_H, bay), vec3(0.08, 0.5 * CORRIDOR_H, 0.08));
    float beam = sdBox(vec3(q.x, q.y - CORRIDOR_H, bay), vec3(CORRIDOR_W + 0.08, 0.08, 0.08));
    float rails = min(sdBox(vec3(x - CORRIDOR_W, q.y - 0.05, 0.0), vec3(0.1, 0.05, 1e3)),
                      sdBox(vec3(x - CORRIDOR_W, q.y - CORRIDOR_H, 0.0), vec3(0.1, 0.1, 1e3)));
    float frame = max(min(min(post, beam), rails), ends);
    float strip = max(sdBox(vec3(x - CORRIDOR_W + 0.18, q.y - CORRIDOR_H + 0.12, 0.0), vec3(0.03, 0.03, 1e3)), ends);
    return strip < frame ? vec2(strip, M_LIGHT) : vec2(frame, M_FRAME);
}

float glassCorridor(vec3 q) {
    float walls = max(abs(abs(q.x) - CORRIDOR_W) - 0.01, q.y - CORRIDOR_H);
    float roof = max(abs(q.y - CORRIDOR_H) - 0.01, abs(q.x) - CORRIDOR_W);
    return max(min(walls, roof), outsideZone(q.z));
}

// Zone 2, the ribbed tunnel: white rings every 1.5 m, every fourth lit from inside.
vec2 zoneTunnel(vec3 q) {
    float ends = outsideZone(q.z);
    float r = length(q.xy - TUNNEL_CENTRE);
    float index = floor((q.z - MARGIN) / 1.5);
    float z = q.z - MARGIN - (index + 0.5) * 1.5;
    float rib = max(max(abs(r - TUNNEL_R) - 0.13, abs(z) - 0.1), ends);
    float lit = mod(index, 4.0) < 0.5 ? max(max(abs(r - TUNNEL_R + 0.14) - 0.015, abs(z) - 0.06), ends) : 1e3;
    return lit < rib ? vec2(lit, M_LIGHT) : vec2(rib, M_FRAME);
}

float glassTunnel(vec3 q) {
    return max(abs(length(q.xy - TUNNEL_CENTRE) - TUNNEL_R) - 0.01, outsideZone(q.z));
}

// Zone 3, the room of cubes: walls of stacked white cubes either side, each
// standing out by its own amount; one great cube hanging over the path.
const float CUBE = 1.1;  // the walls' grid, metres

// The cube in grid cell `cell` (row up, column along) of the wall on `side`.
float wallCube(vec3 q, vec2 cell, float side) {
    if (cell.x < 0.0 || cell.x > 6.0) return 1e3;
    float face = ROOM_W + 1.3 * hash21(cell + side);
    vec2 centre = (cell + 0.5) * CUBE;
    return sdBox(vec3(abs(q.x) - face - 0.6, q.y - centre.x, q.z - centre.y), vec3(0.6, 0.5, 0.5)) - 0.01;
}

vec2 zoneCubes(vec3 q) {
    float ends = outsideZone(q.z);
    // Nothing of the walls nearer than the slab their faces stand in.
    float d = max(max(ROOM_W - abs(q.x), q.y - 7.8), 0.0) + 0.0;
    if (d < 1.5) {
        // Near the walls: the cubes of the nearest 2 x 2 cells, which a cube
        // in any other cell cannot be nearer than.
        float side = q.x > 0.0 ? 3.0 : 7.0;
        vec2 g = vec2(q.y, q.z) / CUBE;
        vec2 cell = floor(g);
        vec2 toward = sign(fract(g) - 0.5);
        d = min(min(wallCube(q, cell, side), wallCube(q, cell + vec2(toward.x, 0.0), side)),
                min(wallCube(q, cell + vec2(0.0, toward.y), side), wallCube(q, cell + toward, side)));
    }
    d = max(d, ends);
    float hanging = max(sdBox(vec3(q.x, q.y - 4.8, q.z - 0.5 * ZONE), vec3(1.15)) - 0.015, ends);
    return vec2(min(d, hanging), M_WHITE);
}

// Everything solid but the floor (traced exactly, not marched).
vec2 mapSolid(vec3 p) {
    float along;
    int zone = zoneOf(p.z, along);
    vec3 q = vec3(p.x, p.y, along);
    vec2 res;
    if (zone == 0) res = zoneVoid(q);
    else if (zone == 1) res = zoneCorridor(q);
    else if (zone == 2) res = zoneTunnel(q);
    else res = zoneCubes(q);
    // The next zone's objects are no nearer than its own margin past the boundary.
    res.x = min(res.x, max(min(along, ZONE - along), 0.0) + MARGIN);
    vec2 door = portal(p);
    if (door.x < res.x) res = door;
    float far = monoliths(p);
    if (far < res.x) res = vec2(far, M_WHITE);
    return res;
}

float mapGlass(vec3 p) {
    float along;
    int zone = zoneOf(p.z, along);
    vec3 q = vec3(p.x, p.y, along);
    float d = 1e3;
    if (zone == 1) d = glassCorridor(q);
    else if (zone == 2) d = glassTunnel(q);
    return min(d, max(min(along, ZONE - along), 0.0) + MARGIN);
}

vec3 solidNormal(vec3 p) {
    const vec2 k = vec2(1.0, -1.0);
    const float e = 0.0008;
    return normalize(k.xyy * mapSolid(p + k.xyy * e).x + k.yyx * mapSolid(p + k.yyx * e).x +
                     k.yxy * mapSolid(p + k.yxy * e).x + k.xxx * mapSolid(p + k.xxx * e).x);
}

vec3 glassNormal(vec3 p) {
    const vec2 k = vec2(1.0, -1.0);
    const float e = 0.0008;
    return normalize(k.xyy * mapGlass(p + k.xyy * e) + k.yyx * mapGlass(p + k.yyx * e) +
                     k.yxy * mapGlass(p + k.yxy * e) + k.xxx * mapGlass(p + k.xxx * e));
}

// ---- tracing ------------------------------------------------------------------------------------

struct Hit {
    float t;
    float material;  // M_*, or -1: nothing but the void
    bool glass;
};

// March towards the nearest solid or pane; the floor (y = 0) is met exactly.
// Far off the haze has nearly whitened everything, so the march may stop
// sooner there: its tolerance grows with distance.
Hit trace(vec3 ro, vec3 rd, float maxT, bool panes, int steps) {
    float floorT = rd.y < -1e-4 ? -ro.y / rd.y : 1e9;
    float limit = min(maxT, floorT);
    float t = 0.0;
    for (int i = 0; i < steps; ++i) {
        vec3 p = ro + rd * t;
        vec2 s = mapSolid(p);
        float g = panes ? mapGlass(p) : 1e9;
        float d = min(s.x, g);
        if (d < 0.0012 * t + 0.0008) return Hit(t, g < s.x ? -2.0 : s.y, g < s.x);
        t += d;
        if (t > limit) break;
    }
    if (floorT <= maxT) return Hit(floorT, M_FLOOR, false);
    return Hit(maxT, -1.0, false);
}

// ---- shading ------------------------------------------------------------------------------------

vec3 sky(vec3 rd) {
    vec3 c = mix(vec3(0.86, 0.89, 0.94), vec3(1.02, 1.03, 1.05), smoothstep(-0.15, 0.5, rd.y));
    return c * 1.1 + KEY * 0.25 * pow(max(dot(rd, KEY_DIR), 0.0), 12.0);
}

float occlusion(vec3 p, vec3 n) {
    float occ = 0.0;
    float weight = 1.0;
    for (int i = 1; i <= 5; ++i) {
        float h = 0.06 * float(i * i);
        occ += (h - min(mapSolid(p + n * h).x, h)) * weight;
        weight *= 0.6;
    }
    return clamp(1.0 - 1.8 * occ, 0.0, 1.0);
}

// Soft shadow from the key light; its start is shifted a little each frame so
// the edge smooths out as frames accumulate.
float keyShadow(vec3 p) {
    float s = 1.0;
    float t = 0.03 + 0.03 * wrand();
    for (int i = 0; i < 20; ++i) {
        float h = mapSolid(p + KEY_DIR * t).x;
        s = min(s, 10.0 * h / t);
        t += clamp(h, 0.05, 3.0);
        if (s < 0.01 || t > 30.0) break;
    }
    return clamp(s, 0.0, 1.0);
}

// The lit edges cast their colour on what is near them.
vec3 nearLights(vec3 p) {
    float along;
    int zone = zoneOf(p.z, along);
    vec3 q = vec3(p.x, p.y, along);
    float d = 1e3;
    float ends = max(outsideZone(along), 0.0);
    if (zone == 1) {
        float strip = sdBox(vec3(abs(q.x) - CORRIDOR_W + 0.18, q.y - CORRIDOR_H + 0.12, 0.0), vec3(0.03, 0.03, 1e3));
        d = max(strip, 0.0) + ends;
    } else if (zone == 2) {
        // Every fourth ring is lit: 0.75 m past the start, then every 6 m.
        float ring = abs(mod(along - MARGIN - 0.75 + 3.0, 6.0) - 3.0);
        d = abs(length(q.xy - TUNNEL_CENTRE) - TUNNEL_R + 0.14) + 0.6 * ring + ends;
    }
    float door = portal(p).x;
    vec3 c = zoneTint(zone) * 0.5 / (1.0 + 10.0 * d * d);
    return c + zoneTint(int(mod(floor(mod(p.z + 0.5 * ZONE, PERIOD) / ZONE), float(ZONES)))) * 0.35 / (1.0 + 4.0 * door * door);
}

vec3 shadeSolid(vec3 p, vec3 rd, vec3 n, float material, bool primary) {
    float along;
    int zone = zoneOf(p.z + (material == M_LIGHT && abs(mod(p.z + 0.5 * ZONE, ZONE) - 0.5 * ZONE) < 1.0 ? 1.0 : 0.0), along);
    if (material == M_LIGHT) return zoneTint(zone) * 3.2;
    float albedo = material == M_FRAME ? 0.9 : 0.82;
    float occ = primary ? occlusion(p, n) : 1.0;
    float shadow = primary ? keyShadow(p + n * 0.01) : 1.0;
    vec3 light = KEY * max(dot(n, KEY_DIR), 0.0) * shadow + sky(n) * 0.6 * occ * (0.6 + 0.4 * n.y);
    return albedo * (light + nearLights(p) * occ);
}

vec3 haze(vec3 c, float dist) {
    return mix(VOID * 1.08, c, exp(-dist * HAZE));
}

// What a reflection sees: the solid world and the floor, lit simply, panes left out.
vec3 reflected(vec3 ro, vec3 rd) {
    Hit h = trace(ro, rd, 90.0, false, 56);
    if (h.material < 0.0) return haze(sky(rd), 90.0);
    vec3 p = ro + rd * h.t;
    vec3 n = h.material == M_FLOOR ? vec3(0.0, 1.0, 0.0) : solidNormal(p);
    vec3 c = h.material == M_FLOOR ? vec3(0.8) * (KEY * KEY_DIR.y + sky(n) * 0.6) : shadeSolid(p, rd, n, h.material, false);
    return haze(c, h.t);
}

// Linear HDR colour along a ray; `depth` is where it first meets something solid.
vec3 radianceWhite(vec3 ro, vec3 rd, out float depth) {
    vec3 colour = vec3(0.0);
    vec3 through = vec3(1.0);
    float travelled = 0.0;
    depth = 220.0;
    for (int layer = 0; layer < 3; ++layer) {
        Hit h = trace(ro, rd, 220.0 - travelled, true, 100);
        vec3 p = ro + rd * h.t;
        float dist = travelled + h.t;
        if (h.glass) {
            // A pane: the white world mirrored a little at a glance, the rest
            // goes through, tinted the more glass it crosses: seen at a slant a
            // pane's colour deepens, as thick glass does.
            vec3 n = glassNormal(p);
            if (dot(n, rd) > 0.0) n = -n;
            float cosine = max(dot(-rd, n), 0.15);
            float f = 0.04 + 0.96 * schlick(cosine);
            colour += through * f * haze(sky(reflect(rd, n)), dist);
            through *= (1.0 - f) * pow(GLASS_TINT, vec3(1.0 / cosine));
            ro = p + rd * 0.03;
            travelled = dist + 0.03;
            continue;
        }
        depth = dist;
        vec3 c;
        if (h.material < 0.0) {
            c = haze(sky(rd), 220.0);
        } else if (h.material == M_FLOOR) {
            // Polished white floor: the world mirrored in it, stronger at a glance.
            vec3 n = vec3(0.0, 1.0, 0.0);
            float occ = occlusion(p, n);
            vec3 base = vec3(0.8) * (KEY * KEY_DIR.y * keyShadow(p + n * 0.01) + sky(n) * 0.6 * occ) + 0.8 * nearLights(p) * occ;
            float f = 0.12 + 0.6 * schlick(dot(-rd, n));
            c = mix(base, reflected(p + n * 0.002, reflect(rd, n)), f);
            // Two light lines inlaid along the path, in the zone's colour.
            float along;
            int zone = zoneOf(p.z, along);
            float line = smoothstep(0.035, 0.02, abs(abs(p.x) - 0.9));
            c = mix(c, zoneTint(zone) * 2.6, line);
        } else {
            vec3 n = solidNormal(p);
            c = shadeSolid(p, rd, n, h.material, true);
        }
        colour += through * haze(c, dist);
        return colour;
    }
    return colour + through * VOID;
}

// ---- camera -------------------------------------------------------------------------------------

// One flight with no cuts (camera.glsl).
int shotAt(float time) {
    return 0;
}

// Openness of the path at z: 1 in the void and the cube room, 0 inside the
// corridor and the tunnel; the camera rises in the open and comes down to
// enter the glass.
float openness(float z) {
    float along;
    int zone = zoneOf(z, along);
    bool open = zone == 0 || zone == 3;
    float inside = smoothstep(MARGIN - 2.0, MARGIN + 6.0, along) * smoothstep(ZONE - MARGIN + 2.0, ZONE - MARGIN - 6.0, along);
    return open ? inside : 0.0;
}

void framing(float time, out vec3 from, out vec3 target, out vec2 lens) {
    float z = time * SPEED;
    float open = openness(z);
    float ahead = openness(z + 8.0);
    from = vec3(1.6 * open * sin(z * 0.05), 1.7 + 2.6 * open, z);
    float lift = 1.7 + 2.6 * ahead;
    // Looking ahead along the path, turning a little in the open to take in its sides.
    target = vec3(from.x + 4.5 * open * sin(z * 0.035 + 1.0), mix(lift, 1.9, 0.5) + 0.25 * open, z + 9.0);
    lens = vec2(10.0, 0.0);  // no depth of field: sharp from near to far
}

// Linear HDR colour of one pixel, as renderPool() (pool.glsl).
vec3 renderWhite(vec2 fragCoord, vec2 resolution, float time, uint frame, vec2 jitter, out float depth) {
    gTime = time;
    wSeed = whash(uint(fragCoord.x) * 1973u + uint(fragCoord.y) * 9277u + frame * 26699u);
    vec2 uv = (fragCoord + jitter - 0.5 * resolution) / resolution.y;
    vec3 ro, fw, rt, up;
    camera(time, ro, fw, rt, up);
    vec3 rd = normalize(uv.x * rt + uv.y * up + FOCAL * fw);
    return radianceWhite(ro, rd, depth);
}
