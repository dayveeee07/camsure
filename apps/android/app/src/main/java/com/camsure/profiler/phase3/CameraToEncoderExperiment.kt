package com.camsure.profiler.phase3

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.camsure.profiler.phase4.AccessUnitSinkSnapshot
import com.camsure.profiler.phase4.CompletedAccessUnitSummary
import com.camsure.profiler.phase4.EncodedAccessUnitSink
import com.camsure.profiler.phase4.FixedReceiverEndpoint
import com.camsure.profiler.phase4.RtpH264Sender
import com.camsure.profiler.phase4.RtpH264TransportSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToLong

data class CameraRoute(
    val cameraId: String,
    val physicalCameraId: String?,
    val characteristicsCameraId: String,
    val label: String
) {
    override fun toString(): String = label
}

data class DirectModePlan(
    val width: Int,
    val height: Int,
    val encoderName: String,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val bitrateMode: Int,
    val bitrateModeName: String,
    val bitrateBps: Int,
    val fpsRange: Range<Int>,
    val cameraAdvertises1080p: Boolean,
    val direct1080pSupported: Boolean,
    val diagnosticNotes: List<String>,
    val encoderAdvertisesExactMode: Boolean = true
)

data class ExperimentSnapshot(
    val state: String = "ready",
    val message: String = "Prepare camera routes before starting a run.",
    val deviceManufacturer: String = Build.MANUFACTURER.orEmpty(),
    val deviceModel: String = Build.MODEL.orEmpty(),
    val androidVersion: String = Build.VERSION.RELEASE.orEmpty(),
    val apiLevel: Int = Build.VERSION.SDK_INT,
    val buildId: String = Build.DISPLAY.orEmpty(),
    val appVersionName: String = "",
    val routeLabel: String? = null,
    val cameraId: String? = null,
    val physicalCameraId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val encoderName: String? = null,
    val hardwareAccelerated: Boolean? = null,
    val encoderConfigurationSucceeded: Boolean? = null,
    val captureSessionConfigured: Boolean? = null,
    val encoderWidthAlignmentPixels: Int? = null,
    val encoderHeightAlignmentPixels: Int? = null,
    val cameraAdvertises1080p: Boolean? = null,
    val exactDirect1080pSupported: Boolean? = null,
    val requestedBitrateBps: Int? = null,
    val requestedBitrateMode: String? = null,
    val requestedFps: Int = 30,
    val cameraAeFpsRange: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val durationSeconds: Double = 0.0,
    val captureFrames: Long = 0,
    val encodedFrames: Long = 0,
    val keyframes: Long = 0,
    val captureDroppedEstimate: Long = 0,
    val encodedDroppedEstimate: Long = 0,
    val captureFailureCount: Long = 0,
    val captureFailureSamples: List<String> = emptyList(),
    val nonMonotonicCaptureTimestamps: Long = 0,
    val nonMonotonicEncodedTimestamps: Long = 0,
    val captureFps: Double? = null,
    val encodedFps: Double? = null,
    val measuredBitrateBps: Long? = null,
    val averageKeyframeIntervalSeconds: Double? = null,
    val captureFirstTimestampNs: Long? = null,
    val captureLastTimestampNs: Long? = null,
    val encodedFirstPresentationTimeUs: Long? = null,
    val encodedLastPresentationTimeUs: Long? = null,
    val captureTimestampSamplesNs: List<Long> = emptyList(),
    val encodedPresentationTimeSamplesUs: List<Long> = emptyList(),
    val encoderOutputFormat: String? = null,
    val outputWidth: Int? = null,
    val outputHeight: Int? = null,
    val visibleCrop: String? = null,
    val outputProfile: Int? = null,
    val outputLevel: Int? = null,
    val endOfStreamSeen: Boolean? = null,
    val appFrameQueueDepth: Int = 0,
    val codecInternalQueueDepth: String = "unavailable through the public MediaCodec API",
    val processCpuPercent: Double? = null,
    val processCpuAveragePercent: Double? = null,
    val processCpuPeakPercent: Double? = null,
    val thermalStatusStart: String? = null,
    val thermalStatusEnd: String? = null,
    val batteryPercentStart: Int? = null,
    val batteryPercentEnd: Int? = null,
    val batteryTemperatureCStart: Double? = null,
    val batteryTemperatureCEnd: Double? = null,
    val chargingStart: Boolean? = null,
    val chargingEnd: Boolean? = null,
    val transport: RtpH264TransportSnapshot? = null,
    val timestampCaveat: String =
        "Camera sensor timestamps and encoder presentation timestamps are recorded separately; no latency subtraction is made because the device timestamp bases have not been qualified.",
    val pathDescription: String =
        "Camera2 writes directly to the MediaCodec input Surface. No ImageReader, CPU pixel copy, or application frame queue is used.",
    val error: String? = null,
    val planNotes: List<String> = emptyList(),
    val sensorSensitivityIso: Int? = null,
    val sensorExposureTimeNs: Long? = null,
    val appliedNoiseReductionMode: Int? = null,
    val appliedEdgeMode: Int? = null
) {
    val isTerminal: Boolean
        get() = state == "completed" || state == "stopped" || state == "failed"
}

object CameraEncoderExperimentDiscovery {
    private const val H264_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TARGET_FPS = 30
    private const val MAX_WIDTH = 1920
    private const val MAX_HEIGHT = 1080

