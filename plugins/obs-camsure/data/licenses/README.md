# Decoder runtime notices

CamSure's native source code remains GPL-2.0-or-later. This package links the
FFmpeg n7.1.1 binaries from OBS obs-deps 2025-07-11 (the existing hash-pinned
build dependency). Their build enables GPL and version3; the combined binary
package is therefore distributed under GPL-3.0-or-later. GPL-2.0-or-later source
can be combined under GPLv3. The bundled x264 library is a transitive DLL import;
CamSure does not encode with it or implement audio with swresample.

The stage includes the dependency bundle's FFmpeg/x264/zlib notices and a
verbatim GPLv3 text (copied from the same bundle's SWIG GPL license document).
Do not overwrite OBS's own decoder libraries. CamSure ships its exact avcodec-61,
avutil-59 and swresample-5 versions alongside the plugin.

Source provenance and build recipes:

- https://github.com/obsproject/obs-deps/tree/2025-07-11
- https://github.com/FFmpeg/FFmpeg/tree/n7.1.1
- https://code.videolan.org/videolan/x264
- https://zlib.net/

Before public binary distribution, supply corresponding source and build
information for the exact binaries under the applicable licenses. This local
development stage is not a completed public release/compliance audit.
