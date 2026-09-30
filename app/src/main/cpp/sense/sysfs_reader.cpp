#include "sense/sysfs_reader.h"

#include <fcntl.h>
#include <unistd.h>

namespace stress {
namespace {

// Parses up to `count` decimal integers (optionally negative) from `text` and
// returns how many were found.
int parseIntegers(const char* text, size_t length, int64_t* out, int count) {
    int found = 0;
    size_t i = 0;
    while (i < length && found < count) {
        const bool negative = text[i] == '-' && i + 1 < length && text[i + 1] >= '0' && text[i + 1] <= '9';
        if (negative || (text[i] >= '0' && text[i] <= '9')) {
            if (negative) ++i;
            int64_t value = 0;
            while (i < length && text[i] >= '0' && text[i] <= '9') {
                value = value * 10 + (text[i] - '0');
                ++i;
            }
            out[found++] = negative ? -value : value;
        } else {
            ++i;
        }
    }
    return found;
}

bool readFile(int fd, int64_t* out) {
    char buffer[128];
    const ssize_t n = pread(fd, buffer, sizeof(buffer) - 1, 0);
    out[0] = SysfsReader::kMissing;
    out[1] = SysfsReader::kMissing;
    if (n <= 0) return false;
    return parseIntegers(buffer, static_cast<size_t>(n), out, SysfsReader::kValuesPerFile) > 0;
}

}  // namespace

SysfsReader::~SysfsReader() { close(); }

std::vector<bool> SysfsReader::open(const std::vector<std::string>& paths) {
    std::lock_guard lock(mutex_);
    closeLocked();
    std::vector<bool> readable;
    readable.reserve(paths.size());
    for (const auto& path : paths) {
        const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        int64_t probe[kValuesPerFile];
        const bool ok = fd >= 0 && readFile(fd, probe);
        if (fd >= 0 && !ok) ::close(fd);
        fds_.push_back(ok ? fd : -1);
        readable.push_back(ok);
    }
    return readable;
}

void SysfsReader::read(int64_t* out, size_t outLength) const {
    std::lock_guard lock(mutex_);
    for (size_t i = 0; i < fds_.size(); ++i) {
        const size_t at = i * kValuesPerFile;
        if (at + kValuesPerFile > outLength) return;
        if (fds_[i] < 0) {
            out[at] = kMissing;
            out[at + 1] = kMissing;
        } else {
            readFile(fds_[i], out + at);
        }
    }
}

void SysfsReader::close() {
    std::lock_guard lock(mutex_);
    closeLocked();
}

void SysfsReader::closeLocked() {
    for (int fd : fds_) {
        if (fd >= 0) ::close(fd);
    }
    fds_.clear();
}

}  // namespace stress