    fun discoverRoutes(context: Context): List<CameraRoute> {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: throw IllegalStateException("Camera service is unavailable.")
        val routes = mutableListOf<CameraRoute>()
        manager.cameraIdList.forEach { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val facingLabel = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "rear"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> "unknown-facing"
            }
            val logical = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
            routes += CameraRoute(
                cameraId = id,
                physicalCameraId = null,
                characteristicsCameraId = id,
                label = "Camera " + id + " · " + facingLabel +
                    if (logical) " · logical route" else " · standalone route"
            )
            if (logical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                characteristics.physicalCameraIds.sorted().forEach { physicalId ->
                    routes += CameraRoute(
                        cameraId = id,
                        physicalCameraId = physicalId,
                        characteristicsCameraId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) physicalId else id,
                        label = "Camera " + id + " · physical output " + physicalId
                    )
                }
            }
        }
        return routes.sortedWith(
            compareBy<CameraRoute> { route ->
                val facing = manager.getCameraCharacteristics(route.characteristicsCameraId)
                    .get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) 0 else 1
            }.thenBy { it.cameraId }.thenBy { it.physicalCameraId ?: "" }
        )
    }

    fun discoverDirectModes(context: Context, route: CameraRoute, include4k: Boolean = false, trial1080: Boolean = false): List<DirectModePlan> {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: throw IllegalStateException("Camera service is unavailable.")
        val characteristics = manager.getCameraCharacteristics(route.characteristicsCameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        val sizes = streamMap.getOutputSizes(MediaCodec::class.java)
            ?.filter { it.width <= (if (include4k) 3840 else MAX_WIDTH) && it.height <= (if (include4k) 2160 else MAX_HEIGHT) }
            ?.filter { it.width.toLong() * 9L == it.height.toLong() * 16L }
            ?.distinctBy { it.width to it.height }
            .orEmpty()
        val fpsRange = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.lower <= TARGET_FPS && it.upper >= TARGET_FPS }
            ?.minWithOrNull(compareBy<Range<Int>> { it.upper - it.lower }.thenBy { it.lower })
            ?: return emptyList()

        val encoders = hardwareH264Encoders()
        val plans = mutableListOf<DirectModePlan>()
        val cameraAdvertises1080 = sizes.any { it.width == 1920 && it.height == 1080 }
        encoders.forEach { info ->
            val codecCapabilities = try {
                info.getCapabilitiesForType(H264_MIME)
            } catch (_: Exception) {
                null
            } ?: return@forEach
            val video = codecCapabilities.videoCapabilities ?: return@forEach
            val encoderCapabilities = codecCapabilities.encoderCapabilities ?: return@forEach
            val bitrateMode = when {
                encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                else -> return@forEach
            }
            sizes.forEach sizeLoop@ { size ->
                val minDuration = try {
                    streamMap.getOutputMinFrameDuration(MediaCodec::class.java, size)
                } catch (_: Exception) {
                    0L
                }
                if (minDuration > 0L && minDuration > 1_000_000_000L / TARGET_FPS) return@sizeLoop
                val supported = try {
                    video.areSizeAndRateSupported(size.width, size.height, TARGET_FPS.toDouble())
                } catch (_: Exception) {
                    false
                }
                if (supported || (trial1080 && size.width == 1920 && size.height == 1080)) {
                    val desired = (8_000_000.0 * size.width * size.height / (1920.0 * 1080.0))
                        .roundToLong().toInt().coerceAtLeast(1_500_000)
                    plans += DirectModePlan(
                        width = size.width,
                        height = size.height,
                        encoderName = info.name,
                        widthAlignment = video.widthAlignment,
                        heightAlignment = video.heightAlignment,
                        bitrateMode = bitrateMode,
                        bitrateModeName = if (bitrateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) "VBR" else "CBR",
                        bitrateBps = desired.coerceIn(video.bitrateRange.lower, video.bitrateRange.upper),
                        fpsRange = fpsRange,
                        cameraAdvertises1080p = cameraAdvertises1080,
                        direct1080pSupported = false,
                        diagnosticNotes = emptyList(),
                        encoderAdvertisesExactMode = supported
                    )
                }
            }
        }

        val anyDirect1080 = plans.any { it.width == 1920 && it.height == 1080 && it.encoderAdvertisesExactMode }
        val alignmentNotes = mutableListOf<String>()
        if (trial1080) alignmentNotes += "Explicit 1080p configuration trial: try real 1920×1080@30 despite encoder metadata rejection. No padding, scaling or crop workaround is applied. Configuration/session/output are unverified until runtime; failure does not silently fall back to 720p."
        if (include4k) {
            val advertised = streamMap.getOutputSizes(MediaCodec::class.java).orEmpty()
            listOf(android.util.Size(1920, 1080), android.util.Size(3840, 2160)).forEach { target ->
                val cameraSize = advertised.any { it == target }
                val duration = if (cameraSize) try { streamMap.getOutputMinFrameDuration(MediaCodec::class.java, target) } catch (_: Exception) { 0L } else 0L
                alignmentNotes += "High-resolution probe ${target}@30: camera output advertised=$cameraSize; minimum frame duration=$duration ns (0 means unknown)."
                encoders.forEach { info ->
                    try {
                        val video = info.getCapabilitiesForType(H264_MIME).videoCapabilities
                            ?: throw IllegalStateException("No video capabilities")
                        alignmentNotes += "${info.name}: ${target}@30 supported=${video.areSizeAndRateSupported(target.width, target.height, 30.0)}; alignment=${video.widthAlignment}×${video.heightAlignment}."
                    } catch (error: Exception) {
                        alignmentNotes += "${info.name}: ${target}@30 query failed: ${error.javaClass.simpleName}."
                    }
                }
            }
            alignmentNotes += "Metadata support is not runtime proof. This trial selects the largest exact eligible size up to 3840×2160; confirm the actual requested and output dimensions in runtime JSON."
        }
        if (cameraAdvertises1080 && !anyDirect1080) {
            val details = encoders.mapNotNull { info ->
                val video = try {
                    info.getCapabilitiesForType(H264_MIME).videoCapabilities
                } catch (_: Exception) {
                    null
                } ?: return@mapNotNull null
                val widthFits = 1920 % video.widthAlignment == 0
                val heightFits = 1080 % video.heightAlignment == 0
                if (!widthFits || !heightFits) {
                    info.name + " reports " + video.widthAlignment + "×" +
                        video.heightAlignment + " alignment; 1920×1080 fails " +
                        (if (!widthFits) "width" else "height") + " alignment."
                } else null
            }.distinct()
            alignmentNotes += "Camera advertises 1920×1080, but no non-alias hardware H.264 encoder reports the exact 1920×1080@30 direct mode as supported."
            alignmentNotes += if (details.isNotEmpty()) details.joinToString(" ") else
                "The exact-size rejection is not explained by the reported alignment fields alone."
            alignmentNotes += "An aligned 1920×1088 backing surface with a visible 1080-line crop or another GPU path has not been attempted."
        }
        return plans
            .sortedWith(compareByDescending<DirectModePlan> { it.width.toLong() * it.height }.thenBy { it.encoderName })
            .map { it.copy(direct1080pSupported = anyDirect1080, diagnosticNotes = alignmentNotes) }
    }

    fun noDirectModeNotes(context: Context, route: CameraRoute): List<String> {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return listOf("Camera service is unavailable.")
        val characteristics = manager.getCameraCharacteristics(route.characteristicsCameraId)
        val has1080 = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaCodec::class.java)
            ?.any { it.width == 1920 && it.height == 1080 } == true
        return buildList {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add("The platform cannot reliably classify hardware acceleration before API 29; no hardware-only run was selected.")
            }
            if (has1080) {
                add("Camera advertises 1920×1080, but no exact Camera2 + hardware H.264 30 fps pair was found for this route.")
                add("A padded 1920×1088 backing surface and GPU crop/conversion path have not been attempted.")
            }
            if (isEmpty()) add("No exact camera output and hardware H.264 encoder mode at 30 fps was found for this route.")
        }
    }

    private fun hardwareH264Encoders(): List<MediaCodecInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && H264_MIME in it.supportedTypes }
            .filter { it.isHardwareAccelerated && !it.isSoftwareOnly && !it.isAlias }
            .toList()
    }
}

