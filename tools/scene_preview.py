"""Renders the reactor scene on the development machine's GPU.

The scene (app/src/main/cpp/gpu/shaders/reactor.glsl) is plain GLSL shared
with the phone's renderer; this wraps it for desktop OpenGL so it can be
looked at, frame by frame, without a phone:

    python tools/scene_preview.py [--times 0,4,8] [--width 632] [--height 1368]

Frames land in tools/out/preview/. Needs `pip install moderngl pillow numpy`.
"""
import argparse
import pathlib
import time

import moderngl
import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCENE = ROOT / "app/src/main/cpp/gpu/shaders/reactor.glsl"
OUT = ROOT / "tools/out/preview"

VERTEX = """
#version 430
in vec2 position;
void main() { gl_Position = vec4(position, 0.0, 1.0); }
"""

FRAGMENT_HEAD = """
#version 430
uniform vec2 resolution;
uniform float time;
out vec4 fragColor;
"""

FRAGMENT_TAIL = """
void main() { fragColor = vec4(reactorScene(gl_FragCoord.xy, resolution, time), 1.0); }
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--times", default="0,3,6,9")
    parser.add_argument("--width", type=int, default=632)
    parser.add_argument("--height", type=int, default=1368)
    args = parser.parse_args()

    ctx = moderngl.create_standalone_context(require=430)
    program = ctx.program(vertex_shader=VERTEX, fragment_shader=FRAGMENT_HEAD + SCENE.read_text() + FRAGMENT_TAIL)
    quad = ctx.buffer(np.array([-1, -1, 3, -1, -1, 3], dtype="f4").tobytes())
    vao = ctx.vertex_array(program, [(quad, "2f", "position")])
    fbo = ctx.simple_framebuffer((args.width, args.height), components=4)
    fbo.use()
    program["resolution"].value = (args.width, args.height)
    OUT.mkdir(parents=True, exist_ok=True)

    for t in [float(x) for x in args.times.split(",")]:
        program["time"].value = t
        ctx.finish()
        start = time.perf_counter()
        vao.render(moderngl.TRIANGLES)
        ctx.finish()
        ms = (time.perf_counter() - start) * 1000
        pixels = np.frombuffer(fbo.read(components=3), dtype=np.uint8).reshape(args.height, args.width, 3)
        path = OUT / f"reactor_t{t:05.1f}.png"
        Image.fromarray(pixels[::-1]).save(path)
        print(f"{path.name}  {ms:.1f} ms")


if __name__ == "__main__":
    main()
