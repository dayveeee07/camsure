// SPDX-License-Identifier: GPL-2.0-or-later
#include "video-decoder.hpp"
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
}
#include <stdexcept>
#include <cstring>
#include <string>
namespace camsure {
static void check(int result) {
 if (result < 0) { char error[AV_ERROR_MAX_STRING_SIZE]{}; av_strerror(result, error, sizeof(error)); throw std::runtime_error(error); }
}
class H264Decoder final : public VideoDecoder {
 AVCodecContext *context = nullptr;
 uint64_t session = 0;
 std::vector<uint8_t> configuration;
 void initialize() {
  const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
  if (!codec) throw std::runtime_error("H.264 decoder unavailable");
  context = avcodec_alloc_context3(codec);
  if (!context) throw std::bad_alloc();
  context->pkt_timebase = AVRational{1, 1000000};
  context->flags |= AV_CODEC_FLAG_LOW_DELAY;
  context->thread_count = 2;
  context->thread_type = FF_THREAD_SLICE;
  context->err_recognition = AV_EF_CAREFUL | AV_EF_EXPLODE;
  check(avcodec_open2(context, codec, nullptr));
 }
 void drain(const Output &output) {
  auto release = [](AVFrame *frame) { av_frame_free(&frame); };
  std::unique_ptr<AVFrame, decltype(release)> frame(av_frame_alloc(), release);
  if (!frame) throw std::bad_alloc();
  for (unsigned i = 0; i < 16; ++i) {
   const int result = avcodec_receive_frame(context, frame.get());
   if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return;
   check(result);
   if (frame->flags & AV_FRAME_FLAG_CORRUPT || frame->decode_error_flags) throw std::runtime_error("Corrupt decoded frame");
   if (frame->format != AV_PIX_FMT_YUV420P || frame->width <= 0 || frame->height <= 0 || frame->width > 1920 || frame->height > 1080 || ((frame->width | frame->height) & 1))
    throw std::runtime_error("Unsupported decoder pixel format/dimensions (8-bit I420 through 1080p required)");
   if (frame->pts == AV_NOPTS_VALUE) throw std::runtime_error("Decoder output has no source PTS");
   DecodedVideoFrame decoded;
   decoded.width = static_cast<uint32_t>(frame->width); decoded.height = static_cast<uint32_t>(frame->height); decoded.stride = decoded.width;
   decoded.presentation_time_us = frame->pts; decoded.format = PixelFormat::I420;
   decoded.full_range = frame->color_range == AVCOL_RANGE_JPEG;
   decoded.bt601 = frame->colorspace == AVCOL_SPC_SMPTE170M || frame->colorspace == AVCOL_SPC_BT470BG;
   decoded.pixels.resize(size_t(decoded.width) * decoded.height * 3 / 2);
   size_t offset = 0;
   for (unsigned plane = 0; plane < 3; ++plane) {
    const uint32_t width = plane ? decoded.width / 2 : decoded.width;
    const uint32_t height = plane ? decoded.height / 2 : decoded.height;
    if (!frame->data[plane] || frame->linesize[plane] < static_cast<int>(width)) throw std::runtime_error("Invalid I420 plane stride");
    for (uint32_t row = 0; row < height; ++row)
     std::memcpy(decoded.pixels.data() + offset + size_t(row) * width, frame->data[plane] + size_t(row) * size_t(frame->linesize[plane]), width);
    offset += size_t(width) * height;
   }
   output(std::move(decoded));
   av_frame_unref(frame.get());
  }
  throw std::runtime_error("Decoder exceeded bounded output drain");
 }
public:
 ~H264Decoder() override { reset(); }
 void reset() override { avcodec_free_context(&context); configuration.clear(); }
 void submit(const EncodedVideoAccessUnit &unit, const Output &output) override {
  if (unit.codec != VideoCodec::H264 || unit.presentation_time_us < 0 || unit.annex_b.empty() || unit.annex_b.size() > 2 * 1024 * 1024 || unit.codec_configuration.size() > 65536)
   throw std::runtime_error("Invalid encoded AU");
  if (unit.discontinuity || session != unit.session_id || (!unit.codec_configuration.empty() && configuration != unit.codec_configuration)) reset();
  if (!context) {
   if (!unit.keyframe || unit.codec_configuration.empty()) return;
   initialize(); session = unit.session_id; configuration = unit.codec_configuration;
  }
  auto release = [](AVPacket *packet) { av_packet_free(&packet); };
  std::unique_ptr<AVPacket, decltype(release)> packet(av_packet_alloc(), release);
  if (!packet) throw std::bad_alloc();
  const size_t prefix = unit.keyframe ? configuration.size() : 0;
  check(av_new_packet(packet.get(), static_cast<int>(prefix + unit.annex_b.size())));
  if (prefix) std::memcpy(packet->data, configuration.data(), prefix);
  std::memcpy(packet->data + prefix, unit.annex_b.data(), unit.annex_b.size());
  packet->pts = unit.presentation_time_us;
  packet->dts = AV_NOPTS_VALUE;
  if (unit.keyframe) packet->flags |= AV_PKT_FLAG_KEY;
  int result = avcodec_send_packet(context, packet.get());
  if (result == AVERROR(EAGAIN)) { drain(output); result = avcodec_send_packet(context, packet.get()); }
  check(result); drain(output);
 }
};
std::unique_ptr<VideoDecoder> make_h264_decoder() { return std::make_unique<H264Decoder>(); }
}