class CameraToEncoderExperiment(
    context: Context,
    private val route: CameraRoute,
    private val plan: DirectModePlan,
    private val onSnapshot: (ExperimentSnapshot) -> Unit,
    private val receiverEndpoint: FixedReceiverEndpoint? = null,
    private val usbLink: com.camsure.profiler.phase4.UsbNetworkLink? = null
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
        ?: throw IllegalStateException("Camera service is unavailable.")
    private val worker = HandlerThread("camsure-phase3-camera-encoder").apply { start() }
    private val handler = Handler(worker.looper)
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val terminal = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var streamSender: RtpH264Sender? = null
    private var accessUnitSink: EncodedAccessUnitSink? = null
    private var inputSurface: Surface? = null
    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var runStartedAtNs = 0L
    private var runStartedAtEpochMs = 0L
    private var cpuSampleMs = 0L
    private var elapsedSampleNs = 0L
    private var cpuPercentSum = 0.0
    private var cpuSampleCount = 0L
    private var cpuPercentPeak = 0.0
    private var captureFrames = 0L
    private var encodedFrames = 0L
    private var keyframes = 0L
    private var captureDropped = 0L
    private var encodedDropped = 0L
    private var captureFailureCount = 0L
    private val captureFailureSamples = ArrayList<String>()
    private var nonMonotonicCapture = 0L
    private var nonMonotonicEncoded = 0L
    private var encodedBytes = 0L
    private var lastCaptureTimestampNs: Long? = null
    private var lastEncodedPtsUs: Long? = null
    private var lastKeyframePtsUs: Long? = null
    private val captureSamples = ArrayList<Long>()
    private val encodedSamples = ArrayList<Long>()
    private val keyframeIntervalsUs = ArrayList<Long>()
    private var outputFormat: String? = null
    private var outputWidth: Int? = null
    private var outputHeight: Int? = null
    private var visibleCrop: String? = null
    private var outputProfile: Int? = null
    private var outputLevel: Int? = null
    private var eosSeen = false
    private var encoderReady = false
    private var captureSessionReady = false
    private var thermalAtStart: String? = null
    private var batteryAtStart = BatteryReading(null, null, null)
    private var cpuPercent: Double? = null
    private var currentState = "preparing"
    private var currentMessage = "Creating hardware encoder input surface…"
    private var currentError: String? = null
    private val runDurationMinutes = if (receiverEndpoint == null) 5 else 10
    private val runDurationMs = runDurationMinutes * 60_000L
    private val completedRunReason = "The " + runDurationMinutes + "-minute run completed."
    private var stopReason = completedRunReason
    private var isStopping = false

    @Volatile
    private var latestSnapshot = ExperimentSnapshot(
        state = "preparing",
        message = "Preparing " + route.label + " for " + plan.width + "×" + plan.height + "@30.",
        appVersionName = com.camsure.profiler.BuildConfig.VERSION_NAME,
        routeLabel = route.label,
        cameraId = route.cameraId,
        physicalCameraId = route.physicalCameraId,
        width = plan.width,
        height = plan.height,
        encoderName = plan.encoderName,
        hardwareAccelerated = true,
        requestedBitrateBps = plan.bitrateBps,
        encoderWidthAlignmentPixels = plan.widthAlignment,
        encoderHeightAlignmentPixels = plan.heightAlignment,
        cameraAdvertises1080p = plan.cameraAdvertises1080p,
        exactDirect1080pSupported = plan.direct1080pSupported,
        requestedBitrateMode = plan.bitrateModeName,
        cameraAeFpsRange = plan.fpsRange.lower.toString() + "–" + plan.fpsRange.upper,
        planNotes = plan.diagnosticNotes
    )

    fun start() {
        handler.post { configureCodecAndOpenCamera() }
    }

    fun current(): ExperimentSnapshot = latestSnapshot

    fun stopByUser() {
        requestStop("Stopped by user.")
    }

    fun stopForBackground() {
        requestStop("Stopped because the app left the foreground.")
    }

    private fun requestStop(reason: String) {
        handler.post {
            if (terminal.get() || isStopping) return@post
            stopReason = reason
            beginStop(completed = false)
        }
    }

    private fun configureCodecAndOpenCamera() {
        if (terminal.get()) return
        try {
            if (receiverEndpoint != null) {
                val sender = RtpH264Sender(receiverEndpoint, usbLink, plan.width, plan.height) {
                    handler.post { if (!terminal.get()) { streamSender?.close(); requestStop("USB link lost or send failed; select link and start a fresh stream.") } }
                }
                streamSender = sender
                accessUnitSink = EncodedAccessUnitSink(
                    queue = sender.queue,
                    onCodecConfiguration = sender::setCodecConfiguration,
                    onAccessUnitCompleted = ::recordCompletedAccessUnit
                )
                latestSnapshot = latestSnapshot.copy(
                    pathDescription = "Camera2 writes directly to the MediaCodec input Surface. The RTP path copies only compressed H.264 access units into a pooled, bounded sender queue; it does not copy raw pixels or wait for network I/O.",
                    timestampCaveat = "MediaCodec PTS stays the source of truth. RTP uses a per-stream 90 kHz mapping; the prototype also carries exact source PTS in an RFC 8285 extension for receiver comparison."
                )
            }
            val created = MediaCodec.createByCodecName(plan.encoderName)
            codec = created
            created.setCallback(encoderCallback, handler)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, plan.width, plan.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, plan.bitrateMode)
            }
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = created.createInputSurface()
            created.start()
            encoderReady = true
            latestSnapshot = latestSnapshot.copy(encoderConfigurationSucceeded = true)
            Log.i(
                TAG,
                "Hardware encoder configured: " + plan.encoderName + " " +
                    plan.width + "×" + plan.height + "@30, bitrate=" +
                    plan.bitrateBps + ", mode=" + plan.bitrateModeName
            )
            update("opening_camera", "Opening Camera2 camera " + route.cameraId + "…")
            if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                fail("Camera permission was revoked before Camera2 could open.", null)
                return
            }
            cameraManager.openCamera(route.cameraId, cameraStateCallback, handler)
        } catch (error: Exception) {
            fail("Hardware encoder or camera setup failed: " + error.javaClass.simpleName + ": " + error.message, error)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            if (terminal.get() || isStopping) {
                device.close()
                return
            }
            camera = device
            configureCaptureSession(device)
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            camera = null
            fail("Camera2 disconnected during the encoder run.", null)
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            camera = null
            fail("Camera2 failed with error " + error + ".", null)
        }
    }

    @Suppress("DEPRECATION")
    private fun configureCaptureSession(device: CameraDevice) {
        val surface = inputSurface ?: run {
            fail("MediaCodec did not provide an input Surface.", null)
            return
        }
        try {
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (terminal.get() || isStopping) {
                        session.close()
                        return
                    }
                    captureSession = session
                    captureSessionReady = true
                    latestSnapshot = latestSnapshot.copy(captureSessionConfigured = true)
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, plan.fpsRange)
                            val focusModes = characteristicsForRoute().get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                            if (focusModes?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true) {
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }
                        }.build()
                        runStartedAtNs = SystemClock.elapsedRealtimeNanos()
                        runStartedAtEpochMs = System.currentTimeMillis()
                        cpuSampleMs = Process.getElapsedCpuTime()
                        elapsedSampleNs = runStartedAtNs
                        thermalAtStart = currentThermalStatus()
                        batteryAtStart = readBattery()
                        update("running", "Running direct Camera2 → hardware H.264 for " + runDurationMinutes + " minutes.")
                        session.setRepeatingRequest(request, captureCallback, handler)
                        Log.i(
                            TAG,
                            "Capture session started: cameraId=" + route.cameraId +
                                ", physicalCameraId=" + route.physicalCameraId +
                                ", route=" + route.label + ", AE FPS range=" + plan.fpsRange
                        )
                        handler.postDelayed(autoStop, runDurationMs)
                        handler.postDelayed(metricsTick, METRICS_INTERVAL_MS)
                    } catch (error: Exception) {
                        fail("Camera capture session could not start: " + error.javaClass.simpleName + ": " + error.message, error)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    fail("Camera2 rejected the camera-to-encoder capture session configuration.", null)
                }
            }
            val output = OutputConfiguration(surface).apply {
                if (route.physicalCameraId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setPhysicalCameraId(route.physicalCameraId)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val executor = Executor { runnable -> handler.post(runnable) }
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(output),
                    executor,
                    callback
                )
                device.createCaptureSession(config)
            } else {
                device.createCaptureSession(listOf(surface), callback, handler)
            }
        } catch (error: Exception) {
            fail("Camera capture session configuration failed: " + error.javaClass.simpleName + ": " + error.message, error)
        }
    }

    private fun characteristicsForRoute(): CameraCharacteristics {
        return cameraManager.getCameraCharacteristics(route.characteristicsCameraId)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (runStartedAtNs == 0L || terminal.get()) return
            captureFrames++
            if (captureFrames % 30L == 1L) {
                latestSnapshot = latestSnapshot.copy(
                    sensorSensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                    sensorExposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    appliedNoiseReductionMode = result.get(CaptureResult.NOISE_REDUCTION_MODE),
                    appliedEdgeMode = result.get(CaptureResult.EDGE_MODE)
                )
            }
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (timestamp != null && timestamp > 0L) {
                val previous = lastCaptureTimestampNs
                if (previous != null) {
                    if (timestamp <= previous) nonMonotonicCapture++
                    else captureDropped += estimateMissingFrames(timestamp - previous)
                }
                lastCaptureTimestampNs = timestamp
                if (captureSamples.size < MAX_TIMESTAMP_SAMPLES) captureSamples += timestamp
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            captureFailureCount++
            if (captureFailureSamples.size < MAX_FAILURE_SAMPLES) {
                captureFailureSamples += "frame=" + failure.frameNumber + ",reason=" + failure.reason
            }
            Log.w(TAG, "Camera capture failed; reason=" + failure.reason + ", frame=" + failure.frameNumber)
        }
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface-input encoders do not use application input buffers.
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            var endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            try {
                val sink = accessUnitSink
                if (sink != null) {
                    sink.onOutputBuffer(codec.getOutputBuffer(index), info)
                } else if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    recordCompletedAccessUnit(
                        CompletedAccessUnitSummary(
                            presentationTimeUs = info.presentationTimeUs,
                            sizeBytes = info.size,
                            mediaCodecFlags = info.flags,
                            isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        )
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Could not inspect an encoder output buffer.", error)
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (error: Exception) {
                    Log.e(TAG, "Could not release an encoder output buffer.", error)
                }
            }
            if (endOfStream) {
                eosSeen = true
                finishRun(completed = stopReason == completedRunReason)
            }
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            fail("MediaCodec failed: " + error.diagnosticInfo, error)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputFormat = format.toString()
            Log.i(TAG, "Encoder output format: " + format)
            outputWidth = format.integerOrNull(MediaFormat.KEY_WIDTH)
            outputHeight = format.integerOrNull(MediaFormat.KEY_HEIGHT)
            outputProfile = format.integerOrNull(MediaFormat.KEY_PROFILE)
            outputLevel = format.integerOrNull(MediaFormat.KEY_LEVEL)
            val left = format.integerOrNull("crop-left")
            val top = format.integerOrNull("crop-top")
            val right = format.integerOrNull("crop-right")
            val bottom = format.integerOrNull("crop-bottom")
            visibleCrop = if (left != null && top != null && right != null && bottom != null) {
                left.toString() + "," + top + "–" + right + "," + bottom
            } else "not reported by encoder"
            accessUnitSink?.onOutputFormatChanged(format)
            postMetrics()
        }
    }

    private val metricsTick = object : Runnable {
        override fun run() {
            if (runStartedAtNs == 0L || terminal.get()) return
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val cpuNowMs = Process.getElapsedCpuTime()
            val wallMs = ((nowNs - elapsedSampleNs).coerceAtLeast(1L)) / 1_000_000L
            cpuPercent = (cpuNowMs - cpuSampleMs).coerceAtLeast(0L) * 100.0 / wallMs.toDouble()
            cpuPercentSum += cpuPercent ?: 0.0
            cpuSampleCount++
            cpuPercentPeak = max(cpuPercentPeak, cpuPercent ?: 0.0)
            cpuSampleMs = cpuNowMs
            elapsedSampleNs = nowNs
            postMetrics()
            handler.postDelayed(this, METRICS_INTERVAL_MS)
        }
    }

    private val autoStop = Runnable {
        if (!terminal.get() && !isStopping) {
            stopReason = completedRunReason
            beginStop(completed = true)
        }
    }

    private fun beginStop(completed: Boolean) {
        if (terminal.get() || isStopping) return
        isStopping = true
        currentState = "stopping"
        currentMessage = if (completed) "$runDurationMinutes-minute run complete; draining the encoder…" else "Stopping and draining the encoder…"
        postMetrics()
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(metricsTick)
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.abortCaptures() } catch (_: Exception) {}
        captureSession?.close()
        captureSession = null
        camera?.close()
        camera = null
        try {
            codec?.signalEndOfInputStream()
            handler.postDelayed({ if (!terminal.get()) finishRun(completed) }, EOS_TIMEOUT_MS)
        } catch (error: Exception) {
            Log.e(TAG, "Could not signal encoder end of input.", error)
            finishRun(completed)
        }
    }

    private fun finishRun(completed: Boolean) {
        if (!terminal.compareAndSet(false, true)) return
        accessUnitSink?.close()
        streamSender?.finishAndDrain()
        val finishedAtMs = System.currentTimeMillis()
        val finishedAtNs = SystemClock.elapsedRealtimeNanos()
        val runDurationNs = if (runStartedAtNs == 0L) 0L else (finishedAtNs - runStartedAtNs).coerceAtLeast(0L)
        val seconds = runDurationNs / 1_000_000_000.0
        val firstCapture = captureSamples.firstOrNull()
        val lastCapture = captureSamples.lastOrNull()
        val firstEncoded = encodedSamples.firstOrNull()
        val lastEncoded = encodedSamples.lastOrNull()
        val captureFps = if (firstCapture != null && lastCapture != null && lastCapture > firstCapture) {
            (captureSamples.size - 1).coerceAtLeast(0).toDouble() *
                1_000_000_000.0 / (lastCapture - firstCapture).toDouble()
        } else null
        val encodedFps = if (firstEncoded != null && lastEncoded != null && lastEncoded > firstEncoded) {
            (encodedSamples.size - 1).coerceAtLeast(0).toDouble() *
                1_000_000.0 / (lastEncoded - firstEncoded).toDouble()
        } else if (seconds > 0.0) {
            encodedFrames.toDouble() / seconds
        } else null
        val bitrate = if (seconds > 0.0) (encodedBytes * 8.0 / seconds).toLong() else null
        val meanKeyframeInterval = if (keyframeIntervalsUs.isNotEmpty()) {
            keyframeIntervalsUs.average() / 1_000_000.0
        } else null
        val battery = readBattery()
        latestSnapshot = latestSnapshot.copy(
            state = if (currentError != null) "failed" else if (completed) "completed" else "stopped",
            message = currentError ?: stopReason,
            startedAt = if (runStartedAtEpochMs > 0L) Instant.ofEpochMilli(runStartedAtEpochMs).toString() else null,
            finishedAt = Instant.ofEpochMilli(finishedAtMs).toString(),
            durationSeconds = seconds,
            captureFrames = captureFrames,
            encodedFrames = encodedFrames,
            keyframes = keyframes,
            captureDroppedEstimate = captureDropped,
            encodedDroppedEstimate = encodedDropped,
            captureFailureCount = captureFailureCount,
            captureFailureSamples = captureFailureSamples.toList(),
            nonMonotonicCaptureTimestamps = nonMonotonicCapture,
            nonMonotonicEncodedTimestamps = nonMonotonicEncoded,
            captureFps = captureFps,
            encodedFps = encodedFps,
            measuredBitrateBps = bitrate,
            averageKeyframeIntervalSeconds = meanKeyframeInterval,
            captureFirstTimestampNs = firstCapture,
            captureLastTimestampNs = lastCaptureTimestampNs,
            encodedFirstPresentationTimeUs = firstEncoded,
            encodedLastPresentationTimeUs = lastEncodedPtsUs,
            captureTimestampSamplesNs = captureSamples.toList(),
            encodedPresentationTimeSamplesUs = encodedSamples.toList(),
            encoderOutputFormat = outputFormat,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            visibleCrop = visibleCrop,
            outputProfile = outputProfile,
            outputLevel = outputLevel,
            endOfStreamSeen = eosSeen,
            encoderConfigurationSucceeded = encoderReady,
            captureSessionConfigured = captureSessionReady,
            processCpuPercent = cpuPercent,
            processCpuAveragePercent = if (cpuSampleCount > 0L) cpuPercentSum / cpuSampleCount else cpuPercent,
            processCpuPeakPercent = if (cpuSampleCount > 0L) cpuPercentPeak else cpuPercent,
            thermalStatusStart = thermalAtStart,
            thermalStatusEnd = currentThermalStatus(),
            batteryPercentStart = batteryAtStart.percent,
            batteryPercentEnd = battery.percent,
            batteryTemperatureCStart = batteryAtStart.temperatureC,
            batteryTemperatureCEnd = battery.temperatureC,
            chargingStart = batteryAtStart.charging,
            chargingEnd = battery.charging,
            transport = streamSender?.snapshot(accessUnitSink?.snapshot() ?: AccessUnitSinkSnapshot()),
            error = currentError
        )
        Log.i(
            TAG,
            "Run finished: state=" + latestSnapshot.state +
                ", durationSeconds=" + latestSnapshot.durationSeconds +
                ", captureFrames=" + captureFrames +
                ", encodedFrames=" + encodedFrames +
                ", keyframes=" + keyframes +
                ", captureDropEstimate=" + captureDropped +
                ", encodedDropEstimate=" + encodedDropped +
                ", captureFailures=" + captureFailureCount +
                ", encoderOutput=" + outputWidth + "×" + outputHeight
        )
        releaseResources()
        streamSender?.close()
        streamSender = null
        accessUnitSink = null
        publish(latestSnapshot)
        worker.quitSafely()
    }

    private fun fail(message: String, error: Throwable?) {
        if (terminal.get()) return
        currentError = message
        currentState = "failed"
        currentMessage = message
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(metricsTick)
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        finishRun(completed = false)
    }

    private fun releaseResources() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
    }

    private fun recordCompletedAccessUnit(summary: CompletedAccessUnitSummary) {
        encodedFrames++
        encodedBytes += summary.sizeBytes.toLong()
        val pts = summary.presentationTimeUs
        val previousPts = lastEncodedPtsUs
        if (previousPts != null) {
            if (pts <= previousPts) nonMonotonicEncoded++
            else encodedDropped += estimateMissingFrames((pts - previousPts) * 1_000L)
        }
        lastEncodedPtsUs = pts
        if (encodedSamples.size < MAX_TIMESTAMP_SAMPLES) encodedSamples += pts
        if (summary.isKeyFrame) {
            keyframes++
            val previousKeyframe = lastKeyframePtsUs
            if (previousKeyframe != null && pts > previousKeyframe) {
                keyframeIntervalsUs += pts - previousKeyframe
            }
            lastKeyframePtsUs = pts
        }
    }

    private fun update(state: String, message: String) {
        currentState = state
        currentMessage = message
        latestSnapshot = latestSnapshot.copy(state = state, message = message)
        publish(latestSnapshot)
    }

    private fun postMetrics() {
        if (terminal.get()) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val durationSeconds = if (runStartedAtNs == 0L) 0.0 else (nowNs - runStartedAtNs) / 1_000_000_000.0
        val firstCapture = captureSamples.firstOrNull()
        val lastCapture = captureSamples.lastOrNull()
        val capturedFps = if (firstCapture != null && lastCapture != null && lastCapture > firstCapture) {
            (captureSamples.size - 1).coerceAtLeast(0).toDouble() *
                1_000_000_000.0 / (lastCapture - firstCapture).toDouble()
        } else if (durationSeconds > 0.0) {
            captureFrames.toDouble() / durationSeconds
        } else null
        val firstEncoded = encodedSamples.firstOrNull()
        val lastEncoded = encodedSamples.lastOrNull()
        val encodedFpsValue = if (firstEncoded != null && lastEncoded != null && lastEncoded > firstEncoded) {
            (encodedSamples.size - 1).coerceAtLeast(0).toDouble() *
                1_000_000.0 / (lastEncoded - firstEncoded).toDouble()
        } else if (durationSeconds > 0.0) {
            encodedFrames.toDouble() / durationSeconds
        } else null
        val bitrate = if (durationSeconds > 0.0) (encodedBytes * 8.0 / durationSeconds).toLong() else null
        val keyInterval = if (keyframeIntervalsUs.isNotEmpty()) keyframeIntervalsUs.average() / 1_000_000.0 else null
        val battery = readBattery()
        latestSnapshot = latestSnapshot.copy(
            state = currentState,
            message = currentMessage,
            durationSeconds = durationSeconds,
            captureFrames = captureFrames,
            encodedFrames = encodedFrames,
            keyframes = keyframes,
            captureDroppedEstimate = captureDropped,
            encodedDroppedEstimate = encodedDropped,
            captureFailureCount = captureFailureCount,
            captureFailureSamples = captureFailureSamples.toList(),
            nonMonotonicCaptureTimestamps = nonMonotonicCapture,
            nonMonotonicEncodedTimestamps = nonMonotonicEncoded,
            captureFps = capturedFps,
            encodedFps = encodedFpsValue,
            measuredBitrateBps = bitrate,
            averageKeyframeIntervalSeconds = keyInterval,
            captureFirstTimestampNs = captureSamples.firstOrNull(),
            captureLastTimestampNs = lastCaptureTimestampNs,
            encodedFirstPresentationTimeUs = encodedSamples.firstOrNull(),
            encodedLastPresentationTimeUs = lastEncodedPtsUs,
            encoderOutputFormat = outputFormat,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            visibleCrop = visibleCrop,
            outputProfile = outputProfile,
            outputLevel = outputLevel,
            endOfStreamSeen = if (isStopping) eosSeen else null,
            encoderConfigurationSucceeded = if (encoderReady) true else null,
            captureSessionConfigured = if (captureSessionReady) true else null,
            processCpuPercent = cpuPercent,
            processCpuAveragePercent = if (cpuSampleCount > 0L) cpuPercentSum / cpuSampleCount else cpuPercent,
            processCpuPeakPercent = if (cpuSampleCount > 0L) cpuPercentPeak else cpuPercent,
            thermalStatusStart = thermalAtStart,
            thermalStatusEnd = currentThermalStatus(),
            batteryPercentStart = batteryAtStart.percent,
            batteryPercentEnd = battery.percent,
            batteryTemperatureCStart = batteryAtStart.temperatureC,
            batteryTemperatureCEnd = battery.temperatureC,
            chargingStart = batteryAtStart.charging,
            chargingEnd = battery.charging,
            transport = streamSender?.snapshot(accessUnitSink?.snapshot() ?: AccessUnitSinkSnapshot())
        )
        publish(latestSnapshot)
    }

    private fun publish(snapshot: ExperimentSnapshot) {
        mainHandler.post { onSnapshot(snapshot) }
    }

    private fun estimateMissingFrames(deltaNs: Long): Long {
        val expectedNs = 1_000_000_000L / 30L
        if (deltaNs <= expectedNs * 3L / 2L) return 0L
        return max(0L, (deltaNs.toDouble() / expectedNs.toDouble()).roundToLong() - 1L)
    }

    private fun currentThermalStatus(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unavailable before API 29"
        val power = appContext.getSystemService(PowerManager::class.java) ?: return "unavailable"
        return when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }

    private fun readBattery(): BatteryReading {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatteryReading(null, null, null)
        val level = intent.getIntExtra("level", -1)
        val scale = intent.getIntExtra("scale", -1)
        val temp = intent.getIntExtra("temperature", Int.MIN_VALUE)
        val status = intent.getIntExtra("status", -1)
        return BatteryReading(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            temperatureC = if (temp != Int.MIN_VALUE) temp / 10.0 else null,
            charging = when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING,
                android.os.BatteryManager.BATTERY_STATUS_FULL -> true
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING,
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> null
            }
        )
    }

    private data class BatteryReading(
        val percent: Int?,
        val temperatureC: Double?,
        val charging: Boolean?
    )

    companion object {
        private const val TAG = "CamSurePhase3"
        private const val METRICS_INTERVAL_MS = 1000L
        private const val EOS_TIMEOUT_MS = 3000L
        private const val MAX_TIMESTAMP_SAMPLES = 10_000
        private const val MAX_FAILURE_SAMPLES = 10_000
    }
}

