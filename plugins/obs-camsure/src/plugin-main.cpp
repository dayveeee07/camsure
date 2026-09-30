// SPDX-License-Identifier: GPL-2.0-or-later
#include <obs-module.h>
#include <graphics/vec4.h>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include "diagnostic.hpp"
#include "video-session.hpp"
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
    std::vector<uint32_t> diagnostic;
	double elapsed = 0.0;
	~Source()
	{
        video.reset();
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
        const bool live = obs_data_get_bool(settings, "live_video");
        const std::string pipe = obs_data_get_string(settings, "au_pipe");
        if (s.live != live || s.pipe_name != pipe) {
            s.video.reset(); s.live = live; s.pipe_name = pipe;
            obs_source_output_video(s.obs, nullptr);
            if (live) s.video = std::make_unique<camsure::VideoSession>(s.obs, pipe);
            s.dirty = true;
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
void tick(void *data, float seconds) noexcept
{
 auto &s = *static_cast<Source *>(data);
 try {
  std::lock_guard<std::mutex> lock(s.mutex);
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
 } catch (...) { blog(LOG_ERROR, "[CamSure] Diagnostic output failed"); }
}
obs_properties_t *properties(void *)
{
	auto *props = obs_properties_create();
    obs_properties_add_bool(props, "live_video", "Real camera video");
    obs_properties_add_text(props, "au_pipe", "Local AU pipe (unique per source)", OBS_TEXT_DEFAULT);
	obs_properties_add_text(props, "friendly_name", obs_module_text("FriendlyName"), OBS_TEXT_DEFAULT);
	auto *resolution = obs_properties_add_list(props, "resolution", obs_module_text("Resolution"),
						   OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
	obs_property_list_add_int(resolution, "1920 x 1080", 1080);
	obs_property_list_add_int(resolution, "1280 x 720", 720);
	auto *fps = obs_properties_add_list(props, "fps", obs_module_text("FPS"), OBS_COMBO_TYPE_LIST,
					    OBS_COMBO_FORMAT_INT);
	obs_property_list_add_int(fps, "30", 30);
	obs_property_list_add_int(fps, "60", 60);
	auto *pattern = obs_properties_add_list(props, "pattern", obs_module_text("Pattern"), OBS_COMBO_TYPE_LIST,
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
