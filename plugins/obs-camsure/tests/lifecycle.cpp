// SPDX-License-Identifier: GPL-2.0-or-later
// Runs against real libobs and D3D11; does not substitute for OBS frontend acceptance.
#include <obs-module.h>
#include <util/platform.h>
#include <graphics/vec4.h>
#include <array>
#include <cstdio>
#include <filesystem>
#include <stdexcept>
#include <string>
#include <windows.h>

static void require(bool ok, const char *message)
{
	if (!ok)
		throw std::runtime_error(message);
}

static void drain_destruction()
{
	// No audio is initialized by this video-only probe, so use explicit task barriers.
	obs_queue_task(OBS_TASK_GRAPHICS, [](void *) {}, nullptr, true);
	obs_queue_task(OBS_TASK_DESTROY, [](void *) {}, nullptr, true);
}

static uint64_t fingerprint(obs_source_t *source, unsigned delay_ms = 100)
{
    os_sleep_ms(delay_ms); // Async upload/timing now requires OBS video ticks.
	obs_enter_graphics();
	auto *target = gs_texrender_create(GS_RGBA, GS_ZS_NONE);
	auto *stage = gs_stagesurface_create(640, 360, GS_RGBA);
	uint64_t hash = 1469598103934665603ULL;
	bool mapped = false;
	if (target && stage && gs_texrender_begin(target, 640, 360)) {
		vec4 clear;
		vec4_zero(&clear);
		gs_clear(GS_CLEAR_COLOR, &clear, 0.0f, 0);
		gs_ortho(0.0f, float(obs_source_get_width(source)), 0.0f, float(obs_source_get_height(source)), -100.0f,
			 100.0f);
		obs_source_video_render(source);
		gs_texrender_end(target);
		gs_stage_texture(stage, gs_texrender_get_texture(target));
		uint8_t *bytes = nullptr;
		uint32_t stride = 0;
		mapped = gs_stagesurface_map(stage, &bytes, &stride);
		if (mapped) {
			// Exclude the animated marker, but include diagnostic text and pattern.
			for (uint32_t y = 0; y < 320; ++y)
				for (uint32_t x = 0; x < 640 * 4; ++x) {
					hash ^= bytes[y * stride + x];
					hash *= 1099511628211ULL;
				}
			gs_stagesurface_unmap(stage);
		}
	}
	gs_stagesurface_destroy(stage);
	gs_texrender_destroy(target);
	obs_leave_graphics();
	require(mapped, "GPU readback failed");
	return hash;
}

