#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
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
#include <sys/resource.h>
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

constexpr const char* kLogTag = "FabulaNative";
constexpr char kSuperResolutionDescription[] =
        "Fabula 2x RAW burst reconstruction prototype; not a native 48 MP capture.";

unsigned workerCount() {
    const unsigned hardwareThreads = std::thread::hardware_concurrency();
    return std::clamp(hardwareThreads == 0 ? 4U : hardwareThreads, 1U, 8U);
}

// Runs body(begin, end, workerIndex) over [0, count) in dynamically scheduled chunks on up to
// workerCount() threads; the calling thread is worker 0. Bodies must not throw.
template <typename Body>
void parallelForWorkers(std::int64_t count, std::int64_t chunk, const Body& body) {
    if (count <= 0) return;
    chunk = std::max<std::int64_t>(1, chunk);
    std::atomic<std::int64_t> next{0};
    const auto worker = [&](unsigned workerIndex) {
        for (;;) {
            const std::int64_t begin = next.fetch_add(chunk, std::memory_order_relaxed);
            if (begin >= count) break;
            body(begin, std::min(begin + chunk, count), workerIndex);
        }
    };
    const std::int64_t chunkCount = (count + chunk - 1) / chunk;
    const auto threadCount = static_cast<unsigned>(
            std::min<std::int64_t>(workerCount(), chunkCount));
    std::vector<std::thread> threads;
    if (threadCount > 1) {
        threads.reserve(threadCount - 1);
        try {
            for (unsigned i = 1; i < threadCount; ++i) threads.emplace_back(worker, i);
        } catch (const std::system_error&) {
            // Fall back to fewer threads; the calling thread still processes the remaining chunks.
        }
    }
    worker(0);
    for (std::thread& thread : threads) thread.join();
}

template <typename Body>
void parallelFor(std::int64_t count, std::int64_t chunk, const Body& body) {
    parallelForWorkers(count, chunk, [&](std::int64_t begin, std::int64_t end, unsigned) {
        body(begin, end);
    });
}

class StageClock {
public:
    double lapMilliseconds() {
        const auto now = std::chrono::steady_clock::now();
        const double elapsed = std::chrono::duration<double, std::milli>(now - last_).count();
        last_ = now;
        return elapsed;
    }

private:
    std::chrono::steady_clock::time_point last_ = std::chrono::steady_clock::now();
};

// Baseline JPEG encoder (YCbCr 4:2:0, standard Annex K tables) that encodes one MCU row
// (16 pixel rows) at a time. Every MCU row is its own restart interval, so rows can be encoded
// independently on several threads and concatenated with RSTn markers in between.
class BandJpegEncoder {
public:
    static constexpr int kMcuSize = 16;

    BandJpegEncoder(std::int64_t width, std::int64_t height, int quality)
            : width_(width), height_(height) {
        static constexpr std::array<std::uint8_t, 64> kLumaQuant{
                16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
                14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
                18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
                49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99};
        static constexpr std::array<std::uint8_t, 64> kChromaQuant{
                17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
                24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
                99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
                99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99};
        // AAN scale factors (with the 2*sqrt(2) DCT normalization folded in).
        static constexpr std::array<float, 8> kAanScale{
                1.0f * 2.828427125f, 1.387039845f * 2.828427125f, 1.306562965f * 2.828427125f,
                1.175875602f * 2.828427125f, 1.0f * 2.828427125f, 0.785694958f * 2.828427125f,
                0.541196100f * 2.828427125f, 0.275899379f * 2.828427125f};
        quality = std::clamp(quality, 1, 100);
        const int scale = quality < 50 ? 5000 / quality : 200 - quality * 2;
        for (int i = 0; i < 64; ++i) {
            lumaQuant_[i] = static_cast<std::uint8_t>(std::clamp((kLumaQuant[i] * scale + 50) / 100, 1, 255));
            chromaQuant_[i] = static_cast<std::uint8_t>(std::clamp((kChromaQuant[i] * scale + 50) / 100, 1, 255));
            const int row = i / 8;
            const int column = i % 8;
            lumaDivisor_[i] = 1.0f / (lumaQuant_[i] * kAanScale[row] * kAanScale[column]);
            chromaDivisor_[i] = 1.0f / (chromaQuant_[i] * kAanScale[row] * kAanScale[column]);
        }
        valid_ = buildCodes(kDcLumaBits, kDcLumaValues.data(), kDcLumaValues.size(), dcLuma_) &&
                buildCodes(kAcLumaBits, kAcLumaValues.data(), kAcLumaValues.size(), acLuma_) &&
                buildCodes(kDcChromaBits, kDcChromaValues.data(), kDcChromaValues.size(), dcChroma_) &&
                buildCodes(kAcChromaBits, kAcChromaValues.data(), kAcChromaValues.size(), acChroma_) &&
                width > 0 && height > 0 && width <= 65535 && height <= 65535;
        // Every symbol the encoder can emit must have a code: DC sizes 0-11, AC EOB/ZRL and
        // (run, size) for runs 0-15 and sizes 1-10.
        for (int size = 0; size <= 11 && valid_; ++size) {
            valid_ = dcLuma_[size].length > 0 && dcChroma_[size].length > 0;
        }
        for (int run = 0; run < 16 && valid_; ++run) {
            for (int size = 1; size <= 10 && valid_; ++size) {
                const int symbol = (run << 4) | size;
                valid_ = acLuma_[symbol].length > 0 && acChroma_[symbol].length > 0;
            }
        }
        valid_ = valid_ && acLuma_[0x00].length > 0 && acLuma_[0xf0].length > 0 &&
                acChroma_[0x00].length > 0 && acChroma_[0xf0].length > 0;
    }

    bool valid() const { return valid_; }
    std::int64_t mcuRows() const { return (height_ + kMcuSize - 1) / kMcuSize; }
    std::int64_t mcusPerRow() const { return (width_ + kMcuSize - 1) / kMcuSize; }

    // SOI, EXIF orientation + description, quantization and Huffman tables, frame and scan headers.
    std::vector<std::uint8_t> header(std::uint16_t exifOrientation, const char* description) const {
        std::vector<std::uint8_t> out;
        const auto put16 = [&](std::uint32_t value) {
            out.push_back(static_cast<std::uint8_t>(value >> 8));
            out.push_back(static_cast<std::uint8_t>(value & 0xff));
        };
        const auto putLe16 = [](std::vector<std::uint8_t>& target, std::uint32_t value) {
            target.push_back(static_cast<std::uint8_t>(value & 0xff));
            target.push_back(static_cast<std::uint8_t>(value >> 8));
        };
        const auto putLe32 = [&](std::vector<std::uint8_t>& target, std::uint32_t value) {
            putLe16(target, value & 0xffff);
            putLe16(target, value >> 16);
        };
        put16(0xffd8);

        // APP1 EXIF: little-endian TIFF with IFD0 = {ImageDescription, Orientation}.
        const std::size_t descriptionLength = std::strlen(description) + 1;
        std::vector<std::uint8_t> tiff{'I', 'I', 42, 0};
        putLe32(tiff, 8);
        putLe16(tiff, 2);
        putLe16(tiff, 0x010e);  // ImageDescription, ASCII
        putLe16(tiff, 2);
        putLe32(tiff, static_cast<std::uint32_t>(descriptionLength));
        putLe32(tiff, 8 + 2 + 2 * 12 + 4);
        putLe16(tiff, 0x0112);  // Orientation, SHORT
        putLe16(tiff, 3);
        putLe32(tiff, 1);
        putLe16(tiff, exifOrientation);
        putLe16(tiff, 0);
        putLe32(tiff, 0);  // No next IFD
        tiff.insert(tiff.end(), description, description + descriptionLength);
        put16(0xffe1);
        put16(static_cast<std::uint32_t>(2 + 6 + tiff.size()));
        for (const char c : {'E', 'x', 'i', 'f', '\0', '\0'}) out.push_back(static_cast<std::uint8_t>(c));
        out.insert(out.end(), tiff.begin(), tiff.end());

        put16(0xffdb);
        put16(2 + 2 * 65);
        out.push_back(0);
        for (int i = 0; i < 64; ++i) out.push_back(lumaQuant_[kZigZag[i]]);
        out.push_back(1);
        for (int i = 0; i < 64; ++i) out.push_back(chromaQuant_[kZigZag[i]]);

        put16(0xffc0);
        put16(17);
        out.push_back(8);
        put16(static_cast<std::uint32_t>(height_));
        put16(static_cast<std::uint32_t>(width_));
        out.push_back(3);
        for (const std::array<std::uint8_t, 3>& component :
             {std::array<std::uint8_t, 3>{1, 0x22, 0}, std::array<std::uint8_t, 3>{2, 0x11, 1},
              std::array<std::uint8_t, 3>{3, 0x11, 1}}) {
            out.insert(out.end(), component.begin(), component.end());
        }

        const auto putTable = [&](std::uint8_t classAndId, const std::array<std::uint8_t, 16>& bits,
                                  const std::uint8_t* values, std::size_t count) {
            out.push_back(classAndId);
            out.insert(out.end(), bits.begin(), bits.end());
            out.insert(out.end(), values, values + count);
        };
        put16(0xffc4);
        put16(static_cast<std::uint32_t>(2 + 4 * 17 + kDcLumaValues.size() + kAcLumaValues.size() +
                                         kDcChromaValues.size() + kAcChromaValues.size()));
        putTable(0x00, kDcLumaBits, kDcLumaValues.data(), kDcLumaValues.size());
        putTable(0x10, kAcLumaBits, kAcLumaValues.data(), kAcLumaValues.size());
        putTable(0x01, kDcChromaBits, kDcChromaValues.data(), kDcChromaValues.size());
        putTable(0x11, kAcChromaBits, kAcChromaValues.data(), kAcChromaValues.size());

        put16(0xffdd);  // Restart interval: one MCU row.
        put16(4);
        put16(static_cast<std::uint32_t>(mcusPerRow()));

        put16(0xffda);
        put16(12);
        out.push_back(3);
        out.push_back(1);
        out.push_back(0x00);
        out.push_back(2);
        out.push_back(0x11);
        out.push_back(3);
        out.push_back(0x11);
        out.push_back(0);
        out.push_back(63);
        out.push_back(0);
        return out;
    }

