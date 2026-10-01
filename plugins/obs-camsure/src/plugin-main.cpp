// SPDX-License-Identifier: GPL-2.0-or-later
#include <obs-module.h>
#include <graphics/vec4.h>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include "diagnostic.hpp"
#include "video-session.hpp"
#include "receiver-process.hpp"
#include <filesystem>
#include <util/bmem.h>
#include <util/platform.h>

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("obs-camsure", "en-US")
MODULE_EXPORT const char *obs_module_description(void)
{
	return "CamSure transport-neutral H.264 camera sources";
}

namespace {
// Process-local diagnostic ID allocator only; no global camera/runtime ownership.
std::atomic<uint64_t> next_id{1};
struct Source {
	const uint64_t id = next_id.fetch_add(1, std::memory_order_relaxed);
	std::mutex mutex;
	camsure::Settings settings;
	bool dirty = true;
	obs_source_t *obs = nullptr;
    std::unique_ptr<camsure::VideoSession> video;
    std::string pipe_name;
    bool live = false;
    bool managed = false, start_requested = false, had_video = false;
    camsure::ReceiverConfig connection;
    std::unique_ptr<camsure::ReceiverProcess> receiver;
    uint64_t properties_updated = 0;
    std::string last_status, connection_failure;
    std::vector<uint32_t> diagnostic;
	double elapsed = 0.0;
	~Source()
	{
        receiver.reset(); video.reset();
		blog(LOG_INFO, "[CamSure] Source destroyed [instance=%llu]", (unsigned long long)id);
	}
};

void defaults(obs_data_t *data)
{
	obs_data_set_default_string(data, "friendly_name", "Camera");
	obs_data_set_default_int(data, "resolution", 1080);
	obs_data_set_default_int(data, "fps", 30);
	obs_data_set_default_int(data, "pattern", 0);
    obs_data_set_default_bool(data, "live_video", false);
    obs_data_set_default_string(data, "au_pipe", "camsure-camera-1");
    obs_data_set_default_bool(data, "managed_receiver", false);
    obs_data_set_default_string(data, "connection_mode", "lan");
    obs_data_set_default_int(data, "media_port", 5004);
}

void update(void *data, obs_data_t *settings) noexcept
{
	auto &s = *static_cast<Source *>(data);
	try {
		camsure::Settings value;
		// Bound diagnostic work even if a hand-edited scene contains a huge name.
		const char *name = obs_data_get_string(settings, "friendly_name");
		size_t size = 0;
		while (size < 128 && name[size])
			++size;
		value.name.assign(name, size);
		if (obs_data_get_int(settings, "resolution") == 720) {
			value.width = 1280;
			value.height = 720;
		}
		value.fps = obs_data_get_int(settings, "fps") == 60 ? 60 : 30;
		value.pattern = obs_data_get_int(settings, "pattern") == 1 ? 1 : 0;
		std::lock_guard<std::mutex> lock(s.mutex);
        const bool managed = obs_data_get_bool(settings, "managed_receiver");
        const bool management_changed = s.managed != managed;
        if (s.receiver && !s.receiver->finished() && (managed != s.managed || !obs_data_get_bool(settings, "live_video"))) s.receiver->request_stop();
        s.managed = managed;
        const bool running = s.receiver && !s.receiver->finished();
        const bool live = running ? true : obs_data_get_bool(settings, "live_video");
        const std::string pipe = obs_data_get_string(settings, "au_pipe");
        if (!running && (s.live != live || s.pipe_name != pipe || management_changed)) {
            s.video.reset(); s.live = live; s.pipe_name = pipe;
            obs_source_output_video(s.obs, nullptr);
            if (live && !managed) s.video = std::make_unique<camsure::VideoSession>(s.obs, pipe);
            s.dirty = true;
        }
        if (!running) {
            s.connection.usb = std::string(obs_data_get_string(settings, "connection_mode")) == "usb";
            s.connection.adapter_key = obs_data_get_string(settings, "pc_adapter");
            s.connection.peer = obs_data_get_string(settings, "phone_ipv4");
            s.connection.port = int(obs_data_get_int(settings, "media_port"));
            s.connection.pipe = pipe;
        }
		if (!(s.settings == value)) {
			s.settings = std::move(value);
			s.dirty = true;
			blog(LOG_INFO, "[CamSure] Source settings changed [instance=%llu, %ux%u, %u FPS]",
			     (unsigned long long)s.id, s.settings.width, s.settings.height, s.settings.fps);
		}
	} catch (...) {
		blog(LOG_ERROR, "[CamSure] Could not apply source settings [instance=%llu]", (unsigned long long)s.id);
	}
}

void *create(obs_data_t *settings, obs_source_t *obs) noexcept
{
	try {
		auto s = std::make_unique<Source>();
        s->obs = obs; obs_source_set_async_unbuffered(obs, true);
		update(s.get(), settings);
		blog(LOG_INFO, "[CamSure] Source created [instance=%llu]", (unsigned long long)s->id);
		return s.release();
	} catch (...) {
		blog(LOG_ERROR, "[CamSure] Source allocation failed");
		return nullptr;
	}
}
void destroy(void *data)
{
	delete static_cast<Source *>(data);
}
void tick_content(void *data, float seconds) noexcept
{
 auto &s = *static_cast<Source *>(data);
 try {
  std::lock_guard<std::mutex> lock(s.mutex);
  if (s.start_requested) {
   s.start_requested = false;
   s.receiver.reset(); s.video.reset(); s.had_video = false; s.connection_failure.clear();
   obs_source_output_video(s.obs, nullptr);
   s.video = std::make_unique<camsure::VideoSession>(s.obs, s.connection.pipe);
   char *path = obs_module_file("receiver/CamSure.RtpReceiver.exe");
   std::string executable = path ? path : ""; bfree(path);
   s.receiver = std::make_unique<camsure::ReceiverProcess>(executable, s.connection);
   s.live = true;
  }
  if (s.receiver && s.video && s.video->has_failed()) {
   s.connection_failure = "Connection failure: video session stopped (AU pipe may already be in use). Stop and restart with a unique pipe.";
   s.receiver->request_stop();
  }
  if (s.receiver && s.video && (s.receiver->stopping() || s.receiver->finished())) {
   s.video.reset(); obs_source_output_video(s.obs, nullptr);
  }
  if (s.live) { if (s.video) s.video->present(); return; }
  s.elapsed = std::fmod(s.elapsed + double(seconds), 10.0);
  if (s.dirty) {
   const auto image = camsure::make_image(s.settings, s.id);
   s.diagnostic.resize(size_t(s.settings.width) * s.settings.height);
   for (uint32_t y = 0; y < s.settings.height; ++y)
    for (uint32_t x = 0; x < s.settings.width; ++x)
     s.diagnostic[size_t(y) * s.settings.width + x] = image[size_t(y * 360 / s.settings.height) * 640 + x * 640 / s.settings.width];
   s.dirty = false;
  }
  // Cache the scaled pattern; each tick only copies it and paints the marker.
  auto pixels = s.diagnostic;
  const auto step = std::floor(s.elapsed * s.settings.fps);
  const uint32_t marker = static_cast<uint32_t>(std::fmod(step, double(s.settings.fps * 2)) / double(s.settings.fps * 2) * double(s.settings.width - 24));
  for (uint32_t y = s.settings.height - 36; y < s.settings.height - 8; ++y)
   for (uint32_t x = marker; x < marker + 24; ++x) pixels[size_t(y) * s.settings.width + x] = 0xffffffff;
  obs_source_frame frame{};
  frame.format = VIDEO_FORMAT_RGBA; frame.width = s.settings.width; frame.height = s.settings.height;
  frame.data[0] = reinterpret_cast<uint8_t *>(pixels.data()); frame.linesize[0] = s.settings.width * 4;
  frame.timestamp = os_gettime_ns(); frame.full_range = true;
  obs_source_output_video(s.obs, &frame);
 } catch (const std::exception &error) {
  std::lock_guard<std::mutex> lock(s.mutex);
  if (s.managed) {
   s.connection_failure = std::string("Connection failure: ") + error.what();
   if (s.receiver) s.receiver->request_stop();
  }
  blog(LOG_ERROR, "[CamSure] Source output failed: %s", error.what());
 } catch (...) { blog(LOG_ERROR, "[CamSure] Source output failed"); }
}
std::string receiver_status(Source &s);
void tick(void *data, float seconds) noexcept {
 tick_content(data, seconds);
 auto &s = *static_cast<Source *>(data);
 const auto now = os_gettime_ns();
 if (now - s.properties_updated > 1'000'000'000ULL) {
  s.properties_updated = now;
  bool changed = false;
  { std::lock_guard<std::mutex> lock(s.mutex); const auto status = receiver_status(s); changed = status != s.last_status; s.last_status = status; }
  if (changed) obs_source_update_properties(s.obs);
 }
}
std::string receiver_status(Source &s) {
 if (!s.connection_failure.empty()) return s.connection_failure;
 if (!s.receiver) return "Stopped. Choose your connection, then Start receiver.";
 if (s.receiver->stopping()) return "Stopping receiver...";
 if (s.video && s.video->has_failed()) return "Connection failure: video session stopped. Check Diagnostics and restart.";
 if (s.receiver->listening() && s.video) {
  const auto last = s.video->last_frame_time();
  if (last && os_gettime_ns() - last < 2'000'000'000ULL) { s.had_video = true; return "Streaming — camera video received by OBS"; }
  if (s.had_video) return "Disconnected / no recent video. Check the phone and link; restart if needed.";
 }
 return s.receiver->status();
}
bool refresh_properties(obs_properties_t *, obs_property_t *, void *data) {
 if (data) obs_source_update_properties(static_cast<Source *>(data)->obs);
 return false;
}
bool start_receiver(obs_properties_t *, obs_property_t *, void *data) {
 auto &s = *static_cast<Source *>(data);
 auto *settings = obs_source_get_settings(s.obs);
 const std::string configured_pipe = obs_data_get_string(settings, "au_pipe");
 if (!obs_data_has_user_value(settings, "au_pipe") || configured_pipe.rfind("camsure-auto-", 0) == 0) {
  const char *uuid = obs_source_get_uuid(s.obs);
  if (uuid) obs_data_set_string(settings, "au_pipe", (std::string("camsure-auto-") + uuid).c_str());
 }
 obs_data_set_bool(settings, "live_video", true);
 obs_source_update(s.obs, settings); obs_data_release(settings);
 std::lock_guard<std::mutex> lock(s.mutex);
 if (s.managed && (!s.receiver || s.receiver->finished())) s.start_requested = true;
 return true;
}
bool stop_receiver(obs_properties_t *, obs_property_t *, void *data) {
 auto &s = *static_cast<Source *>(data);
 std::lock_guard<std::mutex> lock(s.mutex);
 s.start_requested = false;
 if (s.receiver) s.receiver->request_stop();
 return true;
}
bool connection_visibility(void *, obs_properties_t *props, obs_property_t *, obs_data_t *settings) {
 const bool managed = obs_data_get_bool(settings, "managed_receiver"), usb = std::string(obs_data_get_string(settings, "connection_mode")) == "usb";
 for (const char *name : {"connection_mode", "pc_adapter", "media_port", "refresh_adapters", "receiver_status", "start_receiver", "stop_receiver", "connection_help"})
  obs_property_set_visible(obs_properties_get(props, name), managed);
 obs_property_set_visible(obs_properties_get(props, "phone_ipv4"), managed && usb);
 obs_property_set_description(obs_properties_get(props, "connection_help"), usb ?
  "Enable USB tethering on the phone (no USB debugging). Confirm its USB Ethernet adapter below, then enter the phone's tethering IPv4. Enter the selected PC IPv4 in Android Settings." :
  "Keep phone and PC on the same LAN. Start receiver, then find this PC in Android Settings. Manual fallback: use the selected PC IPv4. Allow the receiver through the applicable Windows firewall profile.");
 return true;
}
obs_properties_t *properties(void *data)
{
	auto *props = obs_properties_create();
    obs_properties_add_bool(props, "live_video", "Real camera video");
    auto *managed = obs_properties_add_bool(props, "managed_receiver", "Manage receiver in OBS (no PowerShell)");
    auto *mode = obs_properties_add_list(props, "connection_mode", "Connection", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    obs_property_list_add_string(mode, "Wireless / local network", "lan");
    obs_property_list_add_string(mode, "USB Network", "usb");
    auto *adapters = obs_properties_add_list(props, "pc_adapter", "PC adapter / IPv4 (select explicitly)", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    obs_property_list_add_string(adapters, "Select a PC adapter/address", "");
    try {
     for (const auto &a : camsure::receiver_adapters()) obs_property_list_add_string(adapters, a.label.c_str(), a.key.c_str());
    } catch (...) { obs_property_list_add_string(adapters, "Adapter inventory unavailable — refresh", ""); }
    obs_properties_add_button2(props, "refresh_adapters", "Refresh adapters / status", refresh_properties, data);
    obs_properties_add_int(props, "media_port", "Media port (match Android)", 1, 65535, 1);
    obs_properties_add_text(props, "phone_ipv4", "Phone USB tethering IPv4", OBS_TEXT_DEFAULT);
    obs_properties_add_text(props, "connection_help", "", OBS_TEXT_INFO);
    std::string status = "Stopped";
    bool running = false;
    if (data) {
     auto &s = *static_cast<Source *>(data); std::lock_guard<std::mutex> lock(s.mutex);
     status = receiver_status(s); running = s.start_requested || (s.receiver && !s.receiver->finished());
    }
    obs_properties_add_text(props, "receiver_status", status.c_str(), OBS_TEXT_INFO);
    auto *start = obs_properties_add_button2(props, "start_receiver", "Start receiver", start_receiver, data);
    auto *stop = obs_properties_add_button2(props, "stop_receiver", "Stop receiver", stop_receiver, data);
    obs_property_set_enabled(start, !running && data); obs_property_set_enabled(stop, running && data);
    for (const char *name : {"managed_receiver", "live_video", "connection_mode", "pc_adapter", "media_port", "phone_ipv4"})
     obs_property_set_enabled(obs_properties_get(props, name), !running);
    obs_property_set_modified_callback2(managed, connection_visibility, data);
    obs_property_set_modified_callback2(mode, connection_visibility, data);
    auto *diagnostics = obs_properties_create();
    obs_properties_add_text(diagnostics, "au_pipe", "Local AU pipe (managed sources assign one automatically)", OBS_TEXT_DEFAULT);
    obs_property_set_enabled(obs_properties_get(diagnostics, "au_pipe"), !running);
    if (data) {
     auto &s = *static_cast<Source *>(data); std::lock_guard<std::mutex> lock(s.mutex);
     if (s.receiver) obs_properties_add_text(diagnostics, "receiver_detail", s.receiver->diagnostics().c_str(), OBS_TEXT_INFO);
    }
    obs_properties_add_group(props, "diagnostics", "Diagnostics / external receiver", OBS_GROUP_NORMAL, diagnostics);
	obs_properties_add_text(props, "friendly_name", obs_module_text("FriendlyName"), OBS_TEXT_DEFAULT);
	auto *resolution = obs_properties_add_list(diagnostics, "resolution", "Diagnostic image resolution (camera mode is set on phone)",
						   OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
	obs_property_list_add_int(resolution, "1920 x 1080", 1080);
	obs_property_list_add_int(resolution, "1280 x 720", 720);
	auto *fps = obs_properties_add_list(diagnostics, "fps", "Diagnostic image FPS", OBS_COMBO_TYPE_LIST,
					    OBS_COMBO_FORMAT_INT);
	obs_property_list_add_int(fps, "30", 30);
	obs_property_list_add_int(fps, "60", 60);
	auto *pattern = obs_properties_add_list(diagnostics, "pattern", obs_module_text("Pattern"), OBS_COMBO_TYPE_LIST,
						OBS_COMBO_FORMAT_INT);
	obs_property_list_add_int(pattern, obs_module_text("ColorBand"), 0);
	obs_property_list_add_int(pattern, obs_module_text("Checkerboard"), 1);
	return props;
}
} // namespace

bool obs_module_load(void)
{
	obs_source_info info = {};
	info.id = "camsure_camera"; // Persisted OBS source type, not a future wire-protocol identifier.
	info.type = OBS_SOURCE_TYPE_INPUT;
	info.output_flags = OBS_SOURCE_ASYNC_VIDEO;
	info.get_name = [](void *) {
		return obs_module_text("SourceName");
	};
	info.create = create;
	info.destroy = destroy;
	info.get_defaults = defaults;
	info.get_properties = properties;
	info.update = update;


	info.video_tick = tick;

	info.icon_type = OBS_ICON_TYPE_CAMERA;
	obs_register_source(&info);
	blog(LOG_INFO, "[CamSure] Plugin loaded (0.1.0)");
	return true;
}
void obs_module_unload(void)
{
	blog(LOG_INFO, "[CamSure] Plugin unloaded");
}
