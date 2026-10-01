"""Renders the scenes on the development machine's GPU.

The scenes (app/src/main/cpp/gpu/shaders/*.glsl) are plain GLSL shared with
the phone's renderer; this runs them on desktop OpenGL so they can be looked
at, frame by frame, without a phone:

    python tools/scene_preview.py [--scene pool] [--times 0,4,8]
                                  [--width 632] [--height 1368] [--frames 16]

`pool` is written for temporal accumulation, so each image averages
`--frames` jittered, reseeded frames of the same moment: what the phone's
history holds once it has settled. Everything else is the phone's own
shaders: the water surface and the particles are simulated from time 0
(water_*.comp, particles.comp) and the particles drawn as points
(particles.vert/frag); the average goes through the bloom chain, the depth of
field and the final pass (bloom_*.frag, dof.frag, final.frag) at the phone's
screen size, twice the scene's. Frames land in
tools/out/preview/, with a side-by-side sheet when there are several.
Needs `pip install moderngl pillow numpy`.
"""
import argparse
import pathlib
import re
import struct
import time

import moderngl
import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
SHADERS = ROOT / "app/src/main/cpp/gpu/shaders"
RENDERER = ROOT / "app/src/main/cpp/gpu/scene_renderer.cpp"
RENDERER_HEADER = ROOT / "app/src/main/cpp/gpu/scene_renderer.h"
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
uniform sampler2D water;
#define POOL_WATER water
layout(location = 0) out vec4 fragColor;
layout(location = 1) out float fragDistance;
"""

TAILS = {
    "pool": "void main() { float d; fragColor = vec4(renderPool(gl_FragCoord.xy, resolution, time, frame, jitter, d), 1.0);"
            " fragDistance = d; }",
}


def gl_source(name):
    """One of the phone's shaders, for desktop OpenGL: includes inlined,
    descriptor sets dropped, push constants as uniform block 0."""
    lines = []
    for line in (SHADERS / name).read_text().splitlines():
        if line.startswith("#version"):
            lines.append("#version 430")
        elif line.startswith("#extension GL_GOOGLE_include_directive"):
            continue
        elif line.startswith("#include"):
            lines.append(gl_source(line.split('"')[1]))
        elif "flat in int layer" in line:
            continue  # fullscreen.vert's output; the desktop vertex shader has none
        else:
            line = re.sub(r"\bset\s*=\s*\d+\s*,\s*", "", line) if "layout(" in line else line
            lines.append(line.replace("layout(push_constant)", "layout(std140, binding = 0)")
                         .replace("gl_VertexIndex", "gl_VertexID").replace("gl_InstanceIndex", "gl_InstanceID"))
    return "\n".join(lines)


def renderer_constant(name):
    """A float constant of SceneRenderer, read from its source: one place to change it."""
    return float(re.search(rf"{name} = ([0-9.]+)f;", RENDERER.read_text()).group(1))


def renderer_count(name):
    """An integer constant of SceneRenderer's header."""
    return int(re.search(rf"{name} = (\d+);", RENDERER_HEADER.read_text()).group(1))


class Water:
    """The pool's surface: the renderer's simulation, a fixed step at a time."""

    N = 256  # water_params.glsl: WATER_N
    HZ = 60.0  # WATER_HZ

    def __init__(self, ctx):
        self.ctx = ctx
        self.stepper = ctx.compute_shader(gl_source("water_step.comp"))
        self.deriver = ctx.compute_shader(gl_source("water_surface.comp"))
        still = np.zeros((self.N, self.N), dtype="f4").tobytes()
        # Heights: a step before, now, next; they take turns, as on the phone.
        self.heights = [ctx.texture((self.N, self.N), 1, still, dtype="f4") for _ in range(3)]
        self.surface = ctx.texture((self.N, self.N), 4, dtype="f2")
        self.surface.repeat_x = self.surface.repeat_y = False
        self.params = ctx.buffer(reserve=16)
        self.steps = 0

    def advance(self, seconds):
        """Steps on to `seconds`, then derives the surface the scene samples."""
        groups = self.N // 8
        while self.steps < int(seconds * self.HZ):
            self.params.write(struct.pack("<4I", self.steps, 0, 0, 0))
            self.params.bind_to_uniform_block(0)
            i = self.steps % 3
            self.heights[(i + 1) % 3].bind_to_image(0, read=True, write=False)
            self.heights[i].bind_to_image(1, read=True, write=False)
            self.heights[(i + 2) % 3].bind_to_image(2, read=False, write=True)
            self.stepper.run(groups, groups)
            self.ctx.memory_barrier()
            self.steps += 1
        self.heights[(self.steps + 1) % 3].bind_to_image(0, read=True, write=False)
        self.surface.bind_to_image(3, read=False, write=True)
        self.deriver.run(groups, groups)
        self.ctx.memory_barrier()


