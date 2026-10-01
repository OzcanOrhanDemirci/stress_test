// Sizes of the forest's draws, in one place for the shaders, the renderer
// (scene_renderer.cpp includes this file, so it holds only lines that are
// both GLSL and C++) and the desktop preview.
const int FOREST_GRID = 26;   // tree cells a side
const int TERRAIN_N = 140;    // ground quads a side
const int TRUNK_SIDES = 10;
const int TRUNK_RINGS = 14;
const int WHORLS = 16;        // a spruce's whorls of branches
const int PER_WHORL = 5;      // branches a whorl; each carries two cards