int main(int argc, char **argv)
{
	if (argc != 3 && argc != 4) {
		std::fprintf(
			stderr,
			"Usage: camsure-lifecycle-test plugin.dll plugin-data-directory\nRun from OBS bin/64bit with that directory on PATH.\n");
		return 2;
	}
	bool started = false;
	std::array<obs_source_t *, 4> sources{};
	obs_source_t *duplicate = nullptr;
	obs_data_array_t *saved = nullptr;
	try {
		started = obs_startup("en-US", nullptr, nullptr);
		require(started, "obs_startup failed");
		obs_video_info video{};
		const auto graphics_path = (std::filesystem::current_path() / "libobs-d3d11.dll").u8string();
		video.graphics_module = graphics_path.c_str();
		video.fps_num = 60;
		video.fps_den = 1;
		video.base_width = video.output_width = 1920;
		video.base_height = video.output_height = 1080;
		video.output_format = VIDEO_FORMAT_RGBA;
		video.colorspace = VIDEO_CS_709;
		video.range = VIDEO_RANGE_FULL;
		video.scale_type = OBS_SCALE_BILINEAR;
		require(obs_reset_video(&video) == OBS_VIDEO_SUCCESS, "D3D11 video initialization failed");
		obs_module_t *module = nullptr;
		require(obs_open_module(&module, argv[1], argv[2]) == MODULE_SUCCESS, "Plugin load failed");
        require(obs_init_module(module), "Plugin initialization failed");
        if (argc == 4 && std::string(argv[3]) == "--managed-pair") {
            auto state = [](obs_source_t *source) {
                auto *props = obs_source_properties(source);
                const std::string result = obs_property_description(obs_properties_get(props, "receiver_status"));
                obs_properties_destroy(props); return result;
            };
            auto wait_state = [&](obs_source_t *source, const char *expected) {
                for (unsigned i = 0; i < 150; ++i) {
                    if (state(source).find(expected) != std::string::npos) return;
                    os_sleep_ms(50);
                }
                throw std::runtime_error("Pair source state timeout: " + state(source));
            };
            auto click = [](obs_source_t *source, const char *button) {
                auto *props = obs_source_properties(source);
                obs_property_button_clicked(obs_properties_get(props, button), source);
                obs_properties_destroy(props);
            };
            std::string adapter;
            for (unsigned i = 0; i < 2; ++i) {
                auto *settings = obs_data_create();
                obs_data_set_bool(settings, "managed_receiver", true);
                obs_data_set_int(settings, "media_port", 5021 + i);
                if (i) obs_data_set_string(settings, "pc_adapter", adapter.c_str());
                sources[i] = obs_source_create("camsure_camera", i ? "Receiver B" : "Receiver A", settings, nullptr);
                obs_data_release(settings); require(sources[i] != nullptr, "Pair source creation");
                if (!i) {
                    auto *props = obs_source_properties(sources[0]);
                    auto *list = obs_properties_get(props, "pc_adapter");
                    require(obs_property_list_item_count(list) > 1, "Pair adapter inventory");
                    adapter = obs_property_list_item_string(list, 1); obs_properties_destroy(props);
                    settings = obs_source_get_settings(sources[0]);
                    obs_data_set_string(settings, "pc_adapter", adapter.c_str());
                    obs_source_update(sources[0], settings); obs_data_release(settings);
                }
                os_sleep_ms(150); click(sources[i], "start_receiver"); wait_state(sources[i], "Waiting for phone");
            }
            auto pipe_name = [](obs_source_t *source) {
                auto *settings = obs_source_get_settings(source);
                std::string pipe = obs_data_get_string(settings, "au_pipe"); obs_data_release(settings); return pipe;
            };
            const auto first_pipe = pipe_name(sources[0]), second_pipe = pipe_name(sources[1]);
            require(first_pipe != second_pipe && first_pipe.rfind("camsure-auto-", 0) == 0, "Managed defaults must use separate saved pipes");
            click(sources[1], "stop_receiver"); wait_state(sources[1], "Stopped");
            require(state(sources[0]).find("Waiting for phone") != std::string::npos, "Stopping B must preserve A");
            click(sources[1], "start_receiver"); wait_state(sources[1], "Waiting for phone");
            require(pipe_name(sources[1]) == second_pipe, "B restart must retain its pipe identity");
            duplicate = obs_source_duplicate(sources[0], "Receiver duplicate", false);
            require(duplicate != nullptr, "Duplicate managed source");
            auto *settings = obs_source_get_settings(duplicate);
            obs_data_set_int(settings, "media_port", 5023); obs_source_update(duplicate, settings); obs_data_release(settings);
            os_sleep_ms(150); click(duplicate, "start_receiver"); wait_state(duplicate, "Waiting for phone");
            require(pipe_name(duplicate) != first_pipe && pipe_name(duplicate) != second_pipe, "Duplicate must get its own automatic pipe");
            obs_source_release(duplicate); duplicate = nullptr; drain_destruction();
            click(sources[0], "stop_receiver"); wait_state(sources[0], "Stopped");
            require(state(sources[1]).find("Waiting for phone") != std::string::npos, "Stopping A must preserve B");
            auto *saved = obs_save_source(sources[0]);
            obs_source_release(sources[0]); sources[0] = nullptr; drain_destruction();
            sources[0] = obs_load_source(saved); obs_data_release(saved);
            os_sleep_ms(150); click(sources[0], "start_receiver"); wait_state(sources[0], "Waiting for phone");
            require(pipe_name(sources[0]) == first_pipe, "Save/load must retain automatic pipe identity");
            for (unsigned i = 0; i < 2; ++i) { obs_source_release(sources[i]); sources[i] = nullptr; }
            drain_destruction();
            std::puts("PASS: concurrent managed receivers, automatic unique pipes, independent Stop/restart, duplicate isolation, saved identity and cleanup. Host-only; no two-phone video claim.");
            obs_shutdown(); return 0;
        }
        if (argc == 4 && std::string(argv[3]) == "--managed") {
            auto *settings = obs_data_create();
            obs_data_set_bool(settings, "managed_receiver", true);
            obs_data_set_string(settings, "au_pipe", "camsure-source-owner-test");
            obs_data_set_int(settings, "media_port", 5019);
            sources[0] = obs_source_create("camsure_camera", "Managed source probe", settings, nullptr);
            obs_data_release(settings);
            require(sources[0] != nullptr, "Managed source creation");
            auto *props = obs_source_properties(sources[0]);
            auto *adapters = obs_properties_get(props, "pc_adapter");
            require(obs_property_list_item_count(adapters) > 1, "Managed adapter inventory");
            const std::string key = obs_property_list_item_string(adapters, 1);
            settings = obs_source_get_settings(sources[0]);
            obs_data_set_string(settings, "pc_adapter", key.c_str());
            obs_source_update(sources[0], settings); obs_data_release(settings);
            obs_properties_destroy(props); os_sleep_ms(150);
            auto state = [&] {
                auto *p = obs_source_properties(sources[0]);
                const std::string text = obs_property_description(obs_properties_get(p, "receiver_status"));
                obs_properties_destroy(p); return text;
            };
            auto wait_state = [&](const char *expected) {
                for (unsigned i = 0; i < 150; ++i) {
                    if (state().find(expected) != std::string::npos) return;
                    os_sleep_ms(50);
                }
                throw std::runtime_error("Managed source state timeout: " + state());
            };
            for (unsigned cycle = 0; cycle < 3; ++cycle) {
                props = obs_source_properties(sources[0]);
                obs_property_button_clicked(obs_properties_get(props, "start_receiver"), sources[0]);
                obs_properties_destroy(props); wait_state("Waiting for phone");
                props = obs_source_properties(sources[0]);
                require(!obs_property_enabled(obs_properties_get(props, "pc_adapter")), "Connection changes locked while running");
                obs_property_button_clicked(obs_properties_get(props, "stop_receiver"), sources[0]);
                obs_properties_destroy(props); wait_state("Stopped");
                props = obs_source_properties(sources[0]);
                require(obs_property_enabled(obs_properties_get(props, "pc_adapter")), "Connection changes unlocked after Stop");
                obs_properties_destroy(props);
            }
            settings = obs_source_get_settings(sources[0]);
            obs_data_set_string(settings, "connection_mode", "usb");
            props = obs_source_properties(sources[0]); obs_properties_apply_settings(props, settings);
            require(obs_property_visible(obs_properties_get(props, "phone_ipv4")), "USB shows expected peer field");
            obs_data_set_string(settings, "connection_mode", "lan"); obs_properties_apply_settings(props, settings);
            require(!obs_property_visible(obs_properties_get(props, "phone_ipv4")), "LAN hides USB peer field");
            obs_properties_destroy(props);
            require(std::string(obs_data_get_string(settings, "pc_adapter")) == key, "Exact adapter identity persists in OBS settings");
            auto *saved_source = obs_save_source(sources[0]);
            obs_source_release(sources[0]); sources[0] = nullptr; drain_destruction();
            sources[0] = obs_load_source(saved_source); obs_data_release(saved_source);
            obs_data_release(settings); os_sleep_ms(150); wait_state("Stopped");
            settings = obs_source_get_settings(sources[0]);
            require(std::string(obs_data_get_string(settings, "pc_adapter")) == key, "Adapter setting survives source save/load");
            obs_data_release(settings);
            props = obs_source_properties(sources[0]);
            obs_property_button_clicked(obs_properties_get(props, "start_receiver"), sources[0]);
            obs_properties_destroy(props); wait_state("Waiting for phone");
            obs_source_release(sources[0]); sources[0] = nullptr; drain_destruction();
            std::puts("PASS: managed OBS source adapter inventory, three Start/Stop cycles, field locks, USB/LAN visibility, saved settings, explicit restart after load and removal cleanup.");
            obs_shutdown(); return 0;
        }
        if (argc == 4 && (std::string(argv[3]) == "--video" || std::string(argv[3]) == "--video-4k")) {
            const bool uhd = std::string(argv[3]) == "--video-4k";
            auto *settings = obs_data_create();
            obs_data_set_bool(settings, "live_video", true);
            obs_data_set_string(settings, "au_pipe", "camsure-test-video");
            sources[0] = obs_source_create("camsure_camera", "Synthetic video probe", settings, nullptr);
            obs_data_release(settings);
            require(sources[0] != nullptr, "Video source creation");
            const uint64_t blank = fingerprint(sources[0]);
            uint64_t last_pts = 0; unsigned frames = 0, empty_after_video = 0;
            bool rendered_video = false;
            bool resumed = false;
            const uint64_t began = os_gettime_ns();
            while (os_gettime_ns() - began < 14'000'000'000ULL) {
                auto *frame = obs_source_get_frame(sources[0]);
                if (frame) {
                    if (empty_after_video) resumed = true;
                    require(frame->format == VIDEO_FORMAT_I420 && frame->width == (uhd ? 3840u : 1920u) && frame->height == (uhd ? 2160u : 1080u), "Decoded OBS dimensions/format");
                    require(frame->data[0][frame->linesize[0] * 10 + 10] >= 70 && frame->data[0][frame->linesize[0] * 10 + 10] <= 95, "Decoded OBS luma");
                    if (frame->timestamp != last_pts) { last_pts = frame->timestamp; ++frames; }
                    obs_source_release_frame(sources[0], frame);
                    if (!rendered_video) {
                        // get_frame consumes libobs's current frame. Let subsequent
                        // ticks upload a new frame before testing actual GPU pixels.
                        for (unsigned attempt = 0; attempt < 80 && !rendered_video; ++attempt) {
                            rendered_video = fingerprint(sources[0], 0) != blank;
                            os_sleep_ms(5);
                        }
                        require(rendered_video, "Decoded GPU render/readback must differ from blank");
                    }
                } else if (frames && obs_source_get_width(sources[0]) == 0) ++empty_after_video;
                os_sleep_ms(16);
            }
            require(frames >= 100, "Too few actual decoded frames reached OBS");
            require(empty_after_video > 0, "Stopped stream did not clear OBS output");
            require(resumed, "Video did not resume after stop");
            obs_source_release(sources[0]); sources[0] = nullptr; drain_destruction();
            for (unsigned i = 0; i < 10; ++i) {
                settings = obs_data_create(); obs_data_set_bool(settings, "live_video", true);
                obs_data_set_string(settings, "au_pipe", "camsure-test-idle");
                auto *idle = obs_source_create("camsure_camera", "Idle video", settings, nullptr);
                obs_data_release(settings); os_sleep_ms(25);
                HANDLE partial = INVALID_HANDLE_VALUE;
                if (i & 1) {
                    partial = CreateFileA("\\\\.\\pipe\\camsure-test-idle", GENERIC_WRITE, 0, nullptr, OPEN_EXISTING, 0, nullptr);
                    require(partial != INVALID_HANDLE_VALUE, "Partial reader test pipe connection");
                    DWORD written = 0; const char header[] = "CSAU";
                    require(WriteFile(partial, header, 4, &written, nullptr) != FALSE, "Partial header write");
                    os_sleep_ms(25);
                }
                obs_source_release(idle); drain_destruction();
                if (partial != INVALID_HANDLE_VALUE) CloseHandle(partial);
            }
            std::printf("PASS: synthetic RTP -> AU pipe -> native decoder -> OBS I420/GPU, %u distinct frames; stop cleared output, restart, idle/partial-read removal x10, shutdown\n", frames);
            obs_shutdown(); return 0;
        }
		const char *names[] = {"Pulpit", "Wide", "Keyboard", "Drums"};
		for (size_t i = 0; i < sources.size(); ++i) {
			auto *settings = obs_data_create();
			obs_data_set_string(settings, "friendly_name", names[i]);
			sources[i] = obs_source_create("camsure_camera", names[i], settings, nullptr);
			obs_data_release(settings);
			os_sleep_ms(100);
			require(sources[i] && obs_source_get_width(sources[i]) == 1920, "Source creation failed");
		}
		std::array<uint64_t, 4> before{};
		for (size_t i = 0; i < 4; ++i)
			before[i] = fingerprint(sources[i]);
		for (size_t i = 0; i < 4; ++i)
			for (size_t j = i + 1; j < 4; ++j)
				require(before[i] != before[j], "Instances rendered identical diagnostics");
		auto *edit = obs_data_create();
		obs_data_set_string(edit, "friendly_name", "Wide Changed");
		obs_source_update(sources[1], edit);
		obs_data_release(edit);
		os_sleep_ms(150); // OBS applies video-source updates on its video tick.
		require(fingerprint(sources[1]) != before[1], "CAM 2 name did not reach rendered output");
		const auto edited_wide = fingerprint(sources[1]);
		for (size_t i : {size_t(0), size_t(2), size_t(3)})
			require(fingerprint(sources[i]) == before[i], "CAM 2 update changed another source");
		edit = obs_data_create();
		obs_data_set_int(edit, "resolution", 720);
		obs_data_set_int(edit, "fps", 60);
		obs_data_set_int(edit, "pattern", 1);
		obs_source_update(sources[2], edit);
		obs_data_release(edit);
		os_sleep_ms(150);
		require(obs_source_get_width(sources[2]) == 1280 && obs_source_get_height(sources[2]) == 720,
			"CAM 3 resolution failed");
		require(fingerprint(sources[2]) != before[2], "CAM 3 output unchanged");
		require(fingerprint(sources[0]) == before[0] && fingerprint(sources[3]) == before[3],
			"CAM 3 update leaked");
		require(fingerprint(sources[1]) == edited_wide, "CAM 3 update changed CAM 2");
		const auto edited_keys = fingerprint(sources[2]);
		duplicate = obs_source_duplicate(sources[0], "Pulpit Copy", false);
		require(duplicate && fingerprint(duplicate) != before[0],
			"Duplicate did not get independent runtime ID");
		edit = obs_data_create();
		obs_data_set_string(edit, "friendly_name", "Copy Changed");
		obs_source_update(duplicate, edit);
		obs_data_release(edit);
		os_sleep_ms(150);
		require(fingerprint(sources[0]) == before[0], "Duplicate shares mutable state");
		obs_source_set_name(sources[0], "CAM 1 Renamed");
		require(fingerprint(sources[0]) == before[0], "OBS rename mutated diagnostic settings");
		obs_source_set_enabled(sources[3], false);
		obs_source_set_enabled(sources[3], true);
		require(fingerprint(sources[3]) == before[3], "Enable cycle changed source");
		obs_source_release(duplicate);
		duplicate = nullptr;
		obs_source_release(sources[1]);
		sources[1] = nullptr;
		drain_destruction();
		require(fingerprint(sources[0]) == before[0] && fingerprint(sources[3]) == before[3],
			"Delete CAM 2 disrupted survivors");
		require(fingerprint(sources[2]) == edited_keys, "Delete CAM 2 disrupted CAM 3");
		sources[1] = obs_source_create("camsure_camera", "Wide Again", nullptr, nullptr);
		require(sources[1] && fingerprint(sources[1]) != before[1], "CAM 2 recreation failed");
		saved = obs_data_array_create();
		for (auto *source : sources) {
			auto *data = obs_save_source(source);
			obs_data_array_push_back(saved, data);
			obs_data_release(data);
		}
		for (auto *&source : sources) {
			obs_source_release(source);
			source = nullptr;
		}
		drain_destruction();
		for (size_t i = 0; i < 4; ++i) {
			auto *data = obs_data_array_item(saved, i);
			sources[i] = obs_load_source(data);
			obs_data_release(data);
			os_sleep_ms(100);
			require(sources[i] && obs_source_get_width(sources[i]) == (i == 2 ? 1280u : 1920u),
				"Saved source dimensions did not restore");
			auto *settings = obs_source_get_settings(sources[i]);
			require(std::string(obs_data_get_string(settings, "friendly_name")) ==
					(i == 1 ? "Camera" : names[i]),
				"Saved friendly name did not restore");
			if (i == 2)
				require(obs_data_get_int(settings, "fps") == 60 &&
						obs_data_get_int(settings, "pattern") == 1,
					"Saved properties did not restore");
			obs_data_release(settings);
			fingerprint(sources[i]);
		}
		obs_data_array_release(saved);
		saved = nullptr;
		for (auto *&source : sources) {
			obs_source_release(source);
			source = nullptr;
		}
		drain_destruction();
		obs_shutdown();
		started = false;
		std::puts(
			"PASS: four native D3D11 sources, independent edits/readbacks, duplicate, rename, enable, delete/recreate, save/load, shutdown.");
		return 0;
	} catch (const std::exception &error) {
		std::fprintf(stderr, "FAIL: %s\n", error.what());
		obs_source_release(duplicate);
		for (auto *source : sources)
			obs_source_release(source);
		obs_data_array_release(saved);
		if (started) {
			obs_shutdown();
		}
		return 1;
	}
}
