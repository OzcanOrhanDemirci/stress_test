// The scene and gauge are made upright. Undo the swapchain's pre-rotation
// when looking up a pixel in that upright picture (Vulkan coordinates, y down).
vec2 uprightPixel(vec2 pixel, vec2 size, uint transform) {
    if (transform == 2u || transform == 32u) pixel = vec2(pixel.y, size.y - pixel.x);
    else if (transform == 4u || transform == 64u) pixel = size - pixel;
    else if (transform == 8u || transform == 128u) pixel = vec2(size.x - pixel.y, pixel.x);
    if (transform >= 16u && transform <= 128u) pixel.x = size.x - pixel.x;
    return pixel;
}

// UNORM stores the display-ready SDR values as they are. An sRGB
// attachment encodes its input, so undo that conversion in the shader.
vec3 presentColor(vec3 encoded, uint srgb) {
    if (srgb == 0u) return encoded;
    return mix(pow((encoded + 0.055) / 1.055, vec3(2.4)), encoded / 12.92,
               lessThanEqual(encoded, vec3(0.04045)));
}
