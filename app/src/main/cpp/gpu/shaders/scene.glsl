// The scene whose camera the shared passes (TAA, depth of field, final)
// follow: the pool, or the forest when SCENE_FOREST is defined. The renderer
// builds those passes once for each scene.
#ifdef SCENE_FOREST
#include "forest.glsl"
#else
#include "pool.glsl"
#endif
