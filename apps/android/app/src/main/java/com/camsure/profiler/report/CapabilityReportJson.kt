package com.camsure.profiler.report

import com.camsure.profiler.model.CameraProfile
import com.camsure.profiler.model.CapabilityReport
import com.camsure.profiler.model.CapabilityValue
import com.camsure.profiler.model.CollectionIssue
import com.camsure.profiler.model.EncoderProfile
import com.camsure.profiler.model.FieldStatus
import com.camsure.profiler.model.HighSpeedModeProfile
import com.camsure.profiler.model.NumericRange
import com.camsure.profiler.model.PhysicalCameraProfile
import com.camsure.profiler.model.PhysicalSizeMm
import com.camsure.profiler.model.PixelSize
import com.camsure.profiler.model.RationalValue
import com.camsure.profiler.model.StreamFormatProfile
import com.camsure.profiler.model.StreamSizeProfile
import com.camsure.profiler.model.VideoEncoderConstraints
import org.json.JSONArray
import org.json.JSONObject

object CapabilityReportJson {
    const val SCHEMA_VERSION = "1.0.0"

    fun encode(report: CapabilityReport): String = toJson(report).toString(2)

    fun toJson(report: CapabilityReport): JSONObject = JSONObject()
        .put("schemaVersion", report.schemaVersion)
        .put("collectionTime", report.collectionTime)
        .put("device", JSONObject()
            .put("manufacturer", report.device.manufacturer)
            .put("model", report.device.model)
            .put("androidVersion", report.device.androidVersion)
            .put("apiLevel", report.device.apiLevel)
            .put("appVersionName", report.device.appVersionName)
            .put("appVersionCode", report.device.appVersionCode))
        .putField("cameras", report.cameras) { list -> JSONArray().apply { list.forEach { put(camera(it)) } } }
        .putField("videoEncoders", report.videoEncoders) { list -> JSONArray().apply { list.forEach { put(encoder(it)) } } }
        .put("collectionIssues", issues(report.issues))
        .put("accuracyNotes", JSONArray()
            .put("Camera and encoder entries report advertised metadata; no capture session or encode was run.")
            .put("Camera stream sizes and FPS ranges are separate metadata. Their Cartesian product is not asserted as supported.")
            .put("Camera and encoder lists do not prove an end-to-end compatible mode."))

    private fun camera(value: CameraProfile): JSONObject = JSONObject()
        .put("cameraId", value.cameraId)
        .putField("metadataQuery", value.metadataQuery) { it }
        .putField("lensFacing", value.lensFacing) { it }
        .putField("hardwareSupportLevel", value.hardwareSupportLevel) { it }
        .putField("logicalCamera", value.logicalCamera) { it }
        .put("physicalCameras", JSONObject()
            .putField("ids", value.physicalCameras.ids) { strings(it) }
            .put("metadata", JSONArray().apply { value.physicalCameras.cameras.forEach { put(physicalCamera(it)) } }))
        .putField("focalLengths", value.focalLengthsMm) { floats(it) }
        .putField("apertures", value.aperturesFNumber) { floats(it) }
        .putField("sensorSize", value.sensorSizeMm) { physicalSize(it) }
        .putField("sensorOrientation", value.sensorOrientationDegrees) { it }
        .putField("advertisedCapabilities", value.advertisedCapabilities) { strings(it) }
        .putField("streamFormats", value.streamFormats) { list -> JSONArray().apply { list.forEach { put(streamFormat(it)) } } }
        .putField("aeTargetFpsRanges", value.aeTargetFpsRanges, "frames_per_second") { ranges(it) }
        .putField("constrainedHighSpeedModes", value.constrainedHighSpeedModes) { list -> JSONArray().apply { list.forEach { put(highSpeed(it)) } } }
        .putField("dynamicRangeProfiles", value.dynamicRangeProfiles) { list -> JSONArray().apply {
            list.forEach { (profileId, name) -> put(JSONObject().put("profileId", profileId).put("name", name)) }
        } }
        .put("controls", controls(value))
        .putField("runtimeCaptureTest", value.runtimeCaptureTest) { it }
        .put("collectionIssues", issues(value.issues))

    private fun physicalCamera(value: PhysicalCameraProfile): JSONObject = JSONObject()
        .put("cameraId", value.cameraId)
        .putField("metadataQuery", value.query) { it }
        .putField("lensFacing", value.lensFacing) { it }
        .putField("focalLengths", value.focalLengthsMm) { floats(it) }
        .putField("apertures", value.aperturesFNumber) { floats(it) }
        .putField("sensorSize", value.sensorSizeMm) { physicalSize(it) }
        .putField("sensorOrientation", value.sensorOrientationDegrees) { it }

    private fun streamFormat(value: StreamFormatProfile): JSONObject = JSONObject()
        .put("formatCode", value.formatCode)
        .put("formatName", value.formatName)
        .put("relevantToVideoCapture", value.relevantToVideoCapture)
        .putField("sizes", value.sizes) { list -> JSONArray().apply { list.forEach { put(streamSize(it)) } } }

    private fun streamSize(value: StreamSizeProfile): JSONObject = JSONObject()
        .put("size", size(value.size))
        .putField("minimumFrameDuration", value.minimumFrameDurationNs) { it }
        .putField("stallDuration", value.stallDurationNs) { it }

    private fun highSpeed(value: HighSpeedModeProfile): JSONObject = JSONObject()
        .put("size", size(value.size))
        .putField("fpsRanges", value.fpsRanges, "frames_per_second") { ranges(it) }

