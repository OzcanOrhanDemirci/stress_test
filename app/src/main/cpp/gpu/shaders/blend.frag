#version 450
// G5: almost no shading, all blending: stacked full-screen layers added into a
// half-float target keep the render back-ends and tile memory busy.
layout(location = 0) flat in int layer;
layout(location = 0) out vec4 color;

void main() {
    color = vec4(0.0011, 0.0007, 0.0003, 0.0) * float(layer + 1);
}
