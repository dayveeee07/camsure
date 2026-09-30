package com.camsure.profiler.model

enum class FieldStatus(val wireValue: String) {
    ADVERTISED("advertised"),
    UNSUPPORTED("unsupported"),
    UNAVAILABLE_ON_API_LEVEL("unavailable_on_api_level"),
    INACCESSIBLE("inaccessible"),
    QUERY_FAILED("query_failed"),
    PARTIAL("partial"),
    NOT_RUNTIME_TESTED("not_runtime_tested")
}

data class CapabilityValue<T>(
    val status: FieldStatus,
    val value: T? = null,
    val unit: String? = null,
    val apiLevelRequired: Int? = null,
    val note: String? = null
)

data class CollectionIssue(
    val scope: String,
    val status: FieldStatus,
    val subject: String? = null,
    val message: String
)

data class DeviceRuntime(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val appVersionName: String,
    val appVersionCode: Long
)

data class NumericRange<T : Number>(val lower: T, val upper: T)

data class PixelSize(val width: Int, val height: Int)

data class PhysicalSizeMm(val width: Float, val height: Float)

data class RationalValue(val numerator: Int, val denominator: Int, val decimal: Double)

data class StreamSizeProfile(
    val size: PixelSize,
    val minimumFrameDurationNs: CapabilityValue<Long>,
    val stallDurationNs: CapabilityValue<Long>
)

data class StreamFormatProfile(
    val formatCode: Int,
    val formatName: String,
    val relevantToVideoCapture: Boolean,
    val sizes: CapabilityValue<List<StreamSizeProfile>>
)

data class HighSpeedModeProfile(
    val size: PixelSize,
    val fpsRanges: CapabilityValue<List<NumericRange<Int>>>
)

data class PhysicalCameraProfile(
    val cameraId: String,
    val query: CapabilityValue<String>,
    val lensFacing: CapabilityValue<String>,
    val focalLengthsMm: CapabilityValue<List<Float>>,
    val aperturesFNumber: CapabilityValue<List<Float>>,
    val sensorSizeMm: CapabilityValue<PhysicalSizeMm>,
    val sensorOrientationDegrees: CapabilityValue<Int>
)

data class ZoomProfile(
    val camera2RatioRange: CapabilityValue<NumericRange<Float>>,
    val maximumDigitalZoom: CapabilityValue<Float>,
    val effectiveRange: CapabilityValue<NumericRange<Float>>,
    val effectiveRangeSource: String?
)

data class CameraControlsProfile(
    val autofocusModes: CapabilityValue<List<String>>,
    val minimumFocusDistanceDiopters: CapabilityValue<Float>,
    val exposureCompensationSteps: CapabilityValue<NumericRange<Int>>,
    val exposureCompensationStepEv: CapabilityValue<RationalValue>,
    val aeLockAvailable: CapabilityValue<Boolean>,
    val aeModes: CapabilityValue<List<String>>,
    val awbLockAvailable: CapabilityValue<Boolean>,
    val awbModes: CapabilityValue<List<String>>,
    val manualSensorCapability: CapabilityValue<Boolean>,
    val isoSensitivityRange: CapabilityValue<NumericRange<Int>>,
    val exposureTimeRangeNs: CapabilityValue<NumericRange<Long>>,
    val zoom: ZoomProfile,
    val flashUnitAvailable: CapabilityValue<Boolean>,
    val torchAvailable: CapabilityValue<Boolean>,
    val opticalStabilizationModes: CapabilityValue<List<String>>,
    val videoStabilizationModes: CapabilityValue<List<String>>
)

data class PhysicalCameraListProfile(
    val ids: CapabilityValue<List<String>>,
    val cameras: List<PhysicalCameraProfile>
)

data class CameraProfile(
    val cameraId: String,
    val metadataQuery: CapabilityValue<String>,
    val lensFacing: CapabilityValue<String>,
    val hardwareSupportLevel: CapabilityValue<String>,
    val logicalCamera: CapabilityValue<Boolean>,
    val physicalCameras: PhysicalCameraListProfile,
    val focalLengthsMm: CapabilityValue<List<Float>>,
    val aperturesFNumber: CapabilityValue<List<Float>>,
    val sensorSizeMm: CapabilityValue<PhysicalSizeMm>,
    val sensorOrientationDegrees: CapabilityValue<Int>,
    val advertisedCapabilities: CapabilityValue<List<String>>,
    val streamFormats: CapabilityValue<List<StreamFormatProfile>>,
    val aeTargetFpsRanges: CapabilityValue<List<NumericRange<Int>>>,
    val constrainedHighSpeedModes: CapabilityValue<List<HighSpeedModeProfile>>,
    val dynamicRangeProfiles: CapabilityValue<List<Pair<Long, String>>>,
    val controls: CameraControlsProfile,
    val runtimeCaptureTest: CapabilityValue<String>,
    val issues: List<CollectionIssue>
)

data class CodecProfileLevel(val profile: Int, val profileName: String, val level: Int, val levelName: String)

data class EncoderBitrateMode(val mode: Int, val name: String, val supported: CapabilityValue<Boolean>)

data class EncoderSizeRateCheck(
    val size: PixelSize,
    val frameRate: Int,
    val supported: CapabilityValue<Boolean>,
    val supportedFrameRateRange: CapabilityValue<NumericRange<Double>>
)

data class VideoEncoderConstraints(
    val widthRange: CapabilityValue<NumericRange<Int>>,
    val heightRange: CapabilityValue<NumericRange<Int>>,
    val widthAlignmentPixels: CapabilityValue<Int>,
    val heightAlignmentPixels: CapabilityValue<Int>,
    val bitrateRangeBps: CapabilityValue<NumericRange<Int>>,
    val frameRateRangeFps: CapabilityValue<NumericRange<Int>>,
    val bitrateModes: CapabilityValue<List<EncoderBitrateMode>>,
    val representativeModeChecks: CapabilityValue<List<EncoderSizeRateCheck>>
)

data class EncoderProfile(
    val codecName: String,
    val mimeType: String,
    val hardwareAccelerated: CapabilityValue<Boolean>,
    val softwareOnly: CapabilityValue<Boolean>,
    val vendorCodec: CapabilityValue<Boolean>,
    val alias: CapabilityValue<Boolean>,
    val profileLevels: CapabilityValue<List<CodecProfileLevel>>,
    val videoConstraints: CapabilityValue<VideoEncoderConstraints>,
    val runtimeEncodeTest: CapabilityValue<String>,
    val issues: List<CollectionIssue>
)

data class CapabilityReport(
    val schemaVersion: String,
    val collectionTime: String,
    val device: DeviceRuntime,
    val cameras: CapabilityValue<List<CameraProfile>>,
    val videoEncoders: CapabilityValue<List<EncoderProfile>>,
    val issues: List<CollectionIssue>
)
