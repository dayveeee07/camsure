package com.camsure.profiler.collector

import android.content.Context
import android.os.Build
import com.camsure.profiler.BuildConfig
import com.camsure.profiler.model.CapabilityReport
import com.camsure.profiler.model.CapabilityValue
import com.camsure.profiler.model.CollectionIssue
import com.camsure.profiler.model.DeviceRuntime
import com.camsure.profiler.model.FieldStatus
import com.camsure.profiler.report.CapabilityReportJson
import java.time.Instant

class CapabilityProfiler(context: Context) {
    private val appContext = context.applicationContext

    fun collect(onProgress: (String) -> Unit): CapabilityReport {
        val device = DeviceRuntime(
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" },
            model = Build.MODEL.orEmpty().ifBlank { "Unknown" },
            androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "Unknown" },
            apiLevel = Build.VERSION.SDK_INT,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toLong()
        )

        onProgress("Enumerating Camera2 metadata…")
        val cameraResult = try {
            CameraCapabilityCollector(appContext).collect(onProgress)
        } catch (error: Exception) {
            val issue = CollectionIssue(
                "camera_inventory",
                FieldStatus.QUERY_FAILED,
                message = "Camera collection failed (${error.javaClass.simpleName}); encoder collection will continue."
            )
            CameraCollectionResult(CapabilityValue(FieldStatus.QUERY_FAILED, note = issue.message), listOf(issue))
        }

        onProgress("Enumerating H.264 and HEVC encoders…")
        val encoderResult = try {
            EncoderCapabilityCollector(appContext).collect(onProgress)
        } catch (error: Exception) {
            val issue = CollectionIssue(
                "codec_inventory",
                FieldStatus.QUERY_FAILED,
                message = "Encoder collection failed (${error.javaClass.simpleName}); camera data is retained."
            )
            EncoderCollectionResult(CapabilityValue(FieldStatus.QUERY_FAILED, note = issue.message), listOf(issue))
        }

        return CapabilityReport(
            schemaVersion = CapabilityReportJson.SCHEMA_VERSION,
            collectionTime = Instant.now().toString(),
            device = device,
            cameras = cameraResult.cameras,
            videoEncoders = encoderResult.encoders,
            issues = cameraResult.issues + encoderResult.issues
        )
    }
}
