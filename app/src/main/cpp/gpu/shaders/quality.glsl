// The scene's quality setting (Low, Medium, High) reaches the shaders as
// specialization constants, so each pipeline is compiled for its own level
// and a loop bound by one costs nothing beyond its iterations. The desktop
// preview (no specialization) sees the Medium values.
#ifdef VULKAN
#define QUALITY_CONSTANT(name, value) layout(constant_id = 0) const int name = value;
#else
#define QUALITY_CONSTANT(name, value) const int name = value;
#endif

// Where the s-th of `count` samples of a pixel sits, relative to the frame's
// jitter: a rotated grid whose offsets sum to zero, so the TAA's
// reprojection still finds the pixel where the jitter put it.
vec2 sampleOffset(int s, int count) {
    if (count == 2) return s == 0 ? vec2(0.25, 0.25) : vec2(-0.25, -0.25);
    if (count == 4) {
        if (s == 0) return vec2(0.125, 0.375);
        if (s == 1) return vec2(0.375, -0.125);
        if (s == 2) return vec2(-0.125, -0.375);
        return vec2(-0.375, 0.125);
    }
    return vec2(0.0);
}