object RuntimeExperimentReportJson {
    fun encode(snapshot: ExperimentSnapshot): String {
        val root = JSONObject()
            .put("schemaVersion", if (snapshot.transport == null) "1.0.0" else "1.1.0")
            .put("experiment", if (snapshot.transport == null) "camera2_to_hardware_h264_surface" else "camera2_to_hardware_h264_rtp")
            .put("device", JSONObject()
                .put("manufacturer", snapshot.deviceManufacturer)
                .put("model", snapshot.deviceModel)
                .put("androidVersion", snapshot.androidVersion)
                .put("apiLevel", snapshot.apiLevel)
                .put("buildId", snapshot.buildId)
                .put("appVersionName", snapshot.appVersionName))
            .put("run", JSONObject()
                .put("state", snapshot.state)
                .put("startedAt", snapshot.startedAt)
                .put("finishedAt", snapshot.finishedAt)
                .put("durationSeconds", snapshot.durationSeconds)
                .put("routeLabel", snapshot.routeLabel)
                .put("cameraId", snapshot.cameraId)
                .put("physicalCameraId", snapshot.physicalCameraId)
                .put("cameraRoute", if (snapshot.physicalCameraId == null) "camera_id" else "logical_camera_physical_output"))
            .put("requested", JSONObject()
                .put("width", snapshot.width)
                .put("height", snapshot.height)
                .put("frameRateFps", snapshot.requestedFps)
                .put("cameraAeFpsRange", snapshot.cameraAeFpsRange)
                .put("codecMime", MediaFormat.MIMETYPE_VIDEO_AVC)
                .put("bitrateBps", snapshot.requestedBitrateBps)
                .put("bitrateMode", snapshot.requestedBitrateMode))
            .put("encoder", JSONObject()
                .put("name", snapshot.encoderName)
                .put("hardwareAccelerated", snapshot.hardwareAccelerated)
                .put("configurationSucceeded", snapshot.encoderConfigurationSucceeded)
                .put("captureSessionConfigured", snapshot.captureSessionConfigured)
                .put("widthAlignmentPixels", snapshot.encoderWidthAlignmentPixels)
                .put("heightAlignmentPixels", snapshot.encoderHeightAlignmentPixels)
                .put("cameraAdvertises1080p", snapshot.cameraAdvertises1080p)
                .put("exactDirect1080pSupported", snapshot.exactDirect1080pSupported)
                .put("outputWidth", snapshot.outputWidth)
                .put("outputHeight", snapshot.outputHeight)
                .put("visibleCrop", snapshot.visibleCrop)
                .put("profile", snapshot.outputProfile)
                .put("level", snapshot.outputLevel)
                .put("outputFormat", snapshot.encoderOutputFormat))
            .put("measurements", JSONObject()
                .put("lastSampledSensorSensitivityIso", snapshot.sensorSensitivityIso ?: JSONObject.NULL)
                .put("lastSampledSensorExposureTimeNs", snapshot.sensorExposureTimeNs ?: JSONObject.NULL)
                .put("lastSampledNoiseReductionMode", snapshot.appliedNoiseReductionMode ?: JSONObject.NULL)
                .put("lastSampledEdgeMode", snapshot.appliedEdgeMode ?: JSONObject.NULL)
                .put("cameraProcessingSampleNote", "Latest sampled capture result, sampled every 30 frames; null means not reported. Exposure and processing requests were not changed.")
                .put("captureFrames", snapshot.captureFrames)
                .put("encodedFrames", snapshot.encodedFrames)
                .put("keyframes", snapshot.keyframes)
                .put("captureDroppedFrameEstimate", snapshot.captureDroppedEstimate)
                .put("encodedDroppedFrameEstimate", snapshot.encodedDroppedEstimate)
                .put("captureFailureCount", snapshot.captureFailureCount)
                .put("captureFailureSamples", JSONArray(snapshot.captureFailureSamples))
                .put("nonMonotonicCaptureTimestamps", snapshot.nonMonotonicCaptureTimestamps)
                .put("nonMonotonicEncodedTimestamps", snapshot.nonMonotonicEncodedTimestamps)
                .put("captureFps", snapshot.captureFps)
                .put("encodedFps", snapshot.encodedFps)
                .put("measuredAverageBitrateBps", snapshot.measuredBitrateBps)
                .put("averageKeyframeIntervalSeconds", snapshot.averageKeyframeIntervalSeconds)
                .put("captureFirstTimestampNs", snapshot.captureFirstTimestampNs)
                .put("captureLastTimestampNs", snapshot.captureLastTimestampNs)
                .put("encodedFirstPresentationTimeUs", snapshot.encodedFirstPresentationTimeUs)
                .put("encodedLastPresentationTimeUs", snapshot.encodedLastPresentationTimeUs)
                .put("captureTimestampSamplesNs", JSONArray(snapshot.captureTimestampSamplesNs))
                .put("encodedPresentationTimeSamplesUs", JSONArray(snapshot.encodedPresentationTimeSamplesUs))
                .put("processCpuPercentSample", snapshot.processCpuPercent)
                .put("processCpuAveragePercent", snapshot.processCpuAveragePercent)
                .put("processCpuPeakPercent", snapshot.processCpuPeakPercent)
                .put("thermalStatusStart", snapshot.thermalStatusStart)
                .put("thermalStatusEnd", snapshot.thermalStatusEnd)
                .put("batteryPercentStart", snapshot.batteryPercentStart)
                .put("batteryPercentEnd", snapshot.batteryPercentEnd)
                .put("batteryTemperatureCStart", snapshot.batteryTemperatureCStart)
                .put("batteryTemperatureCEnd", snapshot.batteryTemperatureCEnd)
                .put("chargingStart", snapshot.chargingStart)
                .put("chargingEnd", snapshot.chargingEnd)
                .put("endOfStreamSeen", snapshot.endOfStreamSeen))
            .put("queues", JSONObject()
                .put("applicationFrameQueueDepth", snapshot.appFrameQueueDepth)
                .put("outputCallbackDispatch", "serialized on the camera/encoder handler thread")
                .put("codecInternalQueueDepth", snapshot.codecInternalQueueDepth))
            .put("path", JSONObject()
                .put("description", snapshot.pathDescription)
                .put("timestampCaveat", snapshot.timestampCaveat)
                .put("gpuUsage", "not available through the public app APIs used by this experiment"))
            .put("planNotes", JSONArray(snapshot.planNotes))
            .put("error", snapshot.error)
            .put("acceptanceBoundary", "Runtime evidence is valid only when collected by running this experiment on the named physical device. Build or metadata success alone is not runtime acceptance.")
        snapshot.transport?.let { root.put("transport", transportJson(it)) }
        return root.toString(2)
    }

