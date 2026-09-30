// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <cstdint>
#include <vector>
#include <functional>
#include <memory>

namespace camsure {
enum class VideoCodec { H264 };
struct EncodedVideoAccessUnit {
 uint64_t session_id = 0;
 VideoCodec codec = VideoCodec::H264;
 std::vector<uint8_t> annex_b, codec_configuration;
 int64_t presentation_time_us = 0;
 bool keyframe = false, discontinuity = false;
 int64_t reconstructed_qpc = 0;
};
enum class PixelFormat { I420 };
struct DecodedVideoFrame {
 uint32_t width = 0, height = 0, stride = 0;
 PixelFormat format = PixelFormat::I420;
 bool full_range = false, bt601 = false;
 int64_t presentation_time_us = 0;
 std::vector<uint8_t> pixels; // tight I420: Y, U, V; decoder crop already applied
};
// All calls and destruction belong to the session decoder thread.
class VideoDecoder {
public:
 using Output = std::function<void(DecodedVideoFrame)>;
 virtual ~VideoDecoder() = default;
 virtual void submit(const EncodedVideoAccessUnit &, const Output &) = 0;
 virtual void reset() = 0;
};
std::unique_ptr<VideoDecoder> make_h264_decoder();
}