    // Encodes MCU row `mcuRow` from 16 RGB8 rows (rows[i] for image row mcuRow*16 + i; rows past
    // the image bottom must repeat the last row). Appends a byte-aligned restart interval.
    void encodeMcuRow(const std::uint8_t* const* rows, std::vector<std::uint8_t>& out) const {
        BitWriter writer(out);
        int previousY = 0;
        int previousCb = 0;
        int previousCr = 0;
        std::array<float, 256> luma{};
        std::array<float, 64> cb{};
        std::array<float, 64> cr{};
        std::array<float, 64> block{};
        const std::int64_t lastColumn = width_ - 1;
        for (std::int64_t mcu = 0; mcu < mcusPerRow(); ++mcu) {
            cb.fill(0.0f);
            cr.fill(0.0f);
            for (int y = 0; y < kMcuSize; ++y) {
                const std::uint8_t* row = rows[y];
                for (int x = 0; x < kMcuSize; ++x) {
                    const std::int64_t column = std::min(mcu * kMcuSize + x, lastColumn);
                    const float r = row[column * 3];
                    const float g = row[column * 3 + 1];
                    const float b = row[column * 3 + 2];
                    luma[static_cast<std::size_t>(y * 16 + x)] = 0.299f * r + 0.587f * g + 0.114f * b - 128.0f;
                    const std::size_t chromaIndex = static_cast<std::size_t>((y / 2) * 8 + x / 2);
                    cb[chromaIndex] += 0.25f * (-0.168736f * r - 0.331264f * g + 0.5f * b);
                    cr[chromaIndex] += 0.25f * (0.5f * r - 0.418688f * g - 0.081312f * b);
                }
            }
            for (int blockY = 0; blockY < 2; ++blockY) {
                for (int blockX = 0; blockX < 2; ++blockX) {
                    for (int y = 0; y < 8; ++y) {
                        for (int x = 0; x < 8; ++x) {
                            block[static_cast<std::size_t>(y * 8 + x)] =
                                    luma[static_cast<std::size_t>((blockY * 8 + y) * 16 + blockX * 8 + x)];
                        }
                    }
                    previousY = encodeBlock(writer, block, lumaDivisor_, dcLuma_, acLuma_, previousY);
                }
            }
            previousCb = encodeBlock(writer, cb, chromaDivisor_, dcChroma_, acChroma_, previousCb);
            previousCr = encodeBlock(writer, cr, chromaDivisor_, dcChroma_, acChroma_, previousCr);
        }
        writer.flush();
    }

private:
    struct HuffmanCode {
        std::uint16_t code = 0;
        std::uint8_t length = 0;
    };
    using HuffmanTable = std::array<HuffmanCode, 256>;

    class BitWriter {
    public:
        explicit BitWriter(std::vector<std::uint8_t>& out) : out_(out) {}

        void put(std::uint32_t bits, int length) {
            buffer_ = (buffer_ << length) | (bits & ((1U << length) - 1U));
            count_ += length;
            while (count_ >= 8) {
                const auto byte = static_cast<std::uint8_t>(buffer_ >> (count_ - 8));
                out_.push_back(byte);
                if (byte == 0xff) out_.push_back(0);  // Byte stuffing.
                count_ -= 8;
            }
        }

        // Pads the interval with 1 bits to a byte boundary, as the standard requires.
        void flush() {
            if (count_ > 0) put(0x7f, 8 - count_);
            buffer_ = 0;
        }

    private:
        std::vector<std::uint8_t>& out_;
        std::uint32_t buffer_ = 0;
        int count_ = 0;
    };

    static bool buildCodes(const std::array<std::uint8_t, 16>& bits, const std::uint8_t* values,
                           std::size_t count, HuffmanTable& table) {
        std::uint32_t code = 0;
        std::size_t index = 0;
        for (int length = 1; length <= 16; ++length) {
            for (int i = 0; i < bits[static_cast<std::size_t>(length - 1)]; ++i) {
                if (index >= count) return false;
                table[values[index]] = HuffmanCode{static_cast<std::uint16_t>(code),
                                                   static_cast<std::uint8_t>(length)};
                ++index;
                ++code;
            }
            code <<= 1;
        }
        return index == count;
    }

    static int bitLength(int value) {
        value = value < 0 ? -value : value;
        int length = 0;
        while (value != 0) {
            ++length;
            value >>= 1;
        }
        return length;
    }

    static void putValue(BitWriter& writer, int value, int length) {
        if (length == 0) return;
        writer.put(static_cast<std::uint32_t>(value < 0 ? value - 1 : value), length);
    }

    static void forwardDct8(float* d, int stride) {
        float* p[8];
        for (int i = 0; i < 8; ++i) p[i] = d + i * stride;
        const float tmp0 = *p[0] + *p[7];
        const float tmp7 = *p[0] - *p[7];
        const float tmp1 = *p[1] + *p[6];
        const float tmp6 = *p[1] - *p[6];
        const float tmp2 = *p[2] + *p[5];
        const float tmp5 = *p[2] - *p[5];
        const float tmp3 = *p[3] + *p[4];
        const float tmp4 = *p[3] - *p[4];

        float tmp10 = tmp0 + tmp3;
        const float tmp13 = tmp0 - tmp3;
        float tmp11 = tmp1 + tmp2;
        float tmp12 = tmp1 - tmp2;
        *p[0] = tmp10 + tmp11;
        *p[4] = tmp10 - tmp11;
        const float z1 = (tmp12 + tmp13) * 0.707106781f;
        *p[2] = tmp13 + z1;
        *p[6] = tmp13 - z1;

        tmp10 = tmp4 + tmp5;
        tmp11 = tmp5 + tmp6;
        tmp12 = tmp6 + tmp7;
        const float z5 = (tmp10 - tmp12) * 0.382683433f;
        const float z2 = tmp10 * 0.541196100f + z5;
        const float z4 = tmp12 * 1.306562965f + z5;
        const float z3 = tmp11 * 0.707106781f;
        const float z11 = tmp7 + z3;
        const float z13 = tmp7 - z3;
        *p[5] = z13 + z2;
        *p[3] = z13 - z2;
        *p[1] = z11 + z4;
        *p[7] = z11 - z4;
    }

    static int encodeBlock(BitWriter& writer, std::array<float, 64> block,
                           const std::array<float, 64>& divisor, const HuffmanTable& dc,
                           const HuffmanTable& ac, int previousDc) {
        for (int row = 0; row < 8; ++row) forwardDct8(block.data() + row * 8, 1);
        for (int column = 0; column < 8; ++column) forwardDct8(block.data() + column, 8);
        std::array<int, 64> coefficients{};
        for (int i = 0; i < 64; ++i) {
            const int natural = kZigZag[i];
            // Baseline limits: AC values must fit 10 bits; DC differences then fit 11 bits.
            coefficients[i] = std::clamp(
                    static_cast<int>(std::lround(block[natural] * divisor[natural])), -1023, 1023);
        }
        const int difference = coefficients[0] - previousDc;
        const int dcLength = bitLength(difference);
        writer.put(dc[dcLength].code, dc[dcLength].length);
        putValue(writer, difference, dcLength);

        int run = 0;
        for (int i = 1; i < 64; ++i) {
            const int value = coefficients[i];
            if (value == 0) {
                ++run;
                continue;
            }
            while (run > 15) {
                writer.put(ac[0xf0].code, ac[0xf0].length);
                run -= 16;
            }
            const int length = bitLength(value);
            const HuffmanCode& code = ac[static_cast<std::size_t>((run << 4) | length)];
            writer.put(code.code, code.length);
            putValue(writer, value, length);
            run = 0;
        }
        if (run > 0) writer.put(ac[0x00].code, ac[0x00].length);
        return coefficients[0];
    }

