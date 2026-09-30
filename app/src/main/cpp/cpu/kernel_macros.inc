// Shared assembler macros for the burner kernels.
//
// Every kernel has the same C signature (see kernels.h):
//     void kernel(uint64_t iterations, void* data, uint64_t size, void* digest)
//         x0 = iterations (> 0)   x1 = data   x2 = size in bytes   x3 = digest (16 bytes)
//
// The digest is the XOR of every register that holds kernel state when the
// loop ends. Starting from the same data and running the same number of
// iterations must give the same digest on every core, so a mismatch means the
// hardware computed something wrong.

    .arch armv8.6-a+bf16+i8mm+dotprod

// Declares a global function aligned to a cache line. `hint #34` is BTI C:
// a no-op on cores without branch target identification.
.macro KERNEL name
    .text
    .global \name
    .type \name, %function
    .p2align 6
\name:
    hint #34
.endm

.macro END_KERNEL name
    .size \name, . - \name
.endm

// v8-v15 are callee-saved in their low 64 bits (AAPCS64).
.macro SAVE_SIMD_CALLEE
    stp d8, d9, [sp, #-64]!
    stp d10, d11, [sp, #16]
    stp d12, d13, [sp, #32]
    stp d14, d15, [sp, #48]
.endm

.macro RESTORE_SIMD_CALLEE
    ldp d10, d11, [sp, #16]
    ldp d12, d13, [sp, #32]
    ldp d14, d15, [sp, #48]
    ldp d8, d9, [sp], #64
.endm
