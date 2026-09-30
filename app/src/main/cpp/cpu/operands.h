#pragma once

#include <cstddef>
#include <memory>

#include "cpu/kernel_table.h"

namespace stress {

struct AlignedFree {
    void operator()(void* p) const noexcept;
};

using OperandBuffer = std::unique_ptr<void, AlignedFree>;

// Allocates a 64-byte aligned operand buffer for `spec` and fills it with the
// same pseudo-random operands every time, so every thread computes the same
// digest. Floating point operands lie in [-0.5, 0.5) and are never zero or
// subnormal: multiplying by zero would leave the multipliers idle. When the
// spec asks for a mirror, the second half repeats the first with the leading
// `negatedBytes` of each step negated. Returns nullptr when allocation fails.
OperandBuffer makeOperands(const KernelSpec& spec);

}  // namespace stress