    static constexpr std::array<int, 64> kZigZag{
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};
    static constexpr std::array<std::uint8_t, 16> kDcLumaBits{0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
    static constexpr std::array<std::uint8_t, 12> kDcLumaValues{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    static constexpr std::array<std::uint8_t, 16> kDcChromaBits{0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
    static constexpr std::array<std::uint8_t, 12> kDcChromaValues{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    static constexpr std::array<std::uint8_t, 16> kAcLumaBits{0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
    static constexpr std::array<std::uint8_t, 162> kAcLumaValues{
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
            0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa};
    static constexpr std::array<std::uint8_t, 16> kAcChromaBits{0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
    static constexpr std::array<std::uint8_t, 162> kAcChromaValues{
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
            0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
            0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
            0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
            0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa};

    std::int64_t width_;
    std::int64_t height_;
    bool valid_ = false;
    std::array<std::uint8_t, 64> lumaQuant_{};
    std::array<std::uint8_t, 64> chromaQuant_{};
    std::array<float, 64> lumaDivisor_{};
    std::array<float, 64> chromaDivisor_{};
    HuffmanTable dcLuma_{};
    HuffmanTable acLuma_{};
    HuffmanTable dcChroma_{};
    HuffmanTable acChroma_{};
};

class RawBurstAligner {
public:
    explicit RawBurstAligner(bool superResolutionEnabled)
            : superResolutionEnabled_(superResolutionEnabled) {}

    ~RawBurstAligner() {
        for (StoredFrame& frame : storedFrames_) frame.release();
    }

    RawBurstAligner(const RawBurstAligner&) = delete;
    RawBurstAligner& operator=(const RawBurstAligner&) = delete;

    // Aligns one frame against the reference, adds it to the reduced preview, and keeps a
    // reference to its RAW data (the spool file mapping when available) for the 2x render.
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
            const char* spoolPath,
            std::vector<std::uint16_t>& proxy,
            std::vector<ColorProxyPixel>& colorProxy,
            AlignmentResult& result) {
        std::vector<std::uint16_t> alignProxy;
        std::vector<std::uint16_t> fineProxy;
        StageClock clock;
        const int frameNumber = frameIndex_++;
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
        const double proxyMs = clock.lapMilliseconds();
        if (fusion_.empty()) fusion_.resize(colorProxy.size());
        if (fusion_.size() != colorProxy.size()) return false;

        std::int64_t blocksX = 0;
        std::int64_t blocksY = 0;
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
            accumulateFusion(colorProxy, result, {});
            const double fusionMs = clock.lapMilliseconds();
            for (std::size_t channel = 0; channel < superResolutionGains_.size(); ++channel) {
                superResolutionGains_[channel] =
                        std::isfinite(colorGains[channel]) && colorGains[channel] > 0.0
                        ? colorGains[channel]
                        : 1.0;
            }
            for (std::size_t i = 0; i < superResolutionMatrix_.size(); ++i) {
                superResolutionMatrix_[i] = std::isfinite(colorMatrix[i]) ? colorMatrix[i] : (i % 4 == 0 ? 1.0 : 0.0);
            }
            std::vector<BlockMotion> blocks = buildBlockMotion(result, width, height, blocksX, blocksY);
            storeFrame(bytes, width, height, rowStride, pixelStride, cfaChannels, blackLevels,
                       whiteLevel, spoolPath, std::move(blocks), blocksX, blocksY, {});
            const double storeMs = clock.lapMilliseconds();
            __android_log_print(
                    ANDROID_LOG_INFO, kLogTag,
                    "frame %d (reference): proxy %.0f ms, fusion %.0f ms, store %.0f ms",
                    frameNumber, proxyMs, fusionMs, storeMs);
            return true;
        }
        if (proxyWidth_ != referenceWidth_ || proxyHeight_ != referenceHeight_) return false;

        result = estimateTranslation(alignProxy);
        const double coarseMs = clock.lapMilliseconds();
        refineAlignment(fineProxy, result);
        const double refineMs = clock.lapMilliseconds();
        // One robustness map per frame, shared by the preview fusion and the 2x reconstruction,
        // so neither blends regions that disagree with the reference (moving objects, parallax).
        double averageBlackLevel = 0.0;
        for (int site = 0; site < 4; ++site) averageBlackLevel += std::max(0.0, blackLevels[site]);
        averageBlackLevel /= 4.0;
        std::vector<BlockMotion> blocks = buildBlockMotion(result, width, height, blocksX, blocksY);
        std::vector<float> robustness =
                buildRobustnessMap(proxy, blocks, blocksX, blocksY, averageBlackLevel);
        const double robustnessMs = clock.lapMilliseconds();
        accumulateFusion(colorProxy, result, robustness);
        const double fusionMs = clock.lapMilliseconds();
        storeFrame(bytes, width, height, rowStride, pixelStride, cfaChannels, blackLevels,
                   whiteLevel, spoolPath, std::move(blocks), blocksX, blocksY, std::move(robustness));
        const double storeMs = clock.lapMilliseconds();
        __android_log_print(
                ANDROID_LOG_INFO, kLogTag,
                "frame %d: proxy %.0f ms, coarse align %.0f ms, fine align %.0f ms, "
                "robustness %.0f ms, fusion %.0f ms, store %.0f ms (total %.0f ms)",
                frameNumber, proxyMs, coarseMs, refineMs, robustnessMs, fusionMs, storeMs,
                proxyMs + coarseMs + refineMs + robustnessMs + fusionMs + storeMs);
        return true;
    }

    bool writeSuperResolutionFiles(const std::string& tiffPath, const std::string& jpegPath, jint orientation) {
        std::ofstream tiff(tiffPath, std::ios::binary | std::ios::trunc);
        if (!tiff) return false;
        std::ofstream jpeg;
        if (!jpegPath.empty()) {
            jpeg.open(jpegPath, std::ios::binary | std::ios::trunc);
            if (!jpeg) return false;
        }
        return writeSuperResolution(&tiff, jpegPath.empty() ? nullptr : &jpeg, orientation);
    }

    bool writeSuperResolutionToFileDescriptors(int tiffDescriptor, int jpegDescriptor, jint orientation) {
        const auto openOutput = [](int descriptor) {
            if (descriptor < 0) return -1;
            const int duplicate = dup(descriptor);
            if (duplicate < 0) return -1;
            if (ftruncate(duplicate, 0) != 0 || lseek(duplicate, 0, SEEK_SET) < 0) {
                close(duplicate);
                return -1;
            }
            return duplicate;
        };
        const int tiffOutput = openOutput(tiffDescriptor);
        if (tiffOutput < 0) return false;
        const int jpegOutput = jpegDescriptor >= 0 ? openOutput(jpegDescriptor) : -1;
        if (jpegDescriptor >= 0 && jpegOutput < 0) {
            close(tiffOutput);
            return false;
        }
        bool ok;
        {
            FileDescriptorStreamBuffer tiffBuffer(tiffOutput);
            std::ostream tiffStream(&tiffBuffer);
            std::unique_ptr<FileDescriptorStreamBuffer> jpegBuffer;
            std::unique_ptr<std::ostream> jpegStream;
            if (jpegOutput >= 0) {
                jpegBuffer = std::make_unique<FileDescriptorStreamBuffer>(jpegOutput);
                jpegStream = std::make_unique<std::ostream>(jpegBuffer.get());
            }
            ok = writeSuperResolution(&tiffStream, jpegStream.get(), orientation);
            tiffStream.flush();
            ok = ok && tiffStream.good();
            if (jpegStream) {
                jpegStream->flush();
                ok = ok && jpegStream->good();
            }
        }
        ok = ok && fsync(tiffOutput) == 0 && (jpegOutput < 0 || fsync(jpegOutput) == 0);
        close(tiffOutput);
        if (jpegOutput >= 0) close(jpegOutput);
        return ok;
    }

    // Renders the 2x reconstruction in bands of one JPEG MCU row: each band gathers every stored
    // frame (so no full-size accumulator is ever needed), is demosaiced and color-converted, then
    // streamed to the TIFF and encoded as one JPEG restart interval. Bands run in parallel; a
    // writer thread streams the previous batch while the next one is computed.
    bool writeSuperResolution(std::ostream* tiffOutput, std::ostream* jpegOutput, jint orientation) {
        if (!superResolutionEnabled_ || storedFrames_.empty() || superResolutionWidth_ <= 0 ||
            superResolutionHeight_ <= 0 || tiffOutput == nullptr) {
            return false;
        }
        StageClock clock;
        const std::int64_t width = superResolutionWidth_;
        const std::int64_t height = superResolutionHeight_;
        const std::size_t rowPixelBytes = static_cast<std::size_t>(width) * 3;
        constexpr std::int64_t kBandRows = BandJpegEncoder::kMcuSize;
        constexpr std::int64_t kBandsPerBatch = 16;
        constexpr std::int64_t kBatchRows = kBandRows * kBandsPerBatch;
        constexpr std::int64_t kHaloRows = kBandRows + 2;
        constexpr int kJpegQuality = 92;
        const std::int64_t bandCount = (height + kBandRows - 1) / kBandRows;

        std::unique_ptr<BandJpegEncoder> jpeg;
        if (jpegOutput != nullptr) {
            jpeg = std::make_unique<BandJpegEncoder>(width, height, kJpegQuality);
            if (!jpeg->valid()) return false;
            const std::vector<std::uint8_t> header =
                    jpeg->header(exifOrientation(orientation), kSuperResolutionDescription);
            jpegOutput->write(reinterpret_cast<const char*>(header.data()),
                              static_cast<std::streamsize>(header.size()));
            if (!*jpegOutput) return false;
        }
        if (!writeTiffHeader(*tiffOutput, orientation)) return false;

        struct BandScratch {
            std::vector<float> sums;
            std::vector<float> weights;
            std::vector<float> rgb;
            std::vector<std::uint8_t> valid;
            std::vector<float> robustnessRow;
        };
        // Accumulator rows hold 3 permuted planes of paddedOutputWidth() floats.
        const std::size_t accumulatorRowValues = static_cast<std::size_t>(paddedOutputWidth()) * 3;
        const std::size_t haloValues = static_cast<std::size_t>(kHaloRows * width * 3);
        std::vector<BandScratch> scratch(workerCount());
        for (BandScratch& band : scratch) {
            band.sums.resize(static_cast<std::size_t>(kHaloRows) * accumulatorRowValues);
            band.weights.resize(static_cast<std::size_t>(kHaloRows) * accumulatorRowValues);
            band.robustnessRow.resize(static_cast<std::size_t>(std::max<std::int64_t>(1, proxyWidth_)));
            band.rgb.resize(haloValues);
            band.valid.resize(static_cast<std::size_t>(kHaloRows * width));
        }
        std::array<std::vector<std::uint8_t>, 2> batches;
        std::array<std::vector<std::vector<std::uint8_t>>, 2> segments;
        for (std::size_t i = 0; i < 2; ++i) {
            batches[i].resize(rowPixelBytes * static_cast<std::size_t>(kBatchRows));
            segments[i].resize(static_cast<std::size_t>(kBandsPerBatch));
        }
        const std::size_t frameCount = storedFrames_.size();
        std::unique_ptr<std::atomic<bool>[]> frameContributed(new std::atomic<bool>[frameCount]);
        for (std::size_t i = 0; i < frameCount; ++i) frameContributed[i].store(false);

        // Thread time per stage (summed over workers) and page faults, to profile the render.
        std::atomic<std::int64_t> accumulateNs{0};
        std::atomic<std::int64_t> rowsNs{0};
        std::atomic<std::int64_t> jpegNs{0};
        const auto nowNs = [] {
            return std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
        };
        struct rusage usageBefore {};
        getrusage(RUSAGE_SELF, &usageBefore);
        const auto renderBand = [&](std::int64_t band, BandScratch& work, std::uint8_t* bandOutput,
                                    std::vector<std::uint8_t>& segment) {
            const std::int64_t firstRow = band * kBandRows;
            const std::int64_t endRow = std::min(height, firstRow + kBandRows);
            const std::int64_t haloBegin = std::max<std::int64_t>(0, firstRow - 1);
            const std::int64_t haloEnd = std::min(height, endRow + 1);
            const std::size_t haloSize = static_cast<std::size_t>(haloEnd - haloBegin) * accumulatorRowValues;
            std::fill(work.sums.begin(), work.sums.begin() + static_cast<std::ptrdiff_t>(haloSize), 0.0f);
            std::fill(work.weights.begin(), work.weights.begin() + static_cast<std::ptrdiff_t>(haloSize), 0.0f);
            std::int64_t stageStart = nowNs();
            // Row-major over frames: one output row (8000 x 6 floats) stays in cache while every
            // frame adds to it, instead of streaming the whole band through memory once per frame.
            // Tiles of 16 motion blocks (512 output columns): the raw rows of every frame and the
            // accumulators a tile touches stay in L1/L2 while all frames are added row by row.
            constexpr std::int64_t kTileBlocks = 16;
            const std::int64_t blockCount = paddedOutputWidth() / kBlockColumns;
            for (std::int64_t tile = 0; tile < blockCount; tile += kTileBlocks) {
                const std::int64_t tileEnd = std::min(blockCount, tile + kTileBlocks);
                for (std::int64_t y = haloBegin; y < haloEnd; ++y) {
                    const std::size_t rowOffset = static_cast<std::size_t>(y - haloBegin) * accumulatorRowValues;
                    for (std::size_t frame = 0; frame < frameCount; ++frame) {
                        if (accumulateRow(storedFrames_[frame], y, work.sums.data() + rowOffset,
                                          work.weights.data() + rowOffset, work.robustnessRow.data(),
                                          tile, tileEnd)) {
                            frameContributed[frame].store(true, std::memory_order_relaxed);
                        }
                    }
                }
            }
            std::int64_t stageEnd = nowNs();
            accumulateNs.fetch_add(stageEnd - stageStart, std::memory_order_relaxed);
            stageStart = stageEnd;
            const std::size_t rowValues = static_cast<std::size_t>(width) * 3;
            for (std::int64_t y = haloBegin; y < haloEnd; ++y) {
                const std::size_t row = static_cast<std::size_t>(y - haloBegin);
                decodeAccumulatedRow(work.sums.data() + row * accumulatorRowValues,
                                     work.weights.data() + row * accumulatorRowValues,
                                     work.rgb.data() + row * rowValues,
                                     work.valid.data() + row * static_cast<std::size_t>(width));
            }
            const auto rgbRow = [&](std::int64_t y) -> const float* {
                if (y < haloBegin || y >= haloEnd) return nullptr;
                return work.rgb.data() + static_cast<std::size_t>(y - haloBegin) * rowValues;
            };
            const auto validRow = [&](std::int64_t y) -> const std::uint8_t* {
                if (y < haloBegin || y >= haloEnd) return nullptr;
                return work.valid.data() + static_cast<std::size_t>(y - haloBegin) * static_cast<std::size_t>(width);
            };
            for (std::int64_t y = firstRow; y < endRow; ++y) {
                encodeOutputRow(rgbRow(y - 1), validRow(y - 1), rgbRow(y), validRow(y),
                                rgbRow(y + 1), validRow(y + 1),
                                bandOutput + static_cast<std::size_t>(y - firstRow) * rowPixelBytes);
            }
            stageEnd = nowNs();
            rowsNs.fetch_add(stageEnd - stageStart, std::memory_order_relaxed);
            stageStart = stageEnd;
            segment.clear();
            if (jpeg) {
                std::array<const std::uint8_t*, BandJpegEncoder::kMcuSize> rows{};
                for (std::int64_t i = 0; i < kBandRows; ++i) {
                    const std::int64_t y = std::min(firstRow + i, endRow - 1);
                    rows[static_cast<std::size_t>(i)] =
                            bandOutput + static_cast<std::size_t>(y - firstRow) * rowPixelBytes;
                }
                jpeg->encodeMcuRow(rows.data(), segment);
                jpegNs.fetch_add(nowNs() - stageStart, std::memory_order_relaxed);
            }
        };

        std::thread writer;
        bool writeFailed = false;  // Only touched by the writer thread until it is joined.
        const auto joinWriter = [&]() {
            if (writer.joinable()) writer.join();
            return !writeFailed;
        };
        double renderMs = 0.0;
        try {
            for (std::int64_t batchStart = 0, batchIndex = 0; batchStart < bandCount;
                 batchStart += kBandsPerBatch, ++batchIndex) {
                const std::int64_t batchBands = std::min(kBandsPerBatch, bandCount - batchStart);
                const std::size_t slot = static_cast<std::size_t>(batchIndex & 1);
                std::uint8_t* batch = batches[slot].data();
                std::vector<std::vector<std::uint8_t>>& batchSegments = segments[slot];
                const auto renderStart = std::chrono::steady_clock::now();
                parallelForWorkers(batchBands, 1,
                        [&](std::int64_t begin, std::int64_t end, unsigned workerIndex) {
                            for (std::int64_t i = begin; i < end; ++i) {
                                renderBand(batchStart + i, scratch[workerIndex],
                                           batch + static_cast<std::size_t>(i * kBandRows) * rowPixelBytes,
                                           batchSegments[static_cast<std::size_t>(i)]);
                            }
                        });
                renderMs += std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - renderStart).count();
                if (!joinWriter()) return false;
                const std::int64_t rows = std::min(kBatchRows, height - batchStart * kBandRows);
                const auto writeBatch = [&, batch, rows, batchStart, batchBands, slot]() {
                    tiffOutput->write(reinterpret_cast<const char*>(batch),
                                      static_cast<std::streamsize>(static_cast<std::size_t>(rows) * rowPixelBytes));
                    if (!*tiffOutput) writeFailed = true;
                    if (jpegOutput == nullptr) return;
                    for (std::int64_t i = 0; i < batchBands; ++i) {
                        const std::vector<std::uint8_t>& segment = segments[slot][static_cast<std::size_t>(i)];
                        jpegOutput->write(reinterpret_cast<const char*>(segment.data()),
                                          static_cast<std::streamsize>(segment.size()));
                        const std::int64_t band = batchStart + i;
                        if (band + 1 < bandCount) {
                            const char marker[2] = {static_cast<char>(0xff),
                                                    static_cast<char>(0xd0 + band % 8)};
                            jpegOutput->write(marker, 2);
                        }
                    }
                    if (!*jpegOutput) writeFailed = true;
                };
                try {
                    writer = std::thread(writeBatch);
                } catch (const std::system_error&) {
                    writeBatch();
                    if (writeFailed) return false;
                }
            }
        } catch (...) {
            if (writer.joinable()) writer.join();
            throw;
        }
        if (!joinWriter()) return false;
        if (jpegOutput != nullptr) {
            const char endOfImage[2] = {static_cast<char>(0xff), static_cast<char>(0xd9)};
            jpegOutput->write(endOfImage, 2);
            jpegOutput->flush();
            if (!*jpegOutput) return false;
        }
        tiffOutput->flush();
        struct rusage usageAfter {};
        getrusage(RUSAGE_SELF, &usageAfter);
        superResolutionFrameCount_ = 0;
        for (std::size_t i = 0; i < frameCount; ++i) {
            if (frameContributed[i].load()) ++superResolutionFrameCount_;
        }
        __android_log_print(
                ANDROID_LOG_INFO, kLogTag,
                "2x render %lldx%lld from %d/%zu frames%s: bands %.0f ms, total with streaming %.0f ms; "
                "thread time: accumulate %.0f ms, rows %.0f ms, jpeg %.0f ms; faults %ld major, %ld minor; %u workers",
                static_cast<long long>(width), static_cast<long long>(height),
                superResolutionFrameCount_, frameCount, jpegOutput != nullptr ? " (TIFF + JPEG)" : " (TIFF)",
                renderMs, clock.lapMilliseconds(), accumulateNs.load() / 1e6, rowsNs.load() / 1e6,
                jpegNs.load() / 1e6, usageAfter.ru_majflt - usageBefore.ru_majflt,
                usageAfter.ru_minflt - usageBefore.ru_minflt, workerCount());
        return static_cast<bool>(*tiffOutput) && superResolutionFrameCount_ > 0;
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
            const std::uint8_t red = encodeSrgb(sample.red);
            const std::uint8_t green = encodeSrgb(sample.green);
            const std::uint8_t blue = encodeSrgb(sample.blue);
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

    // Preview samples are integers in 0..4095, so a table gives the exact same result as pow().
    static std::uint8_t encodeSrgb(std::uint16_t linearSample) {
        static const std::array<std::uint8_t, 4096> lookup = [] {
            std::array<std::uint8_t, 4096> values{};
            for (std::size_t i = 0; i < values.size(); ++i) {
                const double linear = static_cast<double>(i) / 4095.0;
                const double srgb = linear <= 0.0031308
                        ? 12.92 * linear
                        : 1.055 * std::pow(linear, 1.0 / 2.4) - 0.055;
                values[i] = static_cast<std::uint8_t>(std::lround(std::clamp(srgb, 0.0, 1.0) * 255.0));
            }
            return values;
        }();
        return lookup[std::min<std::uint16_t>(linearSample, 4095)];
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
        const auto& kImageDescription = kSuperResolutionDescription;
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

    static std::uint16_t exifOrientation(jint degrees) {
        const jint normalized = (degrees % 360 + 360) % 360;
        return normalized == 90 ? 6 : normalized == 180 ? 3 : normalized == 270 ? 8 : 1;
    }

    struct BlockMotion {
        float dx = 0.0f;
        float dy = 0.0f;
        bool valid = false;
        // Every robustness sample the block's output pixels can read is exactly 1.
        bool fullyRobust = false;
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
        parallelFor(height, 32, [&](std::int64_t rowBegin, std::int64_t rowEnd) {
            for (std::int64_t py = rowBegin; py < rowEnd; ++py) {
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
        });

        // Erode so that rejection also covers the soft borders of moving regions.
        std::vector<float> robustness(agreement.size(), 0.0f);
        parallelFor(height, 48, [&](std::int64_t rowBegin, std::int64_t rowEnd) {
            for (std::int64_t py = rowBegin; py < rowEnd; ++py) {
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
        });
        return robustness;
    }

    // A burst frame kept for the final 2x render: packed RAW16 rows (rowStride = width * 2) from
    // the camera spool file mapping (or a private copy), with its motion and robustness maps.
    struct StoredFrame {
        void* mapping = nullptr;
        std::size_t mappingLength = 0;
        std::vector<std::uint8_t> copy;
        std::int64_t rowStride = 0;
        std::array<int, 4> siteChannel{};
        std::array<float, 4> siteBlack{};
        float inverseWhiteRange = 1.0f;
        std::vector<BlockMotion> blocks;
        std::int64_t blocksX = 0;
        std::int64_t blocksY = 0;
        std::vector<float> robustness;

        const std::uint8_t* data() const {
            return mapping != nullptr ? static_cast<const std::uint8_t*>(mapping) : copy.data();
        }

        void release() {
            if (mapping != nullptr) munmap(mapping, mappingLength);
            mapping = nullptr;
            copy.clear();
            copy.shrink_to_fit();
        }
    };

    void storeFrame(
            const std::uint8_t* bytes,
            jint width,
            jint height,
            jint rowStride,
            jint pixelStride,
            const jint* cfaChannels,
            const jdouble* blackLevels,
            jdouble whiteLevel,
            const char* spoolPath,
            std::vector<BlockMotion> blocks,
            std::int64_t blocksX,
            std::int64_t blocksY,
            std::vector<float> robustness) {
        if (!superResolutionEnabled_ || pixelStride != 2) return;
        if (storedFrames_.empty()) {
            const std::int64_t outputPixels = static_cast<std::int64_t>(width) * height * 4;
            if (outputPixels <= 0 || outputPixels > 60'000'000) {
                superResolutionEnabled_ = false;
                return;
            }
            sensorWidth_ = width;
            sensorHeight_ = height;
            superResolutionWidth_ = static_cast<std::int64_t>(width) * 2;
            superResolutionHeight_ = static_cast<std::int64_t>(height) * 2;
        } else if (width != sensorWidth_ || height != sensorHeight_) {
            return;
        }

        StoredFrame frame;
        const std::int64_t packedRowBytes = static_cast<std::int64_t>(width) * 2;
        const auto packedBytes = static_cast<std::size_t>(packedRowBytes * height);
        // The spool file holds exactly these packed samples: map it instead of copying 24 MB.
        if (spoolPath != nullptr && *spoolPath != '\0' && rowStride == packedRowBytes) {
            const int descriptor = open(spoolPath, O_RDONLY | O_CLOEXEC);
            if (descriptor >= 0) {
                struct stat info {};
                if (fstat(descriptor, &info) == 0 && static_cast<std::size_t>(info.st_size) >= packedBytes) {
                    void* mapped = mmap(nullptr, packedBytes, PROT_READ, MAP_SHARED, descriptor, 0);
                    if (mapped != MAP_FAILED) {
                        frame.mapping = mapped;
                        frame.mappingLength = packedBytes;
                    }
                }
                close(descriptor);
            }
        }
        if (frame.mapping == nullptr) {
            frame.copy.resize(packedBytes);
            for (std::int64_t y = 0; y < height; ++y) {
                std::memcpy(frame.copy.data() + y * packedRowBytes, bytes + y * rowStride,
                            static_cast<std::size_t>(packedRowBytes));
            }
        }
        frame.rowStride = packedRowBytes;
        double averageBlackLevel = 0.0;
        for (std::size_t site = 0; site < 4; ++site) {
            frame.siteBlack[site] = static_cast<float>(std::max(0.0, blackLevels[site]));
            averageBlackLevel += frame.siteBlack[site];
            const int channel = cfaChannels[site];
            frame.siteChannel[site] = channel >= 0 && channel < 3 ? channel : -1;
        }
        averageBlackLevel /= 4.0;
        frame.inverseWhiteRange = static_cast<float>(1.0 / std::max(1.0, whiteLevel - averageBlackLevel));
        frame.blocks = std::move(blocks);
        frame.blocksX = blocksX;
        frame.blocksY = blocksY;
        frame.robustness = std::move(robustness);
        markFullyRobustBlocks(frame);
        storedFrames_.push_back(std::move(frame));
    }

    // A motion block covers 4x4 proxy cells; with the bilinear lookup margin its output pixels read
    // cells [4b - 1, 4b + 4] (to the map edge for the last block, whose index is clamped).
    void markFullyRobustBlocks(StoredFrame& frame) const {
        if (frame.robustness.empty()) return;
        const std::int64_t cellsPerBlock = kMotionBlockSize / kProxyScale;
        const auto range = [&](std::int64_t index, std::int64_t count, std::int64_t limit) {
            const std::int64_t first = std::max<std::int64_t>(0, index * cellsPerBlock - 1);
            const std::int64_t last = index == count - 1 ? limit - 1
                    : std::min(limit - 1, index * cellsPerBlock + cellsPerBlock);
            return std::make_pair(first, last);
        };
        for (std::int64_t by = 0; by < frame.blocksY; ++by) {
            const auto [firstY, lastY] = range(by, frame.blocksY, proxyHeight_);
            for (std::int64_t bx = 0; bx < frame.blocksX; ++bx) {
                const auto [firstX, lastX] = range(bx, frame.blocksX, proxyWidth_);
                bool robust = true;
                for (std::int64_t y = firstY; y <= lastY && robust; ++y) {
                    const float* row = frame.robustness.data() + y * proxyWidth_;
                    for (std::int64_t x = firstX; x <= lastX; ++x) {
                        if (row[x] < 1.0f) {
                            robust = false;
                            break;
                        }
                    }
                }
                frame.blocks[static_cast<std::size_t>(by * frame.blocksX + bx)].fullyRobust = robust;
            }
        }
    }

    // Kernel-based multi-frame reconstruction at the CFA level: every output pixel gathers the
    // raw Bayer samples of the frame around its motion-compensated position, each sample only
    // feeding its own color channel (demosaicing happens implicitly through the Gaussian kernels
    // and is finished with color-difference smoothing). Adds weighted sums for output rows
    // [rowBegin, rowEnd) into `sums`/`weights` ((rowEnd - rowBegin) rows of width * 3 floats).
    //
    // Within a motion block the shift is constant, so the kernel weights only depend on the output
    // column parity and the parity of the first sampled sensor column: four tap classes per block
    // are precomputed and the per-pixel work is 16 samples times 3 weight vectors.
    // Accumulators of one band use a planar, phase-permuted layout per output row: for channel c,
    // output column x lives at c * paddedWidth + permutedColumn(x). Within each 32-column motion
    // block, the 8 columns sharing x % 4 are contiguous, which is exactly one SIMD tap class.
    static constexpr std::int64_t kBlockColumns = 2 * kMotionBlockSize;

    static std::int64_t permutedColumn(std::int64_t x) {
        return (x & ~(kBlockColumns - 1)) | ((x & 3) << 3) | ((x >> 2) & 7);
    }

    std::int64_t paddedOutputWidth() const {
        return (superResolutionWidth_ + kBlockColumns - 1) / kBlockColumns * kBlockColumns;
    }

    // Adds one frame to output row `outputY`: `sums`/`weights` point at that row's 3 planes
    // (stride paddedOutputWidth()); `robustnessRow` is scratch of proxyWidth_ floats.
    //
    // Every output pixel gathers the 4x4 raw Bayer samples around its motion-compensated position,
    // each sample only feeding its own color channel, with separable Gaussian weights. Inside a
    // motion block the shift is constant, so the 8 pixels sharing x % 4 have identical weights and
    // their samples are 2 sensor columns apart: they are processed as 8 SIMD lanes, loading each
    // tap row with a deinterleave.
    bool accumulateRow(const StoredFrame& frame, std::int64_t outputY, float* sums, float* weights,
                       float* robustnessRow, std::int64_t blockBegin, std::int64_t blockEnd) const {
        using Float4 = float __attribute__((ext_vector_type(4)));
        using U16x8 = std::uint16_t __attribute__((ext_vector_type(8)));
        static const KernelLut greenKernel = makeKernelLut(0.55);
        static const KernelLut colorKernel = makeKernelLut(0.75);
        const auto tapIndex = [](int tap, int quantizedFraction) {
            return static_cast<std::size_t>(std::clamp(
                    (tap + 1) * kKernelLutScale - quantizedFraction, 0, static_cast<int>(kKernelLutSize) - 1));
        };

        const std::int64_t outputWidth = superResolutionWidth_;
        const std::int64_t planeStride = paddedOutputWidth();
        const std::int64_t width = sensorWidth_;
        const std::int64_t height = sensorHeight_;
        const std::uint8_t* bytes = frame.data();
        const std::int64_t rowStride = frame.rowStride;
        const std::int64_t proxyWidth = proxyWidth_;
        const float inverseWhiteRange = frame.inverseWhiteRange;
        // weight * estimate = robustness * scale * total * clamp((sum - black) / total * invWhite, 0, 1)
        //                   = robustness * scale * invWhite * clamp(sum - black, 0, total / invWhite).
        const float signalScale = kSuperResolutionWeightScale * inverseWhiteRange;
        const float maximumSignalPerWeight = 1.0f / inverseWhiteRange;
        const double referenceY = (static_cast<double>(outputY) + 0.5) * 0.5 - 0.5;
        const std::int64_t blockY = std::clamp<std::int64_t>(
                static_cast<std::int64_t>(std::max(0.0, referenceY)) / kMotionBlockSize, 0, frame.blocksY - 1);

        const bool useRobustness = !frame.robustness.empty();
        bool robustnessRowReady = false;
        const auto prepareRobustnessRow = [&] {
            const double proxyY = std::clamp((referenceY - 1.5) / kProxyScale, 0.0,
                                             static_cast<double>(proxyHeight_ - 1));
            const auto y0 = static_cast<std::int64_t>(proxyY);
            const float* row0 = frame.robustness.data() + y0 * proxyWidth;
            const float* row1 = frame.robustness.data() + std::min(y0 + 1, proxyHeight_ - 1) * proxyWidth;
            const auto fy = static_cast<float>(proxyY - static_cast<double>(y0));
            // Only the proxy columns this tile's output pixels can read (bilinear margin included).
            const std::int64_t cellsPerBlock = kBlockColumns / 2 / kProxyScale;
            const std::int64_t firstCell = std::max<std::int64_t>(0, blockBegin * cellsPerBlock - 1);
            const std::int64_t lastCell = std::min(proxyWidth, blockEnd * cellsPerBlock + 2);
            for (std::int64_t x = firstCell; x < lastCell; ++x) {
                robustnessRow[x] = row0[x] + (row1[x] - row0[x]) * fy;
            }
            robustnessRowReady = true;
        };
        const auto robustnessAt = [&](std::int64_t outputX) {
            const float proxyX = std::clamp(
                    static_cast<float>(outputX) * (0.5f / kProxyScale) - 1.75f / kProxyScale,
                    0.0f, static_cast<float>(proxyWidth - 1));
            const auto x0 = static_cast<std::int64_t>(proxyX);
            const std::int64_t x1 = std::min(x0 + 1, proxyWidth - 1);
            return robustnessRow[x0] + (robustnessRow[x1] - robustnessRow[x0]) * (proxyX - static_cast<float>(x0));
        };

        bool contributed = false;
        for (std::int64_t block = blockBegin; block < blockEnd; ++block) {
            const BlockMotion& motion = frame.blocks[static_cast<std::size_t>(
                    blockY * frame.blocksX + std::min(block, frame.blocksX - 1))];
            if (!motion.valid) continue;

            // Row taps (shared by the whole block row).
            const double currentY = referenceY - motion.dy;
            const double floorY = std::floor(currentY);
            const std::int64_t baseY = static_cast<std::int64_t>(floorY) - 1;
            const int quantizedY = static_cast<int>(std::lround((currentY - floorY) * kKernelLutScale));
            std::array<float, 4> rowGreen{};
            std::array<float, 4> rowColor{};
            std::array<const std::uint8_t*, 4> rowData{};
            bool anyRowInside = false;
            bool allRowsInside = true;
            for (int j = 0; j < 4; ++j) {
                const std::int64_t sampleY = baseY + j;
                const bool inside = sampleY >= 0 && sampleY < height;
                anyRowInside = anyRowInside || inside;
                allRowsInside = allRowsInside && inside;
                rowData[j] = inside ? bytes + sampleY * rowStride : nullptr;
                const std::size_t index = tapIndex(j, quantizedY);
                rowGreen[j] = greenKernel[index];
                rowColor[j] = colorKernel[index];
            }
            if (!anyRowInside) continue;

            // Column taps for both output column parities (residues r and r + 2 share them).
            std::array<std::int64_t, 2> floorShift{};
            std::array<std::array<float, 4>, 2> columnGreen{};
            std::array<std::array<float, 4>, 2> columnColor{};
            for (int parity = 0; parity < 2; ++parity) {
                // Output column x = 2k + parity maps to currentX = k + (parity / 2 - 0.25 - dx).
                const double shift = parity * 0.5 - 0.25 - motion.dx;
                const double floored = std::floor(shift);
                floorShift[parity] = static_cast<std::int64_t>(floored);
                const int quantizedX = static_cast<int>(std::lround((shift - floored) * kKernelLutScale));
                for (int i = 0; i < 4; ++i) {
                    const std::size_t index = tapIndex(i, quantizedX);
                    columnGreen[parity][i] = greenKernel[index];
                    columnColor[parity][i] = colorKernel[index];
                }
            }
            const bool blockRobust = !useRobustness || motion.fullyRobust;
            if (!blockRobust && !robustnessRowReady) prepareRobustnessRow();

            for (int residue = 0; residue < 4; ++residue) {
                // Output column x = 32 * block + residue + 4 * lane; its first tap column is
                // baseX0 + 2 * lane, so all 8 lanes share the same CFA phase and weights.
                const int parity = residue & 1;
                const std::int64_t baseX0 = block * kMotionBlockSize + (residue >> 1) + floorShift[parity] - 1;
                const std::int64_t firstColumn = block * kBlockColumns + residue;
                const int lanes = static_cast<int>(std::clamp<std::int64_t>(
                        (outputWidth - firstColumn + 3) / 4, 0, 8));
                if (lanes == 0) continue;

                // Separable weights: in tap row j, the even taps (i = 0, 2) are one CFA site and
                // the odd taps (i = 1, 3) another; each uses the green or color kernel.
                std::array<int, 4> evenSlot{};
                std::array<int, 4> oddSlot{};
                std::array<float, 4> evenRow{};
                std::array<float, 4> oddRow{};
                std::array<const float*, 4> evenColumn{};
                std::array<const float*, 4> oddColumn{};
                std::array<std::size_t, 4> evenSite{};
                std::array<std::size_t, 4> oddSite{};
                std::array<float, 3> total{};
                std::array<float, 3> black{};
                for (int j = 0; j < 4; ++j) {
                    const std::size_t siteRow = static_cast<std::size_t>(((baseY + j) & 1) << 1);
                    evenSite[j] = siteRow | static_cast<std::size_t>(baseX0 & 1);
                    oddSite[j] = siteRow | static_cast<std::size_t>((baseX0 + 1) & 1);
                    const int evenChannel = frame.siteChannel[evenSite[j]];
                    const int oddChannel = frame.siteChannel[oddSite[j]];
                    evenSlot[j] = evenChannel < 0 ? 3 : evenChannel;
                    oddSlot[j] = oddChannel < 0 ? 3 : oddChannel;
                    evenRow[j] = evenChannel == 1 ? rowGreen[j] : rowColor[j];
                    oddRow[j] = oddChannel == 1 ? rowGreen[j] : rowColor[j];
                    evenColumn[j] = evenChannel == 1 ? columnGreen[parity].data() : columnColor[parity].data();
                    oddColumn[j] = oddChannel == 1 ? columnGreen[parity].data() : columnColor[parity].data();
                    if (evenChannel >= 0) {
                        const float weight = evenRow[j] * (evenColumn[j][0] + evenColumn[j][2]);
                        total[static_cast<std::size_t>(evenChannel)] += weight;
                        black[static_cast<std::size_t>(evenChannel)] += weight * frame.siteBlack[evenSite[j]];
                    }
                    if (oddChannel >= 0) {
                        const float weight = oddRow[j] * (oddColumn[j][1] + oddColumn[j][3]);
                        total[static_cast<std::size_t>(oddChannel)] += weight;
                        black[static_cast<std::size_t>(oddChannel)] += weight * frame.siteBlack[oddSite[j]];
                    }
                }

                std::array<float, 8> laneRobustness{1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
                if (!blockRobust) {
                    bool anyLane = false;
                    for (int lane = 0; lane < lanes; ++lane) {
                        const float robustness = robustnessAt(firstColumn + 4 * lane);
                        laneRobustness[lane] = robustness >= 0.02f ? robustness : 0.0f;
                        anyLane = anyLane || laneRobustness[lane] > 0.0f;
                    }
                    if (!anyLane) continue;
                }

                const std::int64_t accumulatorOffset = block * kBlockColumns + residue * 8;
                const bool fast = allRowsInside && lanes == 8 && baseX0 >= 0 && baseX0 + 17 < width;
                if (fast) {
                    // Tap rows 0 and 2 share one CFA row phase, rows 1 and 3 the other, so four
                    // register accumulators (phase x even/odd taps) cover every sample.
                    Float4 phaseLow[2][2] = {};   // [row phase][even, odd], lanes 0-3
                    Float4 phaseHigh[2][2] = {};  // lanes 4-7
                    for (int j = 0; j < 4; ++j) {
                        const int phase = j & 1;
                        U16x8 a0;
                        U16x8 a1;
                        U16x8 b0;
                        U16x8 b1;
                        const std::uint8_t* row = rowData[j] + baseX0 * 2;
                        std::memcpy(&a0, row, sizeof(a0));
                        std::memcpy(&a1, row + 16, sizeof(a1));
                        std::memcpy(&b0, row + 4, sizeof(b0));
                        std::memcpy(&b1, row + 20, sizeof(b1));
                        // Lane m reads columns baseX0 + 2m + i for taps i = 0..3.
                        const U16x8 tap0 = __builtin_shufflevector(a0, a1, 0, 2, 4, 6, 8, 10, 12, 14);
                        const U16x8 tap1 = __builtin_shufflevector(a0, a1, 1, 3, 5, 7, 9, 11, 13, 15);
                        const U16x8 tap2 = __builtin_shufflevector(b0, b1, 0, 2, 4, 6, 8, 10, 12, 14);
                        const U16x8 tap3 = __builtin_shufflevector(b0, b1, 1, 3, 5, 7, 9, 11, 13, 15);
                        const auto lowHalf = [](U16x8 v) {
                            return __builtin_convertvector(__builtin_shufflevector(v, v, 0, 1, 2, 3), Float4);
                        };
                        const auto highHalf = [](U16x8 v) {
                            return __builtin_convertvector(__builtin_shufflevector(v, v, 4, 5, 6, 7), Float4);
                        };
                        const float* even = evenColumn[j];
                        const float* odd = oddColumn[j];
                        phaseLow[phase][0] += evenRow[j] * (even[0] * lowHalf(tap0) + even[2] * lowHalf(tap2));
                        phaseHigh[phase][0] += evenRow[j] * (even[0] * highHalf(tap0) + even[2] * highHalf(tap2));
                        phaseLow[phase][1] += oddRow[j] * (odd[1] * lowHalf(tap1) + odd[3] * lowHalf(tap3));
                        phaseHigh[phase][1] += oddRow[j] * (odd[1] * highHalf(tap1) + odd[3] * highHalf(tap3));
                    }
                    Float4 low[4] = {};
                    Float4 high[4] = {};
                    for (int phase = 0; phase < 2; ++phase) {
                        low[evenSlot[phase]] += phaseLow[phase][0];
                        high[evenSlot[phase]] += phaseHigh[phase][0];
                        low[oddSlot[phase]] += phaseLow[phase][1];
                        high[oddSlot[phase]] += phaseHigh[phase][1];
                    }
                    Float4 robustLow;
                    Float4 robustHigh;
                    std::memcpy(&robustLow, laneRobustness.data(), sizeof(robustLow));
                    std::memcpy(&robustHigh, laneRobustness.data() + 4, sizeof(robustHigh));
                    for (std::size_t c = 0; c < 3; ++c) {
                        if (total[c] < 1.0e-3f) continue;
                        const float weightScale = total[c] * kSuperResolutionWeightScale;
                        Float4 maskedLow = robustLow;
                        Float4 maskedHigh = robustHigh;
                        if (!blockRobust || weightScale < 1.0f) {
                            // frameWeight = robustness * weightScale must reach 1 to count; the
                            // comparison yields -1 (true) or 0 per lane.
                            const float minimumRobustness = 1.0f / weightScale;
                            maskedLow *= -__builtin_convertvector(robustLow >= minimumRobustness, Float4);
                            maskedHigh *= -__builtin_convertvector(robustHigh >= minimumRobustness, Float4);
                        }
                        const float maximumSignal = total[c] * maximumSignalPerWeight;
                        const Float4 zero = 0.0f;
                        const Float4 ceiling = maximumSignal;
                        const auto clampSignal = [&](Float4 value) {
                            return __builtin_elementwise_min(__builtin_elementwise_max(value, zero), ceiling);
                        };
                        const Float4 signalLow = clampSignal(low[c] - black[c]);
                        const Float4 signalHigh = clampSignal(high[c] - black[c]);
                        float* channelSums = sums + static_cast<std::int64_t>(c) * planeStride + accumulatorOffset;
                        float* channelWeights = weights + static_cast<std::int64_t>(c) * planeStride + accumulatorOffset;
                        Float4 sumLow;
                        Float4 sumHigh;
                        Float4 weightLow;
                        Float4 weightHigh;
                        std::memcpy(&sumLow, channelSums, sizeof(sumLow));
                        std::memcpy(&sumHigh, channelSums + 4, sizeof(sumHigh));
                        std::memcpy(&weightLow, channelWeights, sizeof(weightLow));
                        std::memcpy(&weightHigh, channelWeights + 4, sizeof(weightHigh));
                        sumLow += maskedLow * signalScale * signalLow;
                        sumHigh += maskedHigh * signalScale * signalHigh;
                        weightLow += maskedLow * weightScale;
                        weightHigh += maskedHigh * weightScale;
                        std::memcpy(channelSums, &sumLow, sizeof(sumLow));
                        std::memcpy(channelSums + 4, &sumHigh, sizeof(sumHigh));
                        std::memcpy(channelWeights, &weightLow, sizeof(weightLow));
                        std::memcpy(channelWeights + 4, &weightHigh, sizeof(weightHigh));
                        contributed = true;
                    }
                    continue;
                }

                // Border lanes: same weights, but only the taps that fall inside the sensor.
                for (int lane = 0; lane < lanes; ++lane) {
                    const float robustness = laneRobustness[lane];
                    if (robustness <= 0.0f) continue;
                    const std::int64_t baseX = baseX0 + 2 * lane;
                    if (baseX + 3 < 0 || baseX >= width) continue;
                    std::array<float, 3> sum{};
                    std::array<float, 3> laneTotal{};
                    std::array<float, 3> laneBlack{};
                    for (int j = 0; j < 4; ++j) {
                        if (rowData[j] == nullptr) continue;
                        for (int i = 0; i < 4; ++i) {
                            const std::int64_t sampleX = baseX + i;
                            if (sampleX < 0 || sampleX >= width) continue;
                            const bool even = (i & 1) == 0;
                            const int slot = even ? evenSlot[j] : oddSlot[j];
                            if (slot > 2) continue;
                            const float weight = even ? evenRow[j] * evenColumn[j][i] : oddRow[j] * oddColumn[j][i];
                            const auto c = static_cast<std::size_t>(slot);
                            sum[c] += weight * static_cast<float>(readRawSample(rowData[j], sampleX * 2));
                            laneTotal[c] += weight;
                            laneBlack[c] += weight * frame.siteBlack[even ? evenSite[j] : oddSite[j]];
                        }
                    }
                    for (std::size_t c = 0; c < 3; ++c) {
                        if (laneTotal[c] < 1.0e-3f) continue;
                        const float frameWeight = laneTotal[c] * robustness * kSuperResolutionWeightScale;
                        if (frameWeight < 1.0f) continue;
                        const float signal = std::clamp(sum[c] - laneBlack[c], 0.0f,
                                                        laneTotal[c] * maximumSignalPerWeight);
                        const std::int64_t index = static_cast<std::int64_t>(c) * planeStride + accumulatorOffset + lane;
                        sums[index] += robustness * signalScale * signal;
                        weights[index] += frameWeight;
                        contributed = true;
                    }
                }
            }
        }
        return contributed;
    }

    // Per-channel weighted means (linear camera RGB, 0..1) from the permuted planar accumulators;
    // missing channels are borrowed from the available ones, and empty pixels are marked invalid.
    void decodeAccumulatedRow(const float* sums, const float* weights, float* rgb,
                              std::uint8_t* valid) const {
        const std::int64_t width = superResolutionWidth_;
        const std::int64_t planeStride = paddedOutputWidth();
        for (std::int64_t x = 0; x < width; ++x) {
            const std::int64_t index = permutedColumn(x);
            std::array<float, 3> color{};
            std::array<bool, 3> present{};
            for (std::size_t channel = 0; channel < 3; ++channel) {
                const std::int64_t at = static_cast<std::int64_t>(channel) * planeStride + index;
                present[channel] = weights[at] > 0.0f;
                color[channel] = present[channel] ? sums[at] / weights[at] : 0.0f;
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
    }

    // Smooths chroma (R-G, B-G) over 3x3 while keeping full-resolution green, then applies the
    // white balance gains, the color matrix, and the sRGB curve. Neighbor rows may be null.
    void encodeOutputRow(const float* above, const std::uint8_t* aboveValid,
                         const float* center, const std::uint8_t* centerValid,
                         const float* below, const std::uint8_t* belowValid,
                         std::uint8_t* outputRow) const {
        const std::int64_t width = superResolutionWidth_;
        const std::array<const float*, 3> rows{above, center, below};
        const std::array<const std::uint8_t*, 3> validRows{aboveValid, centerValid, belowValid};
        for (std::int64_t x = 0; x < width; ++x) {
            const std::size_t offset = static_cast<std::size_t>(x) * 3;
            if (centerValid[x] == 0) {
                outputRow[offset] = 0;
                outputRow[offset + 1] = 0;
                outputRow[offset + 2] = 0;
                continue;
            }
            float redDifference = 0.0f;
            float blueDifference = 0.0f;
            int count = 0;
            for (std::size_t r = 0; r < 3; ++r) {
                const float* rgb = rows[r];
                const std::uint8_t* valid = validRows[r];
                if (rgb == nullptr) continue;
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

        // Proxy rows read disjoint sensor rows and write disjoint outputs.
        parallelFor(proxyHeight_, 16, [&](std::int64_t proxyRowBegin, std::int64_t proxyRowEnd) {
            for (std::int64_t proxyY = proxyRowBegin; proxyY < proxyRowEnd; ++proxyY) {
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
        });
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
        parallelFor(height, 64, [&](std::int64_t rowBegin, std::int64_t rowEnd) {
            for (std::int64_t y = rowBegin; y < rowEnd; ++y) {
                const std::uint16_t* row = plane.data() + y * width;
                std::uint32_t* out = horizontal.data() + y * width;
                out[0] = 3U * row[0] + row[1];
                for (std::int64_t x = 1; x + 1 < width; ++x) {
                    out[x] = row[x - 1] + 2U * row[x] + row[x + 1];
                }
                out[width - 1] = row[width - 2] + 3U * row[width - 1];
            }
        });
        parallelFor(height, 64, [&](std::int64_t rowBegin, std::int64_t rowEnd) {
            for (std::int64_t y = rowBegin; y < rowEnd; ++y) {
                const std::uint32_t* above = horizontal.data() + std::max<std::int64_t>(0, y - 1) * width;
                const std::uint32_t* center = horizontal.data() + y * width;
                const std::uint32_t* below = horizontal.data() + std::min(height - 1, y + 1) * width;
                std::uint16_t* out = plane.data() + y * width;
                for (std::int64_t x = 0; x < width; ++x) {
                    out[x] = static_cast<std::uint16_t>((above[x] + 2U * center[x] + below[x] + 8U) / 16U);
                }
            }
        });
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

    // Bilinear robustness lookup in proxy coordinates; an empty map means full trust (reference).
    float sampleRobustness(const std::vector<float>& robustness, double proxyX, double proxyY) const {
        if (robustness.empty()) return 1.0f;
        const std::int64_t width = proxyWidth_;
        const std::int64_t height = proxyHeight_;
        const double x = std::clamp(proxyX, 0.0, static_cast<double>(width - 1));
        const double y = std::clamp(proxyY, 0.0, static_cast<double>(height - 1));
        const auto x0 = static_cast<std::int64_t>(x);
        const auto y0 = static_cast<std::int64_t>(y);
        const std::int64_t x1 = std::min(x0 + 1, width - 1);
        const std::int64_t y1 = std::min(y0 + 1, height - 1);
        const float fx = static_cast<float>(x - static_cast<double>(x0));
        const float fy = static_cast<float>(y - static_cast<double>(y0));
        const float* row0 = robustness.data() + y0 * width;
        const float* row1 = robustness.data() + y1 * width;
        const float top = row0[x0] + (row0[x1] - row0[x0]) * fx;
        const float bottom = row1[x0] + (row1[x1] - row1[x0]) * fx;
        return top + (bottom - top) * fy;
    }

    void accumulateFusion(
            const std::vector<ColorProxyPixel>& colorProxy,
            const AlignmentResult& alignment,
            const std::vector<float>& robustness) {
        constexpr double kWeightScale = 256.0;
        // motionAt() returns the global correction or a convex blend of reliable local ones, so
        // this bounds how many rows away from its source a sample can be splatted.
        double maxCorrection = std::max(std::abs(alignment.xCorrectionPixels),
                                        std::abs(alignment.yCorrectionPixels));
        for (const LocalMotionRegion& region : alignment.localRegions) {
            if (!region.reliable) continue;
            maxCorrection = std::max({maxCorrection, std::abs(region.xCorrectionPixels),
                                      std::abs(region.yCorrectionPixels)});
        }
        // Stripes k and k + 2 never write the same rows when the stripe is taller than twice the
        // reach, so even stripes run in parallel, then odd stripes.
        const std::int64_t stripeRows = std::isfinite(maxCorrection) && maxCorrection < 1.0e6
                ? std::max<std::int64_t>(
                          64, 2 * (static_cast<std::int64_t>(std::ceil(maxCorrection / kFusionScale)) + 2) + 2)
                : std::max<std::int64_t>(1, fusionHeight_);
        const std::int64_t stripeCount = (fusionHeight_ + stripeRows - 1) / stripeRows;
        std::atomic<bool> contributed{false};

        const auto processRows = [&](std::int64_t rowBegin, std::int64_t rowEnd) {
            bool localContribution = false;
            for (std::int64_t y = rowBegin; y < rowEnd; ++y) {
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
                    // The target is in reference coordinates; a 2x2 preview cell centered on
                    // sensor (2t + 0.5) maps to proxy ((2t + 0.5) - 1.5) / 4.
                    const double sourceRobustness = sampleRobustness(
                            robustness, (targetX - 0.5) / 2.0, (targetY - 0.5) / 2.0);
                    if (sourceRobustness < 0.02) continue;
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
                                    std::clamp(weightX * weightY * sourceRobustness * kWeightScale,
                                               0.0, kWeightScale)));
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
                            localContribution = true;
                        }
                    }
                }
            }
            if (localContribution) contributed.store(true, std::memory_order_relaxed);
        };

        for (std::int64_t phase = 0; phase < 2; ++phase) {
            const std::int64_t phaseStripes = (stripeCount - phase + 1) / 2;
            parallelFor(phaseStripes, 1, [&](std::int64_t begin, std::int64_t end) {
                for (std::int64_t i = begin; i < end; ++i) {
                    const std::int64_t stripe = 2 * i + phase;
                    processRows(stripe * stripeRows,
                                std::min(fusionHeight_, (stripe + 1) * stripeRows));
                }
            });
        }
        if (contributed.load()) ++fusionFrameCount_;
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
        const std::int64_t localStep = kCorrelationSampleStep / 2;

        // Regions are independent: evaluate them in parallel, then aggregate in grid order.
        std::array<bool, kLocalRegionCount> evaluated{};
        std::array<double, kLocalRegionCount> squaredDeviation{};
        parallelFor(static_cast<std::int64_t>(kLocalRegionCount), 1,
                [&](std::int64_t begin, std::int64_t end) {
            for (std::int64_t regionIndex = begin; regionIndex < end; ++regionIndex) {
                const std::int64_t row = regionIndex / kLocalGridColumns;
                const std::int64_t column = regionIndex % kLocalGridColumns;
                const std::int64_t rowStart = innerTop +
                        (innerBottom - innerTop) * row / kLocalGridRows;
                const std::int64_t rowEnd = innerTop +
                        (innerBottom - innerTop) * (row + 1) / kLocalGridRows;
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
                                current, candidateX, candidateY, startX, endX, startY, endY, localStep);
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

                const std::int64_t localDeltaX = bestX - globalX;
                const std::int64_t localDeltaY = bestY - globalY;
                const bool reachedLocalLimit =
                        std::abs(localDeltaX) == kLocalSearchRadius ||
                        std::abs(localDeltaY) == kLocalSearchRadius;

                double subpixelX = 0.0;
                double subpixelY = 0.0;
                if (std::abs(localDeltaX) < kLocalSearchRadius) {
                    subpixelX = parabolicOffset(
                            scoreRegionAt(current, bestX - 1, bestY, startX, endX, startY, endY, localStep),
                            bestScore,
                            scoreRegionAt(current, bestX + 1, bestY, startX, endX, startY, endY, localStep));
                }
                if (std::abs(localDeltaY) < kLocalSearchRadius) {
                    subpixelY = parabolicOffset(
                            scoreRegionAt(current, bestX, bestY - 1, startX, endX, startY, endY, localStep),
                            bestScore,
                            scoreRegionAt(current, bestX, bestY + 1, startX, endX, startY, endY, localStep));
                }

                const double localX = (static_cast<double>(bestX) + subpixelX) * kProxyScale;
                const double localY = (static_cast<double>(bestY) + subpixelY) * kProxyScale;
                LocalMotionRegion& region = result.localRegions[static_cast<std::size_t>(regionIndex)];
                region.xCorrectionPixels = localX;
                region.yCorrectionPixels = localY;
                region.confidence = confidence;
                region.reliable = confidence >= 0.05 && !reachedLocalLimit;
                evaluated[static_cast<std::size_t>(regionIndex)] = true;
                if (!region.reliable) continue;

                const double deviationX =
                        localX - (static_cast<double>(globalX) + globalSubpixelX) * kProxyScale;
                const double deviationY =
                        localY - (static_cast<double>(globalY) + globalSubpixelY) * kProxyScale;
                squaredDeviation[static_cast<std::size_t>(regionIndex)] =
                        deviationX * deviationX + deviationY * deviationY;
            }
        });

        double confidenceSum = 0.0;
        double squaredSpreadSum = 0.0;
        std::int32_t evaluatedRegionCount = 0;
        for (std::size_t i = 0; i < kLocalRegionCount; ++i) {
            if (!evaluated[i]) continue;
            confidenceSum += result.localRegions[i].confidence;
            ++evaluatedRegionCount;
            if (!result.localRegions[i].reliable) continue;
            squaredSpreadSum += squaredDeviation[i];
            ++result.reliableLocalRegionCount;
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
        parallelFor(static_cast<std::int64_t>(kCandidateCount), 4,
                [&](std::int64_t begin, std::int64_t end) {
                    for (std::int64_t index = begin; index < end; ++index) {
                        const std::int64_t dx = index % static_cast<std::int64_t>(kCandidateSide) - kSearchRadius;
                        const std::int64_t dy = index / static_cast<std::int64_t>(kCandidateSide) - kSearchRadius;
                        scores[static_cast<std::size_t>(index)] = scoreAt(current, dx, dy);
                    }
                });
        for (std::int64_t dy = -kSearchRadius; dy <= kSearchRadius; ++dy) {
            for (std::int64_t dx = -kSearchRadius; dx <= kSearchRadius; ++dx) {
                const auto scoreIndex = static_cast<std::size_t>(dy + kSearchRadius) *
                        kCandidateSide + static_cast<std::size_t>(dx + kSearchRadius);
                const double score = scores[scoreIndex];
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
        if (result.reachedSearchLimit) return result;
        if (result.confidence >= 0.02) {
            // The offsets map current-frame coordinates back into the first frame's coordinates.
            result.xCorrectionPixels = (static_cast<double>(bestX) + subpixelX) * kProxyScale;
            result.yCorrectionPixels = (static_cast<double>(bestY) + subpixelY) * kProxyScale;
        }
        // Parallax and hand roll make a single translation fit later frames poorly, which lowers
        // the global confidence even though each region still matches well; every region is
        // searched around the global estimate and validated by its own confidence.
        estimateLocalMotion(current, bestX, bestY, subpixelX, subpixelY, result);
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
        if (result.isReference || result.reachedSearchLimit ||
            (result.confidence < 0.02 && result.reliableLocalRegionCount == 0) ||
            fineReference_.size() != fineCurrent.size() || fusionWidth_ <= 2 * kSearchRadius + 8 ||
            fusionHeight_ <= 2 * kSearchRadius + 8) {
            return;
        }
        // A weak global match is not refined (and stays unused); reliable regions still are.
        const bool refineGlobal = result.confidence >= 0.02;

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
        parallelFor(static_cast<std::int64_t>(kRefineCount), 1,
                [&](std::int64_t begin, std::int64_t end) {
                    for (std::int64_t index = begin; index < end; ++index) {
                        const std::int64_t dx = index % static_cast<std::int64_t>(kRefineSide) - kRefineRadius;
                        const std::int64_t dy = index / static_cast<std::int64_t>(kRefineSide) - kRefineRadius;
                        globalScores[static_cast<std::size_t>(index)] = fineScoreAt(
                                fineCurrent,
                                globalCenterX + dx,
                                globalCenterY + dy,
                                globalStartX,
                                globalEndX,
                                globalStartY,
                                globalEndY,
                                kFineStep);
                    }
                });
        for (std::int64_t dy = -kRefineRadius; dy <= kRefineRadius; ++dy) {
            for (std::int64_t dx = -kRefineRadius; dx <= kRefineRadius; ++dx) {
                const std::size_t index = static_cast<std::size_t>(dy + kRefineRadius) *
                        kRefineSide + static_cast<std::size_t>(dx + kRefineRadius);
                const double score = globalScores[index];
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
        if (refineGlobal && fineGlobalConfidence >= 0.01 && std::isfinite(globalBestScore)) {
            // globalBestX/Y are offsets (often zero or negative), not coordinates: the neighbors
            // are always evaluable because fineScoreAt() skips samples that fall outside the frame.
            const double subpixelX = parabolicOffset(
                    fineScoreAt(fineCurrent, globalBestX - 1, globalBestY,
                                globalStartX, globalEndX, globalStartY, globalEndY, kFineStep),
                    globalBestScore,
                    fineScoreAt(fineCurrent, globalBestX + 1, globalBestY,
                                globalStartX, globalEndX, globalStartY, globalEndY, kFineStep));
            const double subpixelY = parabolicOffset(
                    fineScoreAt(fineCurrent, globalBestX, globalBestY - 1,
                                globalStartX, globalEndX, globalStartY, globalEndY, kFineStep),
                    globalBestScore,
                    fineScoreAt(fineCurrent, globalBestX, globalBestY + 1,
                                globalStartX, globalEndX, globalStartY, globalEndY, kFineStep));
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
        // Each region only updates its own entry, so the regions are refined in parallel.
        parallelFor(static_cast<std::int64_t>(kLocalRegionCount), 1,
                [&](std::int64_t regionBegin, std::int64_t regionEnd) {
            for (std::int64_t regionIndex = regionBegin; regionIndex < regionEnd; ++regionIndex) {
                const std::int64_t row = regionIndex / kLocalGridColumns;
                const std::int64_t column = regionIndex % kLocalGridColumns;
                const std::int64_t rowStart = innerTop + (innerBottom - innerTop) * row / kLocalGridRows;
                const std::int64_t rowEnd = innerTop + (innerBottom - innerTop) * (row + 1) / kLocalGridRows;
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

                // bestX/Y are offsets, not coordinates (see the global refinement above).
                const double subpixelX = parabolicOffset(
                        fineScoreAt(fineCurrent, bestX - 1, bestY,
                                    startX, endX, startY, endY, kFineStep),
                        bestScore,
                        fineScoreAt(fineCurrent, bestX + 1, bestY,
                                    startX, endX, startY, endY, kFineStep));
                const double subpixelY = parabolicOffset(
                        fineScoreAt(fineCurrent, bestX, bestY - 1,
                                    startX, endX, startY, endY, kFineStep),
                        bestScore,
                        fineScoreAt(fineCurrent, bestX, bestY + 1,
                                    startX, endX, startY, endY, kFineStep));
                region.xCorrectionPixels = (bestX + subpixelX) * 2.0;
                region.yCorrectionPixels = (bestY + subpixelY) * 2.0;
                region.confidence = std::max(region.confidence, fineConfidence);
            }
        });

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
    int frameIndex_ = 0;
    bool superResolutionEnabled_ = false;
    std::vector<StoredFrame> storedFrames_;
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
        jboolean superResolutionEnabled) {
    auto* aligner = new (std::nothrow) RawBurstAligner(superResolutionEnabled == JNI_TRUE);
    if (aligner == nullptr) {
        throwOutOfMemory(env, "Could not allocate the RAW burst aligner.");
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<std::intptr_t>(aligner));
}

namespace {

jintArray superResolutionDimensions(JNIEnv* env, RawBurstAligner* aligner) {
    const jint dimensions[] = {
            aligner->superResolutionWidth(),
            aligner->superResolutionHeight(),
            aligner->superResolutionFrameCount(),
    };
    jintArray result = env->NewIntArray(3);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 3, dimensions);
    return result;
}

std::string javaString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jint parcelFileDescriptor(JNIEnv* env, jobject parcelFileDescriptor) {
    if (parcelFileDescriptor == nullptr) return -1;
    jclass descriptorClass = env->GetObjectClass(parcelFileDescriptor);
    if (descriptorClass == nullptr) return -1;
    jmethodID getFileDescriptor = env->GetMethodID(descriptorClass, "getFd", "()I");
    if (getFileDescriptor == nullptr) return -1;
    const jint descriptor = env->CallIntMethod(parcelFileDescriptor, getFileDescriptor);
    return env->ExceptionCheck() ? -1 : descriptor;
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_azuchan_fabula_ui_camera_NativeRawBurstAligner_nativeWriteSuperResolutionFiles(
        JNIEnv* env,
        jobject /* receiver */,
        jlong handle,
        jstring tiffPath,
        jstring jpegPath,
        jint orientation) {
    auto* aligner = reinterpret_cast<RawBurstAligner*>(static_cast<std::intptr_t>(handle));
    if (aligner == nullptr || tiffPath == nullptr) {
        throwIllegalState(env, "The RAW burst aligner is closed or the TIFF path is null.");
        return nullptr;
    }
    const std::string tiff = javaString(env, tiffPath);
    const std::string jpeg = javaString(env, jpegPath);
    if (env->ExceptionCheck()) return nullptr;
    try {
        if (!aligner->writeSuperResolutionFiles(tiff, jpeg, orientation)) return env->NewIntArray(0);
        return superResolutionDimensions(env, aligner);
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env, "Could not allocate the bands of the RAW 2x reconstruction.");
        return nullptr;
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_azuchan_fabula_ui_camera_NativeRawBurstAligner_nativeWriteSuperResolutionToDescriptors(
        JNIEnv* env,
        jobject /* receiver */,
        jlong handle,
        jobject tiffDescriptor,
        jobject jpegDescriptor,
        jint orientation) {
    auto* aligner = reinterpret_cast<RawBurstAligner*>(static_cast<std::intptr_t>(handle));
    if (aligner == nullptr || tiffDescriptor == nullptr) {
        throwIllegalState(env, "The RAW burst aligner is closed or the TIFF descriptor is null.");
        return nullptr;
    }
    const jint tiff = parcelFileDescriptor(env, tiffDescriptor);
    const jint jpeg = parcelFileDescriptor(env, jpegDescriptor);
    if (env->ExceptionCheck()) return nullptr;
    try {
        if (!aligner->writeSuperResolutionToFileDescriptors(tiff, jpegDescriptor != nullptr ? jpeg : -1, orientation)) {
            return env->NewIntArray(0);
        }
        return superResolutionDimensions(env, aligner);
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env, "Could not allocate the bands of the RAW 2x reconstruction.");
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
        jdoubleArray colorMatrixArray,
        jstring spoolPath) {
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
    const std::string spool = javaString(env, spoolPath);
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
                    spool.c_str(),
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