    private fun transportJson(transport: RtpH264TransportSnapshot): JSONObject {
        val queue = transport.queue
        val sink = transport.sink
        return JSONObject()
            .put("protocol", "RTP/UDP with RFC 6184 H.264 non-interleaved packetization mode 1")
            .put("destination", transport.destination)
            .put("transportMode", transport.transportMode)
            .put("linkState", transport.linkState)
            .put("inFlightStaleDrops", transport.inFlightStaleDrops)
            .put("currentSenderFps", transport.currentSenderFps)
            .put("currentRtpBitrateBps", transport.currentRtpBitrateBps)
            .put("localAddress", transport.localAddress)
            .put("localInterface", transport.localInterface)
            .put("effectiveSendBufferBytes", transport.effectiveSendBufferBytes)
            .put("payloadType", transport.payloadType)
            .put("packetizationMode", transport.packetizationMode)
            .put("rtpClockHz", transport.timestampClockHz)
            .put("rtpDatagramLimitBytes", transport.rtpDatagramLimitBytes)
            .put("ssrc", transport.ssrc)
            .put("state", transport.state)
            .put("accessUnitsSent", transport.accessUnitsSent)
            .put("packetsSent", transport.packetsSent)
            .put("sendFailures", transport.sendFailures)
            .put("malformedAccessUnitsDropped", transport.malformedAccessUnitsDropped)
            .put("missingCodecConfigurationDrops", transport.missingCodecConfigurationDrops)
            .put("lastSentPresentationTimeUs", transport.lastSentPresentationTimeUs)
            .put("timestampOriginPresentationTimeUs", transport.timestampOriginPresentationTimeUs)
            .put("timestampMapping", "RTP timestamp = random 32-bit base + round((MediaCodec PTS - first sent PTS) * 90000 / 1000000), modulo 2^32")
            .put("privateRtpHeaderExtensions", JSONObject()
                .put("profile", "RFC 8285 one-byte profile 0xBEDE")
                .put("id1", "64-bit original MediaCodec presentationTimeUs")
                .put("id2", "32-bit union of MediaCodec BufferInfo flags for the completed access unit")
                .put("id3", "keyframe and codec-configuration-included flags"))
            .put("sink", JSONObject()
                .put("completedAccessUnits", sink.completedAccessUnits)
                .put("incompleteUnitsDropped", sink.incompleteUnitsDropped)
                .put("oversizedUnitsDropped", sink.oversizedUnitsDropped)
                .put("malformedOutputDropped", sink.malformedOutputDropped)
                .put("codecConfigurationVersion", sink.codecConfigurationVersion)
                .put("spsCount", sink.codecConfigurationSpsCount)
                .put("ppsCount", sink.codecConfigurationPpsCount)
                .put("codecConfigurationFailures", sink.codecConfigurationFailures))
            .put("senderQueue", JSONObject()
                .put("maxQueuedBytes", queue.maxQueuedBytes)
                .put("maxQueueAgeMs", queue.maxQueueAgeMs)
                .put("maxQueuedUnits", queue.maxQueuedUnits)
                .put("depth", queue.depth)
                .put("currentBytes", queue.currentBytes)
                .put("highWaterDepth", queue.highWaterDepth)
                .put("highWaterBytes", queue.highWaterBytes)
                .put("oldestItemAgeMs", queue.oldestItemAgeMs)
                .put("highWaterAgeMs", queue.highWaterAgeMs)
                .put("queuedPresentationSpanUs", queue.queuedPresentationSpanUs)
                .put("enqueuedUnits", queue.enqueuedUnits)
                .put("droppedForOverflow", queue.droppedForOverflow)
                .put("droppedAsStale", queue.droppedAsStale)
                .put("droppedWhileWaitingForKeyFrame", queue.droppedWhileWaitingForKeyFrame)
                .put("droppedOversized", queue.droppedOversized)
                .put("droppedAfterClose", queue.droppedAfterClose)
                .put("recoveryFlushUnits", queue.recoveryFlushUnits)
                .put("waitingForKeyFrame", queue.waitingForKeyFrame))
            .put("receiverCountersAvailable", false)
            .put("receiverCountersNote", "Read receiver-side packet loss, incomplete access units, and reassembly-buffer counters from the Windows receiver console. Sender success does not prove receiver delivery.")
    }
}

private fun MediaFormat.integerOrNull(key: String): Int? =
    try {
        if (containsKey(key)) getInteger(key) else null
    } catch (_: Exception) {
        null
    }
