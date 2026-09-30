# Capability Report JSON — Schema 1.0.0

Reports are UTF-8 JSON objects exported with the Android document picker. The
schema version is `1.0.0`. Camera and encoder metadata is independently
collected; the report does not claim an end-to-end camera/encoder combination
is usable.

## Top-level fields

| Field | Meaning |
|---|---|
| `schemaVersion` | Report format version. |
| `collectionTime` | UTC ISO-8601 timestamp for the collection. |
| `device` | Manufacturer, model, Android release/API, and app version. It contains no serial number or persistent device identifier. |
| `cameras` | Status-bearing list of Camera2 camera profiles. |
| `videoEncoders` | Status-bearing list of regular H.264 (`video/avc`) and HEVC (`video/hevc`) encoder profiles. |
| `collectionIssues` | Bounded list of collection issues with scope, status, optional subject, and message. |
| `accuracyNotes` | Interpretation limits included with every report. |

## Status-bearing values

Each optional capability is an object with `status` and `value`; `value` is
JSON `null` when no value was collected. Optional `unit`, `apiLevelRequired`,
and `note` fields provide context. A zero, empty list, `false`, and `null` are
distinct values and must not be substituted for one another.

| Status | Meaning |
|---|---|
| `advertised` | Metadata was returned by the Android API. It is not a runtime test. |
| `unsupported` | The API was available and reported no support/value for this field. |
| `unavailable_on_api_level` | The field requires a newer Android API; `apiLevelRequired` identifies it. |
| `inaccessible` | Permission or platform access was denied. |
| `query_failed` | The metadata query failed. Check `note` and `collectionIssues`. |
| `partial` | Some bounded entries or subqueries were omitted or failed. |
| `not_runtime_tested` | The profiler deliberately did not exercise this behavior. |

## Camera profile fields

Each `cameras.value[]` entry includes `cameraId`, status-bearing metadata query,
lens-facing and hardware-level values, logical-camera status, physical-camera
IDs/metadata, focal lengths, apertures, sensor size/orientation, advertised
Camera2 capabilities, stream formats and sizes, AE FPS ranges, constrained
high-speed modes, dynamic-range profiles, camera controls, runtime-capture
status, and camera-scoped issues.

Stream sizes include minimum frame duration and stall duration in nanoseconds.
FPS ranges use `frames_per_second`; sensor size uses millimeters; focal lengths
use millimeters; focus distance uses diopters; exposure compensation is in
steps/EV; sensor exposure range is in nanoseconds; zoom values are ratios.
Camera IDs are platform-local labels and should not be treated as stable device
identifiers across phones or OS changes.

## Encoder profile fields

Each `videoEncoders.value[]` entry includes codec name/MIME type, API-gated
hardware/software/vendor/alias classification, profile/level entries,
video width/height ranges and alignment, bitrate and frame-rate ranges,
bitrate modes, representative 720p30/1080p30/1080p60 size-rate point queries,
runtime-encode status, and codec-scoped issues.

Ranges and alignment describe codec-advertised constraints. The representative
point queries are not an exhaustive valid-size/FPS matrix and do not instantiate
or run an encoder. Camera stream sizes and FPS ranges are reported separately;
their Cartesian product is not asserted as supported.

## Comparing reports

Compare `device` release/API metadata first, then compare each capability's
status before comparing values. Keep `unavailable_on_api_level`, inaccessible,
failed, partial, and unsupported values distinct. Compare camera and encoder
data separately. Runtime capture, thermal behavior, stock-camera parity, and
camera/encoder compatibility require separate physical-device validation.
