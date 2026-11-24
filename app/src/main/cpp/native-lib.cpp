#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <limits>
#include <new>
#include <ostream>
#include <streambuf>
#include <string>
#include <system_error>
#include <thread>
#include <utility>
#include <vector>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>

namespace {

constexpr std::int64_t kProxyScale = 4;
constexpr std::int64_t kFusionScale = 2;
constexpr std::int64_t kSearchRadius = 8;
constexpr std::int64_t kCorrelationSampleStep = 2;
constexpr std::int64_t kLocalSearchRadius = 3;
constexpr std::int64_t kLocalGridColumns = 5;
constexpr std::int64_t kLocalGridRows = 4;
constexpr std::size_t kLocalRegionCount =
        static_cast<std::size_t>(kLocalGridColumns * kLocalGridRows);
constexpr std::size_t kCandidateSide = static_cast<std::size_t>(2 * kSearchRadius + 1);
constexpr std::size_t kCandidateCount = kCandidateSide * kCandidateSide;
constexpr std::size_t kLocalCandidateSide = static_cast<std::size_t>(2 * kLocalSearchRadius + 1);
constexpr std::size_t kLocalCandidateCount = kLocalCandidateSide * kLocalCandidateSide;

struct LocalMotionRegion {
    double xCorrectionPixels = 0.0;
    double yCorrectionPixels = 0.0;
    double confidence = 0.0;
    bool reliable = false;
};

struct ColorProxyPixel {
    std::uint16_t red = 0;
    std::uint16_t green = 0;
    std::uint16_t blue = 0;
};

struct FusionPixelAccumulator {
    std::uint16_t red = 0;
    std::uint16_t green = 0;
    std::uint16_t blue = 0;
    std::uint16_t weight = 0;
};

static_assert(sizeof(FusionPixelAccumulator) == 8);

struct SuperResolutionPixel {
    // Per-channel running means of black-subtracted camera RGB (0..65535 of the white range)
    // and their accumulated kernel weights.
    std::array<std::uint16_t, 3> mean{};
    std::array<std::uint16_t, 3> weight{};
};

static_assert(sizeof(SuperResolutionPixel) == 12);

class FileDescriptorStreamBuffer final : public std::streambuf {
public:
    explicit FileDescriptorStreamBuffer(int fileDescriptor) : fileDescriptor_(fileDescriptor) {
        setp(buffer_.data(), buffer_.data() + buffer_.size());
    }

protected:
    int sync() override {
        return flushBuffer();
    }

    int_type overflow(int_type character) override {
        if (flushBuffer() != 0) return traits_type::eof();
        if (!traits_type::eq_int_type(character, traits_type::eof())) {
            *pptr() = traits_type::to_char_type(character);
            pbump(1);
        }
        return traits_type::not_eof(character);
    }

    std::streamsize xsputn(const char* source, std::streamsize count) override {
        std::streamsize written = 0;
        while (count > 0) {
            if (pptr() == epptr() && flushBuffer() != 0) break;
            const std::streamsize available = epptr() - pptr();
            const std::streamsize chunk = std::min(count, available);
            std::memcpy(pptr(), source, static_cast<std::size_t>(chunk));
            pbump(static_cast<int>(chunk));
            source += chunk;
            count -= chunk;
            written += chunk;
        }
        return written;
    }

private:
    int flushBuffer() {
        char* source = pbase();
        std::ptrdiff_t remaining = pptr() - pbase();
        while (remaining > 0) {
            const ssize_t count = ::write(
                    fileDescriptor_,
                    source,
                    static_cast<std::size_t>(remaining));
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) return -1;
            source += count;
            remaining -= count;
        }
        setp(buffer_.data(), buffer_.data() + buffer_.size());
        return 0;
    }

    int fileDescriptor_;
    std::array<char, 64 * 1024> buffer_{};
};

struct AlignmentResult {
    bool isReference = false;
    double xCorrectionPixels = 0.0;
    double yCorrectionPixels = 0.0;
    double meanAbsoluteError = 0.0;
    double confidence = 0.0;
    double localMotionSpreadPixels = 0.0;
    double meanLocalConfidence = 0.0;
    std::int32_t reliableLocalRegionCount = 0;
    std::int32_t evaluatedLocalRegionCount = 0;
    bool reachedSearchLimit = false;
    std::array<LocalMotionRegion, kLocalRegionCount> localRegions{};
};

void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

