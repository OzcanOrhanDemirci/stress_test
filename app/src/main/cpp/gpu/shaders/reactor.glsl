// The reactor scene: shared by the renderer (scene.frag, Vulkan) and the
// desktop preview (tools/scene_preview.py, OpenGL), so it uses nothing but
// plain GLSL and takes its inputs as arguments.
//
// A dark reactor hall. In the middle a pulsing core hangs over a pedestal,
// ringed by six columns; a spiked ring (FurMark's furry donut, grown up)
// turns around it. Cherenkov-blue light fills the air; a radiation trefoil
// and a hazard band are painted on the floor.
//
// Everything is ray marched: signed distance functions, one reflection
// bounce, soft shadows towards the core and a volumetric glow accumulated
// along each ray. Heavy on purpose: this is part of the load.

const float PI = 3.14159265359;
const vec3 CHERENKOV = vec3(0.16, 0.55, 1.0);
const vec3 CORE_WHITE = vec3(0.75, 0.92, 1.0);
const vec3 HAZARD = vec3(1.0, 0.72, 0.08);

const float MAT_FLOOR = 1.0;
const float MAT_RING = 2.0;
const float MAT_COLUMN = 3.0;
const float MAT_CORE = 4.0;
const float MAT_WALL = 5.0;
const float MAT_PEDESTAL = 6.0;
const float MAT_HALO = 7.0;

const vec3 CORE_POS = vec3(0.0, 1.45, 0.0);

float gTime;

mat2 rot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, -s, s, c);
}

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float hash31(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash31(i), n100 = hash31(i + vec3(1, 0, 0));
    float n010 = hash31(i + vec3(0, 1, 0)), n110 = hash31(i + vec3(1, 1, 0));
    float n001 = hash31(i + vec3(0, 0, 1)), n101 = hash31(i + vec3(1, 0, 1));
    float n011 = hash31(i + vec3(0, 1, 1)), n111 = hash31(i + vec3(1, 1, 1));
    return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
               mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; ++i) {
        v += a * noise3(p);
        p = p * 2.03 + vec3(1.7, 9.2, 3.1);
        a *= 0.5;
    }
    return v;
}

float sdTorus(vec3 p, vec2 t) {
    vec2 q = vec2(length(p.xz) - t.x, p.y);
    return length(q) - t.y;
}

float sdCylinder(vec3 p, float r, float h) {
    vec2 d = abs(vec2(length(p.xz), p.y)) - vec2(r, h);
    return min(max(d.x, d.y), 0.0) + length(max(d, 0.0));
}

// Core brightness, pulsing like a heartbeat under load.
float corePulse() {
    float beat = pow(0.5 + 0.5 * sin(gTime * 2.4), 6.0);
    return 1.0 + 0.6 * beat;
}

// The ring's frame: tilted, spinning around its own axis.
vec3 ringSpace(vec3 p) {
    vec3 q = p - CORE_POS;
    q.xy *= rot(0.42);
    q.yz *= rot(0.18 * sin(gTime * 0.3));
    q.xz *= rot(gTime * 0.55);
    return q;
}

