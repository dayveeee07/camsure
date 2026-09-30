// SPDX-License-Identifier: GPL-2.0-or-later
// Synthetic MF encoder -> FFmpeg decoder proof; never physical camera acceptance.
#include "video-decoder.hpp"
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <mftransform.h>
#include <wmcodecdsp.h>
#include <codecapi.h>
#include <wrl/client.h>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <fstream>
using Microsoft::WRL::ComPtr;
static void check(HRESULT hr) { if (FAILED(hr)) throw std::runtime_error("HRESULT=" + std::to_string(static_cast<uint32_t>(hr))); }
static void require(bool ok, const char *why) { if (!ok) throw std::runtime_error(why); }
static std::vector<uint8_t> config(const std::vector<uint8_t> &bytes)
{
 std::vector<uint8_t> result;
 for (size_t i = 0; i + 4 < bytes.size();) {
  size_t prefix = 0;
  if (bytes[i] == 0 && bytes[i + 1] == 0) {
   if (bytes[i + 2] == 1) prefix = 3;
   else if (bytes[i + 2] == 0 && bytes[i + 3] == 1) prefix = 4;
  }
  if (!prefix) { ++i; continue; }
  size_t end = i + prefix + 1;
  while (end + 3 < bytes.size() && !(bytes[end] == 0 && bytes[end + 1] == 0 && (bytes[end + 2] == 1 || (bytes[end + 2] == 0 && bytes[end + 3] == 1)))) ++end;
  if (end + 3 >= bytes.size()) end = bytes.size();
  const unsigned type = bytes[i + prefix] & 31;
  if (type == 7 || type == 8) result.insert(result.end(), bytes.begin() + static_cast<ptrdiff_t>(i), bytes.begin() + static_cast<ptrdiff_t>(end));
  i = end;
 }
 return result;
}
static void test(uint32_t width, uint32_t height, const char *fixture)
{
 ComPtr<IMFTransform> encoder;
 check(CoCreateInstance(CLSID_CMSH264EncoderMFT, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&encoder)));
 ComPtr<IMFAttributes> attrs;
 if (SUCCEEDED(encoder->GetAttributes(&attrs))) attrs->SetUINT32(MF_LOW_LATENCY, TRUE);
 ComPtr<IMFMediaType> type;
 check(MFCreateMediaType(&type));
 check(type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video)); check(type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264));
 check(type->SetUINT32(MF_MT_AVG_BITRATE, 4'000'000)); check(type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive));
 check(type->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Base));
 check(MFSetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, width, height));
 check(MFSetAttributeRatio(type.Get(), MF_MT_FRAME_RATE, 30, 1)); check(MFSetAttributeRatio(type.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1));
 check(encoder->SetOutputType(0, type.Get(), 0));
 check(MFCreateMediaType(&type));
 check(type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video)); check(type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12));
 check(type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive));
 check(MFSetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, width, height));
 check(MFSetAttributeRatio(type.Get(), MF_MT_FRAME_RATE, 30, 1)); check(MFSetAttributeRatio(type.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1));
 check(encoder->SetInputType(0, type.Get(), 0));
 check(encoder->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)); check(encoder->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0));
 auto decoder = camsure::make_h264_decoder();
 std::vector<camsure::EncodedVideoAccessUnit> units;
 std::vector<uint8_t> configuration;
 size_t decoded = 0;
 int64_t previous = -1;
 auto output = [&](camsure::DecodedVideoFrame frame) {
  require(frame.width == width && frame.height == height && frame.stride == width, "Dimensions/stride");
  require(frame.pixels.size() == size_t(width) * height * 3 / 2 && frame.format == camsure::PixelFormat::I420, "I420 ownership");
  require(frame.presentation_time_us > previous, "Output PTS ordering");
  if ((frame.presentation_time_us - 1'000'000) % 33'333 != 0) throw std::runtime_error("Exact source PTS output=" + std::to_string(frame.presentation_time_us));
  require(frame.pixels[width * 10 + 10] >= 70 && frame.pixels[width * 10 + 10] <= 95, "Decoded luma");
  require(frame.pixels[size_t(width) * height + 10] >= 82 && frame.pixels[size_t(width) * height + 10] <= 98, "Decoded U chroma");
  require(frame.pixels[size_t(width) * height * 5 / 4 + 10] >= 172 && frame.pixels[size_t(width) * height * 5 / 4 + 10] <= 188, "Decoded V chroma");
  previous = frame.presentation_time_us; ++decoded;
 };
 auto drain = [&] {
  for (;;) {
   MFT_OUTPUT_STREAM_INFO info{}; check(encoder->GetOutputStreamInfo(0, &info));
   ComPtr<IMFSample> sample; ComPtr<IMFMediaBuffer> buffer;
   check(MFCreateSample(&sample)); check(MFCreateMemoryBuffer(info.cbSize, &buffer)); check(sample->AddBuffer(buffer.Get()));
   MFT_OUTPUT_DATA_BUFFER data{}; data.pSample = sample.Get(); DWORD status = 0;
   const HRESULT hr = encoder->ProcessOutput(0, 1, &data, &status);
   if (data.pEvents) data.pEvents->Release();
   if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) return;
   check(hr);
   check(sample->ConvertToContiguousBuffer(&buffer));
   BYTE *bytes = nullptr; DWORD length = 0; check(buffer->Lock(&bytes, nullptr, &length));
   camsure::EncodedVideoAccessUnit unit; unit.annex_b.assign(bytes, bytes + length); check(buffer->Unlock());
   auto found = config(unit.annex_b); if (!found.empty()) configuration = found;
   UINT32 key = 0; sample->GetUINT32(MFSampleExtension_CleanPoint, &key);
   LONGLONG pts = 0; check(sample->GetSampleTime(&pts));
   unit.presentation_time_us = pts / 10; unit.keyframe = key != 0; unit.codec_configuration = configuration; unit.session_id = 42;
   unit.discontinuity = units.empty(); units.push_back(unit);
   decoder->submit(unit, output);
  }
 };
 for (unsigned index = 0; index < 35; ++index) {
  ComPtr<IMFSample> sample; ComPtr<IMFMediaBuffer> buffer;
  const DWORD size = width * height * 3 / 2;
  check(MFCreateSample(&sample)); check(MFCreateMemoryBuffer(size, &buffer));
  BYTE *bytes = nullptr; check(buffer->Lock(&bytes, nullptr, nullptr));
  std::memset(bytes, 81, size_t(width) * height);
  for (size_t offset = size_t(width) * height; offset < size; offset += 2) { bytes[offset] = 90; bytes[offset + 1] = 180; }
  check(buffer->Unlock()); check(buffer->SetCurrentLength(size)); check(sample->AddBuffer(buffer.Get()));
  check(sample->SetSampleTime(10'000'000 + int64_t(index) * 333'330)); check(sample->SetSampleDuration(333'330));
  HRESULT hr = encoder->ProcessInput(0, sample.Get(), 0);
  if (hr == MF_E_NOTACCEPTING) { drain(); hr = encoder->ProcessInput(0, sample.Get(), 0); }
  check(hr); drain();
 }
 check(encoder->ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0)); drain();
 require(decoded >= 30 && !configuration.empty(), "Decoder did not produce frames/config");
 decoder->reset(); previous = -1;
 const size_t before = decoded;
 for (auto unit : units) { unit.session_id = 43; decoder->submit(unit, output); }
 require(decoded > before + 29, "Restart recovery");
 decoder->reset();
 auto dependent = units.back(); dependent.keyframe = false; dependent.discontinuity = true;
 decoder->submit(dependent, [&](camsure::DecodedVideoFrame) { throw std::runtime_error("Dependent frame emitted after reset"); });
 if (fixture) {
  std::ofstream file(fixture, std::ios::binary);
  for (const auto &unit : units) {
   auto write = [&](auto value) { file.write(reinterpret_cast<const char *>(&value), sizeof(value)); };
   write(uint32_t(0x55415343)); write(uint32_t(1)); write(uint32_t(unit.annex_b.size())); write(uint32_t(unit.codec_configuration.size()));
   write(uint32_t((unit.keyframe ? 1 : 0) | (unit.discontinuity ? 2 : 0))); write(uint32_t(0));
   write(unit.session_id); write(unit.presentation_time_us); write(int64_t(0));
   file.write(reinterpret_cast<const char *>(unit.codec_configuration.data()), static_cast<std::streamsize>(unit.codec_configuration.size()));
   file.write(reinterpret_cast<const char *>(unit.annex_b.data()), static_cast<std::streamsize>(unit.annex_b.size()));
  }
 }
 std::printf("PASS: %ux%u backend=%s encoded=%zu decoded=%zu including reset/replay, owned I420 and exact ordered PTS\n", width, height, decoder->backend(), units.size(), decoded);
}
int main(int argc, char **argv)
{
 check(CoInitializeEx(nullptr, COINIT_MULTITHREADED)); check(MFStartup(MF_VERSION));
 int result = 0;
 try { test(1280, 720, nullptr); test(1920, 1080, argc > 1 ? argv[1] : nullptr); test(3840, 2160, argc > 2 ? argv[2] : nullptr); }
 catch (const std::exception &error) { std::fprintf(stderr, "FAIL: %s\n", error.what()); result = 1; }
 MFShutdown(); CoUninitialize(); return result;
}
