// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <obs-module.h>
#include <atomic>
#include <thread>
#include <string>
#include <mutex>
#include <optional>
#include "video-decoder.hpp"

namespace camsure {
class VideoSession {
 obs_source_t *source;
 std::string pipe_name;
 std::atomic<bool> stop{false};
 std::thread worker;
 struct PendingFrame {
  DecodedVideoFrame frame;
  uint64_t obs_timestamp;
  int64_t available_qpc;
 };
 std::mutex frame_mutex;
 std::optional<PendingFrame> latest;
 std::atomic<uint64_t> submitted{0}, stale{0}, replaced{0};
 std::atomic<double> max_frame_age{0}, max_output_ms{0};
 std::atomic<bool> clear_output{false};
 std::atomic<uint64_t> last_presented{0};
 std::atomic<bool> failed{false};
 void run() noexcept;
public:
 VideoSession(obs_source_t *, std::string);
 ~VideoSession();
 void present(); // OBS video tick; at most one latest frame.
 uint64_t last_frame_time() const { return last_presented.load(); }
 bool has_failed() const { return failed.load(); }
 VideoSession(const VideoSession &) = delete;
 VideoSession &operator=(const VideoSession &) = delete;
};
}