// Distance and material of the nearest surface.
vec2 map(vec3 p) {
    // Floor, with a shallow pit under the pedestal.
    vec2 res = vec2(p.y, MAT_FLOOR);

    // Core: a sphere whose surface boils.
    vec3 c = p - CORE_POS;
    float boil = 0.035 * sin(9.0 * c.x + gTime * 3.1) * sin(9.0 * c.y - gTime * 2.3) * sin(9.0 * c.z + gTime * 1.7);
    // The boil steepens the field; shortening the step keeps rays from passing into the core.
    float core = (length(c) - 0.52 - boil) * 0.7;
    if (core < res.x) res = vec2(core, MAT_CORE);

    // Ring: a torus with spikes along it, twisting slowly.
    vec3 r = ringSpace(p);
    float major = atan(r.z, r.x);
    vec2 tube = vec2(length(r.xz) - 1.3, r.y);
    float minor = atan(tube.y, tube.x);
    float spikes = pow(abs(sin(major * 36.0 + minor * 2.0) * sin(minor * 5.0 + gTime)), 6.0);
    float ring = (length(tube) - 0.11 - 0.09 * spikes) * 0.6;
    if (ring < res.x) res = vec2(ring, MAT_RING);

    // Pedestal under the core.
    float pedestal = sdCylinder(p - vec3(0.0, 0.25, 0.0), 0.85, 0.25) - 0.03;
    pedestal = min(pedestal, sdCylinder(p - vec3(0.0, 0.62, 0.0), 0.35, 0.14) - 0.02);
    if (pedestal < res.x) res = vec2(pedestal, MAT_PEDESTAL);

    // Six columns, folded into one sector.
    vec3 q = p;
    float sector = 2.0 * PI / 6.0;
    float a = mod(atan(q.z, q.x) + sector * 0.5, sector) - sector * 0.5;
    q.xz = vec2(cos(a), sin(a)) * length(q.xz);
    q.x -= 2.35;
    float column = sdCylinder(q - vec3(0.0, 1.7, 0.0), 0.13, 1.7);
    column = min(column, sdCylinder(q - vec3(0.0, 0.08, 0.0), 0.24, 0.08));
    if (column < res.x) res = vec2(column, MAT_COLUMN);

    // Halo ring tying the columns together at the top.
    float halo = sdTorus(p - vec3(0.0, 3.35, 0.0), vec2(2.35, 0.045));
    if (halo < res.x) res = vec2(halo, MAT_HALO);

    // The hall's wall: a cylinder seen from inside.
    float wall = 9.0 - length(p.xz);
    if (wall < res.x) res = vec2(wall, MAT_WALL);

    return res;
}

vec3 normalAt(vec3 p) {
    const vec2 k = vec2(1.0, -1.0);
    const float e = 0.0015;
    return normalize(k.xyy * map(p + k.xyy * e).x + k.yyx * map(p + k.yyx * e).x +
                     k.yxy * map(p + k.yxy * e).x + k.xxx * map(p + k.xxx * e).x);
}

// Glow of the core and the ring at a point in the air.
vec3 emissionAt(vec3 p) {
    float dCore = length(p - CORE_POS);
    vec3 r = ringSpace(p);
    float dRing = length(vec2(length(r.xz) - 1.3, r.y));
    float pulse = corePulse();
    return CHERENKOV * (0.035 * pulse / (0.08 + dCore * dCore)) + CHERENKOV * (0.0035 / (0.02 + dRing * dRing));
}

// March until a surface, gathering the glow of the air on the way.
vec2 march(vec3 ro, vec3 rd, int steps, out float t, out vec3 glow) {
    t = 0.0;
    glow = vec3(0.0);
    vec2 hit = vec2(-1.0);
    for (int i = 0; i < steps; ++i) {
        vec3 p = ro + rd * t;
        vec2 h = map(p);
        float stepLength = max(h.x, 0.004);
        glow += emissionAt(p) * min(stepLength, 0.25);
        if (h.x < 0.0008 * t) {
            hit = h;
            break;
        }
        t += h.x;
        if (t > 30.0) break;
    }
    return hit;
}

float softShadow(vec3 p, vec3 toLight, float maxT) {
    float s = 1.0;
    float t = 0.03;
    for (int i = 0; i < 20; ++i) {
        vec2 h = map(p + toLight * t);
        if (h.y == MAT_CORE) break;  // reached the light itself
        s = min(s, 10.0 * h.x / t);
        t += clamp(h.x, 0.03, 0.4);
        if (s < 0.01 || t > maxT) break;
    }
    return clamp(s, 0.0, 1.0);
}

// Radiation trefoil, centred on the origin: 1 inside the paint.
float trefoil(vec2 p) {
    float r = length(p);
    float a = atan(p.y, p.x) + PI / 2.0;
    float blade = step(0.5, fract(a / (2.0 * PI / 3.0) + 1.0 / 6.0 + 0.5));
    return blade * step(1.05, r) * step(r, 1.95);
}