    private fun controls(value: CameraProfile): JSONObject {
        val controls = value.controls
        return JSONObject()
            .putField("autofocusModes", controls.autofocusModes) { strings(it) }
            .putField("minimumFocusDistance", controls.minimumFocusDistanceDiopters, "diopters") { it }
            .putField("exposureCompensationSteps", controls.exposureCompensationSteps, "steps") { range(it) }
            .putField("exposureCompensationStep", controls.exposureCompensationStepEv, "EV per step") { rational(it) }
            .putField("aeLockAvailable", controls.aeLockAvailable) { it }
            .putField("aeModes", controls.aeModes) { strings(it) }
            .putField("awbLockAvailable", controls.awbLockAvailable) { it }
            .putField("awbModes", controls.awbModes) { strings(it) }
            .putField("manualSensorCapability", controls.manualSensorCapability) { it }
            .putField("isoSensitivityRange", controls.isoSensitivityRange, "ISO") { range(it) }
            .putField("exposureTimeRange", controls.exposureTimeRangeNs, "nanoseconds") { range(it) }
            .put("zoom", JSONObject()
                .putField("camera2RatioRange", controls.zoom.camera2RatioRange, "ratio") { range(it) }
                .putField("maximumDigitalZoom", controls.zoom.maximumDigitalZoom, "ratio") { it }
                .putField("effectiveRange", controls.zoom.effectiveRange, "ratio") { range(it) }
                .putNullable("effectiveRangeSource", controls.zoom.effectiveRangeSource))
            .putField("flashUnitAvailable", controls.flashUnitAvailable) { it }
            .putField("torchAvailable", controls.torchAvailable) { it }
            .putField("opticalStabilizationModes", controls.opticalStabilizationModes) { strings(it) }
            .putField("videoStabilizationModes", controls.videoStabilizationModes) { strings(it) }
    }

    private fun encoder(value: EncoderProfile): JSONObject = JSONObject()
        .put("codecName", value.codecName)
        .put("mimeType", value.mimeType)
        .putField("hardwareAccelerated", value.hardwareAccelerated) { it }
        .putField("softwareOnly", value.softwareOnly) { it }
        .putField("vendorCodec", value.vendorCodec) { it }
        .putField("alias", value.alias) { it }
        .putField("profileLevels", value.profileLevels) { list -> JSONArray().apply { list.forEach {
            put(JSONObject()
                .put("profile", it.profile)
                .put("profileName", it.profileName)
                .put("level", it.level)
                .put("levelName", it.levelName))
        } } }
        .putField("videoConstraints", value.videoConstraints) { constraints(it) }
        .putField("runtimeEncodeTest", value.runtimeEncodeTest) { it }
        .put("collectionIssues", issues(value.issues))

    private fun constraints(value: VideoEncoderConstraints): JSONObject = JSONObject()
        .putField("widthRange", value.widthRange, "pixels") { range(it) }
        .putField("heightRange", value.heightRange, "pixels") { range(it) }
        .putField("widthAlignment", value.widthAlignmentPixels, "pixels") { it }
        .putField("heightAlignment", value.heightAlignmentPixels, "pixels") { it }
        .putField("bitrateRange", value.bitrateRangeBps, "bits_per_second") { range(it) }
        .putField("frameRateRange", value.frameRateRangeFps, "frames_per_second") { range(it) }
        .putField("bitrateModes", value.bitrateModes) { list -> JSONArray().apply { list.forEach {
            put(JSONObject().put("mode", it.mode).put("name", it.name).putField("supported", it.supported) { it })
        } } }
        .putField("representativeModeChecks", value.representativeModeChecks) { list -> JSONArray().apply { list.forEach {
            put(JSONObject()
                .put("size", size(it.size))
                .put("frameRate", it.frameRate)
                .putField("supported", it.supported) { supported -> supported }
                .putField("supportedFrameRateRange", it.supportedFrameRateRange, "frames_per_second") { range(it) })
        } } }
        .put("interpretation", "Range and alignment are codec constraints, not an exhaustive list of valid size/FPS combinations.")

    private fun issues(values: List<CollectionIssue>): JSONArray = JSONArray().apply { values.forEach {
        put(JSONObject()
            .put("scope", it.scope)
            .put("status", it.status.wireValue)
            .putNullable("subject", it.subject)
            .put("message", it.message))
    } }

    private fun size(value: PixelSize): JSONObject = JSONObject().put("width", value.width).put("height", value.height)

    private fun rational(value: RationalValue): JSONObject = JSONObject()
        .put("numerator", value.numerator)
        .put("denominator", value.denominator)
        .put("decimal", value.decimal)

    private fun physicalSize(value: PhysicalSizeMm): JSONObject = JSONObject()
        .put("width", value.width)
        .put("height", value.height)

    private fun <T : Number> range(value: NumericRange<T>): JSONObject = JSONObject()
        .put("lower", value.lower)
        .put("upper", value.upper)

    private fun ranges(values: List<NumericRange<Int>>): JSONArray = JSONArray().apply { values.forEach { put(range(it)) } }

    private fun strings(values: List<String>): JSONArray = JSONArray().apply { values.forEach(::put) }

    private fun floats(values: List<Float>): JSONArray = JSONArray().apply { values.forEach(::put) }

    private fun <T> JSONObject.putField(
        name: String,
        field: CapabilityValue<T>,
        unit: String? = field.unit,
        serialize: (T) -> Any?
    ): JSONObject {
        val payload = JSONObject()
            .put("status", field.status.wireValue)
            .put("value", field.value?.let(serialize) ?: JSONObject.NULL)
        (unit ?: field.unit)?.let { payload.put("unit", it) }
        field.apiLevelRequired?.let { payload.put("apiLevelRequired", it) }
        field.note?.let { payload.put("note", it) }
        return put(name, payload)
    }

    private fun JSONObject.putNullable(name: String, value: String?): JSONObject =
        put(name, value ?: JSONObject.NULL)
}
