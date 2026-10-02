#include "cpu/kernel_table.h"

#include <sys/auxv.h>

#include <array>

namespace stress {
namespace {

constexpr uint32_t kL1Buffer = 16 * 1024;          // fits the 64 KiB L1D of both core types
constexpr uint32_t kL2Buffer = 256 * 1024;         // past L1, inside the L2 of an A715
constexpr uint32_t kDramBuffer = 32 * 1024 * 1024; // far past the last-level cache

// Operation counts follow the instruction blocks in tools/gen_kernels.py.
constexpr std::array<KernelSpec, 15> kTable{{
    // key          code   kernel                 data            buffer             step neg  ops/step        unit    feature
    {"dry",         "K0",  stress_k_dry,          DataKind::I8,   64,                0,   0,   4,              "OP",   CpuFeature::None},
    {"fp32_reg",    "C1",  stress_k_fp32_reg,     DataKind::F32,  25 * 16,           0,   0,   24 * 4 * 2,     "FLOP", CpuFeature::None},
    {"fp32_gemm",   "C2",  stress_k_fp32_gemm,    DataKind::F32,  200 * 80,          80,  32,  24 * 4 * 2,     "FLOP", CpuFeature::None},
    {"fp64_gemm",   "C3",  stress_k_fp64_gemm,    DataKind::F64,  144 * 112,         112, 64,  24 * 2 * 2,     "FLOP", CpuFeature::None},
    {"bf16_mmla",   "C4",  stress_k_bf16_mmla,    DataKind::BF16, kL1Buffer,         128, 64,  16 * 32,        "FLOP", CpuFeature::Bf16},
    {"i8_mmla",     "C5",  stress_k_i8_mmla,      DataKind::I8,   kL1Buffer,         128, 0,   16 * 64,        "OP",   CpuFeature::I8mm},
    {"i8_dot",      "C6",  stress_k_i8_dot,       DataKind::I8,   200 * 80,          80,  0,   24 * 32,        "OP",   CpuFeature::DotProd},
    {"mixed",       "C7",  stress_k_mixed,        DataKind::F32,  200 * 80,          80,  32,  24 * 4 * 2,     "FLOP", CpuFeature::None},
    {"fp32_l2",     "C8",  stress_k_fp32_stream,  DataKind::F32,  kL2Buffer,         64,  32,  16 * 4 * 2,     "FLOP", CpuFeature::None},
    {"fp32_dram",   "C9",  stress_k_fp32_stream,  DataKind::F32,  kDramBuffer,       64,  32,  16 * 4 * 2,     "FLOP", CpuFeature::None},
    {"memcopy",     "C10", stress_k_memcopy,      DataKind::I8,   kDramBuffer,       128, 0,   128,            "B",    CpuFeature::None},
    // C8 won the first sweep; these sizes look for the buffer that draws the most.
    {"fp32_s128k",  "C11", stress_k_fp32_stream,  DataKind::F32,  128 * 1024,        64,  32,  16 * 4 * 2,     "FLOP", CpuFeature::None},
    {"fp32_s512k",  "C12", stress_k_fp32_stream,  DataKind::F32,  512 * 1024,        64,  32,  16 * 4 * 2,     "FLOP", CpuFeature::None},
    {"fp32_s1m",    "C13", stress_k_fp32_stream,  DataKind::F32,  1024 * 1024,       64,  32,  16 * 4 * 2,     "FLOP", CpuFeature::None},
    {"fp32_s2m",    "C14", stress_k_fp32_stream,  DataKind::F32,  2 * 1024 * 1024,   64,  32,  16 * 4 * 2,     "FLOP", CpuFeature::None},
}};

constexpr bool isValid(const KernelSpec& k) {
    if (k.bufferBytes == 0) return false;
    if (k.stepBytes == 0) return k.negatedBytes == 0;
    if (k.bufferBytes % k.stepBytes != 0) return false;
    if (k.negatedBytes > k.stepBytes) return false;
    // A mirrored buffer needs an even number of steps so each half is whole.
    if (k.negatedBytes != 0 && k.steps() % 2 != 0) return false;
    return true;
}

constexpr bool allValid() {
    for (const auto& k : kTable) {
        if (!isValid(k)) return false;
    }
    return true;
}

static_assert(allValid(), "kernel table entry violates the buffer layout rules");

}  // namespace

std::span<const KernelSpec> kernelTable() { return kTable; }

const KernelSpec* kernelAt(int index) {
    if (index < 0 || static_cast<size_t>(index) >= kTable.size()) return nullptr;
    return &kTable[static_cast<size_t>(index)];
}

bool kernelSupported(const KernelSpec& spec) {
    // Linux's arm64 hwcap bits (asm/hwcap.h), spelled out so an older sysroot still builds.
    constexpr unsigned long kHwcapAsimdDp = 1UL << 20;
    constexpr unsigned long kHwcap2I8mm = 1UL << 13;
    constexpr unsigned long kHwcap2Bf16 = 1UL << 14;
    static const unsigned long hwcap = getauxval(AT_HWCAP);
    static const unsigned long hwcap2 = getauxval(AT_HWCAP2);
    switch (spec.feature) {
        case CpuFeature::None: return true;
        case CpuFeature::DotProd: return (hwcap & kHwcapAsimdDp) != 0;
        case CpuFeature::I8mm: return (hwcap2 & kHwcap2I8mm) != 0;
        case CpuFeature::Bf16: return (hwcap2 & kHwcap2Bf16) != 0;
    }
    return false;
}

}  // namespace stress
