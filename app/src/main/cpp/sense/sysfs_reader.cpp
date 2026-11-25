#include "sense/sysfs_reader.h"

#include <cerrno>
#include <charconv>
#include <fcntl.h>
#include <unistd.h>

namespace stress {
namespace {

bool space(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
}

int readFile(int fd, int64_t* out) {
    char buffer[128];
    out[0] = SysfsReader::kMissing;
    out[1] = SysfsReader::kMissing;
    const ssize_t n = pread(fd, buffer, sizeof(buffer), 0);
    if (n < 0) return -errno;
    if (n == 0 || n == static_cast<ssize_t>(sizeof(buffer))) return 0;

    // Treat only leading decimal fields as values. Searching through arbitrary
    // text can turn an error message or a clock dump into a reading.
    const char* next = buffer;
    const char* end = buffer + n;
    int found = 0;
    while (found < SysfsReader::kValuesPerFile) {
        while (next < end && space(*next)) ++next;
        int64_t value;
        const auto [after, error] = std::from_chars(next, end, value);
        if (error != std::errc{}) break;
        if (after != end && !space(*after)) {
            if (found == 0 && *after == '%' && (after + 1 == end || space(after[1]))) out[found++] = value;
            break;
        }
        out[found++] = value;
        next = after;
    }
    return found;
}

bool clockFile(const std::string& path) {
    return path.ends_with("/scaling_cur_freq") || path.ends_with("/cpuinfo_cur_freq");
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
        const bool retry = clockFile(path) && (fd >= 0 || errno == ENOENT || errno == ENODEV);
        int64_t probe[kValuesPerFile];
        const bool ok = fd >= 0 && readFile(fd, probe) > 0;
        // An opened clock can fail its first read while all its CPUs are offline.
        files_.push_back({fd, retry ? path : std::string{}});
        readable.push_back(ok);
    }
    return readable;
}

void SysfsReader::read(int64_t* out, size_t outLength) {
    std::lock_guard lock(mutex_);
    for (size_t i = 0; i < files_.size(); ++i) {
        const size_t at = i * kValuesPerFile;
        if (at + kValuesPerFile > outLength) return;
        File& file = files_[i];
        if (file.fd < 0 && !file.clockPath.empty()) {
            file.fd = ::open(file.clockPath.c_str(), O_RDONLY | O_CLOEXEC);
            if (file.fd < 0 && errno != ENOENT && errno != ENODEV) file.clockPath.clear();
        }
        if (file.fd < 0) {
            out[at] = kMissing;
            out[at + 1] = kMissing;
        } else {
            const int read = readFile(file.fd, out + at);
            if (!file.clockPath.empty() && (read == -ENODEV || read == -ENOENT)) {
                ::close(file.fd);
                file.fd = -1;
            }
        }
    }
}

void SysfsReader::close() {
    std::lock_guard lock(mutex_);
    closeLocked();
}

void SysfsReader::closeLocked() {
    for (const File& file : files_) {
        if (file.fd >= 0) ::close(file.fd);
    }
    files_.clear();
}

}  // namespace stress
