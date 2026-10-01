// SPDX-License-Identifier: GPL-2.0-or-later
#include "video-session.hpp"
#include "video-decoder.hpp"
#include <windows.h>
#include <util/platform.h>
#include <algorithm>
#include <cstring>
#include <stdexcept>
#include <deque>

namespace camsure {
namespace {
struct Handle {
 HANDLE value;
 explicit Handle(HANDLE h) : value(h) {}
 ~Handle() { if (value != INVALID_HANDLE_VALUE && value) CloseHandle(value); }
};
int64_t qpc() { LARGE_INTEGER n{}; QueryPerformanceCounter(&n); return n.QuadPart; }
double milliseconds(int64_t ticks) { LARGE_INTEGER f{}; QueryPerformanceFrequency(&f); return double(ticks) * 1000.0 / double(f.QuadPart); }
// All outstanding overlapped I/O is cancelled and completed before stack memory
// or pipe handles are released. Stop is checked at most every 20 ms.
bool complete(HANDLE pipe, OVERLAPPED &overlap, std::atomic<bool> &stop, DWORD &bytes, int64_t deadline, double timeout_ms = 100)
{
 while (!stop.load()) {
  const DWORD result = WaitForSingleObject(overlap.hEvent, 20);
  if (result == WAIT_OBJECT_0) return GetOverlappedResult(pipe, &overlap, &bytes, FALSE) != FALSE;
  if (result == WAIT_FAILED || (deadline && milliseconds(qpc() - deadline) > timeout_ms)) break;
 }
 CancelIoEx(pipe, &overlap);
 GetOverlappedResult(pipe, &overlap, &bytes, TRUE);
 return false;
}
bool read_exact(HANDLE pipe, void *destination, DWORD count, std::atomic<bool> &stop, int64_t deadline = 0, double timeout_ms = 100)
{
 auto *bytes = static_cast<uint8_t *>(destination);
 Handle event(CreateEventW(nullptr, TRUE, FALSE, nullptr));
 if (!event.value) return false;
 while (count && !stop.load()) {
  OVERLAPPED overlap{}; overlap.hEvent = event.value; ResetEvent(event.value);
  DWORD received = 0;
  if (!ReadFile(pipe, bytes, count, &received, &overlap)) {
   if (GetLastError() != ERROR_IO_PENDING || !complete(pipe, overlap, stop, received, deadline, timeout_ms)) return false;
  }
  if (!received) return false;
  bytes += received; count -= received;
 }
 return count == 0;
}
template<class T> T field(const uint8_t *header, size_t offset) { T value{}; std::memcpy(&value, header + offset, sizeof(T)); return value; }
}
VideoSession::VideoSession(obs_source_t *s, std::string name) : source(s), pipe_name(std::move(name))
{
 if (pipe_name.empty() || pipe_name.size() > 64 || !std::all_of(pipe_name.begin(), pipe_name.end(), [](char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-'; }))
  throw std::runtime_error("Invalid AU pipe name");
 worker = std::thread([this] { run(); });
}
VideoSession::~VideoSession() { stop.store(true); if (worker.joinable()) worker.join(); }
void VideoSession::present()
{
 if (clear_output.exchange(false)) { last_presented.store(0); obs_source_output_video(source, nullptr); }
 std::optional<PendingFrame> pending;
 { std::lock_guard<std::mutex> lock(frame_mutex); pending.swap(latest); }
 if (!pending) return;
 const double age = milliseconds(qpc() - pending->available_qpc);
 max_frame_age.store(std::max(max_frame_age.load(), age));
 if (age > 100) { ++stale; return; }
 auto &frame = pending->frame;
 obs_source_frame output{};
 output.format = VIDEO_FORMAT_I420; output.width = frame.width; output.height = frame.height;
 output.data[0] = frame.pixels.data(); output.data[1] = frame.pixels.data() + size_t(frame.stride) * frame.height;
 output.data[2] = output.data[1] + size_t(frame.stride) * frame.height / 4;
 output.linesize[0] = frame.stride; output.linesize[1] = output.linesize[2] = frame.stride / 2; output.timestamp = pending->obs_timestamp;
 video_format_get_parameters(frame.bt601 ? VIDEO_CS_601 : VIDEO_CS_709, frame.full_range ? VIDEO_RANGE_FULL : VIDEO_RANGE_PARTIAL, output.color_matrix, output.color_range_min, output.color_range_max);
 output.full_range = frame.full_range;
 const int64_t before = qpc();
 obs_source_output_video(source, &output); // libobs copies planes synchronously
 last_presented.store(os_gettime_ns());
 ++submitted; max_output_ms.store(std::max(max_output_ms.load(), milliseconds(qpc() - before)));
}
void VideoSession::run() noexcept
{
 uint64_t inputs = 0, decoded = 0, drops = 0, resets = 0, errors = 0;
 double max_age = 0, max_decode = 0;
 uint32_t width = 0, height = 0;
 const int64_t began = qpc(); int64_t report = began;
 try {
  auto decoder = make_h264_decoder();
  std::string reported_backend;
  while (!stop.load()) {
   const std::string path = "\\\\.\\pipe\\" + pipe_name;
   Handle pipe(CreateNamedPipeA(path.c_str(), PIPE_ACCESS_INBOUND | FILE_FLAG_OVERLAPPED | FILE_FLAG_FIRST_PIPE_INSTANCE,
    PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS, 1, 0, 64 * 1024, 0, nullptr));
   if (pipe.value == INVALID_HANDLE_VALUE) throw std::runtime_error("AU pipe unavailable (another source may own this name)");
   Handle event(CreateEventW(nullptr, TRUE, FALSE, nullptr));
   if (!event.value) throw std::runtime_error("Pipe event creation failed");
   OVERLAPPED connect{}; connect.hEvent = event.value; DWORD transferred = 0;
   BOOL connected = ConnectNamedPipe(pipe.value, &connect);
   if (!connected) {
    const DWORD error = GetLastError();
    if (error != ERROR_PIPE_CONNECTED && (error != ERROR_IO_PENDING || !complete(pipe.value, connect, stop, transferred, 0))) continue;
   }
   decoder->reset(); ++resets;
   bool waiting = true; uint64_t session = 0;
   std::vector<uint8_t> configuration;
   std::deque<std::pair<int64_t, int64_t>> pending_inputs;
   int64_t origin_pts = 0, last_pts = -1; uint64_t origin_obs = 0;
   while (!stop.load()) {
    uint8_t header[48]{};
    if (!read_exact(pipe.value, header, sizeof(header), stop, qpc(), 1500)) break;
    const int64_t received = qpc();
    const uint32_t size = field<uint32_t>(header, 8), config_size = field<uint32_t>(header, 12), flags = field<uint32_t>(header, 16);
    if (field<uint32_t>(header, 0) != 0x55415343 || field<uint32_t>(header, 4) != 1 || !size || size > 2 * 1024 * 1024 || config_size > 64 * 1024 || (flags & ~3u)) { ++errors; break; }
    EncodedVideoAccessUnit unit;
    unit.session_id = field<uint64_t>(header, 24); unit.presentation_time_us = field<int64_t>(header, 32);
    unit.reconstructed_qpc = field<int64_t>(header, 40); unit.keyframe = (flags & 1) != 0; unit.discontinuity = (flags & 2) != 0;
    unit.annex_b.resize(size); unit.codec_configuration.resize(config_size);
    if (!read_exact(pipe.value, unit.codec_configuration.data(), config_size, stop, received) || !read_exact(pipe.value, unit.annex_b.data(), size, stop, received)) break;
    ++inputs;
    const double age = milliseconds(qpc() - unit.reconstructed_qpc);
    max_age = std::max(max_age, age);
    if (unit.discontinuity || unit.session_id != session || (!unit.codec_configuration.empty() && configuration != unit.codec_configuration) || unit.presentation_time_us <= last_pts || age > 100 || age < 0) {
     decoder->reset(); ++resets; waiting = true;
     pending_inputs.clear();
     { std::lock_guard<std::mutex> lock(frame_mutex); latest.reset(); }
     session = unit.session_id; last_pts = -1;
     configuration = unit.codec_configuration;
    }
    if (age > 100 || age < 0 || unit.presentation_time_us < 0 || (waiting && (!unit.keyframe || unit.codec_configuration.empty()))) { ++drops; continue; }
    if (waiting) { origin_pts = unit.presentation_time_us; origin_obs = os_gettime_ns(); waiting = false; }
    last_pts = unit.presentation_time_us;
    const int64_t submit_time = qpc();
    try {
     if (pending_inputs.size() >= 16) throw std::runtime_error("Decoder retained too many inputs");
     pending_inputs.emplace_back(unit.presentation_time_us, submit_time);
     decoder->submit(unit, [&](DecodedVideoFrame frame) {
      if (reported_backend != decoder->backend()) {
       reported_backend = decoder->backend();
       blog(LOG_INFO, "[CamSure] Decoder backend=%s pipe=%s", reported_backend.c_str(), pipe_name.c_str());
      }
      ++decoded;
      const int64_t available = qpc();
      auto input = std::find_if(pending_inputs.begin(), pending_inputs.end(), [&](const auto &entry) { return entry.first == frame.presentation_time_us; });
      if (input == pending_inputs.end()) throw std::runtime_error("Decoder output PTS=" + std::to_string(frame.presentation_time_us) + " has no matching input; submit=" + std::to_string(unit.presentation_time_us));
      const double decode_age = milliseconds(available - input->second);
      max_decode = std::max(max_decode, decode_age);
      pending_inputs.erase(input);
      if (decode_age > 100) { ++drops; return; }
      if (frame.presentation_time_us < origin_pts) { ++drops; return; }
      width = frame.width; height = frame.height;
      const uint64_t timestamp = origin_obs + uint64_t(frame.presentation_time_us - origin_pts) * 1000;
      std::lock_guard<std::mutex> lock(frame_mutex);
      if (latest) ++replaced;
      latest = PendingFrame{std::move(frame), timestamp, available};
     });
    } catch (const std::exception &error) {
     ++errors; decoder->reset(); ++resets; waiting = true; pending_inputs.clear();
     if (errors < 5 || errors % 100 == 0) blog(LOG_WARNING, "[CamSure] Decode recovery: %s", error.what());
    }
    if (milliseconds(qpc() - report) >= 1000) {
     const double seconds = milliseconds(qpc() - began) / 1000;
     size_t depth = 0; { std::lock_guard<std::mutex> lock(frame_mutex); depth = latest ? 1 : 0; }
     blog(LOG_INFO, "[CamSure] Video pipe=%s input=%llu decoded=%llu submitted=%llu AU-dropped=%llu frame-replaced=%llu frame-stale=%llu resets=%llu errors=%llu input-fps=%.2f decoded-fps=%.2f resolution=%ux%u AU-age-max=%.2fms submit-to-decoded-max=%.2fms decoded-to-tick-max=%.2fms output-copy-max=%.2fms AU-inflight=1 decoded-depth=%zu/1 OBS-unbuffered=1",
      pipe_name.c_str(), (unsigned long long)inputs, (unsigned long long)decoded, (unsigned long long)submitted.load(), (unsigned long long)drops, (unsigned long long)replaced.load(), (unsigned long long)stale.load(), (unsigned long long)resets, (unsigned long long)errors,
      double(inputs) / seconds, double(decoded) / seconds, width, height, max_age, max_decode, max_frame_age.load(), max_output_ms.load(), depth);
     report = qpc();
     const auto timing = decoder->timing();
     blog(LOG_INFO, "[CamSure] Decoder timing pipe=%s backend=%s GPU-transfer-avg/max=%.3f/%.3fms pixel-copy-convert-avg/max=%.3f/%.3fms codec-call-avg/max=%.3f/%.3fms transfer-frames=%llu converted-frames=%llu codec-calls=%llu",
      pipe_name.c_str(), decoder->backend(),
      timing.transfer_count ? timing.transfer_total_ms / timing.transfer_count : 0.0, timing.transfer_max_ms,
      timing.conversion_count ? timing.conversion_total_ms / timing.conversion_count : 0.0, timing.conversion_max_ms,
      timing.codec_count ? timing.codec_total_ms / timing.codec_count : 0.0, timing.codec_max_ms,
      (unsigned long long)timing.transfer_count, (unsigned long long)timing.conversion_count, (unsigned long long)timing.codec_count);
    }
   }
   { std::lock_guard<std::mutex> lock(frame_mutex); latest.reset(); }
   clear_output.store(true);
   DisconnectNamedPipe(pipe.value);
  }
 } catch (const std::exception &error) { failed.store(true); blog(LOG_ERROR, "[CamSure] Video session stopped: %s", error.what()); }
 clear_output.store(true);
 blog(LOG_INFO, "[CamSure] Video stopped pipe=%s input=%llu decoded=%llu submitted=%llu dropped=%llu resets=%llu errors=%llu",
  pipe_name.c_str(), (unsigned long long)inputs, (unsigned long long)decoded, (unsigned long long)submitted.load(), (unsigned long long)drops, (unsigned long long)resets, (unsigned long long)errors);
}
}