class Particles:
    """The renderer's particles: a physics step a frame (60 Hz here), then drawn as points."""

    HZ = 60.0

    def __init__(self, ctx, scene):
        self.ctx = ctx
        self.count = renderer_count("kParticles")
        self.scene = scene
        self.stepper = ctx.compute_shader(gl_source("particles.comp"))
        draw = ctx.program(vertex_shader=gl_source("particles.vert"), fragment_shader=gl_source("particles.frag"))
        self.vao = ctx.vertex_array(draw, [])
        self.buffer = ctx.buffer(reserve=self.count * 32)
        self.params = ctx.buffer(reserve=32)
        self.target = ctx.texture(scene, 4, dtype="f2")
        self.target.repeat_x = self.target.repeat_y = False
        self.fbo = ctx.framebuffer(color_attachments=[self.target])
        self.frames = 0

    def write(self, t, dt):
        self.params.write(struct.pack("<4f4I", self.scene[0], self.scene[1], t, dt, self.count, self.frames, 0, 0))
        self.params.bind_to_uniform_block(0)
        self.buffer.bind_to_storage_buffer(0)

    def advance(self, seconds):
        """Steps on to `seconds`; the first step gives every particle its start."""
        while self.frames <= int(seconds * self.HZ):
            self.write(self.frames / self.HZ, 0.0 if self.frames == 0 else 1.0 / self.HZ)
            self.stepper.run((self.count + 63) // 64)
            self.ctx.memory_barrier()
            self.frames += 1

    def draw(self, t, distance):
        """The points at time `t`, depth-tested against the scene's `distance`."""
        self.fbo.use()
        self.fbo.clear(0.0, 0.0, 0.0, 0.0)
        self.ctx.enable(moderngl.BLEND | moderngl.PROGRAM_POINT_SIZE)
        self.ctx.blend_func = moderngl.ONE, moderngl.ONE
        self.write(t, 0.0)
        distance.use(location=1)
        self.vao.render(moderngl.POINTS, vertices=self.count)
        self.ctx.disable(moderngl.BLEND | moderngl.PROGRAM_POINT_SIZE)
        return self.target


class Post:
    """The renderer's chain after the history: bloom down and up, depth of field, then the final picture."""

    LEVELS = 5  # SceneRenderer::kBloomLevels

    def __init__(self, ctx, quad, scene, screen):
        self.ctx = ctx

        def program(name):
            p = ctx.program(vertex_shader=VERTEX, fragment_shader=gl_source(name))
            return ctx.vertex_array(p, [(quad, "2f", "position")])

        self.down = program("bloom_down.frag")
        self.up = program("bloom_up.frag")
        self.dof = program("dof.frag")
        self.final = program("final.frag")
        self.params = ctx.buffer(reserve=32)
        self.none = self.target(scene, np.zeros((scene[1], scene[0], 4), dtype="f2"))  # the phone's particle layer
        self.levels = []
        size = scene
        for _ in range(self.LEVELS):
            size = (max(1, size[0] // 2), max(1, size[1] // 2))
            texture = self.target(size)
            self.levels.append((texture, ctx.framebuffer(color_attachments=[texture])))
        self.blurred = self.target(self.levels[0][0].size)
        self.blurred_fbo = ctx.framebuffer(color_attachments=[self.blurred])
        self.screen = ctx.framebuffer(color_attachments=[ctx.texture(screen, 4)])
        self.threshold = renderer_constant("kBloomThreshold")
        self.strength = renderer_constant("kBloomStrength")

    def target(self, size, data=None):
        texture = self.ctx.texture(size, 4, None if data is None else data.tobytes(), dtype="f2")
        texture.repeat_x = texture.repeat_y = False
        return texture

    def run(self, history, distance, particles, t):
        """`history`: the scene, linear HDR, at the scene size; `distance`: each pixel's
        distance along its ray; `particles`: their layer, or None. Returns the screen as 8-bit RGB."""
        ubo = self.params
        particles = particles or self.none
        source = history
        for i, (texture, fbo) in enumerate(self.levels):
            fbo.use()
            ubo.write(struct.pack("<8f", 1 / source.width, 1 / source.height, texture.width, texture.height,
                                  self.threshold, 1.0 if i == 0 else 0.0, 0.0, 0.0))
            ubo.bind_to_uniform_block(0)
            source.use(location=0)
            particles.use(location=1)
            self.down.render(moderngl.TRIANGLES)
            source = texture
        self.ctx.enable(moderngl.BLEND)
        self.ctx.blend_func = moderngl.ONE, moderngl.ONE
        for i in range(self.LEVELS - 2, -1, -1):
            texture, fbo = self.levels[i]
            smaller = self.levels[i + 1][0]
            fbo.use()
            ubo.write(struct.pack("<8f", 1 / smaller.width, 1 / smaller.height, texture.width, texture.height,
                                  0.0, 0.0, 1.0, 1.0))
            ubo.bind_to_uniform_block(0)
            smaller.use(location=0)
            self.up.render(moderngl.TRIANGLES)
        self.ctx.disable(moderngl.BLEND)

        self.blurred_fbo.use()
        ubo.write(struct.pack("<8f", 1 / history.width, 1 / history.height, self.blurred.width, self.blurred.height,
                              t, history.height, 0.0, 0.0))
        ubo.bind_to_uniform_block(0)
        history.use(location=0)
        distance.use(location=1)
        self.dof.render(moderngl.TRIANGLES)

        self.screen.use()
        width, height = self.screen.size
        ubo.write(struct.pack("<4f", width, height, t, self.strength) + bytes(16))
        ubo.bind_to_uniform_block(0)
        history.use(location=0)
        self.levels[0][0].use(location=1)
        particles.use(location=2)
        self.blurred.use(location=3)
        distance.use(location=4)
        self.final.render(moderngl.TRIANGLES)
        rgb = np.frombuffer(self.screen.read(components=3), dtype=np.uint8).reshape(height, width, 3)
        return rgb[::-1]


# R2 low-discrepancy sequence: well spread sub-pixel offsets, as the renderer uses.
def jitter(i):
    g = 1.32471795724474602596
    return ((0.5 + i / g) % 1.0) - 0.5, ((0.5 + i / (g * g)) % 1.0) - 0.5


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--scene", default="pool", choices=sorted(TAILS))
    parser.add_argument("--times", default="0,6,12,18")
    parser.add_argument("--width", type=int, default=632)
    parser.add_argument("--height", type=int, default=1368)
    parser.add_argument("--frames", type=int, default=16)
    parser.add_argument("--no-particles", action="store_true")
    args = parser.parse_args()

    ctx = moderngl.create_standalone_context(require=430)
    source = (SHADERS / f"{args.scene}.glsl").read_text()
    program = ctx.program(vertex_shader=VERTEX, fragment_shader=HEAD + source + TAILS[args.scene])
    quad = ctx.buffer(np.array([-1, -1, 3, -1, -1, 3], dtype="f4").tobytes())
    vao = ctx.vertex_array(program, [(quad, "2f", "position")])
    scene = (args.width, args.height)
    target = ctx.texture(scene, 4, dtype="f4")
    distance = ctx.texture(scene, 1, dtype="f4")
    fbo = ctx.framebuffer(color_attachments=[target, distance])
    program["resolution"].value = scene
    post = Post(ctx, quad, scene, (args.width * 2, args.height * 2))
    OUT.mkdir(parents=True, exist_ok=True)
    water = Water(ctx)
    particles = None if args.no_particles else Particles(ctx, scene)

    frames = args.frames
    images = []
    for t in [float(x) for x in args.times.split(",")]:
        if t * Water.HZ < water.steps:
            water = Water(ctx)  # the simulations only run forward
            particles = particles and Particles(ctx, scene)
        water.advance(t)
        if particles:
            particles.advance(t)
        water.surface.use(location=0)
        if "water" in program:
            program["water"].value = 0
        program["time"].value = t
        fbo.use()
        total = np.zeros((args.height, args.width, 4), dtype=np.float64)
        ctx.finish()
        start = time.perf_counter()
        for i in range(frames):
            if "frame" in program:
                program["frame"].value = i
            if "jitter" in program:
                program["jitter"].value = jitter(i)
            vao.render(moderngl.TRIANGLES)
            total += np.frombuffer(fbo.read(components=4, dtype="f4"), dtype=np.float32).reshape(args.height, args.width, 4)
        ms = (time.perf_counter() - start) * 1000 / frames
        hdr = (total / frames).astype(np.float32)
        broken = int((~np.isfinite(hdr)).any(axis=2).sum())
        if broken:
            # A NaN would smear across the bloom; count it, then blank it.
            print(f"  WARNING: {broken} non-finite pixels")
            hdr = np.nan_to_num(hdr, nan=0.0, posinf=0.0, neginf=0.0)
        # The phone keeps one frame's distances, not an average: the last one here.
        history = post.target(scene, hdr.astype("f2"))
        layer = particles.draw(t, distance) if particles else None
        image = post.run(history, distance, layer, t)
        history.release()
        path = OUT / f"{args.scene}_t{t:05.1f}.png"
        Image.fromarray(image).save(path)
        images.append(Image.fromarray(image).resize((args.width, args.height), Image.LANCZOS))
        print(f"{path.name}  {ms:.1f} ms/frame (with readback)")
    if len(images) > 1:
        sheet = np.concatenate([np.asarray(i) for i in images], axis=1)
        Image.fromarray(sheet).save(OUT / f"{args.scene}_sheet.png")


if __name__ == "__main__":
    main()
