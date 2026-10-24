// The black hole: the fourth cinematic scene. Shared by the renderer (Vulkan)
// and the desktop preview (tools/scene_preview.py, OpenGL): plain GLSL, inputs
// as arguments, output linear HDR colour (tone mapping and bloom come after).
//
// A supermassive black hole in deep space, burning an accretion disk. Where
// the other scenes march signed distance fields, this one integrates each
// ray's fall: the light's path is stepped through the field of the hole, bent
// towards it, wound round it, and lost in it. That bending is what the picture
// is made of — the disk's far side rises over and under the shadow, a thin
// ring of light orbits at the photon sphere, and the starfield doubles around
// the edge. Nothing is stored and nothing is sampled: every pixel integrates
// its own ray, and the stars and the disk's turbulence come out of hashes. The
// phone's GPU has ~1 TFLOPS but ~16 GB/s, and this scene spends almost all of
// its time in the arithmetic of the integration.
//
// Built for temporal accumulation like the others: the disk's turbulence is
// reseeded every frame and the camera moves without a cut until a shot ends,
// so the TAA's history converges on a clean image and drops it at a cut.
//
// Units: the horizon's radius is 1. Metres, y up, linear HDR out.

#include "camera.glsl"

// The quality levers, each its own constant id (scene_renderer.cpp pushes all
// four): the samples a pixel takes (0), the fall's step budget (1), the
// disk's turbulence octaves (2) and the starfield's shells (3). Higher
// quality winds rays longer, samples more, and grows both textures.
#ifdef VULKAN
layout(constant_id = 0) const int HOLE_SAMPLES = 1;
layout(constant_id = 1) const int HOLE_STEPS = 104;
layout(constant_id = 2) const int HOLE_OCTAVES = 3;
layout(constant_id = 3) const int HOLE_SHELLS = 2;
#else
// The preview forces these from --quality or --steps.
#ifdef HOLE_SAMPLES_FORCED
const int HOLE_SAMPLES = HOLE_SAMPLES_FORCED;
#else
const int HOLE_SAMPLES = 1;
#endif
#ifdef HOLE_STEPS_FORCED
const int HOLE_STEPS = HOLE_STEPS_FORCED;
#else
const int HOLE_STEPS = 104;
#endif
#ifdef HOLE_OCTAVES_FORCED
const int HOLE_OCTAVES = HOLE_OCTAVES_FORCED;
#else
const int HOLE_OCTAVES = 3;
#endif
#ifdef HOLE_SHELLS_FORCED
const int HOLE_SHELLS = HOLE_SHELLS_FORCED;
#else
const int HOLE_SHELLS = 2;
#endif
#endif

const float PI = 3.14159265359;

// ---- the hole -----------------------------------------------------------------------------------

const float HORIZON = 1.0;             // the radius nothing comes back from
const float PHOTON = 1.5;              // where light orbits: the ring lives here
const float DISK_IN = 2.4;             // the disk's inner edge, near the ISCO
const float DISK_OUT = 7.2;
const float DISK_HALF = 0.16;          // the disk's half thickness at the inner edge
const float ESCAPE = 34.0;             // beyond this the ray is out, into the stars
const float STAR_DISTANCE = 480.0;     // far enough that the stars reproject as if at infinity

// Palette: white-hot at the inner edge, ember red at the rim, cold stars.
const vec3 HOT = vec3(1.00, 0.93, 0.78);
const vec3 WARM = vec3(1.00, 0.62, 0.26);
const vec3 EMBER = vec3(0.62, 0.22, 0.07);
const vec3 STARLIGHT = vec3(0.72, 0.80, 1.00);

// The scripted sequence: shots of SHOT_SECONDS each, over and over. Shot
// boundaries are cuts: the TAA pass sees the shot number change and drops its
// history.
const float SHOT_SECONDS = 14.0;
const int SHOTS = 4;

// ---- random numbers and noise -------------------------------------------------------------------

float gTime;

