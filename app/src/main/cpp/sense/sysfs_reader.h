#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace stress {

// Reads a fixed set of small sysfs files many times per second. Each file is
// opened once and re-read with pread() at offset 0, which costs one system
// call per file instead of open/read/close.
class SysfsReader {
public:
    static constexpr int64_t kMissing = INT64_MIN;
    static constexpr int kValuesPerFile = 2;

    ~SysfsReader();

    // Replaces the current set. Returns, for each path, whether it could be
    // opened and read once.
    std::vector<bool> open(const std::vector<std::string>& paths);

    // Fills kValuesPerFile values per path: the first two integers found in the
    // file (e.g. gpubusy holds "busy total"). Missing values are kMissing.
    void read(int64_t* out, size_t outLength) const;

    void close();

private:
    void closeLocked();

    mutable std::mutex mutex_;
    std::vector<int> fds_;
};

}  // namespace stress
