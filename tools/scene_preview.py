"""Renders the scenes on the development machine's GPU.

The scenes (app/src/main/cpp/gpu/shaders/*.glsl) are plain GLSL shared with
the phone's renderer; this wraps them for desktop OpenGL so they can be looked
at, frame by frame, without a phone:

    python tools/scene_preview.py [--scene pool|reactor] [--times 0,4,8]
                                  [--width 632] [--height 1368] [--frames 16]

`pool` is written for temporal accumulation, so each image averages
`--frames` jittered, reseeded frames of the same moment, then gets the
phone's post-processing (bloom, tone mapping, vignette, grain): what the
phone shows once its history has settled. Frames land in tools/out/preview/.
Needs `pip install moderngl pillow numpy`.
"""
import argparse
import pathlib
import time

import moderngl
import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
SHADERS = ROOT / "app/src/main/cpp/gpu/shaders"
OUT = ROOT / "tools/out/preview"

VERTEX = """
#version 430
in vec2 position;
void main() { gl_Position = vec4(position, 0.0, 1.0); }
"""

HEAD = """
#version 430
uniform vec2 resolution;
uniform float time;
uniform uint frame;
uniform vec2 jitter;
out vec4 fragColor;
"""

TAILS = {
    "reactor": "void main() { fragColor = vec4(reactorScene(gl_FragCoord.xy, resolution, time), 1.0); }",
    "pool": "void main() { float d; fragColor = vec4(renderPool(gl_FragCoord.xy, resolution, time, frame, jitter, d), 1.0); }",
}

# R2 low-discrepancy sequence: well spread sub-pixel offsets, as the renderer uses.
def jitter(i):
    g = 1.32471795724474602596
    return ((0.5 + i / g) % 1.0) - 0.5, ((0.5 + i / (g * g)) % 1.0) - 0.5


def aces(x):
    return np.clip((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0)


def box_blur(img, radius, axis):
    """Mean over a window of 2*radius+1 along one axis, edges clamped."""
    pad = [(0, 0)] * img.ndim
    pad[axis] = (radius + 1, radius)
    c = np.cumsum(np.pad(img, pad, mode="edge"), axis=axis)
    n = img.shape[axis]
    upper = np.take(c, np.arange(2 * radius + 1, 2 * radius + 1 + n), axis=axis)
    lower = np.take(c, np.arange(0, n), axis=axis)
    return (upper - lower) / (2 * radius + 1)


def blur(img, radius):
    """Three box blurs: close to a Gaussian of about that radius."""
    r = max(1, int(radius / 1.7))
    for _ in range(3):
        img = box_blur(box_blur(img, r, 0), r, 1)
    return img


def bloom(hdr):
    """Bright parts, blurred at three widths and added back: the glow round lights."""
    bright = np.clip(hdr - 1.0, 0.0, None)
    scale = hdr.shape[1] / 632
    total = np.zeros_like(hdr)
    for radius, weight in ((4, 0.5), (14, 0.35), (40, 0.25)):
        total += weight * blur(bright, radius * scale)
    return hdr + total


def finish(hdr, seed):
    """The phone's post-processing, in numpy."""
    color = aces(bloom(hdr) * 0.9)
    h, w, _ = color.shape
    y, x = np.mgrid[0:h, 0:w]
    q = np.stack([x / w, y / h], axis=-1)
    vignette = 0.35 + 0.65 * (16 * q[..., 0] * q[..., 1] * (1 - q[..., 0]) * (1 - q[..., 1])) ** 0.22
    color *= vignette[..., None]
    color += (np.random.default_rng(seed).random((h, w, 1)) - 0.5) * 0.015
    return (np.clip(color, 0, 1) ** (1 / 2.2) * 255).astype(np.uint8)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--scene", default="pool", choices=sorted(TAILS))
    parser.add_argument("--times", default="0,6,12,18")
    parser.add_argument("--width", type=int, default=632)
    parser.add_argument("--height", type=int, default=1368)
    parser.add_argument("--frames", type=int, default=16)
    args = parser.parse_args()

    ctx = moderngl.create_standalone_context(require=430)
    source = (SHADERS / f"{args.scene}.glsl").read_text()
    program = ctx.program(vertex_shader=VERTEX, fragment_shader=HEAD + source + TAILS[args.scene])
    quad = ctx.buffer(np.array([-1, -1, 3, -1, -1, 3], dtype="f4").tobytes())
    vao = ctx.vertex_array(program, [(quad, "2f", "position")])
    target = ctx.texture((args.width, args.height), 4, dtype="f4")
    fbo = ctx.framebuffer(color_attachments=[target])
    fbo.use()
    program["resolution"].value = (args.width, args.height)
    OUT.mkdir(parents=True, exist_ok=True)

    frames = args.frames if args.scene == "pool" else 1
    for t in [float(x) for x in args.times.split(",")]:
        program["time"].value = t
        total = np.zeros((args.height, args.width, 3), dtype=np.float64)
        ctx.finish()
        start = time.perf_counter()
        for i in range(frames):
            if "frame" in program:
                program["frame"].value = i
            if "jitter" in program:
                program["jitter"].value = jitter(i)
            vao.render(moderngl.TRIANGLES)
            data = np.frombuffer(fbo.read(components=4, dtype="f4"), dtype=np.float32).reshape(args.height, args.width, 4)
            total += data[::-1, :, :3]
        ms = (time.perf_counter() - start) * 1000 / frames
        hdr = (total / frames).astype(np.float32)
        broken = int((~np.isfinite(hdr)).any(axis=2).sum())
        if broken:
            # A NaN would smear across the bloom; count it, then blank it.
            print(f"  WARNING: {broken} non-finite pixels")
            hdr = np.nan_to_num(hdr, nan=0.0, posinf=0.0, neginf=0.0)
        image = finish(hdr, int(t * 100)) if args.scene == "pool" else (np.clip(hdr, 0, 1) * 255).astype(np.uint8)
        path = OUT / f"{args.scene}_t{t:05.1f}.png"
        Image.fromarray(image).save(path)
        print(f"{path.name}  {ms:.1f} ms/frame (with readback)")


if __name__ == "__main__":
    main()