// The cells' numbers come from hashing their whole coordinates as integers:
// a float fract-hash of `cell * k` loses its low bits once the cells number
// in the tens, and the quantised values showed up as bands that slid over
// the sky as the camera moved.
uint pcg(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

// One hash of a grid cell, salted per use. The +512 keeps the conversion
// exact for the negative cells; the scene's cells stay within ±64 of it.
uint cellHash(vec3 c, uint salt) {
    uvec3 q = uvec3(max(c, vec3(-511.0)) + 512.0);
    return pcg(q.x * 73856093u ^ q.y * 19349663u ^ q.z * 83492791u ^ salt);
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

// The disk's turbulence: streaks pulled into arcs by the faster inner rim.
// The noise is sampled on the ring itself, so it has no seam to hide; the
// differential rotation comes in as the angle's own drift with radius, and
// the radial coordinate drifts slowly, so the features read as long arcs.
float streaks(float radius, float angle, float time) {
    float spin = time * 0.9 / pow(max(radius, 1.4), 1.5);
    vec2 ring = vec2(cos(angle + spin), sin(angle + spin));
    // The octaves share one total weight — the 4-octave mix the scene was
    // tuned with — so a longer tail adds detail, not brightness.
    float weight = 0.0;
    for (int i = 0; i < HOLE_OCTAVES; ++i) weight += pow(0.55, float(i));
    float v = 0.0;
    for (int i = 0; i < HOLE_OCTAVES; ++i) {
        float cells = (2.6 + radius * 1.1) * (1.0 + 0.7 * float(i));
        v += 1.211 * pow(0.55, float(i)) / weight
           * noise2(ring * cells + vec2(radius * (1.1 + 0.4 * float(i)), 13.7 * float(i) + 31.0));
    }
    return v;
}

// ---- the background -----------------------------------------------------------------------------

// Stars from a hashed grid of the direction itself: shells of cells, a point
// star in some of them, its colour and size its own. Behind them, the band of
// a distant galaxy, stretched along one great circle.
vec3 starfield(vec3 rd) {
    vec3 sky = vec3(0.0);
    for (int shell = 0; shell < HOLE_SHELLS; ++shell) {
        float scale = shell == 0 ? 22.0 : (shell == 1 ? 55.0 : 110.0);
        float glow = shell == 0 ? 2.4 : (shell == 1 ? 0.7 : 0.45);
        vec3 cell = floor(rd * scale);
        for (int x = -1; x <= 1; ++x) {
            for (int y = -1; y <= 1; ++y) {
                for (int z = -1; z <= 1; ++z) {
                    vec3 c = cell + vec3(float(x), float(y), float(z));
                    uint h = cellHash(c, uint(shell) * 53u + 7u);
                    float pick = float(h) * (1.0 / 4294967295.0);
                    if (pick < (shell == 0 ? 0.90 : (shell == 1 ? 0.86 : 0.82))) continue;
                    vec3 spread = vec3(float(pcg(h + 11u)), float(pcg(h + 23u)), float(pcg(h + 37u)))
                                * (1.0 / 4294967295.0);
                    vec3 at = (c + 0.15 + 0.7 * spread) / scale;
                    float d = length(rd - normalize(at));
                    // A dot soft enough not to snap between pixels as the
                    // frame's jitter moves it, sharp enough to stay a point.
                    float shine = pow(max(0.0, 1.0 - d * scale * mix(6.5, 3.6, fract(pick * 7.7))), 11.0);
                    sky += mix(STARLIGHT, vec3(1.0, 0.82, 0.62), fract(pick * 91.3)) * shine * glow;
                }
            }
        }
    }
    float band = exp(-7.0 * abs(dot(rd, normalize(vec3(0.28, 0.82, -0.5)))));
    // The band's own plane: two axes across it, so the haze has no seam and
    // no direction the screen reads as a stripe.
    vec3 sideA = normalize(vec3(0.9, 0.0, 0.44));
    vec3 sideB = cross(normalize(vec3(0.28, 0.82, -0.5)), sideA);
    float haze = noise2(vec2(dot(rd, sideA), dot(rd, sideB)) * 5.0) * 0.6
               + noise2(vec2(dot(rd, sideA), dot(rd, sideB)) * 11.0 + 7.0) * 0.4;
    sky += mix(vec3(0.05, 0.08, 0.16), vec3(0.17, 0.13, 0.23), haze) * band * 0.5;
    sky += vec3(0.012, 0.014, 0.022);  // the floor of deep space, never pure black
    return sky;
}

// ---- the disk -----------------------------------------------------------------------------------

// A bright fold riding the inner edge, once a lap: the pulse, as the pool has
// its TRIGA flash. It is more disk, not a light in the void.
float hotspot(float radius, float angle, float time) {
    float lap = mod(time, SHOT_SECONDS * float(SHOTS));
    float at = mod(lap * 2.2 - angle + PI, 2.0 * PI) - PI;
    float near = exp(-14.0 * (radius - DISK_IN) * (radius - DISK_IN));
    return 1.0 + 2.6 * near * exp(-9.0 * at * at) * smoothstep(DISK_OUT, DISK_IN + 1.5, radius);
}

// The colour and weight of the disk where the ray crosses its plane at `p`.
// Gravity redshifts and darkens the inner rim; the orbit's speed beams the
// side coming towards the camera and dims the side going away.
vec3 disk(vec3 p, vec3 dir, out float weight) {
    float r = length(p.xz);
    float h = DISK_HALF * smoothstep(DISK_OUT, DISK_IN, r);
    float vertical = exp(-p.y * p.y / max(h * h, 1e-4));
    if (vertical < 0.01 || r < DISK_IN - 0.1 || r > DISK_OUT + 0.4) {
        weight = 0.0;
        return vec3(0.0);
    }
    float angle = atan(p.z, p.x);
    float grain = streaks(r, angle, gTime);
    // Density: the rim falls away, the streaks gather the rest into arcs.
    float density = vertical * smoothstep(DISK_OUT + 0.4, DISK_OUT - 2.2, r)
                  * smoothstep(DISK_IN - 0.1, DISK_IN + 0.55, r)
                  * (0.18 + 0.82 * grain) * hotspot(r, angle, gTime);
    weight = clamp(density * 1.35, 0.0, 1.0);
    if (weight < 0.004) {
        weight = 0.0;
        return vec3(0.0);
    }
    // Heat: the inner rim white-hot, the rim embers.
    float heat = pow(clamp(1.0 - (r - DISK_IN) / (DISK_OUT - DISK_IN), 0.0, 1.0), 1.35);
    vec3 colour = mix(EMBER, WARM, smoothstep(0.25, 0.6, heat));
    colour = mix(colour, HOT, smoothstep(0.72, 0.98, heat));
    // Doppler beaming: the orbit is tangent, faster within; the approaching
    // side brightens and cools towards white, the receding side fades.
    vec3 tangent = normalize(vec3(-p.z, 0.0, p.x));
    float speed = 0.55 / sqrt(max(r, 1.2) - 0.9);
    float beam = 1.0 / (1.0 - speed * dot(tangent, dir));
    beam = clamp(pow(max(beam, 0.25), 3.0), 0.15, 3.0);
    colour = mix(colour, vec3(1.0, 0.97, 0.90), clamp(beam - 1.0, 0.0, 0.5));
    // Gravity's own dimming near the inner edge.
    float redshift = sqrt(clamp(1.0 - 1.0 / max(r, HORIZON + 0.2), 0.0, 1.0));
    return colour * beam * (0.5 + 1.25 * heat) * mix(0.45, 1.0, redshift);
}

// ---- the fall -----------------------------------------------------------------------------------

// The field's pull on light: for a horizon of radius 1, a ray's path bends
// by -1.5 h² p / r⁵, h the ray's angular momentum — the exact shape that
// winds a ray round the photon sphere and gives the disk its halo.
vec3 pull(vec3 p, float h2) {
    float r2 = max(dot(p, p), 0.03);
    float r = sqrt(r2);
    return -1.5 * h2 * p / (r2 * r2 * r);
}

// One ray's fall. The path is stepped under the field's own pull with a
// Runge-Kutta step: the pull is stiff near the photon sphere, and the ring's
// shape is where a cheap step shows. The step grows with the distance from
// the hole, where the pull is gentle, and shrinks near the disk, where the
// picture is made.
vec3 fall(vec3 ro, vec3 rd, int steps, out float depth) {
    vec3 p = ro;
    vec3 v = rd;
    vec3 l = cross(p, v);
    float h2 = dot(l, l);
    vec3 colour = vec3(0.0);
    float through = 1.0;
    float travelled = 0.0;
    float depthSeen = 1e3;
    float nearest = 1e3;
    bool escaped = false;
    bool captured = false;

    for (int i = 0; i < steps; ++i) {
        float r2 = dot(p, p);
        float r = sqrt(r2);
        nearest = min(nearest, r);
        if (r < HORIZON) {
            captured = true;
            break;
        }
        if (r > ESCAPE && dot(p, v) > 0.0) {
            escaped = true;
            break;
        }
        // Past the disk's rim the pull is weak: one step may leap radii.
        float step = clamp(r * 0.14, 0.02, 18.0);
        if (abs(p.y) < DISK_HALF * 2.2 + 0.3 && r < DISK_OUT + 2.0) step = min(step, 0.06 + r * 0.045);

        // Runge-Kutta on (position, velocity): four pulls a step.
        vec3 k1p = v;
        vec3 k1v = pull(p, h2);
        vec3 k2p = v + k1v * (step * 0.5);
        vec3 k2v = pull(p + k1p * (step * 0.5), h2);
        vec3 k3p = v + k2v * (step * 0.5);
        vec3 k3v = pull(p + k2p * (step * 0.5), h2);
        vec3 k4p = v + k3v * step;
        vec3 k4v = pull(p + k3p * step, h2);
        vec3 pNew = p + (k1p + (k2p + k3p) * 2.0 + k4p) * (step / 6.0);
        vec3 vNew = v + (k1v + (k2v + k3v) * 2.0 + k4v) * (step / 6.0);

        // A crossing of the disk's plane between the steps: find it, shade it.
        if (p.y * pNew.y < 0.0) {
            float k = p.y / (p.y - pNew.y);
            vec3 at = mix(p, pNew, k);
            float w;
            vec3 c = disk(at, normalize(vNew), w);
            if (w > 0.0) {
                if (depthSeen > 9e2) depthSeen = travelled + step * k;
                colour += through * c * w;
                through *= 1.0 - w;
                if (through < 0.02) break;
            }
        }
        p = pNew;
        v = vNew;
        travelled += step;
    }

    // The ring of light that orbits at the photon sphere: rays skimming it
    // wind round and come back, and the disk behind them lights the way. A
    // faint additive fold keeps the ring legible at every step budget.
    if (!captured && nearest > PHOTON - 0.3 && nearest < PHOTON + 0.6) {
        // A wide, quiet fold: a tight one would flicker, its radius moving
        // with every frame's sub-pixel jitter.
        float ring = exp(-13.0 * (nearest - PHOTON) * (nearest - PHOTON));
        colour += through * vec3(1.0, 0.75, 0.42) * ring * 0.7;
    }
    if (escaped) {
        colour += starfield(normalize(v));
        depthSeen = STAR_DISTANCE;
    } else if (!captured) {
        // The steps ran out mid-fall: a ray winding at the photon sphere, or
        // one a whisker from the horizon. How free it is blends the stars in
        // continuously, so the boundary cannot flicker frame to frame.
        float free = smoothstep(PHOTON - 0.1, PHOTON + 0.45, nearest);
        colour += through * starfield(normalize(v)) * free;
        colour += through * vec3(0.012, 0.014, 0.022) * 0.25 * (1.0 - free);
        if (depthSeen > 9e2) depthSeen = min(travelled, 220.0);
    } else if (depthSeen > 9e2) {
        // The ray was swallowed: the shadow keeps whatever crossed in front
        // of it, and the floor of deep space below the disk's crossings.
        colour += vec3(0.012, 0.014, 0.022) * 0.25;
        depthSeen = min(travelled, 220.0);
    }
    depth = depthSeen;
    return colour;
}

// ---- camera -------------------------------------------------------------------------------------

// One sequence with cuts between its shots (camera.glsl).
int shotAt(float time) {
    return int(mod(max(time, 0.0), SHOT_SECONDS * float(SHOTS)) / SHOT_SECONDS);
}

// Where the camera stands and what it looks at, shot by shot. Each shot holds
// one idea and moves slowly through it; between shots there is a cut, and the
// TAA drops what it kept.
void framing(float time, out vec3 from, out vec3 target, out vec2 lens) {
    gTime = time;
    float t = mod(max(time, 0.0), SHOT_SECONDS);  // 0..SHOT_SECONDS within the shot
    float u = t / SHOT_SECONDS;                   // 0..1 through it
    int shot = shotAt(time);
    // A slow easing, so no shot begins or ends abruptly.
    float ease = u * u * (3.0 - 2.0 * u);

    float radius;
    float inclination;  // radians above the disk's plane
    float bearing;      // radians round the hole
    bool ahead = false; // looking along the disk, not at the hole
    if (shot == 0) {
        // From above: the disk a wheel of fire, slowly dropping towards its plane.
        radius = mix(26.0, 22.0, ease);
        inclination = mix(0.75, 0.5, ease);
        bearing = 0.4 + 0.35 * ease;
        lens = vec2(12.0, 0.0);
    } else if (shot == 1) {
        // The approach: near edge-on, where the far side of the disk rises
        // over and under the shadow and the ring stands alone.
        radius = mix(22.0, 15.0, ease);
        inclination = mix(0.5, 0.06, ease);
        bearing = 0.75 + 0.5 * ease;
        lens = vec2(11.0, 0.0015);
    } else if (shot == 2) {
        // The skim: out past the rim, the disk's inner edge streaming beneath.
        radius = mix(15.0, 9.5, ease);
        inclination = mix(0.06, 0.2, ease);
        bearing = 1.25 + 1.1 * ease;
        ahead = true;
        lens = vec2(7.0, 0.004);
    } else {
        // The farewell: climbing away, the whole system small against the stars.
        radius = mix(9.5, 28.0, ease * ease);
        inclination = mix(0.2, 0.55, ease);
        bearing = 2.35 + 0.3 * ease;
        lens = vec2(13.0, 0.0);
    }
    from = vec3(radius * cos(inclination) * cos(bearing),
                radius * sin(inclination) + 0.15 * sin(time * 0.21),
                radius * cos(inclination) * sin(bearing));
    // The hole, nudged a little off centre; in the skim, the disk ahead.
    vec3 centre = vec3(0.0, -0.12 * sin(inclination), 0.0);
    vec3 forward = vec3(cos(bearing + 2.1), 0.0, sin(bearing + 2.1)) * 2.4;
    target = ahead ? forward : centre;
}

// Linear HDR colour of one pixel, as renderPool() (pool.glsl).
vec3 renderHole(vec2 fragCoord, vec2 resolution, float time, uint frame, vec2 jitter, out float depth) {
    gTime = time;
    vec2 uv = (fragCoord + jitter - 0.5 * resolution) / resolution.y;
    vec3 ro, fw, rt, up;
    camera(time, ro, fw, rt, up);
    vec3 rd = normalize(uv.x * rt + uv.y * up + FOCAL * fw);
    return fall(ro, rd, HOLE_STEPS, depth);
}