// Floor paint and finish: base steel, hex plating, trefoil and hazard band.
vec3 floorAlbedo(vec3 p, out float roughness) {
    vec2 uv = p.xz;
    // Hexagonal plate seams.
    vec2 h = uv * 2.2;
    vec2 g = vec2(h.x + h.y * 0.57735, h.y * 1.1547);
    vec2 cell = fract(g) - 0.5;
    float seam = smoothstep(0.46, 0.5, max(abs(cell.x), abs(cell.y)));
    float wear = fbm(vec3(uv * 3.0, 1.0));
    vec3 steel = mix(vec3(0.05, 0.06, 0.07), vec3(0.11, 0.12, 0.13), wear) * (1.0 - 0.6 * seam);
    roughness = mix(0.25, 0.55, wear) + 0.3 * seam;

    float r = length(uv);
    vec2 ruv = rot(gTime * 0.02) * uv;
    float paint = trefoil(ruv);
    // Hazard band: diagonal stripes between two circles.
    float band = step(3.1, r) * step(r, 3.45);
    float stripes = step(0.5, fract((atan(uv.y, uv.x) * 18.0 + r * 3.0) / PI));
    float scuff = smoothstep(0.35, 0.7, fbm(vec3(uv * 6.0, 4.0)));
    vec3 color = steel;
    color = mix(color, HAZARD * 0.55 * (0.7 + 0.3 * scuff), paint * (1.0 - 0.4 * scuff));
    color = mix(color, mix(vec3(0.02), HAZARD * 0.5, stripes), band);
    roughness = mix(roughness, 0.6, max(paint, band));
    return color;
}

vec3 shade(vec3 p, vec3 rd, vec3 n, float mat, out float reflectivity) {
    vec3 toCore = CORE_POS - p;
    float distCore = length(toCore);
    vec3 l = toCore / distCore;
    float pulse = corePulse();
    vec3 lightColor = CORE_WHITE * mix(CHERENKOV, vec3(1.0), 0.35) * 9.0 * pulse / (1.0 + distCore * distCore);

    vec3 albedo = vec3(0.08);
    float roughness = 0.4;
    vec3 emission = vec3(0.0);
    reflectivity = 0.0;

    if (mat == MAT_CORE) {
        float rim = 1.0 - max(dot(n, -rd), 0.0);
        float veins = fbm(p * 4.5 + vec3(0.0, gTime * 0.9, 0.0));
        // Kept below the tone curve's shoulder so it stays blue instead of clipping to white.
        vec3 hot = CHERENKOV * 1.3 + CORE_WHITE * 2.2 * pow(veins, 2.5);
        return mix(hot, CHERENKOV * 2.6, pow(rim, 1.2)) * pulse;
    } else if (mat == MAT_FLOOR) {
        albedo = floorAlbedo(p, roughness);
        reflectivity = 0.28 * (1.0 - roughness);
    } else if (mat == MAT_RING) {
        albedo = vec3(0.35, 0.38, 0.42);
        roughness = 0.2;
        reflectivity = 0.6;
        vec3 r = ringSpace(p);
        float tip = smoothstep(0.13, 0.19, length(vec2(length(r.xz) - 1.3, r.y)));
        emission = CHERENKOV * 2.5 * tip * pulse;
    } else if (mat == MAT_COLUMN) {
        albedo = vec3(0.12, 0.13, 0.15);
        roughness = 0.35;
        reflectivity = 0.3;
        // Emissive strips running up the columns, flowing.
        float strip = smoothstep(0.02, 0.0, abs(fract(p.y * 1.2 - gTime * 0.4) - 0.5) - 0.06);
        float facing = smoothstep(0.2, 0.9, dot(n, normalize(vec3(-p.x, 0.0, -p.z))));
        emission = CHERENKOV * 1.6 * strip * facing;
    } else if (mat == MAT_PEDESTAL) {
        albedo = vec3(0.1, 0.1, 0.11);
        roughness = 0.3;
        reflectivity = 0.25;
        float groove = smoothstep(0.46, 0.49, abs(fract(atan(p.z, p.x) * 24.0 / (2.0 * PI)) - 0.5));
        emission = CHERENKOV * 0.9 * groove * step(p.y, 0.45);
    } else if (mat == MAT_HALO) {
        albedo = vec3(0.15);
        emission = CHERENKOV * 0.7;
    } else if (mat == MAT_WALL) {
        float y = p.y;
        float panel = step(0.04, fract(y * 0.5)) * step(0.02, fract(atan(p.z, p.x) * 8.0 / PI));
        albedo = vec3(0.03, 0.035, 0.04) * (0.6 + 0.4 * panel);
        roughness = 0.6;
        // Lamp strips, broken into segments, above and at waist height.
        float segments = step(0.25, fract(atan(p.z, p.x) * 12.0 / PI));
        float lamp = (smoothstep(0.07, 0.0, abs(y - 7.0)) + smoothstep(0.04, 0.0, abs(y - 1.8)) * 0.6) * segments;
        emission = mix(CHERENKOV, vec3(1.0), 0.3) * lamp * 0.9;
    }

    float diffuse = max(dot(n, l), 0.0);
    float shadow = diffuse > 0.0 && mat != MAT_WALL ? softShadow(p + n * 0.01, l, distCore - 0.55) : 1.0;
    vec3 h = normalize(l - rd);
    float specPower = mix(256.0, 8.0, roughness);
    float spec = pow(max(dot(n, h), 0.0), specPower) * (specPower + 8.0) / 25.0;
    vec3 ambient = CHERENKOV * 0.012 * (0.5 + 0.5 * n.y);
    return albedo * (lightColor * diffuse * shadow + ambient) + lightColor * spec * shadow * 0.15 + emission;
}

