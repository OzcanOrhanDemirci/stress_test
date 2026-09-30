#version 450
// One triangle that covers the screen; the instance index tells stacked layers apart.
layout(location = 0) flat out int layer;

void main() {
    vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    layer = gl_InstanceIndex;
}
