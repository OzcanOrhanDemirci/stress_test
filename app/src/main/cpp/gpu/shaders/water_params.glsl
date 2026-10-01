// The pool's water surface as a height field: a grid over the pool, solved
// with the wave equation a fixed step at a time (SceneRenderer::WaterParams).
// Shared by the Vulkan renderer and the desktop preview (OpenGL), hence the
// binding macros.
#ifdef VULKAN
#define WATER_SLOT(n) set = 0, binding = n
#define WATER_PUSH layout(push_constant)
#else
#define WATER_SLOT(n) binding = n
#define WATER_PUSH layout(std140, binding = 0)
#endif

WATER_PUSH uniform Water {
    uint step;  // steps since the start: seeds the bursts
    uint pad0;
    uint pad1;
    uint pad2;
} params;

const int WATER_N = 256;
const float WATER_R = 2.4;                        // the grid spans the pool: [-R, R] on x and z
const float WATER_DX = 2.0 * WATER_R / float(WATER_N);
const float WATER_HZ = 60.0;                      // steps a second

// Centre of cell `c`, in metres on the surface.
vec2 waterPosition(ivec2 c) {
    return ((vec2(c) + 0.5) / float(WATER_N) - 0.5) * 2.0 * WATER_R;
}

// Cells the water cannot move in: beyond the pool wall, and inside the four
// control-rod tubes that stand in it.
bool waterSolid(vec2 xz) {
    return length(xz) > WATER_R - WATER_DX || length(abs(xz) - vec2(0.55)) < 0.065;
}

uint waterHash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}