void throwIllegalState(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

void throwOutOfMemory(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/OutOfMemoryError");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

std::uint16_t readRawSample(const std::uint8_t* bytes, std::int64_t offset) {
    return static_cast<std::uint16_t>(
            bytes[offset] | (static_cast<std::uint16_t>(bytes[offset + 1]) << 8));
}

class RawBurstAligner {
public:
    explicit RawBurstAligner(std::string superResolutionScratchPath)
            : superResolutionScratchPath_(std::move(superResolutionScratchPath)) {}

    ~RawBurstAligner() {
        if (superResolutionPixels_ != nullptr) {
            msync(superResolutionPixels_, superResolutionBytes_, MS_ASYNC);
            munmap(superResolutionPixels_, superResolutionBytes_);
        }
        if (superResolutionFileDescriptor_ >= 0) close(superResolutionFileDescriptor_);
    }

    bool addFrame(
            const std::uint8_t* bytes,
            jlong capacity,
            jint width,
            jint height,
            jint rowStride,
            jint pixelStride,
            const jint* cfaChannels,
            const jdouble* blackLevels,
            jdouble whiteLevel,
            const jdouble* colorGains,
            const jdouble* colorMatrix,
            std::vector<std::uint16_t>& proxy,
            std::vector<ColorProxyPixel>& colorProxy,
            AlignmentResult& result) {
        std::vector<std::uint16_t> alignProxy;
        std::vector<std::uint16_t> fineProxy;
        if (!makeProxy(
                    bytes,
                    capacity,
                    width,
                    height,
                    rowStride,
                    pixelStride,
                    cfaChannels,
                    blackLevels,
                    whiteLevel,
                    colorGains,
                    colorMatrix,
                    proxy,
                    alignProxy,
                    fineProxy,
                    colorProxy)) {
            return false;
        }
        if (fusion_.empty()) fusion_.resize(colorProxy.size());
        if (fusion_.size() != colorProxy.size()) return false;

        if (reference_.empty()) {
            reference_ = std::move(proxy);
            alignReference_ = std::move(alignProxy);
            fineReference_ = std::move(fineProxy);
            alignWeights_ = textureWeights(alignReference_, proxyWidth_, proxyHeight_);
            fineWeights_ = textureWeights(fineReference_, fusionWidth_, fusionHeight_);
            referenceWidth_ = proxyWidth_;
            referenceHeight_ = proxyHeight_;
            fusion_.resize(colorProxy.size());
            result.isReference = true;
            accumulateFusion(colorProxy, result);
            for (std::size_t channel = 0; channel < superResolutionGains_.size(); ++channel) {
                superResolutionGains_[channel] =
                        std::isfinite(colorGains[channel]) && colorGains[channel] > 0.0
                        ? colorGains[channel]
                        : 1.0;
            }
            for (std::size_t i = 0; i < superResolutionMatrix_.size(); ++i) {
                superResolutionMatrix_[i] = std::isfinite(colorMatrix[i]) ? colorMatrix[i] : (i % 4 == 0 ? 1.0 : 0.0);
            }
            initializeSuperResolution(width, height, pixelStride);
            if (superResolutionPixels_ != nullptr) {
                accumulateSuperResolution(
                        bytes, width, height, rowStride, pixelStride,
                        cfaChannels, blackLevels, whiteLevel, reference_, result);
            }
            return true;
        }
        if (proxyWidth_ != referenceWidth_ || proxyHeight_ != referenceHeight_) return false;

        result = estimateTranslation(alignProxy);
        refineAlignment(fineProxy, result);
        accumulateFusion(colorProxy, result);
        if (superResolutionPixels_ != nullptr && width == sensorWidth_ && height == sensorHeight_) {
            accumulateSuperResolution(
                    bytes, width, height, rowStride, pixelStride,
                    cfaChannels, blackLevels, whiteLevel, proxy, result);
        }
        return true;
    }

    bool writeSuperResolutionTiff(const std::string& outputPath, jint orientation) const {
        std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
        if (!output) return false;
        return writeSuperResolutionTiff(output, orientation);
    }

    bool writeSuperResolutionTiffToFileDescriptor(int fileDescriptor, jint orientation) const {
        if (fileDescriptor < 0) return false;
        const int outputDescriptor = dup(fileDescriptor);
        if (outputDescriptor < 0) return false;
        if (ftruncate(outputDescriptor, 0) != 0 || lseek(outputDescriptor, 0, SEEK_SET) < 0) {
            close(outputDescriptor);
            return false;
        }
        FileDescriptorStreamBuffer streamBuffer(outputDescriptor);
        std::ostream output(&streamBuffer);
        const bool encoded = writeSuperResolutionTiff(output, orientation);
        output.flush();
        const bool flushed = output.good() && fsync(outputDescriptor) == 0;
        close(outputDescriptor);
        return encoded && flushed;
    }

    bool writeSuperResolutionTiff(std::ostream& output, jint orientation) const {
        if (superResolutionPixels_ == nullptr || superResolutionFrameCount_ <= 0 ||
            superResolutionWidth_ <= 0 || superResolutionHeight_ <= 0) {
            return false;
        }
        if (!writeTiffHeader(output, orientation)) return false;

        const std::int64_t width = superResolutionWidth_;
        const std::int64_t height = superResolutionHeight_;
        // Ring of three decoded rows (camera RGB, linear, 0..1) for the 3x3 chroma smoothing.
        std::array<std::vector<float>, 3> decodedRows;
        std::array<std::vector<std::uint8_t>, 3> validRows;
        for (std::size_t i = 0; i < decodedRows.size(); ++i) {
            decodedRows[i].resize(static_cast<std::size_t>(width) * 3);
            validRows[i].resize(static_cast<std::size_t>(width));
        }
        const auto decodeRow = [&](std::int64_t y) {
            const std::size_t slot = static_cast<std::size_t>(y % 3);
            float* rgb = decodedRows[slot].data();
            std::uint8_t* valid = validRows[slot].data();
            const SuperResolutionPixel* row = superResolutionPixels_ + y * width;
            for (std::int64_t x = 0; x < width; ++x) {
                const SuperResolutionPixel& pixel = row[x];
                std::array<float, 3> color{};
                std::array<bool, 3> present{};
                for (std::size_t channel = 0; channel < 3; ++channel) {
                    present[channel] = pixel.weight[channel] > 0;
                    color[channel] = pixel.mean[channel] / 65535.0f;
                }
                if (!present[1]) {
                    if (present[0] && present[2]) color[1] = 0.5f * (color[0] + color[2]);
                    else if (present[0]) color[1] = color[0];
                    else if (present[2]) color[1] = color[2];
                }
                const bool any = present[0] || present[1] || present[2];
                if (!present[0]) color[0] = color[1];
                if (!present[2]) color[2] = color[1];
                valid[x] = any ? 1 : 0;
                rgb[x * 3] = color[0];
                rgb[x * 3 + 1] = color[1];
                rgb[x * 3 + 2] = color[2];
            }
        };

        const std::size_t rowPixelBytes = static_cast<std::size_t>(width) * 3;
        std::vector<std::uint8_t> outputRow(rowPixelBytes);
        decodeRow(0);
        if (height > 1) decodeRow(1);
        for (std::int64_t y = 0; y < height; ++y) {
            if (y + 1 < height && y >= 1) decodeRow(y + 1);
            const std::int64_t firstRow = std::max<std::int64_t>(0, y - 1);
            const std::int64_t lastRow = std::min(height - 1, y + 1);
            const float* center = decodedRows[static_cast<std::size_t>(y % 3)].data();
            const std::uint8_t* centerValid = validRows[static_cast<std::size_t>(y % 3)].data();
            for (std::int64_t x = 0; x < width; ++x) {
                const std::size_t offset = static_cast<std::size_t>(x) * 3;
                if (centerValid[x] == 0) {
                    outputRow[offset] = 0;
                    outputRow[offset + 1] = 0;
                    outputRow[offset + 2] = 0;
                    continue;
                }
                // Smooth chroma (R-G, B-G) over 3x3 while keeping the full-resolution green detail.
                float redDifference = 0.0f;
                float blueDifference = 0.0f;
                int count = 0;
                for (std::int64_t ny = firstRow; ny <= lastRow; ++ny) {
                    const float* rgb = decodedRows[static_cast<std::size_t>(ny % 3)].data();
                    const std::uint8_t* valid = validRows[static_cast<std::size_t>(ny % 3)].data();
                    for (std::int64_t nx = std::max<std::int64_t>(0, x - 1);
                         nx <= std::min(width - 1, x + 1);
                         ++nx) {
                        if (valid[nx] == 0) continue;
                        redDifference += rgb[nx * 3] - rgb[nx * 3 + 1];
                        blueDifference += rgb[nx * 3 + 2] - rgb[nx * 3 + 1];
                        ++count;
                    }
                }
                const float green = center[offset + 1];
                const std::array<double, 3> cameraColor{
                        std::clamp(static_cast<double>(green + redDifference / count) *
                                           superResolutionGains_[0], 0.0, 1.0),
                        std::clamp(static_cast<double>(green) * superResolutionGains_[1], 0.0, 1.0),
                        std::clamp(static_cast<double>(green + blueDifference / count) *
                                           superResolutionGains_[2], 0.0, 1.0),
                };
                const std::array<double, 3> corrected =
                        applyColorMatrix(cameraColor, superResolutionMatrix_.data());
                outputRow[offset] = encodeSrgb12(corrected[0]);
                outputRow[offset + 1] = encodeSrgb12(corrected[1]);
                outputRow[offset + 2] = encodeSrgb12(corrected[2]);
            }
            output.write(reinterpret_cast<const char*>(outputRow.data()),
                         static_cast<std::streamsize>(outputRow.size()));
            if (!output) return false;
        }
        output.flush();
        return static_cast<bool>(output);
    }

    jint superResolutionWidth() const {
        return static_cast<jint>(superResolutionWidth_);
    }

    jint superResolutionHeight() const {
        return static_cast<jint>(superResolutionHeight_);
    }

    jint superResolutionFrameCount() const {
        return superResolutionFrameCount_;
    }

    std::vector<jint> finishFusionPreview() const {
        if (fusion_.empty()) return {};
        std::vector<jint> output(fusion_.size() + 1);
        output[0] = fusionFrameCount_;
        for (std::size_t i = 0; i < fusion_.size(); ++i) {
            const FusionPixelAccumulator& sample = fusion_[i];
            if (sample.weight <= 0.0f) {
                output[i + 1] = static_cast<jint>(0xff000000u);
                continue;
            }
            const std::uint8_t red = encodeSrgb(static_cast<float>(sample.red));
            const std::uint8_t green = encodeSrgb(static_cast<float>(sample.green));
            const std::uint8_t blue = encodeSrgb(static_cast<float>(sample.blue));
            output[i + 1] = static_cast<jint>(
                    0xff000000u | (static_cast<std::uint32_t>(red) << 16) |
                    (static_cast<std::uint32_t>(green) << 8) | blue);
        }
        return output;
    }

private:
    static std::array<double, 3> applyColorMatrix(
            const std::array<double, 3>& sensorRgb,
            const jdouble* colorMatrix) {
        std::array<double, 3> output{};
        for (std::size_t row = 0; row < output.size(); ++row) {
            for (std::size_t column = 0; column < sensorRgb.size(); ++column) {
                output[row] += colorMatrix[row * 3 + column] * sensorRgb[column];
            }
        }
        return output;
    }

    static std::uint8_t encodeSrgb(float linearSample) {
        const double linear = std::clamp(static_cast<double>(linearSample) / 4095.0, 0.0, 1.0);
        const double srgb = linear <= 0.0031308
                ? 12.92 * linear
                : 1.055 * std::pow(linear, 1.0 / 2.4) - 0.055;
        return static_cast<std::uint8_t>(std::lround(std::clamp(srgb, 0.0, 1.0) * 255.0));
    }

    static std::uint8_t encodeSrgb12(double linear) {
        static const std::array<std::uint8_t, 4096> lookup = [] {
            std::array<std::uint8_t, 4096> values{};
            for (std::size_t i = 0; i < values.size(); ++i) {
                const double value = static_cast<double>(i) / 4095.0;
                const double srgb = value <= 0.0031308
                        ? 12.92 * value
                        : 1.055 * std::pow(value, 1.0 / 2.4) - 0.055;
                values[i] = static_cast<std::uint8_t>(std::lround(
                        std::clamp(srgb, 0.0, 1.0) * 255.0));
            }
            return values;
        }();
        const long index = std::lround(std::clamp(linear, 0.0, 1.0) * 4095.0);
        return lookup[static_cast<std::size_t>(index)];
    }

    static void writeU16(std::ostream& output, std::uint16_t value) {
        const char bytes[2] = {
                static_cast<char>(value & 0xff),
                static_cast<char>((value >> 8) & 0xff),
        };
        output.write(bytes, sizeof(bytes));
    }

    static void writeU32(std::ostream& output, std::uint32_t value) {
        const char bytes[4] = {
                static_cast<char>(value & 0xff),
                static_cast<char>((value >> 8) & 0xff),
                static_cast<char>((value >> 16) & 0xff),
                static_cast<char>((value >> 24) & 0xff),
        };
        output.write(bytes, sizeof(bytes));
    }

    static void writeTiffEntry(
            std::ostream& output,
            std::uint16_t tag,
            std::uint16_t type,
            std::uint32_t count,
            std::uint32_t value) {
        writeU16(output, tag);
        writeU16(output, type);
        writeU32(output, count);
        if (type == 3 && count == 1) {
            writeU16(output, static_cast<std::uint16_t>(value));
            writeU16(output, 0);
        } else {
            writeU32(output, value);
        }
    }

    bool writeTiffHeader(std::ostream& output, jint orientation) const {
        constexpr std::uint16_t kTagCount = 16;
        constexpr char kImageDescription[] =
                "Fabula 2x RAW burst reconstruction prototype; not a native 48 MP capture.";
        constexpr std::uint32_t kIfdEndOffset = 8 + 2 + kTagCount * 12 + 4;
        constexpr std::uint32_t kDescriptionOffset = kIfdEndOffset;
        constexpr std::uint32_t kBitsPerSampleOffset =
                (kDescriptionOffset + sizeof(kImageDescription) + 1) & ~1U;
        constexpr std::uint32_t kXResolutionOffset = (kBitsPerSampleOffset + 6 + 3) & ~3U;
        constexpr std::uint32_t kYResolutionOffset = kXResolutionOffset + 8;
        constexpr std::uint32_t kPixelDataOffset = kYResolutionOffset + 8;
        const std::uint64_t imageBytes = static_cast<std::uint64_t>(
                superResolutionWidth_) * superResolutionHeight_ * 3;
        if (imageBytes > std::numeric_limits<std::uint32_t>::max()) return false;

        output.put('I');
        output.put('I');
        writeU16(output, 42);
        writeU32(output, 8);
        writeU16(output, kTagCount);
        writeTiffEntry(output, 254, 4, 1, 0);  // NewSubfileType
        writeTiffEntry(output, 256, 4, 1, static_cast<std::uint32_t>(superResolutionWidth_));
        writeTiffEntry(output, 257, 4, 1, static_cast<std::uint32_t>(superResolutionHeight_));
        writeTiffEntry(output, 258, 3, 3, kBitsPerSampleOffset);
        writeTiffEntry(output, 259, 3, 1, 1);  // No compression
        writeTiffEntry(output, 262, 3, 1, 2);  // RGB
        writeTiffEntry(output, 270, 2, sizeof(kImageDescription), kDescriptionOffset);
        writeTiffEntry(output, 273, 4, 1, kPixelDataOffset);
        const jint normalizedOrientation = (orientation % 360 + 360) % 360;
        const std::uint16_t tiffOrientation = normalizedOrientation == 90 ? 6
                : normalizedOrientation == 180 ? 3
                : normalizedOrientation == 270 ? 8
                : 1;
        writeTiffEntry(output, 274, 3, 1, tiffOrientation);
        writeTiffEntry(output, 277, 3, 1, 3);  // SamplesPerPixel
        writeTiffEntry(output, 278, 4, 1, static_cast<std::uint32_t>(superResolutionHeight_));
        writeTiffEntry(output, 279, 4, 1, static_cast<std::uint32_t>(imageBytes));
        writeTiffEntry(output, 282, 5, 1, kXResolutionOffset);
        writeTiffEntry(output, 283, 5, 1, kYResolutionOffset);
        writeTiffEntry(output, 284, 3, 1, 1);  // Chunky planar configuration
        writeTiffEntry(output, 296, 3, 1, 2);  // Inches
        writeU32(output, 0);  // No next IFD
        output.write(kImageDescription, sizeof(kImageDescription));
        for (std::uint32_t offset = kDescriptionOffset + sizeof(kImageDescription);
             offset < kBitsPerSampleOffset;
             ++offset) {
            output.put('\0');
        }
        writeU16(output, 8);
        writeU16(output, 8);
        writeU16(output, 8);
        for (std::uint32_t offset = kBitsPerSampleOffset + 6;
             offset < kXResolutionOffset;
             ++offset) {
            output.put('\0');
        }
        writeU32(output, 72);
        writeU32(output, 1);
        writeU32(output, 72);
        writeU32(output, 1);
        return static_cast<bool>(output);
    }

    bool initializeSuperResolution(jint width, jint height, jint pixelStride) {
        sensorWidth_ = width;
        sensorHeight_ = height;
        if (superResolutionScratchPath_.empty() || width <= 0 || height <= 0) return false;
        superResolutionWidth_ = static_cast<std::int64_t>(width) * 2;
        superResolutionHeight_ = static_cast<std::int64_t>(height) * 2;
        const std::int64_t pixelCount = superResolutionWidth_ * superResolutionHeight_;
        if (pixelCount <= 0 || pixelCount > 60'000'000) return false;
        if (static_cast<std::uint64_t>(pixelCount) >
            std::numeric_limits<std::size_t>::max() / sizeof(SuperResolutionPixel)) {
            return false;
        }
        superResolutionBytes_ = static_cast<std::size_t>(pixelCount) * sizeof(SuperResolutionPixel);
        const std::size_t slash = superResolutionScratchPath_.find_last_of('/');
        const std::string scratchDirectory = slash == std::string::npos
                ? "."
                : superResolutionScratchPath_.substr(0, slash);
        struct statvfs storageInfo {};
        if (statvfs(scratchDirectory.c_str(), &storageInfo) != 0 || storageInfo.f_frsize == 0) {
            return false;
        }
        const std::uint64_t availableBytes =
                static_cast<std::uint64_t>(storageInfo.f_bavail) * storageInfo.f_frsize;
        const std::uint64_t outputPixelCount = static_cast<std::uint64_t>(pixelCount);
        const std::uint64_t rawFrameBytes = static_cast<std::uint64_t>(width) * height *
                static_cast<std::uint64_t>(std::max(1, pixelStride));
        constexpr std::uint64_t kMaxFrames = 15;
        constexpr std::uint64_t kHeadroomBytes = 64ULL * 1024 * 1024;
        const std::uint64_t requiredBytes = outputPixelCount * (sizeof(SuperResolutionPixel) + 3) +
                rawFrameBytes * kMaxFrames + kHeadroomBytes;
        if (availableBytes < requiredBytes) return false;
        superResolutionFileDescriptor_ = open(
                superResolutionScratchPath_.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600);
        if (superResolutionFileDescriptor_ < 0) return false;
        if (ftruncate(superResolutionFileDescriptor_, static_cast<off_t>(superResolutionBytes_)) != 0) {
            close(superResolutionFileDescriptor_);
            superResolutionFileDescriptor_ = -1;
            return false;
        }
        void* mapped = mmap(
                nullptr,
                superResolutionBytes_,
                PROT_READ | PROT_WRITE,
                MAP_SHARED,
                superResolutionFileDescriptor_,
                0);
        if (mapped == MAP_FAILED) {
            close(superResolutionFileDescriptor_);
            superResolutionFileDescriptor_ = -1;
            return false;
        }
        superResolutionPixels_ = static_cast<SuperResolutionPixel*>(mapped);
        std::memset(superResolutionPixels_, 0, superResolutionBytes_);
        return true;
    }

    struct BlockMotion {
        float dx = 0.0f;
        float dy = 0.0f;
        bool valid = false;
    };

    static constexpr std::int64_t kMotionBlockSize = 16;
    static constexpr int kKernelLutScale = 64;
    static constexpr std::size_t kKernelLutSize = 4 * kKernelLutScale + 1;
    static constexpr float kSuperResolutionWeightScale = 1024.0f;

    using KernelLut = std::array<float, kKernelLutSize>;

    static KernelLut makeKernelLut(double sigma) {
        KernelLut lut{};
        for (std::size_t i = 0; i < lut.size(); ++i) {
            const double distance = static_cast<double>(i) / kKernelLutScale - 2.0;
            lut[i] = static_cast<float>(std::exp(-(distance * distance) / (2.0 * sigma * sigma)));
        }
        return lut;
    }

    static std::size_t kernelIndex(double distance) {
        const long index = std::lround((distance + 2.0) * kKernelLutScale);
        return static_cast<std::size_t>(std::clamp<long>(index, 0, kKernelLutSize - 1));
    }

    std::vector<BlockMotion> buildBlockMotion(
            const AlignmentResult& alignment,
            jint width,
            jint height,
            std::int64_t& blocksX,
            std::int64_t& blocksY) const {
        blocksX = (width + kMotionBlockSize - 1) / kMotionBlockSize;
        blocksY = (height + kMotionBlockSize - 1) / kMotionBlockSize;
        std::vector<BlockMotion> blocks(static_cast<std::size_t>(blocksX * blocksY));
        for (std::int64_t by = 0; by < blocksY; ++by) {
            for (std::int64_t bx = 0; bx < blocksX; ++bx) {
                double dx = 0.0;
                double dy = 0.0;
                BlockMotion& block = blocks[static_cast<std::size_t>(by * blocksX + bx)];
                block.valid = motionAt(
                        alignment,
                        std::min<std::int64_t>(bx * kMotionBlockSize + kMotionBlockSize / 2, width - 1),
                        std::min<std::int64_t>(by * kMotionBlockSize + kMotionBlockSize / 2, height - 1),
                        width,
                        height,
                        dx,
                        dy);
                block.dx = static_cast<float>(dx);
                block.dy = static_cast<float>(dy);
            }
        }
        return blocks;
    }

    static float sampleBilinear(
            const std::vector<std::uint16_t>& plane,
            std::int64_t width,
            std::int64_t height,
            double x,
            double y,
            bool& inside) {
        inside = x >= 0.0 && y >= 0.0 && x <= static_cast<double>(width - 1) &&
                y <= static_cast<double>(height - 1);
        if (!inside) return 0.0f;
        const auto x0 = static_cast<std::int64_t>(x);
        const auto y0 = static_cast<std::int64_t>(y);
        const std::int64_t x1 = std::min(x0 + 1, width - 1);
        const std::int64_t y1 = std::min(y0 + 1, height - 1);
        const double fx = x - static_cast<double>(x0);
        const double fy = y - static_cast<double>(y0);
        const auto at = [&](std::int64_t sx, std::int64_t sy) {
            return static_cast<double>(plane[static_cast<std::size_t>(sy * width + sx)]);
        };
        const double top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx;
        const double bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx;
        return static_cast<float>(top + (bottom - top) * fy);
    }

    // Per-proxy-pixel weight in [0, 1]: 1 where the aligned frame agrees with the reference,
    // 0 where it differs beyond a noise- and texture-aware tolerance (moving objects, misalignment).
    std::vector<float> buildRobustnessMap(
            const std::vector<std::uint16_t>& current,
            const std::vector<BlockMotion>& blocks,
            std::int64_t blocksX,
            std::int64_t blocksY,
            double blackLevel) const {
        const std::int64_t width = proxyWidth_;
        const std::int64_t height = proxyHeight_;
        std::vector<float> agreement(static_cast<std::size_t>(width * height), 0.0f);
        if (current.size() != reference_.size()) return agreement;
        for (std::int64_t py = 0; py < height; ++py) {
            const std::int64_t blockY = std::min(py * kProxyScale / kMotionBlockSize, blocksY - 1);
            for (std::int64_t px = 0; px < width; ++px) {
                const std::int64_t blockX = std::min(px * kProxyScale / kMotionBlockSize, blocksX - 1);
                const BlockMotion& motion = blocks[static_cast<std::size_t>(blockY * blocksX + blockX)];
                if (!motion.valid) continue;
                bool inside = false;
                const float aligned = sampleBilinear(
                        current,
                        width,
                        height,
                        static_cast<double>(px) - motion.dx / kProxyScale,
                        static_cast<double>(py) - motion.dy / kProxyScale,
                        inside);
                if (!inside) continue;

                double localSum = 0.0;
                double localSquares = 0.0;
                int localCount = 0;
                for (std::int64_t ny = std::max<std::int64_t>(0, py - 1);
                     ny <= std::min(height - 1, py + 1);
                     ++ny) {
                    for (std::int64_t nx = std::max<std::int64_t>(0, px - 1);
                         nx <= std::min(width - 1, px + 1);
                         ++nx) {
                        const double value = reference_[static_cast<std::size_t>(ny * width + nx)];
                        localSum += value;
                        localSquares += value * value;
                        ++localCount;
                    }
                }
                const double localMean = localSum / localCount;
                const double localDeviation = std::sqrt(std::max(
                        0.0, localSquares / localCount - localMean * localMean));
                const double referenceValue = reference_[static_cast<std::size_t>(py * width + px)];
                const double signal = std::max(0.0, referenceValue - blackLevel);
                const double tolerance = 0.05 * signal + 1.5 * std::sqrt(signal + 16.0) +
                        0.25 * localDeviation + 2.0;
                const double difference = std::abs(static_cast<double>(aligned) - referenceValue);
                agreement[static_cast<std::size_t>(py * width + px)] = static_cast<float>(
                        std::clamp((2.0 * tolerance - difference) / tolerance, 0.0, 1.0));
            }
        }

        // Erode so that rejection also covers the soft borders of moving regions.
        std::vector<float> robustness(agreement.size(), 0.0f);
        for (std::int64_t py = 0; py < height; ++py) {
            for (std::int64_t px = 0; px < width; ++px) {
                float minimum = 1.0f;
                for (std::int64_t ny = std::max<std::int64_t>(0, py - 1);
                     ny <= std::min(height - 1, py + 1);
                     ++ny) {
                    for (std::int64_t nx = std::max<std::int64_t>(0, px - 1);
                         nx <= std::min(width - 1, px + 1);
                         ++nx) {
                        minimum = std::min(minimum, agreement[static_cast<std::size_t>(ny * width + nx)]);
                    }
                }
                robustness[static_cast<std::size_t>(py * width + px)] = minimum;
            }
        }
        return robustness;
    }

    // Kernel-based multi-frame reconstruction at the CFA level: every output pixel gathers the
    // raw Bayer samples of the current frame around its motion-compensated position, each sample
    // only feeding its own color channel. Per-channel running means are kept in 16-bit linear
    // precision; demosaicing happens implicitly through the anisotropy-free Gaussian kernels and
    // is finished in the TIFF writer with color-difference smoothing.
    void accumulateSuperResolution(
            const std::uint8_t* bytes,
            jint width,
            jint height,
            jint rowStride,
            jint pixelStride,
            const jint* cfaChannels,
            const jdouble* blackLevels,
            jdouble whiteLevel,
            const std::vector<std::uint16_t>& currentProxy,
            const AlignmentResult& alignment) {
        static const KernelLut greenKernel = makeKernelLut(0.55);
        static const KernelLut colorKernel = makeKernelLut(0.75);

        double averageBlackLevel = 0.0;
        std::array<float, 4> siteBlack{};
        std::array<int, 4> siteChannel{};
        for (std::size_t site = 0; site < 4; ++site) {
            siteBlack[site] = static_cast<float>(std::max(0.0, blackLevels[site]));
            averageBlackLevel += siteBlack[site];
            const int channel = cfaChannels[site];
            siteChannel[site] = channel >= 0 && channel < 3 ? channel : -1;
        }
        averageBlackLevel /= 4.0;
        const float inverseWhiteRange = static_cast<float>(
                1.0 / std::max(1.0, whiteLevel - averageBlackLevel));

        std::int64_t blocksX = 0;
        std::int64_t blocksY = 0;
        const std::vector<BlockMotion> blocks = buildBlockMotion(alignment, width, height, blocksX, blocksY);
        std::vector<float> robustness;
        if (!alignment.isReference) {
            robustness = buildRobustnessMap(currentProxy, blocks, blocksX, blocksY, averageBlackLevel);
        }
        const std::int64_t proxyWidth = proxyWidth_;
        const std::int64_t proxyHeight = proxyHeight_;
        const auto robustnessAt = [&](double sensorX, double sensorY) {
            const double x = std::clamp((sensorX - 1.5) / kProxyScale, 0.0, static_cast<double>(proxyWidth - 1));
            const double y = std::clamp((sensorY - 1.5) / kProxyScale, 0.0, static_cast<double>(proxyHeight - 1));
            const auto x0 = static_cast<std::int64_t>(x);
            const auto y0 = static_cast<std::int64_t>(y);
            const std::int64_t x1 = std::min(x0 + 1, proxyWidth - 1);
            const std::int64_t y1 = std::min(y0 + 1, proxyHeight - 1);
            const float fx = static_cast<float>(x - static_cast<double>(x0));
            const float fy = static_cast<float>(y - static_cast<double>(y0));
            const float* row0 = robustness.data() + y0 * proxyWidth;
            const float* row1 = robustness.data() + y1 * proxyWidth;
            const float top = row0[x0] + (row0[x1] - row0[x0]) * fx;
            const float bottom = row1[x0] + (row1[x1] - row1[x0]) * fx;
            return top + (bottom - top) * fy;
        };

        const std::int64_t outputWidth = superResolutionWidth_;
        const std::int64_t outputHeight = superResolutionHeight_;
        SuperResolutionPixel* pixels = superResolutionPixels_;
        std::atomic<std::int64_t> nextRow{0};
        std::atomic<bool> contributed{false};
        constexpr std::int64_t kRowChunk = 32;

        const auto worker = [&]() {
            bool localContribution = false;
            for (;;) {
                const std::int64_t chunkStart = nextRow.fetch_add(kRowChunk);
                if (chunkStart >= outputHeight) break;
                const std::int64_t chunkEnd = std::min(chunkStart + kRowChunk, outputHeight);
                for (std::int64_t outputY = chunkStart; outputY < chunkEnd; ++outputY) {
                    const double referenceY = (static_cast<double>(outputY) + 0.5) * 0.5 - 0.5;
                    const std::int64_t blockY = std::clamp<std::int64_t>(
                            static_cast<std::int64_t>(std::max(0.0, referenceY)) / kMotionBlockSize,
                            0,
                            blocksY - 1);
                    SuperResolutionPixel* outputRow = pixels + outputY * outputWidth;
                    for (std::int64_t outputX = 0; outputX < outputWidth; ++outputX) {
                        const double referenceX = (static_cast<double>(outputX) + 0.5) * 0.5 - 0.5;
                        const std::int64_t blockX = std::clamp<std::int64_t>(
                                static_cast<std::int64_t>(std::max(0.0, referenceX)) / kMotionBlockSize,
                                0,
                                blocksX - 1);
                        const BlockMotion& motion = blocks[static_cast<std::size_t>(blockY * blocksX + blockX)];
                        if (!motion.valid) continue;
                        float frameRobustness = 1.0f;
                        if (!robustness.empty()) {
                            frameRobustness = robustnessAt(referenceX, referenceY);
                            if (frameRobustness < 0.02f) continue;
                        }

                        const double currentX = referenceX - motion.dx;
                        const double currentY = referenceY - motion.dy;
                        const double floorX = std::floor(currentX);
                        const double floorY = std::floor(currentY);
                        const auto baseX = static_cast<std::int64_t>(floorX) - 1;
                        const auto baseY = static_cast<std::int64_t>(floorY) - 1;
                        if (baseX + 3 < 0 || baseX >= width || baseY + 3 < 0 || baseY >= height) continue;
                        const double fractionX = currentX - floorX;
                        const double fractionY = currentY - floorY;

                        std::array<std::size_t, 4> columnIndex{};
                        for (std::size_t i = 0; i < 4; ++i) {
                            columnIndex[i] = kernelIndex(static_cast<double>(i) - 1.0 - fractionX);
                        }
                        std::array<float, 3> sums{};
                        std::array<float, 3> weights{};
                        for (std::int64_t j = 0; j < 4; ++j) {
                            const std::int64_t sampleY = baseY + j;
                            if (sampleY < 0 || sampleY >= height) continue;
                            const std::size_t rowIndex = kernelIndex(static_cast<double>(j) - 1.0 - fractionY);
                            const float rowGreen = greenKernel[rowIndex];
                            const float rowColor = colorKernel[rowIndex];
                            const std::int64_t rowOffset = sampleY * rowStride;
                            const std::size_t siteRow = static_cast<std::size_t>((sampleY & 1) << 1);
                            for (std::int64_t i = 0; i < 4; ++i) {
                                const std::int64_t sampleX = baseX + i;
                                if (sampleX < 0 || sampleX >= width) continue;
                                const std::size_t site = siteRow | static_cast<std::size_t>(sampleX & 1);
                                const int channel = siteChannel[site];
                                if (channel < 0) continue;
                                const float value = std::max(
                                        0.0f,
                                        static_cast<float>(readRawSample(bytes, rowOffset + sampleX * pixelStride)) -
                                                siteBlack[site]);
                                const float weight = channel == 1
                                        ? greenKernel[columnIndex[static_cast<std::size_t>(i)]] * rowGreen
                                        : colorKernel[columnIndex[static_cast<std::size_t>(i)]] * rowColor;
                                sums[static_cast<std::size_t>(channel)] += weight * value;
                                weights[static_cast<std::size_t>(channel)] += weight;
                            }
                        }

                        SuperResolutionPixel& destination = outputRow[outputX];
                        for (std::size_t channel = 0; channel < 3; ++channel) {
                            if (weights[channel] < 1.0e-3f) continue;
                            const float frameWeight =
                                    weights[channel] * frameRobustness * kSuperResolutionWeightScale;
                            if (frameWeight < 1.0f) continue;
                            const float estimate = std::clamp(
                                    sums[channel] / weights[channel] * inverseWhiteRange, 0.0f, 1.0f) * 65535.0f;
                            const float previousWeight = destination.weight[channel];
                            const float totalWeight = previousWeight + frameWeight;
                            const float previousMean = destination.mean[channel];
                            const float mean = previousMean + (estimate - previousMean) * frameWeight / totalWeight;
                            destination.mean[channel] = static_cast<std::uint16_t>(
                                    std::lround(std::clamp(mean, 0.0f, 65535.0f)));
                            destination.weight[channel] = static_cast<std::uint16_t>(
                                    std::lround(std::min(totalWeight, 65535.0f)));
                            localContribution = true;
                        }
                    }
                }
            }
            if (localContribution) contributed.store(true, std::memory_order_relaxed);
        };

        const unsigned hardwareThreads = std::thread::hardware_concurrency();
        const unsigned threadCount = std::clamp(hardwareThreads == 0 ? 4U : hardwareThreads, 1U, 8U);
        std::vector<std::thread> threads;
        threads.reserve(threadCount - 1);
        try {
            for (unsigned i = 1; i < threadCount; ++i) threads.emplace_back(worker);
        } catch (const std::system_error&) {
            // Fall back to fewer threads; the calling thread still processes the remaining rows.
        }
        worker();
        for (std::thread& thread : threads) thread.join();
        if (contributed.load()) ++superResolutionFrameCount_;
    }

    bool makeProxy(
            const std::uint8_t* bytes,
            jlong capacity,
            jint width,
            jint height,
            jint rowStride,
            jint pixelStride,
            const jint* cfaChannels,
            const jdouble* blackLevels,
            jdouble whiteLevel,
            const jdouble* colorGains,
            const jdouble* colorMatrix,
            std::vector<std::uint16_t>& proxy,
            std::vector<std::uint16_t>& alignProxy,
            std::vector<std::uint16_t>& fineProxy,
            std::vector<ColorProxyPixel>& colorProxy) {
        if (bytes == nullptr || width <= 0 || height <= 0 || rowStride <= 0 || pixelStride < 2 ||
            cfaChannels == nullptr || blackLevels == nullptr || colorGains == nullptr ||
            !std::isfinite(whiteLevel) || whiteLevel <= 0.0) {
            return false;
        }
        const std::int64_t pixelCount = static_cast<std::int64_t>(width) * height;
        if (pixelCount > 80'000'000) return false;

        const std::int64_t lastSampleOffset =
                static_cast<std::int64_t>(height - 1) * rowStride +
                static_cast<std::int64_t>(width - 1) * pixelStride;
        if (lastSampleOffset < 0 || lastSampleOffset + 1 >= capacity) return false;

        proxyWidth_ = (width + kProxyScale - 1) / kProxyScale;
        proxyHeight_ = (height + kProxyScale - 1) / kProxyScale;
        fusionWidth_ = (width + kFusionScale - 1) / kFusionScale;
        fusionHeight_ = (height + kFusionScale - 1) / kFusionScale;
        const std::int64_t proxyCount = proxyWidth_ * proxyHeight_;
        const std::int64_t fusionPixelCount = fusionWidth_ * fusionHeight_;
        if (proxyCount <= 0 || proxyCount > 10'000'000 ||
            fusionPixelCount <= 0 || fusionPixelCount > 20'000'000
        ) {
            return false;
        }
        proxy.resize(static_cast<std::size_t>(proxyCount));
        alignProxy.assign(static_cast<std::size_t>(proxyCount), 0);
        fineProxy.assign(static_cast<std::size_t>(fusionPixelCount), 0);
        colorProxy.resize(static_cast<std::size_t>(fusionPixelCount));

        double averageBlackLevel = 0.0;
        for (int site = 0; site < 4; ++site) {
            averageBlackLevel += std::max(0.0, blackLevels[site]);
        }
        averageBlackLevel /= 4.0;
        const double whiteRange = std::max(1.0, whiteLevel - averageBlackLevel);

        for (std::int64_t proxyY = 0; proxyY < proxyHeight_; ++proxyY) {
            const std::int64_t startY = proxyY * kProxyScale;
            const std::int64_t endY = startY + kProxyScale < height
                    ? startY + kProxyScale
                    : height;
            for (std::int64_t proxyX = 0; proxyX < proxyWidth_; ++proxyX) {
                const std::int64_t startX = proxyX * kProxyScale;
                const std::int64_t endX = startX + kProxyScale < width
                        ? startX + kProxyScale
                        : width;
                std::uint64_t sum = 0;
                std::uint32_t count = 0;
                std::array<std::array<double, 3>, 4> cellChannelSums{};
                std::array<std::array<std::uint32_t, 3>, 4> cellChannelCounts{};
                std::array<double, 4> cellSignalSums{};
                std::array<std::uint32_t, 4> cellCounts{};
                for (std::int64_t y = startY; y < endY; ++y) {
                    const std::int64_t rowOffset = y * rowStride;
                    for (std::int64_t x = startX; x < endX; ++x) {
                        const std::uint16_t sample = readRawSample(bytes, rowOffset + x * pixelStride);
                        sum += sample;
                        ++count;
                        const std::size_t cell = static_cast<std::size_t>(
                                ((y - startY) / kFusionScale) * 2 +
                                (x - startX) / kFusionScale);
                        const int site = static_cast<int>((y & 1) * 2 + (x & 1));
                        const double signal = std::max(
                                0.0,
                                static_cast<double>(sample) - blackLevels[site]);
                        cellSignalSums[cell] += signal;
                        ++cellCounts[cell];
                        const int channel = cfaChannels[site];
                        if (channel >= 0 && channel < 3) {
                            cellChannelSums[cell][static_cast<std::size_t>(channel)] += signal;
                            ++cellChannelCounts[cell][static_cast<std::size_t>(channel)];
                        }
                    }
                }
                const std::size_t proxyIndex = static_cast<std::size_t>(proxyY * proxyWidth_ + proxyX);
                proxy[proxyIndex] = static_cast<std::uint16_t>(sum / count);
                double blockSignal = 0.0;
                for (const double cellSignal : cellSignalSums) blockSignal += cellSignal;
                alignProxy[proxyIndex] = stabilizeSignal(blockSignal / count);

                for (std::int64_t cellY = 0; cellY < 2; ++cellY) {
                    for (std::int64_t cellX = 0; cellX < 2; ++cellX) {
                        const std::int64_t fusionX = proxyX * 2 + cellX;
                        const std::int64_t fusionY = proxyY * 2 + cellY;
                        if (fusionX >= fusionWidth_ || fusionY >= fusionHeight_) continue;
                        const std::size_t cell = static_cast<std::size_t>(cellY * 2 + cellX);
                        if (cellCounts[cell] == 0) continue;

                        const double graySignal = cellSignalSums[cell] / cellCounts[cell];
                        fineProxy[static_cast<std::size_t>(fusionY * fusionWidth_ + fusionX)] =
                                stabilizeSignal(graySignal);
                        std::array<double, 3> linearColor{};
                        for (std::size_t channel = 0; channel < linearColor.size(); ++channel) {
                            const double signal = cellChannelCounts[cell][channel] > 0
                                    ? cellChannelSums[cell][channel] / cellChannelCounts[cell][channel]
                                    : graySignal;
                            const double gain = std::isfinite(colorGains[channel]) && colorGains[channel] > 0.0
                                    ? colorGains[channel]
                                    : 1.0;
                            linearColor[channel] = std::clamp(signal * gain / whiteRange, 0.0, 1.0);
                        }
                        const std::array<double, 3> correctedColor =
                                applyColorMatrix(linearColor, colorMatrix);
                        std::array<std::uint16_t, 3> color{};
                        for (std::size_t channel = 0; channel < color.size(); ++channel) {
                            color[channel] = static_cast<std::uint16_t>(std::lround(
                                    std::clamp(correctedColor[channel], 0.0, 1.0) * 4095.0));
                        }
                        colorProxy[static_cast<std::size_t>(fusionY * fusionWidth_ + fusionX)] =
                                ColorProxyPixel{color[0], color[1], color[2]};
                    }
                }
            }
        }
        blurBinomial(alignProxy, proxyWidth_, proxyHeight_);
        blurBinomial(fineProxy, fusionWidth_, fusionHeight_);
        return true;
    }

    // Square-root (Anscombe-like) transform of the black-subtracted signal: shot noise becomes
    // roughly constant across brightness, and the fixed-point scale keeps the sub-DN precision
    // that integer RAW means lose in dark scenes.
    static std::uint16_t stabilizeSignal(double signal) {
        const double value = std::sqrt(std::max(0.0, signal) + 0.375) * 256.0;
        return static_cast<std::uint16_t>(std::lround(std::min(value, 65535.0)));
    }

    // Separable [1 2 1] / 4 blur, a light denoising prefilter applied before matching.
    static void blurBinomial(std::vector<std::uint16_t>& plane, std::int64_t width, std::int64_t height) {
        if (width < 3 || height < 3) return;
        std::vector<std::uint32_t> horizontal(plane.size());
        for (std::int64_t y = 0; y < height; ++y) {
            const std::uint16_t* row = plane.data() + y * width;
            std::uint32_t* out = horizontal.data() + y * width;
            out[0] = 3U * row[0] + row[1];
            for (std::int64_t x = 1; x + 1 < width; ++x) {
                out[x] = row[x - 1] + 2U * row[x] + row[x + 1];
            }
            out[width - 1] = row[width - 2] + 3U * row[width - 1];
        }
        for (std::int64_t y = 0; y < height; ++y) {
            const std::uint32_t* above = horizontal.data() + std::max<std::int64_t>(0, y - 1) * width;
            const std::uint32_t* center = horizontal.data() + y * width;
            const std::uint32_t* below = horizontal.data() + std::min(height - 1, y + 1) * width;
            std::uint16_t* out = plane.data() + y * width;
            for (std::int64_t x = 0; x < width; ++x) {
                out[x] = static_cast<std::uint16_t>((above[x] + 2U * center[x] + below[x] + 8U) / 16U);
            }
        }
    }

    // Matching weight per reference pixel: textured pixels carry the alignment signal, while flat
    // (noise-only) pixels keep a small floor weight so uniform scenes still behave predictably.
    static std::vector<std::uint16_t> textureWeights(
            const std::vector<std::uint16_t>& plane,
            std::int64_t width,
            std::int64_t height) {
        std::vector<std::uint16_t> weights(plane.size(), 16);
        for (std::int64_t y = 1; y + 1 < height; ++y) {
            for (std::int64_t x = 1; x + 1 < width; ++x) {
                const std::size_t index = static_cast<std::size_t>(y * width + x);
                const std::size_t stride = static_cast<std::size_t>(width);
                const int gradient =
                        std::abs(static_cast<int>(plane[index + 1]) - static_cast<int>(plane[index - 1])) +
                        std::abs(static_cast<int>(plane[index + stride]) - static_cast<int>(plane[index - stride]));
                weights[index] = static_cast<std::uint16_t>(16 + std::min(gradient, 2048) / 4);
            }
        }
        return weights;
    }

    bool motionAt(
            const AlignmentResult& alignment,
            std::int64_t x,
            std::int64_t y,
            std::int64_t coordinateWidth,
            std::int64_t coordinateHeight,
            double& dx,
            double& dy) const {
        if (alignment.isReference) {
            dx = 0.0;
            dy = 0.0;
            return true;
        }

        const double gridX = std::clamp(
                (static_cast<double>(x) + 0.5) * kLocalGridColumns / coordinateWidth - 0.5,
                0.0,
                static_cast<double>(kLocalGridColumns - 1));
        const double gridY = std::clamp(
                (static_cast<double>(y) + 0.5) * kLocalGridRows / coordinateHeight - 0.5,
                0.0,
                static_cast<double>(kLocalGridRows - 1));
        const auto x0 = static_cast<std::int64_t>(std::floor(gridX));
        const auto y0 = static_cast<std::int64_t>(std::floor(gridY));
        const auto x1 = std::min(x0 + 1, kLocalGridColumns - 1);
        const auto y1 = std::min(y0 + 1, kLocalGridRows - 1);
        const double fractionX = gridX - x0;
        const double fractionY = gridY - y0;
        const std::array<std::int64_t, 4> columns{x0, x1, x0, x1};
        const std::array<std::int64_t, 4> rows{y0, y0, y1, y1};
        const std::array<double, 4> weights{
                (1.0 - fractionX) * (1.0 - fractionY),
                fractionX * (1.0 - fractionY),
                (1.0 - fractionX) * fractionY,
                fractionX * fractionY,
        };
        double totalWeight = 0.0;
        double weightedX = 0.0;
        double weightedY = 0.0;
        for (std::size_t i = 0; i < weights.size(); ++i) {
            const std::size_t index = static_cast<std::size_t>(rows[i] * kLocalGridColumns + columns[i]);
            const LocalMotionRegion& region = alignment.localRegions[index];
            if (!region.reliable || weights[i] <= 0.0) continue;
            totalWeight += weights[i];
            weightedX += region.xCorrectionPixels * weights[i];
            weightedY += region.yCorrectionPixels * weights[i];
        }
        if (totalWeight > 0.25) {
            dx = weightedX / totalWeight;
            dy = weightedY / totalWeight;
            return true;
        }
        if (alignment.confidence >= 0.02 && !alignment.reachedSearchLimit) {
            dx = alignment.xCorrectionPixels;
            dy = alignment.yCorrectionPixels;
            return true;
        }
        return false;
    }

    void accumulateFusion(const std::vector<ColorProxyPixel>& colorProxy, const AlignmentResult& alignment) {
        bool contributed = false;
        constexpr double kWeightScale = 256.0;
        for (std::int64_t y = 0; y < fusionHeight_; ++y) {
            for (std::int64_t x = 0; x < fusionWidth_; ++x) {
                double correctionX = 0.0;
                double correctionY = 0.0;
                if (!motionAt(alignment, x, y, fusionWidth_, fusionHeight_, correctionX, correctionY)) {
                    continue;
                }

                const double targetX = static_cast<double>(x) + correctionX / kFusionScale;
                const double targetY = static_cast<double>(y) + correctionY / kFusionScale;
                const std::int64_t left = static_cast<std::int64_t>(std::floor(targetX));
                const std::int64_t top = static_cast<std::int64_t>(std::floor(targetY));
                const double fractionX = targetX - left;
                const double fractionY = targetY - top;
                const ColorProxyPixel& source = colorProxy[
                        static_cast<std::size_t>(y * fusionWidth_ + x)];
                for (std::int64_t offsetY = 0; offsetY <= 1; ++offsetY) {
                    const std::int64_t outputY = top + offsetY;
                    if (outputY < 0 || outputY >= fusionHeight_) continue;
                    const double weightY = offsetY == 0 ? 1.0 - fractionY : fractionY;
                    for (std::int64_t offsetX = 0; offsetX <= 1; ++offsetX) {
                        const std::int64_t outputX = left + offsetX;
                        if (outputX < 0 || outputX >= fusionWidth_) continue;
                        const double weightX = offsetX == 0 ? 1.0 - fractionX : fractionX;
                        const auto weight = static_cast<std::uint16_t>(std::lround(
                                std::clamp(weightX * weightY * kWeightScale, 0.0, kWeightScale)));
                        if (weight == 0) continue;
                        FusionPixelAccumulator& destination = fusion_[
                                static_cast<std::size_t>(outputY * fusionWidth_ + outputX)];
                        const std::uint32_t totalWeight = destination.weight + weight;
                        const double blend = static_cast<double>(weight) / totalWeight;
                        destination.red = static_cast<std::uint16_t>(std::lround(
                                destination.red + (static_cast<double>(source.red) - destination.red) * blend));
                        destination.green = static_cast<std::uint16_t>(std::lround(
                                destination.green + (static_cast<double>(source.green) - destination.green) * blend));
                        destination.blue = static_cast<std::uint16_t>(std::lround(
                                destination.blue + (static_cast<double>(source.blue) - destination.blue) * blend));
                        destination.weight = static_cast<std::uint16_t>(std::min<std::uint32_t>(
                                totalWeight, std::numeric_limits<std::uint16_t>::max()));
                        contributed = true;
                    }
                }
            }
        }
        if (contributed) ++fusionFrameCount_;
    }

    double scoreAt(
            const std::vector<std::uint16_t>& current,
            std::int64_t offsetX,
            std::int64_t offsetY) const {
        const std::int64_t margin = kSearchRadius;
        return scoreRegionAt(
                current,
                offsetX,
                offsetY,
                margin,
                proxyWidth_ - margin,
                margin,
                proxyHeight_ - margin,
                kCorrelationSampleStep);
    }

    double scoreRegionAt(
            const std::vector<std::uint16_t>& current,
            std::int64_t offsetX,
            std::int64_t offsetY,
            std::int64_t startX,
            std::int64_t endX,
            std::int64_t startY,
            std::int64_t endY,
            std::int64_t sampleStep) const {
        std::uint64_t totalDifference = 0;
        std::uint64_t totalWeight = 0;

        for (std::int64_t y = startY; y < endY; y += sampleStep) {
            // Quincunx sampling: alternate rows are offset by half a step so regular textures
            // cannot alias with the sampling grid.
            const std::int64_t phase = (((y - startY) / sampleStep) & 1) * (sampleStep / 2);
            for (std::int64_t x = startX + phase; x < endX; x += sampleStep) {
                const auto referenceIndex = static_cast<std::size_t>(
                        (y + offsetY) * proxyWidth_ + x + offsetX);
                const std::int64_t currentIndex = y * proxyWidth_ + x;
                const int difference = static_cast<int>(alignReference_[referenceIndex]) -
                        static_cast<int>(current[static_cast<std::size_t>(currentIndex)]);
                const std::uint32_t weight = alignWeights_[referenceIndex];
                totalDifference += static_cast<std::uint64_t>(difference < 0 ? -difference : difference) * weight;
                totalWeight += weight;
            }
        }
        return totalWeight == 0 ? std::numeric_limits<double>::infinity()
                                : static_cast<double>(totalDifference) / static_cast<double>(totalWeight);
    }

    void estimateLocalMotion(
            const std::vector<std::uint16_t>& current,
            std::int64_t globalX,
            std::int64_t globalY,
            double globalSubpixelX,
            double globalSubpixelY,
            AlignmentResult& result) const {
        const std::int64_t patchMargin = kSearchRadius + kLocalSearchRadius + 1;
        const std::int64_t gridMargin = kSearchRadius + 1;
        const std::int64_t innerLeft = gridMargin;
        const std::int64_t innerRight = proxyWidth_ - gridMargin;
        const std::int64_t innerTop = gridMargin;
        const std::int64_t innerBottom = proxyHeight_ - gridMargin;
        double confidenceSum = 0.0;
        double squaredSpreadSum = 0.0;
        std::int32_t evaluatedRegionCount = 0;

        for (std::int64_t row = 0; row < kLocalGridRows; ++row) {
            const std::int64_t rowStart = innerTop +
                    (innerBottom - innerTop) * row / kLocalGridRows;
            const std::int64_t rowEnd = innerTop +
                    (innerBottom - innerTop) * (row + 1) / kLocalGridRows;
            for (std::int64_t column = 0; column < kLocalGridColumns; ++column) {
                const std::int64_t columnStart = innerLeft +
                        (innerRight - innerLeft) * column / kLocalGridColumns;
                const std::int64_t columnEnd = innerLeft +
                        (innerRight - innerLeft) * (column + 1) / kLocalGridColumns;
                const std::int64_t startX = columnStart + patchMargin;
                const std::int64_t endX = columnEnd - patchMargin;
                const std::int64_t startY = rowStart + patchMargin;
                const std::int64_t endY = rowEnd - patchMargin;
                if (endX - startX < 12 || endY - startY < 12) continue;

                std::array<double, kLocalCandidateCount> scores{};
                double bestScore = std::numeric_limits<double>::infinity();
                std::int64_t bestX = globalX;
                std::int64_t bestY = globalY;
                for (std::int64_t dy = -kLocalSearchRadius; dy <= kLocalSearchRadius; ++dy) {
                    for (std::int64_t dx = -kLocalSearchRadius; dx <= kLocalSearchRadius; ++dx) {
                        const std::int64_t candidateX = globalX + dx;
                        const std::int64_t candidateY = globalY + dy;
                        const double score = scoreRegionAt(
                                current,
                                candidateX,
                                candidateY,
                                startX,
                                endX,
                                startY,
                                endY,
                                kCorrelationSampleStep / 2);
                        const auto scoreIndex = static_cast<std::size_t>(dy + kLocalSearchRadius) *
                                kLocalCandidateSide + static_cast<std::size_t>(dx + kLocalSearchRadius);
                        scores[scoreIndex] = score;
                        if (score < bestScore) {
                            bestScore = score;
                            bestX = candidateX;
                            bestY = candidateY;
                        }
                    }
                }

                double secondBestScore = std::numeric_limits<double>::infinity();
                for (std::int64_t dy = -kLocalSearchRadius; dy <= kLocalSearchRadius; ++dy) {
                    for (std::int64_t dx = -kLocalSearchRadius; dx <= kLocalSearchRadius; ++dx) {
                        if (std::abs(dx) <= 1 && std::abs(dy) <= 1) continue;
                        const auto scoreIndex = static_cast<std::size_t>(dy + kLocalSearchRadius) *
                                kLocalCandidateSide + static_cast<std::size_t>(dx + kLocalSearchRadius);
                        secondBestScore = std::min(secondBestScore, scores[scoreIndex]);
                    }
                }

                double confidence = 0.0;
                if (std::isfinite(secondBestScore) && secondBestScore > 0.0) {
                    confidence = std::max(
                            0.0,
                            (secondBestScore - bestScore) / (secondBestScore + 1.0));
                }
                confidenceSum += confidence;
                ++evaluatedRegionCount;

                const std::int64_t localDeltaX = bestX - globalX;
                const std::int64_t localDeltaY = bestY - globalY;
                const bool reachedLocalLimit =
                        std::abs(localDeltaX) == kLocalSearchRadius ||
                        std::abs(localDeltaY) == kLocalSearchRadius;

                double subpixelX = 0.0;
                double subpixelY = 0.0;
                if (std::abs(localDeltaX) < kLocalSearchRadius) {
                    subpixelX = parabolicOffset(
                            scoreRegionAt(current, bestX - 1, bestY, startX, endX, startY, endY,
                                    kCorrelationSampleStep / 2),
                            bestScore,
                            scoreRegionAt(current, bestX + 1, bestY, startX, endX, startY, endY,
                                    kCorrelationSampleStep / 2));
                }
                if (std::abs(localDeltaY) < kLocalSearchRadius) {
                    subpixelY = parabolicOffset(
                            scoreRegionAt(current, bestX, bestY - 1, startX, endX, startY, endY,
                                    kCorrelationSampleStep / 2),
                            bestScore,
                            scoreRegionAt(current, bestX, bestY + 1, startX, endX, startY, endY,
                                    kCorrelationSampleStep / 2));
                }

                const double localX = (static_cast<double>(bestX) + subpixelX) * kProxyScale;
                const double localY = (static_cast<double>(bestY) + subpixelY) * kProxyScale;
                const std::size_t regionIndex = static_cast<std::size_t>(row * kLocalGridColumns + column);
                LocalMotionRegion& region = result.localRegions[regionIndex];
                region.xCorrectionPixels = localX;
                region.yCorrectionPixels = localY;
                region.confidence = confidence;
                region.reliable = confidence >= 0.05 && !reachedLocalLimit;
                if (!region.reliable) continue;

                const double globalSensorX =
                        (static_cast<double>(globalX) + globalSubpixelX) * kProxyScale;
                const double globalSensorY =
                        (static_cast<double>(globalY) + globalSubpixelY) * kProxyScale;
                const double deviationX = localX - globalSensorX;
                const double deviationY = localY - globalSensorY;
                squaredSpreadSum += deviationX * deviationX + deviationY * deviationY;
                ++result.reliableLocalRegionCount;
            }
        }

        if (evaluatedRegionCount > 0) {
            result.meanLocalConfidence = confidenceSum / evaluatedRegionCount;
        }
        result.evaluatedLocalRegionCount = evaluatedRegionCount;
        if (result.reliableLocalRegionCount > 0) {
            result.localMotionSpreadPixels = std::sqrt(
                    squaredSpreadSum / result.reliableLocalRegionCount);
        }
    }

    static double parabolicOffset(double before, double center, double after) {
        const double denominator = before - 2.0 * center + after;
        if (!std::isfinite(denominator) || denominator <= 1e-9) return 0.0;
        const double offset = 0.5 * (before - after) / denominator;
        return offset < -0.5 ? -0.5 : (offset > 0.5 ? 0.5 : offset);
    }

    AlignmentResult estimateTranslation(const std::vector<std::uint16_t>& current) const {
        AlignmentResult result;
        if (proxyWidth_ <= 2 * kSearchRadius + 1 || proxyHeight_ <= 2 * kSearchRadius + 1) {
            result.meanAbsoluteError = -1.0;
            return result;
        }

        double bestScore = std::numeric_limits<double>::infinity();
        std::int64_t bestX = 0;
        std::int64_t bestY = 0;
        std::array<double, kCandidateCount> scores{};
        for (std::int64_t dy = -kSearchRadius; dy <= kSearchRadius; ++dy) {
            for (std::int64_t dx = -kSearchRadius; dx <= kSearchRadius; ++dx) {
                const double score = scoreAt(current, dx, dy);
                const auto scoreIndex = static_cast<std::size_t>(dy + kSearchRadius) *
                        kCandidateSide + static_cast<std::size_t>(dx + kSearchRadius);
                scores[scoreIndex] = score;
                if (score < bestScore) {
                    bestScore = score;
                    bestX = dx;
                    bestY = dy;
                }
            }
        }

        double secondBestScore = std::numeric_limits<double>::infinity();
        for (std::int64_t dy = -kSearchRadius; dy <= kSearchRadius; ++dy) {
            for (std::int64_t dx = -kSearchRadius; dx <= kSearchRadius; ++dx) {
                if (std::abs(dx - bestX) <= 1 && std::abs(dy - bestY) <= 1) continue;
                const auto scoreIndex = static_cast<std::size_t>(dy + kSearchRadius) *
                        kCandidateSide + static_cast<std::size_t>(dx + kSearchRadius);
                secondBestScore = std::min(secondBestScore, scores[scoreIndex]);
            }
        }

        double subpixelX = 0.0;
        double subpixelY = 0.0;
        if (bestX > -kSearchRadius && bestX < kSearchRadius) {
            subpixelX = parabolicOffset(
                    scoreAt(current, bestX - 1, bestY),
                    bestScore,
                    scoreAt(current, bestX + 1, bestY));
        }
        if (bestY > -kSearchRadius && bestY < kSearchRadius) {
            subpixelY = parabolicOffset(
                    scoreAt(current, bestX, bestY - 1),
                    bestScore,
                    scoreAt(current, bestX, bestY + 1));
        }

        result.meanAbsoluteError = bestScore;
        if (std::isfinite(secondBestScore) && secondBestScore > 0.0) {
            result.confidence = std::max(
                    0.0,
                    (secondBestScore - bestScore) / (secondBestScore + 1.0));
        }
        result.reachedSearchLimit = bestX == -kSearchRadius || bestX == kSearchRadius ||
                bestY == -kSearchRadius || bestY == kSearchRadius;
        if (result.confidence >= 0.05 && !result.reachedSearchLimit) {
            // The offsets map current-frame coordinates back into the first frame's coordinates.
            result.xCorrectionPixels = (static_cast<double>(bestX) + subpixelX) * kProxyScale;
            result.yCorrectionPixels = (static_cast<double>(bestY) + subpixelY) * kProxyScale;
            estimateLocalMotion(current, bestX, bestY, subpixelX, subpixelY, result);
        } else if (result.confidence >= 0.02 && !result.reachedSearchLimit) {
            result.xCorrectionPixels = (static_cast<double>(bestX) + subpixelX) * kProxyScale;
            result.yCorrectionPixels = (static_cast<double>(bestY) + subpixelY) * kProxyScale;
        }
        return result;
    }

    double fineScoreAt(
            const std::vector<std::uint16_t>& current,
            std::int64_t offsetX,
            std::int64_t offsetY,
            std::int64_t startX,
            std::int64_t endX,
            std::int64_t startY,
            std::int64_t endY,
            std::int64_t sampleStep) const {
        std::uint64_t totalDifference = 0;
        std::uint64_t samples = 0;
        for (std::int64_t y = startY; y < endY; y += sampleStep) {
            const std::int64_t referenceY = y + offsetY;
            if (referenceY < 0 || referenceY >= fusionHeight_) continue;
            // Quincunx sampling: alternate rows are offset by half a step so regular textures
            // cannot alias with the sampling grid.
            const std::int64_t phase = (((y - startY) / sampleStep) & 1) * (sampleStep / 2);
            for (std::int64_t x = startX + phase; x < endX; x += sampleStep) {
                const std::int64_t referenceX = x + offsetX;
                if (referenceX < 0 || referenceX >= fusionWidth_) continue;
                const auto referenceIndex = static_cast<std::size_t>(referenceY * fusionWidth_ + referenceX);
                const std::int64_t currentIndex = y * fusionWidth_ + x;
                const int difference = static_cast<int>(fineReference_[referenceIndex]) -
                        static_cast<int>(current[static_cast<std::size_t>(currentIndex)]);
                const std::uint32_t weight = fineWeights_[referenceIndex];
                totalDifference += static_cast<std::uint64_t>(
                        difference < 0 ? -difference : difference) * weight;
                samples += weight;
            }
        }
        return samples == 0
                ? std::numeric_limits<double>::infinity()
                : static_cast<double>(totalDifference) / static_cast<double>(samples);
    }

    void refineAlignment(const std::vector<std::uint16_t>& fineCurrent, AlignmentResult& result) const {
        if (result.isReference || result.confidence < 0.02 || result.reachedSearchLimit ||
            fineReference_.size() != fineCurrent.size() || fusionWidth_ <= 2 * kSearchRadius + 8 ||
            fusionHeight_ <= 2 * kSearchRadius + 8) {
            return;
        }

        constexpr std::int64_t kRefineRadius = 2;
        constexpr std::int64_t kFineStep = 2;
        constexpr std::size_t kRefineSide = static_cast<std::size_t>(2 * kRefineRadius + 1);
        constexpr std::size_t kRefineCount = kRefineSide * kRefineSide;
        const std::int64_t globalMargin = 2 * kSearchRadius + 2;
        const std::int64_t globalStartX = globalMargin;
        const std::int64_t globalEndX = fusionWidth_ - globalMargin;
        const std::int64_t globalStartY = globalMargin;
        const std::int64_t globalEndY = fusionHeight_ - globalMargin;
        const std::int64_t globalCenterX = static_cast<std::int64_t>(
                std::lround(result.xCorrectionPixels / 2.0));
        const std::int64_t globalCenterY = static_cast<std::int64_t>(
                std::lround(result.yCorrectionPixels / 2.0));
        std::array<double, kRefineCount> globalScores{};
        double globalBestScore = std::numeric_limits<double>::infinity();
        std::int64_t globalBestX = globalCenterX;
        std::int64_t globalBestY = globalCenterY;
        for (std::int64_t dy = -kRefineRadius; dy <= kRefineRadius; ++dy) {
            for (std::int64_t dx = -kRefineRadius; dx <= kRefineRadius; ++dx) {
                const double score = fineScoreAt(
                        fineCurrent,
                        globalCenterX + dx,
                        globalCenterY + dy,
                        globalStartX,
                        globalEndX,
                        globalStartY,
                        globalEndY,
                        kFineStep);
                const std::size_t index = static_cast<std::size_t>(dy + kRefineRadius) *
                        kRefineSide + static_cast<std::size_t>(dx + kRefineRadius);
                globalScores[index] = score;
                if (score < globalBestScore) {
                    globalBestScore = score;
                    globalBestX = globalCenterX + dx;
                    globalBestY = globalCenterY + dy;
                }
            }
        }
        double globalSecondBest = std::numeric_limits<double>::infinity();
        for (std::int64_t dy = -kRefineRadius; dy <= kRefineRadius; ++dy) {
            for (std::int64_t dx = -kRefineRadius; dx <= kRefineRadius; ++dx) {
                const std::size_t index = static_cast<std::size_t>(dy + kRefineRadius) *
                        kRefineSide + static_cast<std::size_t>(dx + kRefineRadius);
                if (std::abs(globalCenterX + dx - globalBestX) <= 1 &&
                    std::abs(globalCenterY + dy - globalBestY) <= 1) {
                    continue;
                }
                globalSecondBest = std::min(globalSecondBest, globalScores[index]);
            }
        }
        const double fineGlobalConfidence = std::isfinite(globalSecondBest) && globalSecondBest > 0.0
                ? std::max(0.0, (globalSecondBest - globalBestScore) / (globalSecondBest + 1.0))
                : 0.0;
        if (fineGlobalConfidence >= 0.01 && std::isfinite(globalBestScore)) {
            double subpixelX = 0.0;
            double subpixelY = 0.0;
            if (globalBestX > 0 && globalBestX + 1 < fusionWidth_) {
                subpixelX = parabolicOffset(
                        fineScoreAt(fineCurrent, globalBestX - 1, globalBestY,
                                    globalStartX, globalEndX, globalStartY, globalEndY, kFineStep),
                        globalBestScore,
                        fineScoreAt(fineCurrent, globalBestX + 1, globalBestY,
                                    globalStartX, globalEndX, globalStartY, globalEndY, kFineStep));
            }
            if (globalBestY > 0 && globalBestY + 1 < fusionHeight_) {
                subpixelY = parabolicOffset(
                        fineScoreAt(fineCurrent, globalBestX, globalBestY - 1,
                                    globalStartX, globalEndX, globalStartY, globalEndY, kFineStep),
                        globalBestScore,
                        fineScoreAt(fineCurrent, globalBestX, globalBestY + 1,
                                    globalStartX, globalEndX, globalStartY, globalEndY, kFineStep));
            }
            result.xCorrectionPixels = (globalBestX + subpixelX) * 2.0;
            result.yCorrectionPixels = (globalBestY + subpixelY) * 2.0;
            result.confidence = std::max(result.confidence, fineGlobalConfidence);
            result.meanAbsoluteError = globalBestScore;
        }

        const std::int64_t localGridMargin = 2 * (kSearchRadius + 1);
        const std::int64_t localPatchMargin = 2 * (kSearchRadius + kLocalSearchRadius + 1);
        const std::int64_t innerLeft = localGridMargin;
        const std::int64_t innerRight = fusionWidth_ - localGridMargin;
        const std::int64_t innerTop = localGridMargin;
        const std::int64_t innerBottom = fusionHeight_ - localGridMargin;
        for (std::int64_t row = 0; row < kLocalGridRows; ++row) {
            const std::int64_t rowStart = innerTop + (innerBottom - innerTop) * row / kLocalGridRows;
            const std::int64_t rowEnd = innerTop + (innerBottom - innerTop) * (row + 1) / kLocalGridRows;
            for (std::int64_t column = 0; column < kLocalGridColumns; ++column) {
                LocalMotionRegion& region = result.localRegions[
                        static_cast<std::size_t>(row * kLocalGridColumns + column)];
                if (!region.reliable) continue;
                const std::int64_t columnStart = innerLeft +
                        (innerRight - innerLeft) * column / kLocalGridColumns;
                const std::int64_t columnEnd = innerLeft +
                        (innerRight - innerLeft) * (column + 1) / kLocalGridColumns;
                const std::int64_t startX = columnStart + localPatchMargin;
                const std::int64_t endX = columnEnd - localPatchMargin;
                const std::int64_t startY = rowStart + localPatchMargin;
                const std::int64_t endY = rowEnd - localPatchMargin;
                if (endX - startX < 12 || endY - startY < 12) continue;

                const std::int64_t centerX = static_cast<std::int64_t>(
                        std::lround(region.xCorrectionPixels / 2.0));
                const std::int64_t centerY = static_cast<std::int64_t>(
                        std::lround(region.yCorrectionPixels / 2.0));
                std::array<double, kRefineCount> scores{};
                double bestScore = std::numeric_limits<double>::infinity();
                std::int64_t bestX = centerX;
                std::int64_t bestY = centerY;
                for (std::int64_t dy = -kRefineRadius; dy <= kRefineRadius; ++dy) {
                    for (std::int64_t dx = -kRefineRadius; dx <= kRefineRadius; ++dx) {
                        const double score = fineScoreAt(
                                fineCurrent,
                                centerX + dx,
                                centerY + dy,
                                startX,
                                endX,
                                startY,
                                endY,
                                kFineStep);
                        const std::size_t index = static_cast<std::size_t>(dy + kRefineRadius) *
                                kRefineSide + static_cast<std::size_t>(dx + kRefineRadius);
                        scores[index] = score;
                        if (score < bestScore) {
                            bestScore = score;
                            bestX = centerX + dx;
                            bestY = centerY + dy;
                        }
                    }
                }
                double secondBestScore = std::numeric_limits<double>::infinity();
                for (std::int64_t dy = -kRefineRadius; dy <= kRefineRadius; ++dy) {
                    for (std::int64_t dx = -kRefineRadius; dx <= kRefineRadius; ++dx) {
                        if (std::abs(centerX + dx - bestX) <= 1 &&
                            std::abs(centerY + dy - bestY) <= 1) {
                            continue;
                        }
                        const std::size_t index = static_cast<std::size_t>(dy + kRefineRadius) *
                                kRefineSide + static_cast<std::size_t>(dx + kRefineRadius);
                        secondBestScore = std::min(secondBestScore, scores[index]);
                    }
                }
                const double fineConfidence = std::isfinite(secondBestScore) && secondBestScore > 0.0
                        ? std::max(0.0, (secondBestScore - bestScore) / (secondBestScore + 1.0))
                        : 0.0;
                if (fineConfidence < 0.01 || !std::isfinite(bestScore)) continue;

                double subpixelX = 0.0;
                double subpixelY = 0.0;
                if (bestX > 0 && bestX + 1 < fusionWidth_) {
                    subpixelX = parabolicOffset(
                            fineScoreAt(fineCurrent, bestX - 1, bestY,
                                        startX, endX, startY, endY, kFineStep),
                            bestScore,
                            fineScoreAt(fineCurrent, bestX + 1, bestY,
                                        startX, endX, startY, endY, kFineStep));
                }
                if (bestY > 0 && bestY + 1 < fusionHeight_) {
                    subpixelY = parabolicOffset(
                            fineScoreAt(fineCurrent, bestX, bestY - 1,
                                        startX, endX, startY, endY, kFineStep),
                            bestScore,
                            fineScoreAt(fineCurrent, bestX, bestY + 1,
                                        startX, endX, startY, endY, kFineStep));
                }
                region.xCorrectionPixels = (bestX + subpixelX) * 2.0;
                region.yCorrectionPixels = (bestY + subpixelY) * 2.0;
                region.confidence = std::max(region.confidence, fineConfidence);
            }
        }

        double confidenceSum = 0.0;
        double squaredSpread = 0.0;
        std::int32_t reliableCount = 0;
        for (const LocalMotionRegion& region : result.localRegions) {
            if (!region.reliable) continue;
            const double dx = region.xCorrectionPixels - result.xCorrectionPixels;
            const double dy = region.yCorrectionPixels - result.yCorrectionPixels;
            squaredSpread += dx * dx + dy * dy;
            confidenceSum += region.confidence;
            ++reliableCount;
        }
        result.reliableLocalRegionCount = reliableCount;
        if (reliableCount > 0) {
            result.localMotionSpreadPixels = std::sqrt(squaredSpread / reliableCount);
            result.meanLocalConfidence = confidenceSum / reliableCount;
        }
    }

    std::int64_t proxyWidth_ = 0;
    std::int64_t proxyHeight_ = 0;
    std::int64_t fusionWidth_ = 0;
    std::int64_t fusionHeight_ = 0;
    std::int64_t sensorWidth_ = 0;
    std::int64_t sensorHeight_ = 0;
    std::int64_t superResolutionWidth_ = 0;
    std::int64_t superResolutionHeight_ = 0;
    std::int64_t referenceWidth_ = 0;
    std::int64_t referenceHeight_ = 0;
    std::vector<std::uint16_t> reference_;
    std::vector<std::uint16_t> fineReference_;
    std::vector<std::uint16_t> alignReference_;
    std::vector<std::uint16_t> alignWeights_;
    std::vector<std::uint16_t> fineWeights_;
    std::vector<FusionPixelAccumulator> fusion_;
    std::int32_t fusionFrameCount_ = 0;
    std::string superResolutionScratchPath_;
    int superResolutionFileDescriptor_ = -1;
    std::size_t superResolutionBytes_ = 0;
    SuperResolutionPixel* superResolutionPixels_ = nullptr;
    std::int32_t superResolutionFrameCount_ = 0;
    std::array<double, 3> superResolutionGains_{1.0, 1.0, 1.0};
    std::array<double, 9> superResolutionMatrix_{1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0};
};

}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_azuchan_fabula_ui_camera_NativeRawFrameAnalyzer_analyzeRawBuffer(
        JNIEnv* env,
        jobject /* receiver */,
        jobject buffer,
        jint width,
        jint height,
        jint rowStride,
        jint pixelStride,
        jint sampleStep) {
    if (buffer == nullptr || width <= 0 || height <= 0 || rowStride <= 0 ||
        pixelStride < 2 || sampleStep <= 0) {
        throwIllegalArgument(env, "Invalid RAW frame buffer or dimensions.");
        return nullptr;
    }

    const auto* bytes = static_cast<const std::uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (bytes == nullptr || capacity < 0) {
        throwIllegalArgument(env, "RAW frame data must use a direct ByteBuffer.");
        return nullptr;
    }

    const std::int64_t lastSampleOffset =
            static_cast<std::int64_t>(height - 1) * rowStride +
            static_cast<std::int64_t>(width - 1) * pixelStride;
    if (lastSampleOffset < 0 || lastSampleOffset + 1 >= capacity) {
        throwIllegalArgument(env, "RAW frame layout exceeds the available buffer capacity.");
        return nullptr;
    }

    std::uint16_t minimum = std::numeric_limits<std::uint16_t>::max();
    std::uint16_t maximum = 0;
    std::int64_t sum = 0;
    std::int64_t sampleCount = 0;

    for (std::int64_t y = 0; y < height; y += sampleStep) {
        const std::int64_t rowOffset = y * rowStride;
        for (std::int64_t x = 0; x < width; x += sampleStep) {
            const std::int64_t offset = rowOffset + x * pixelStride;
            const std::uint16_t sample = static_cast<std::uint16_t>(
                    bytes[offset] | (static_cast<std::uint16_t>(bytes[offset + 1]) << 8));

            minimum = sample < minimum ? sample : minimum;
            maximum = sample > maximum ? sample : maximum;
            sum += sample;
            ++sampleCount;
        }
    }

    const jlong values[] = {
            sampleCount,
            minimum,
            maximum,
            sum,
    };
    constexpr jsize kResultLength = static_cast<jsize>(sizeof(values) / sizeof(values[0]));
    jlongArray result = env->NewLongArray(kResultLength);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, kResultLength, values);
    }
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_azuchan_fabula_ui_camera_NativeRawBurstAligner_createAligner(
        JNIEnv* env,
        jobject /* receiver */,
        jstring scratchPath) {
    if (scratchPath == nullptr) {
        throwIllegalArgument(env, "A scratch-file path is required for RAW fusion.");
        return 0;
    }
    const char* pathChars = env->GetStringUTFChars(scratchPath, nullptr);
    if (pathChars == nullptr) return 0;
    std::string path(pathChars);
    env->ReleaseStringUTFChars(scratchPath, pathChars);
    auto* aligner = new (std::nothrow) RawBurstAligner(std::move(path));
    if (aligner == nullptr) {
        throwOutOfMemory(env, "Could not allocate the RAW burst aligner.");
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<std::intptr_t>(aligner));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_azuchan_fabula_ui_camera_NativeRawBurstAligner_nativeWriteSuperResolutionTiff(
        JNIEnv* env,
        jobject /* receiver */,
        jlong handle,
        jstring outputPath,
        jint orientation) {
    auto* aligner = reinterpret_cast<RawBurstAligner*>(static_cast<std::intptr_t>(handle));
    if (aligner == nullptr || outputPath == nullptr) {
        throwIllegalState(env, "The RAW burst aligner is closed or the output path is null.");
        return nullptr;
    }
    const char* pathChars = env->GetStringUTFChars(outputPath, nullptr);
    if (pathChars == nullptr) return nullptr;
    const std::string path(pathChars);
    env->ReleaseStringUTFChars(outputPath, pathChars);
    try {
        if (!aligner->writeSuperResolutionTiff(path, orientation)) return env->NewIntArray(0);
        const jint dimensions[] = {
                static_cast<jint>(aligner->superResolutionWidth()),
                static_cast<jint>(aligner->superResolutionHeight()),
                aligner->superResolutionFrameCount(),
        };
        jintArray result = env->NewIntArray(3);
        if (result != nullptr) env->SetIntArrayRegion(result, 0, 3, dimensions);
        return result;
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env, "Could not allocate a row while writing the RAW super-resolution TIFF.");
        return nullptr;
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_azuchan_fabula_ui_camera_NativeRawBurstAligner_nativeWriteSuperResolutionTiffToDescriptor(
        JNIEnv* env,
        jobject /* receiver */,
        jlong handle,
        jobject parcelFileDescriptor,
        jint orientation) {
    auto* aligner = reinterpret_cast<RawBurstAligner*>(static_cast<std::intptr_t>(handle));
    if (aligner == nullptr || parcelFileDescriptor == nullptr) {
        throwIllegalState(env, "The RAW burst aligner is closed or the output descriptor is null.");
        return nullptr;
    }
    jclass descriptorClass = env->GetObjectClass(parcelFileDescriptor);
    if (descriptorClass == nullptr) return nullptr;
    jmethodID getFileDescriptor = env->GetMethodID(descriptorClass, "getFd", "()I");
    if (getFileDescriptor == nullptr) return nullptr;
    const jint fileDescriptor = env->CallIntMethod(parcelFileDescriptor, getFileDescriptor);
    if (env->ExceptionCheck()) return nullptr;

    try {
        if (!aligner->writeSuperResolutionTiffToFileDescriptor(fileDescriptor, orientation)) {
            return env->NewIntArray(0);
        }
        const jint dimensions[] = {
                aligner->superResolutionWidth(),
                aligner->superResolutionHeight(),
                aligner->superResolutionFrameCount(),
        };
        jintArray result = env->NewIntArray(3);
        if (result != nullptr) env->SetIntArrayRegion(result, 0, 3, dimensions);
        return result;
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env, "Could not allocate a row while writing the RAW super-resolution TIFF.");
        return nullptr;
    }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_azuchan_fabula_ui_camera_NativeRawBurstAligner_nativeAddFrame(
        JNIEnv* env,
        jobject /* receiver */,
        jlong handle,
        jobject buffer,
        jint width,
        jint height,
        jint rowStride,
        jint pixelStride,
        jintArray cfaChannelsArray,
        jdoubleArray blackLevelsArray,
        jdouble whiteLevel,
        jdoubleArray colorGainsArray,
        jdoubleArray colorMatrixArray) {
    auto* aligner = reinterpret_cast<RawBurstAligner*>(static_cast<std::intptr_t>(handle));
    if (aligner == nullptr || buffer == nullptr || cfaChannelsArray == nullptr ||
        blackLevelsArray == nullptr || colorGainsArray == nullptr || colorMatrixArray == nullptr) {
        throwIllegalState(env, "The RAW burst aligner is closed or an input is null.");
        return nullptr;
    }

    const auto* bytes = static_cast<const std::uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (bytes == nullptr || capacity < 0) {
        throwIllegalArgument(env, "RAW frame data must use a direct ByteBuffer.");
        return nullptr;
    }

    if (env->GetArrayLength(cfaChannelsArray) != 4 ||
        env->GetArrayLength(blackLevelsArray) != 4 ||
        env->GetArrayLength(colorGainsArray) != 3 ||
        env->GetArrayLength(colorMatrixArray) != 9) {
        throwIllegalArgument(env, "CFA, black-level, color-gain, and matrix arrays have invalid lengths.");
        return nullptr;
    }
    jint cfaChannels[4];
    jdouble blackLevels[4];
    jdouble colorGains[3];
    jdouble colorMatrix[9];
    env->GetIntArrayRegion(cfaChannelsArray, 0, 4, cfaChannels);
    env->GetDoubleArrayRegion(blackLevelsArray, 0, 4, blackLevels);
    env->GetDoubleArrayRegion(colorGainsArray, 0, 3, colorGains);
    env->GetDoubleArrayRegion(colorMatrixArray, 0, 9, colorMatrix);
    if (env->ExceptionCheck()) return nullptr;

    try {
        std::vector<std::uint16_t> proxy;
        std::vector<ColorProxyPixel> colorProxy;
        AlignmentResult alignment;
        if (!aligner->addFrame(
                    bytes,
                    capacity,
                    width,
                    height,
                    rowStride,
                    pixelStride,
                    cfaChannels,
                    blackLevels,
                    whiteLevel,
                    colorGains,
                    colorMatrix,
                    proxy,
                    colorProxy,
                    alignment)) {
            throwIllegalArgument(env, "RAW frame layout is invalid or changed during the burst.");
            return nullptr;
        }

        const jdouble values[] = {
                alignment.xCorrectionPixels,
                alignment.yCorrectionPixels,
                alignment.meanAbsoluteError,
                alignment.confidence,
                alignment.isReference ? 1.0 : 0.0,
                alignment.reachedSearchLimit ? 1.0 : 0.0,
                alignment.localMotionSpreadPixels,
                alignment.meanLocalConfidence,
                static_cast<double>(alignment.reliableLocalRegionCount),
                static_cast<double>(alignment.evaluatedLocalRegionCount),
        };
        constexpr jsize headerLength = static_cast<jsize>(sizeof(values) / sizeof(values[0]));
        constexpr jsize regionStride = 4;
        constexpr jsize resultLength = headerLength +
                static_cast<jsize>(kLocalRegionCount) * regionStride;
        jdoubleArray result = env->NewDoubleArray(resultLength);
        if (result != nullptr) {
            env->SetDoubleArrayRegion(result, 0, headerLength, values);
            jdouble localValues[kLocalRegionCount * regionStride];
            for (std::size_t i = 0; i < kLocalRegionCount; ++i) {
                const jsize base = static_cast<jsize>(i * regionStride);
                const LocalMotionRegion& region = alignment.localRegions[i];
                localValues[base] = region.xCorrectionPixels;
                localValues[base + 1] = region.yCorrectionPixels;
                localValues[base + 2] = region.confidence;
                localValues[base + 3] = region.reliable ? 1.0 : 0.0;
            }
            env->SetDoubleArrayRegion(
                    result,
                    headerLength,
                    static_cast<jsize>(kLocalRegionCount * regionStride),
                    localValues);
        }
        return result;
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env, "Could not allocate a reduced RAW frame proxy.");
        return nullptr;
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_azuchan_fabula_ui_camera_NativeRawBurstAligner_nativeFinishFusionPreview(
        JNIEnv* env,
        jobject /* receiver */,
        jlong handle) {
    auto* aligner = reinterpret_cast<RawBurstAligner*>(static_cast<std::intptr_t>(handle));
    if (aligner == nullptr) {
        throwIllegalState(env, "The native RAW burst aligner is closed.");
        return nullptr;
    }
    try {
        const std::vector<jint> pixels = aligner->finishFusionPreview();
        if (pixels.empty()) return env->NewIntArray(0);
        jintArray result = env->NewIntArray(static_cast<jsize>(pixels.size()));
        if (result != nullptr) {
            env->SetIntArrayRegion(result, 0, static_cast<jsize>(pixels.size()), pixels.data());
        }
        return result;
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env, "Could not allocate the RAW fusion preview.");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_azuchan_fabula_ui_camera_NativeRawBurstAligner_destroyAligner(
        JNIEnv* /* env */,
        jobject /* receiver */,
        jlong handle) {
    auto* aligner = reinterpret_cast<RawBurstAligner*>(static_cast<std::intptr_t>(handle));
    delete aligner;
}
