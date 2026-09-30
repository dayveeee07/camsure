// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace camsure {
struct Settings {
	std::string name = "Camera";
	uint32_t width = 1920, height = 1080, fps = 30, pattern = 0;
	bool operator==(const Settings &b) const
	{
		return name == b.name && width == b.width && height == b.height && fps == b.fps && pattern == b.pattern;
	}
};

// Original 5x7 diagnostic glyphs; lowercase is displayed as uppercase.
inline std::array<uint8_t, 7> glyph(char c)
{
	if (c >= 'a' && c <= 'z')
		c = char(c - 'a' + 'A');
	switch (c) {
	case 'A':
		return {14, 17, 17, 31, 17, 17, 17};
	case 'B':
		return {30, 17, 17, 30, 17, 17, 30};
	case 'C':
		return {14, 17, 16, 16, 16, 17, 14};
	case 'D':
		return {30, 17, 17, 17, 17, 17, 30};
	case 'E':
		return {31, 16, 16, 30, 16, 16, 31};
	case 'F':
		return {31, 16, 16, 30, 16, 16, 16};
	case 'G':
		return {14, 17, 16, 23, 17, 17, 14};
	case 'H':
		return {17, 17, 17, 31, 17, 17, 17};
	case 'I':
		return {31, 4, 4, 4, 4, 4, 31};
	case 'J':
		return {7, 2, 2, 2, 18, 18, 12};
	case 'K':
		return {17, 18, 20, 24, 20, 18, 17};
	case 'L':
		return {16, 16, 16, 16, 16, 16, 31};
	case 'M':
		return {17, 27, 21, 21, 17, 17, 17};
	case 'N':
		return {17, 25, 21, 19, 17, 17, 17};
	case 'O':
		return {14, 17, 17, 17, 17, 17, 14};
	case 'P':
		return {30, 17, 17, 30, 16, 16, 16};
	case 'Q':
		return {14, 17, 17, 17, 21, 18, 13};
	case 'R':
		return {30, 17, 17, 30, 20, 18, 17};
	case 'S':
		return {15, 16, 16, 14, 1, 1, 30};
	case 'T':
		return {31, 4, 4, 4, 4, 4, 4};
	case 'U':
		return {17, 17, 17, 17, 17, 17, 14};
	case 'V':
		return {17, 17, 17, 17, 17, 10, 4};
	case 'W':
		return {17, 17, 17, 21, 21, 21, 10};
	case 'X':
		return {17, 17, 10, 4, 10, 17, 17};
	case 'Y':
		return {17, 17, 10, 4, 4, 4, 4};
	case 'Z':
		return {31, 1, 2, 4, 8, 16, 31};
	case '0':
		return {14, 17, 19, 21, 25, 17, 14};
	case '1':
		return {4, 12, 4, 4, 4, 4, 14};
	case '2':
		return {14, 17, 1, 2, 4, 8, 31};
	case '3':
		return {30, 1, 1, 14, 1, 1, 30};
	case '4':
		return {2, 6, 10, 18, 31, 2, 2};
	case '5':
		return {31, 16, 16, 30, 1, 1, 30};
	case '6':
		return {14, 16, 16, 30, 17, 17, 14};
	case '7':
		return {31, 1, 2, 4, 8, 8, 8};
	case '8':
		return {14, 17, 17, 14, 17, 17, 14};
	case '9':
		return {14, 17, 17, 15, 1, 1, 14};
	case '-':
		return {0, 0, 0, 31, 0, 0, 0};
	case ':':
		return {0, 4, 4, 0, 4, 4, 0};
	case '.':
		return {0, 0, 0, 0, 0, 4, 4};
	case ' ':
		return {};
	default:
		return {14, 17, 1, 2, 4, 0, 4};
	}
}

// A fixed 640x360 texture is scaled by OBS to the selected source dimensions.
// No external media, font service, or full-resolution per-frame CPU upload.
inline std::vector<uint32_t> make_image(const Settings &s, uint64_t id)
{
	std::vector<uint32_t> pixels(640 * 360, 0xff241b16);
	const uint32_t colors[] = {0xffccb044, 0xff66aaee, 0xffaa77cc, 0xff88cc66};
	const auto accent = colors[(id + s.pattern) % 4];
	for (uint32_t y = 280; y < 360; ++y)
		for (uint32_t x = 0; x < 640; ++x)
			pixels[y * 640 + x] = s.pattern == 1 && ((x / 40 + y / 40) % 2) ? 0xffeeeeee : accent;
	auto text = [&](const std::string &str, uint32_t y, uint32_t scale) {
		uint32_t x = 24;
		for (char c : str) {
			if (x + 5 * scale >= 640)
				break;
			const auto rows = glyph(c);
			for (uint32_t r = 0; r < 7; ++r)
				for (uint32_t col = 0; col < 5; ++col)
					if (rows[r] & (1u << (4 - col)))
						for (uint32_t dy = 0; dy < scale; ++dy)
							for (uint32_t dx = 0; dx < scale; ++dx)
								pixels[(y + r * scale + dy) * 640 + x + col * scale +
								       dx] = 0xffeeeeee;
			x += 6 * scale;
		}
	};
	text("CamSure", 24, 5);
	text("INSTANCE " + std::to_string(id), 90, 3);
	text(s.name, 132, 3);
	text(std::to_string(s.width) + " X " + std::to_string(s.height), 182, 3);
	text(std::to_string(s.fps) + " FPS TEST", 224, 3);
	return pixels;
}
} // namespace camsure