vec3 renderRay(vec3 ro, vec3 rd) {
    float t;
    vec3 glow;
    vec2 hit = march(ro, rd, 140, t, glow);
    vec3 color = vec3(0.0);
    if (hit.x >= 0.0) {
        vec3 p = ro + rd * t;
        vec3 n = normalAt(p);
        float reflectivity;
        color = shade(p, rd, n, hit.y, reflectivity);
        if (reflectivity > 0.0) {
            vec3 rr = reflect(rd, n);
            float t2;
            vec3 glow2;
            vec2 hit2 = march(p + n * 0.02, rr, 64, t2, glow2);
            vec3 reflected = glow2;
            if (hit2.x >= 0.0) {
                vec3 p2 = p + n * 0.02 + rr * t2;
                float unused;
                reflected += shade(p2, rr, normalAt(p2), hit2.y, unused);
            }
            float fresnel = reflectivity + (1.0 - reflectivity) * pow(1.0 - max(dot(-rd, n), 0.0), 5.0);
            color = mix(color, reflected, fresnel * reflectivity * 1.6);
        }
        color *= exp(-0.02 * t);
    }
    return color + glow;
}

// ACES filmic curve (Narkowicz fit).
vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// The finished pixel, display-ready (gamma encoded).
vec3 reactorScene(vec2 fragCoord, vec2 resolution, float time) {
    gTime = time;
    vec2 uv = (fragCoord - 0.5 * resolution) / resolution.y;

    // Camera: sways between two columns (they frame the shot instead of
    // crossing it), drifting in and out and up and down.
    // Columns stand every 60 degrees; +-14 degrees keeps the two nearest at the sides.
    float angle = 0.25 * sin(time * 0.13);
    float radius = 7.2 + 0.6 * sin(time * 0.11);
    vec3 ro = vec3(sin(angle) * radius, 4.4 + 0.5 * sin(time * 0.21), cos(angle) * radius);
    vec3 target = vec3(0.0, 1.0, 0.0);
    vec3 forward = normalize(target - ro);
    vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), forward));
    vec3 up = cross(forward, right);
    vec3 rd = normalize(uv.x * right + uv.y * up + 1.25 * forward);

    vec3 color = renderRay(ro, rd);

    // Film finish: exposure, tone map, vignette, grain, gamma.
    color = aces(color * 1.1);
    vec2 q = fragCoord / resolution;
    color *= 0.35 + 0.65 * pow(16.0 * q.x * q.y * (1.0 - q.x) * (1.0 - q.y), 0.22);
    color += (hash21(fragCoord + fract(time) * 91.7) - 0.5) * 0.02;
    return pow(max(color, 0.0), vec3(1.0 / 2.2));
}
