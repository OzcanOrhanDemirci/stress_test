// The camera every scene shares. A scene says only where the camera stands,
// what it looks at and how the lens is set (framing()), and which shot a
// moment belongs to (shotAt(): a cut between shots drops TAA's history);
// the passes that follow the camera (TAA's reprojection, the depth of field,
// the particles) go through here. Image coordinates: height 1, centred;
// FOCAL is the distance to the image plane.

const float FOCAL = 1.3;

// The scene's shot at a moment: camera position, the point it looks at, and
// its lens, (focus distance, aperture). The aperture is how far a point at
// infinity blurs, as a fraction of the image height.
void framing(float time, out vec3 from, out vec3 target, out vec2 lens);
int shotAt(float time);

void lookAt(vec3 from, vec3 target, out vec3 position, out vec3 forward, out vec3 right, out vec3 up) {
    position = from;
    forward = normalize(target - from);
    right = normalize(cross(vec3(0.0, 1.0, 0.0), forward));
    up = cross(forward, right);
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
