#pragma once

#include <cstdint>
#include <span>

#include "cpu/kernels.h"

namespace stress {

// What the operand buffer holds.
enum class DataKind : uint8_t { F32, F64, BF16, I8 };

// Everything the engine and the app need to know about one kernel. This table
// is the single source: the app reads it through JNI instead of keeping a copy.
struct KernelSpec {
    const char* key;           // stable identifier shared with the app
    const char* code;          // short label shown to the user (K0, C1, ...)
    KernelFn fn;
    DataKind data;
    uint32_t bufferBytes;      // operand buffer size, passed to the kernel as `size`
    uint32_t stepBytes;        // bytes consumed per inner step; 0 = not a streaming kernel
    uint32_t negatedBytes;     // leading bytes of each step negated in the mirrored half; 0 = no mirror
    double opsPerStep;         // work per step (per iteration when stepBytes == 0)
    const char* unit;          // "FLOP", "OP" or "B"

    constexpr uint64_t steps() const { return stepBytes == 0 ? 1 : bufferBytes / stepBytes; }
    constexpr double opsPerIteration() const { return opsPerStep * static_cast<double>(steps()); }
};

std::span<const KernelSpec> kernelTable();

// Returns nullptr when `index` is out of range.
const KernelSpec* kernelAt(int index);

}  // namespace stress
