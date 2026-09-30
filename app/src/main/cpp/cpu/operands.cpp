#include "cpu/operands.h"

#include <bit>
#include <cstdint>
#include <cstdlib>
#include <cstring>

namespace stress {
namespace {

constexpr uint64_t kSeed = 0x5EED'2026'1001'0001ULL;

// SplitMix64: small, fast and good enough to make operand bits look random.
class SplitMix64 {
public:
    explicit SplitMix64(uint64_t seed) : state_(seed) {}

    uint64_t next() {
        uint64_t z = (state_ += 0x9E3779B97F4A7C15ULL);
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
        return z ^ (z >> 31);
    }

private:
    uint64_t state_;
};

// A float in [-0.5, 0.5) with a random 23-bit mantissa, never zero.
float nextFloat(SplitMix64& rng) {
    const uint32_t mantissa = static_cast<uint32_t>(rng.next() >> 41);
    const float value = std::bit_cast<float>(0x3F800000u | mantissa) - 1.5f;
    return value == 0.0f ? 0.25f : value;
}

double nextDouble(SplitMix64& rng) {
    const uint64_t mantissa = rng.next() >> 12;
    const double value = std::bit_cast<double>(0x3FF0000000000000ULL | mantissa) - 1.5;
    return value == 0.0 ? 0.25 : value;
}

void fill(DataKind kind, uint8_t* out, size_t bytes, SplitMix64& rng) {
    switch (kind) {
        case DataKind::F32:
            for (size_t i = 0; i + 4 <= bytes; i += 4) {
                const float v = nextFloat(rng);
                std::memcpy(out + i, &v, 4);
            }
            break;
        case DataKind::F64:
            for (size_t i = 0; i + 8 <= bytes; i += 8) {
                const double v = nextDouble(rng);
                std::memcpy(out + i, &v, 8);
            }
            break;
        case DataKind::BF16:
            // bfloat16 is the upper half of a float32.
            for (size_t i = 0; i + 2 <= bytes; i += 2) {
                const uint16_t v = static_cast<uint16_t>(std::bit_cast<uint32_t>(nextFloat(rng)) >> 16);
                std::memcpy(out + i, &v, 2);
            }
            break;
        case DataKind::I8:
            for (size_t i = 0; i < bytes; i += 8) {
                const uint64_t v = rng.next();
                std::memcpy(out + i, &v, bytes - i < 8 ? bytes - i : 8);
            }
            break;
    }
}

size_t elementBytes(DataKind kind) {
    switch (kind) {
        case DataKind::F32: return 4;
        case DataKind::F64: return 8;
        case DataKind::BF16: return 2;
        case DataKind::I8: return 1;
    }
    return 1;
}

// Flips the sign bit of every element in [p, p + bytes). Elements are stored
// little-endian, so the sign bit is the top bit of the element's last byte.
void negate(DataKind kind, uint8_t* p, size_t bytes) {
    const size_t size = elementBytes(kind);
    for (size_t i = size - 1; i < bytes; i += size) {
        p[i] ^= 0x80;
    }
}

}  // namespace

void AlignedFree::operator()(void* p) const noexcept { std::free(p); }

OperandBuffer makeOperands(const KernelSpec& spec) {
    const size_t bytes = spec.bufferBytes;
    const size_t allocated = (bytes + 63) / 64 * 64;
    auto* data = static_cast<uint8_t*>(std::aligned_alloc(64, allocated));
    if (data == nullptr) return OperandBuffer{};
    std::memset(data, 0, allocated);

    SplitMix64 rng(kSeed);
    if (spec.negatedBytes == 0) {
        fill(spec.data, data, bytes, rng);
    } else {
        const size_t half = bytes / 2;
        fill(spec.data, data, half, rng);
        std::memcpy(data + half, data, half);
        for (size_t step = half; step < bytes; step += spec.stepBytes) {
            negate(spec.data, data + step, spec.negatedBytes);
        }
    }
    return OperandBuffer(data);
}

}  // namespace stress
