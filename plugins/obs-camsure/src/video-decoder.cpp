// SPDX-License-Identifier: GPL-2.0-or-later
#include "video-decoder.hpp"
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/hwcontext.h>
}
#include <stdexcept>
#include <cstring>
#include <string>
#include <cstdlib>
#include <chrono>
#include <algorithm>
namespace camsure {
static void check(int result) {
 if (result < 0) { char error[AV_ERROR_MAX_STRING_SIZE]{}; av_strerror(result, error, sizeof(error)); throw std::runtime_error(error); }
}
class H264Decoder final : public VideoDecoder {
 AVCodecContext *context = nullptr;
 AVBufferRef *hardware_device = nullptr;
 uint64_t session = 0;
 std::vector<uint8_t> configuration;
 bool hardware_disabled = false, hardware_used = false;
 Timing measurements;
 using Clock = std::chrono::steady_clock;
 static void record(Clock::time_point start, uint64_t &count, double &total, double &maximum) {
  const double elapsed = std::chrono::duration<double, std::milli>(Clock::now() - start).count();
  ++count; total += elapsed; maximum = std::max(maximum, elapsed);
 }
 static AVPixelFormat choose_format(AVCodecContext *ctx, const AVPixelFormat *formats) {
  auto *self = static_cast<H264Decoder *>(ctx->opaque);
  self->hardware_used = false;
  if (!self->hardware_disabled && !std::getenv("CAMSURE_FORCE_SOFTWARE") &&
      (ctx->width > 1920 || ctx->height > 1080)) {
   for (const auto *p = formats; *p != AV_PIX_FMT_NONE; ++p) {
    if (*p == AV_PIX_FMT_D3D11) {
     if (!self->hardware_device && av_hwdevice_ctx_create(&self->hardware_device, AV_HWDEVICE_TYPE_D3D11VA, nullptr, nullptr, 0) < 0) {
      self->hardware_disabled = true; break;
     }
     if (!ctx->hw_device_ctx) ctx->hw_device_ctx = av_buffer_ref(self->hardware_device);
     if (!ctx->hw_device_ctx) return AV_PIX_FMT_NONE;
     self->hardware_used = true;
     return *p;
    }
   }
  }
  for (const auto *p = formats; *p != AV_PIX_FMT_NONE; ++p) if (*p == AV_PIX_FMT_YUV420P) return *p;
  return AV_PIX_FMT_NONE;
 }
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
  context->max_pixels = 3840LL * 2160;
  context->opaque = this;
  context->get_format = choose_format;
  check(avcodec_open2(context, codec, nullptr));
 }
 void drain(const Output &output) {
  auto release = [](AVFrame *frame) { av_frame_free(&frame); };
  std::unique_ptr<AVFrame, decltype(release)> frame(av_frame_alloc(), release);
  if (!frame) throw std::bad_alloc();
  for (unsigned i = 0; i < 16; ++i) {
   const auto codec_start = Clock::now();
   const int result = avcodec_receive_frame(context, frame.get());
   record(codec_start, measurements.codec_count, measurements.codec_total_ms, measurements.codec_max_ms);
   if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return;
   check(result);
   if (frame->format == AV_PIX_FMT_D3D11) {
    std::unique_ptr<AVFrame, decltype(release)> cpu(av_frame_alloc(), release);
    if (!cpu) throw std::bad_alloc();
    const auto transfer_start = Clock::now();
    check(av_hwframe_transfer_data(cpu.get(), frame.get(), 0));
    record(transfer_start, measurements.transfer_count, measurements.transfer_total_ms, measurements.transfer_max_ms);
    check(av_frame_copy_props(cpu.get(), frame.get()));
    av_frame_unref(frame.get());
    av_frame_move_ref(frame.get(), cpu.get());
   }
   if (frame->flags & AV_FRAME_FLAG_CORRUPT || frame->decode_error_flags) throw std::runtime_error("Corrupt decoded frame");
   if ((frame->format != AV_PIX_FMT_YUV420P && frame->format != AV_PIX_FMT_NV12) || frame->width <= 0 || frame->height <= 0 || frame->width > 3840 || frame->height > 2160 || ((frame->width | frame->height) & 1))
    throw std::runtime_error("Unsupported decoder pixel format/dimensions (8-bit I420 through 3840x2160 required)");
   if (frame->pts == AV_NOPTS_VALUE) throw std::runtime_error("Decoder output has no source PTS");
   const auto conversion_start = Clock::now();
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
    const bool interleaved = frame->format == AV_PIX_FMT_NV12 && plane > 0;
    const unsigned source_plane = interleaved ? 1 : plane;
    if (!frame->data[source_plane] || frame->linesize[source_plane] < static_cast<int>(interleaved ? width * 2 : width)) throw std::runtime_error("Invalid decoded plane stride");
    for (uint32_t row = 0; row < height; ++row) {
     auto *destination = decoded.pixels.data() + offset + size_t(row) * width;
     const auto *source = frame->data[source_plane] + size_t(row) * size_t(frame->linesize[source_plane]);
     if (interleaved) for (uint32_t col = 0; col < width; ++col) destination[col] = source[col * 2 + plane - 1];
     else std::memcpy(destination, source, width);
    }
    offset += size_t(width) * height;
   }
   record(conversion_start, measurements.conversion_count, measurements.conversion_total_ms, measurements.conversion_max_ms);
   output(std::move(decoded));
   av_frame_unref(frame.get());
  }
  throw std::runtime_error("Decoder exceeded bounded output drain");
 }
public:
 ~H264Decoder() override { reset(); av_buffer_unref(&hardware_device); }
 Timing timing() const override { return measurements; }
 const char *backend() const override { return hardware_used ? "D3D11VA" : (hardware_disabled ? "software-fallback" : "software"); }
 void reset() override { avcodec_free_context(&context); configuration.clear(); }
 void submit(const EncodedVideoAccessUnit &unit, const Output &output) override {
  try { submit_impl(unit, output); }
  catch (...) { if (hardware_used) { hardware_disabled = true; hardware_used = false; reset(); av_buffer_unref(&hardware_device); } throw; }
 }
 void submit_impl(const EncodedVideoAccessUnit &unit, const Output &output) {
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
  auto codec_start = Clock::now();
  int result = avcodec_send_packet(context, packet.get());
  record(codec_start, measurements.codec_count, measurements.codec_total_ms, measurements.codec_max_ms);
  if (result == AVERROR(EAGAIN)) {
   drain(output); codec_start = Clock::now(); result = avcodec_send_packet(context, packet.get());
   record(codec_start, measurements.codec_count, measurements.codec_total_ms, measurements.codec_max_ms);
  }
  check(result); drain(output);
 }
};
std::unique_ptr<VideoDecoder> make_h264_decoder() { return std::make_unique<H264Decoder>(); }
}
