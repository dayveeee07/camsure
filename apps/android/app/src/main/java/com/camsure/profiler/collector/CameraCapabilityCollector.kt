package com.camsure.profiler.collector

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Build
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import com.camsure.profiler.model.CameraControlsProfile
import com.camsure.profiler.model.CameraProfile
import com.camsure.profiler.model.CapabilityValue
import com.camsure.profiler.model.CollectionIssue
import com.camsure.profiler.model.FieldStatus
import com.camsure.profiler.model.HighSpeedModeProfile
import com.camsure.profiler.model.NumericRange
import com.camsure.profiler.model.PhysicalCameraListProfile
import com.camsure.profiler.model.PhysicalCameraProfile
import com.camsure.profiler.model.PhysicalSizeMm
import com.camsure.profiler.model.PixelSize
import com.camsure.profiler.model.RationalValue
import com.camsure.profiler.model.StreamFormatProfile
import com.camsure.profiler.model.StreamSizeProfile
import com.camsure.profiler.model.ZoomProfile
import java.lang.reflect.Modifier

data class CameraCollectionResult(
    val cameras: CapabilityValue<List<CameraProfile>>,
    val issues: List<CollectionIssue>
)

class CameraCapabilityCollector(context: Context) {
    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)

    fun collect(onProgress: (String) -> Unit = {}): CameraCollectionResult {
        val cameraManager = manager ?: return CameraCollectionResult(
            CapabilityValue(FieldStatus.QUERY_FAILED, note = "CameraManager service is unavailable."),
            listOf(CollectionIssue("camera_inventory", FieldStatus.QUERY_FAILED, message = "CameraManager service is unavailable."))
        )

        val ids = try {
            cameraManager.cameraIdList.toList()
        } catch (error: SecurityException) {
            val issue = CollectionIssue("camera_inventory", FieldStatus.INACCESSIBLE, message = "Camera access was denied. Grant Camera permission and retry.")
            return CameraCollectionResult(CapabilityValue(FieldStatus.INACCESSIBLE, note = issue.message), listOf(issue))
        } catch (error: Exception) {
            val issue = CollectionIssue("camera_inventory", FieldStatus.QUERY_FAILED, message = "Camera ID enumeration failed (${error.javaClass.simpleName}).")
            return CameraCollectionResult(CapabilityValue(FieldStatus.QUERY_FAILED, note = issue.message), listOf(issue))
        }

        val boundedIds = ids.take(MAX_CAMERAS)
        val partialInventory = ids.size > boundedIds.size
        val profiles = ArrayList<CameraProfile>(boundedIds.size)
        boundedIds.forEachIndexed { index, cameraId ->
            onProgress("Reading camera ${index + 1} of ${boundedIds.size}…")
            val cameraIssues = mutableListOf<CollectionIssue>()
            val profile = try {
                collectCamera(cameraManager, cameraId, cameraIssues)
            } catch (error: SecurityException) {
                cameraIssues.add(CollectionIssue("camera_metadata", FieldStatus.INACCESSIBLE, cameraId, "Camera metadata access was denied."))
                failedCamera(cameraId, FieldStatus.INACCESSIBLE, "Camera metadata access was denied.", cameraIssues)
            } catch (error: Exception) {
                cameraIssues.add(CollectionIssue("camera_metadata", FieldStatus.QUERY_FAILED, cameraId, "Camera metadata query failed (${error.javaClass.simpleName})."))
                failedCamera(cameraId, FieldStatus.QUERY_FAILED, "Camera metadata query failed.", cameraIssues)
            } catch (error: LinkageError) {
                cameraIssues.add(CollectionIssue("camera_metadata", FieldStatus.QUERY_FAILED, cameraId, "Camera metadata API is unavailable (${error.javaClass.simpleName})."))
                failedCamera(cameraId, FieldStatus.QUERY_FAILED, "Camera metadata API is unavailable.", cameraIssues)
            }
            profiles.add(profile.copy(issues = cameraIssues.take(MAX_ISSUES_PER_CAMERA)))
        }

        val status = if (partialInventory) FieldStatus.PARTIAL else if (profiles.isEmpty()) FieldStatus.UNSUPPORTED else FieldStatus.ADVERTISED
        val note = when {
            partialInventory -> "Camera ID list was limited to $MAX_CAMERAS entries."
            profiles.isEmpty() -> "CameraManager returned no camera IDs."
            else -> null
        }
        val issues = if (partialInventory) {
            listOf(CollectionIssue("camera_inventory", FieldStatus.PARTIAL, message = note!!))
        } else emptyList()
        return CameraCollectionResult(CapabilityValue(status, profiles, note = note), issues)
    }

    private fun collectCamera(
        cameraManager: CameraManager,
        cameraId: String,
        issues: MutableList<CollectionIssue>
    ): CameraProfile {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val rawCapabilities = readField("available_capabilities", cameraId, issues) {
            characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
        }
        val capabilities = mapField(rawCapabilities) { values -> values.map(::cameraCapabilityName) }
        val isLogical = when {
            rawCapabilities.status != FieldStatus.ADVERTISED -> CapabilityValue(
                rawCapabilities.status,
                apiLevelRequired = rawCapabilities.apiLevelRequired,
                note = rawCapabilities.note
            )
            capabilities.value?.contains("LOGICAL_MULTI_CAMERA") == true -> CapabilityValue(
                FieldStatus.ADVERTISED,
                true,
                note = "Derived from REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA."
            )
            else -> CapabilityValue(
                FieldStatus.UNSUPPORTED,
                false,
                note = "The camera does not advertise logical multi-camera capability."
            )
        }

        val physical = collectPhysicalCameras(cameraManager, cameraId, characteristics, isLogical, issues)
        val streamMap = readField("stream_configuration_map", cameraId, issues) {
            characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        }
        val formats = if (streamMap.status != FieldStatus.ADVERTISED || streamMap.value == null) {
            CapabilityValue(streamMap.status, note = streamMap.note)
        } else {
            collectStreamFormats(streamMap.value, cameraId, issues)
        }
        val highSpeedModes = streamMap.value?.let { collectHighSpeedModes(it, cameraId, issues) }
            ?: CapabilityValue(streamMap.status, note = streamMap.note)
        val dynamicRanges = collectDynamicRangeProfiles(characteristics, cameraId, issues)

        val flash = readField("flash_available", cameraId, issues) {
            characteristics[CameraCharacteristics.FLASH_INFO_AVAILABLE]
        }
        val controls = collectControls(characteristics, cameraId, rawCapabilities, flash, issues)

        return CameraProfile(
            cameraId = cameraId,
            metadataQuery = CapabilityValue(FieldStatus.ADVERTISED, "Camera2 characteristics queried."),
            lensFacing = readField("lens_facing", cameraId, issues) {
                characteristics[CameraCharacteristics.LENS_FACING]?.let(::lensFacingName)
            },
            hardwareSupportLevel = readField("hardware_support_level", cameraId, issues) {
                characteristics[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]?.let(::hardwareLevelName)
            },
            logicalCamera = isLogical,
            physicalCameras = physical,
            focalLengthsMm = readList("focal_lengths", cameraId, "millimeters", issues) {
                characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.toList()
            },
            aperturesFNumber = readList("apertures", cameraId, "f_number", issues) {
                characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]?.toList()
            },
            sensorSizeMm = readField("sensor_size", cameraId, issues) {
                characteristics[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.let(::physicalSize)
            }.copy(unit = "millimeters"),
            sensorOrientationDegrees = readField("sensor_orientation", cameraId, issues) {
                characteristics[CameraCharacteristics.SENSOR_ORIENTATION]
            }.copy(unit = "degrees"),
            advertisedCapabilities = capabilities,
            streamFormats = formats,
            aeTargetFpsRanges = readList("ae_target_fps_ranges", cameraId, "frames_per_second", issues) {
                characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
                    ?.map { NumericRange(it.lower, it.upper) }
            },
            constrainedHighSpeedModes = highSpeedModes,
            dynamicRangeProfiles = dynamicRanges,
            controls = controls,
            runtimeCaptureTest = CapabilityValue(
                FieldStatus.NOT_RUNTIME_TESTED,
                note = "No capture session was started."
            ),
            issues = emptyList()
        )
    }

    private fun collectPhysicalCameras(
        cameraManager: CameraManager,
        cameraId: String,
        characteristics: CameraCharacteristics,
        logical: CapabilityValue<Boolean>,
        issues: MutableList<CollectionIssue>
    ): PhysicalCameraListProfile {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val unavailable = apiUnavailable<List<String>>(28)
            return PhysicalCameraListProfile(unavailable, emptyList())
        }
        val idsField = readList("physical_camera_ids", cameraId, null, issues) {
            characteristics.physicalCameraIds.toList()
        }
        val ids = idsField.value.orEmpty().take(MAX_PHYSICAL_CAMERAS)
        val boundedIdsField = if (idsField.value.orEmpty().size > ids.size) {
            CapabilityValue(FieldStatus.PARTIAL, ids, note = "Physical camera ID list was limited to $MAX_PHYSICAL_CAMERAS entries.")
        } else idsField
        val profiles = ids.map { physicalId ->
            val physicalIssues = mutableListOf<CollectionIssue>()
            try {
                val physical = cameraManager.getCameraCharacteristics(physicalId)
                PhysicalCameraProfile(
                    cameraId = physicalId,
                    query = CapabilityValue(FieldStatus.ADVERTISED, "Physical camera metadata queried."),
                    lensFacing = readField("physical_lens_facing", physicalId, physicalIssues) {
                        physical[CameraCharacteristics.LENS_FACING]?.let(::lensFacingName)
                    },
                    focalLengthsMm = readList("physical_focal_lengths", physicalId, "millimeters", physicalIssues) {
                        physical[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.toList()
                    },
                    aperturesFNumber = readList("physical_apertures", physicalId, "f_number", physicalIssues) {
                        physical[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]?.toList()
                    },
                    sensorSizeMm = readField("physical_sensor_size", physicalId, physicalIssues) {
                        physical[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.let(::physicalSize)
                    }.copy(unit = "millimeters"),
                    sensorOrientationDegrees = readField("physical_sensor_orientation", physicalId, physicalIssues) {
                        physical[CameraCharacteristics.SENSOR_ORIENTATION]
                    }.copy(unit = "degrees")
                )
            } catch (error: SecurityException) {
                issues.add(CollectionIssue("physical_camera_metadata", FieldStatus.INACCESSIBLE, physicalId, "Physical camera metadata access was denied."))
                failedPhysicalCamera(physicalId, FieldStatus.INACCESSIBLE, "Physical camera metadata access was denied.")
            } catch (error: Exception) {
                issues.add(CollectionIssue("physical_camera_metadata", FieldStatus.QUERY_FAILED, physicalId, "Physical camera metadata query failed (${error.javaClass.simpleName})."))
                failedPhysicalCamera(physicalId, FieldStatus.QUERY_FAILED, "Physical camera metadata query failed.")
            }
        }
        if (logical.status == FieldStatus.UNSUPPORTED && boundedIdsField.value.isNullOrEmpty()) {
            return PhysicalCameraListProfile(
                CapabilityValue(FieldStatus.UNSUPPORTED, emptyList(), note = "No physical camera IDs are exposed for this camera."),
                emptyList()
            )
        }
        return PhysicalCameraListProfile(boundedIdsField, profiles)
    }

    private fun collectStreamFormats(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        cameraId: String,
        issues: MutableList<CollectionIssue>
    ): CapabilityValue<List<StreamFormatProfile>> {
        val outputFormatsField = readList("stream_formats", cameraId, null, issues) {
            map.outputFormats?.toList()
        }
        val outputFormats = outputFormatsField.value.orEmpty()
        val formats = outputFormats.take(MAX_STREAM_FORMATS)
        var remainingSizes = MAX_STREAM_SIZES_PER_CAMERA
        val profiles = formats.map { format ->
            val sizesField = readField("stream_sizes_format_$format", cameraId, issues) {
                map.getOutputSizes(format)?.toList()
            }
            val allSizes = sizesField.value.orEmpty()
            val includedSizes = allSizes.take(remainingSizes)
            remainingSizes -= includedSizes.size
            val streamSizes = includedSizes.map { size ->
                val minimumDuration = readField("minimum_frame_duration_format_$format", cameraId, issues) {
                    map.getOutputMinFrameDuration(format, size)
                }.copy(unit = "nanoseconds")
                val stallDuration = readField("stall_duration_format_$format", cameraId, issues) {
                    map.getOutputStallDuration(format, size)
                }.copy(unit = "nanoseconds")
                StreamSizeProfile(PixelSize(size.width, size.height), minimumDuration, stallDuration)
            }
            val sizesStatus = when {
                remainingSizes == 0 && allSizes.size > includedSizes.size -> FieldStatus.PARTIAL
                allSizes.isEmpty() && sizesField.status == FieldStatus.ADVERTISED -> FieldStatus.UNSUPPORTED
                else -> sizesField.status
            }
            val sizesNote = if (sizesStatus == FieldStatus.PARTIAL) "Per-camera stream-size limit reached; later sizes were omitted." else sizesField.note
            StreamFormatProfile(
                formatCode = format,
                formatName = imageFormatName(format),
                relevantToVideoCapture = isVideoRelevantFormat(format),
                sizes = CapabilityValue(sizesStatus, streamSizes, "pixels", sizesField.apiLevelRequired, sizesNote)
            )
        }
        val formatListTruncated = outputFormats.size > formats.size
        val sizeListTruncated = profiles.any { it.sizes.status == FieldStatus.PARTIAL }
        return when {
            formatListTruncated -> CapabilityValue(
                FieldStatus.PARTIAL,
                profiles,
                note = "Stream-format list was limited to $MAX_STREAM_FORMATS entries."
            )
            sizeListTruncated -> CapabilityValue(
                FieldStatus.PARTIAL,
                profiles,
                note = "One or more per-format stream-size lists were limited to $MAX_STREAM_SIZES_PER_CAMERA entries per camera."
            )
            else -> CapabilityValue(outputFormatsField.status, profiles, note = outputFormatsField.note)
        }
    }

    private fun collectHighSpeedModes(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        cameraId: String,
        issues: MutableList<CollectionIssue>
    ): CapabilityValue<List<HighSpeedModeProfile>> {
        val sizesField = readList("constrained_high_speed_sizes", cameraId, null, issues) {
            map.highSpeedVideoSizes?.toList()
        }
        val allSizes = sizesField.value.orEmpty()
        val selected = allSizes.take(MAX_HIGH_SPEED_SIZES)
        val modes = selected.map { size ->
            val ranges = readList("high_speed_fps_ranges_${size.width}x${size.height}", cameraId, "frames_per_second", issues) {
                map.getHighSpeedVideoFpsRangesFor(size)?.map { NumericRange(it.lower, it.upper) }
            }
            HighSpeedModeProfile(PixelSize(size.width, size.height), ranges)
        }
        return if (allSizes.size > selected.size) {
            CapabilityValue(FieldStatus.PARTIAL, modes, note = "High-speed size list was limited to $MAX_HIGH_SPEED_SIZES entries.")
        } else CapabilityValue(sizesField.status, modes, note = sizesField.note)
    }

    private fun collectDynamicRangeProfiles(
        characteristics: CameraCharacteristics,
        cameraId: String,
        issues: MutableList<CollectionIssue>
    ): CapabilityValue<List<Pair<Long, String>>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return apiUnavailable(33)
        val profiles = readList("dynamic_range_profiles", cameraId, null, issues) {
            characteristics[CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES]
                ?.supportedProfiles
                ?.map { it to dynamicRangeName(it) }
        }
        return profiles.copy(note = listOfNotNull(profiles.note, "Profile metadata only; stream/session compatibility was not queried.").joinToString(" "))
    }

    private fun collectControls(
        characteristics: CameraCharacteristics,
        cameraId: String,
        capabilities: CapabilityValue<IntArray>,
        flash: CapabilityValue<Boolean>,
        issues: MutableList<CollectionIssue>
    ): CameraControlsProfile {
        val manualSensor = when {
            capabilities.status != FieldStatus.ADVERTISED -> CapabilityValue(
                capabilities.status,
                apiLevelRequired = capabilities.apiLevelRequired,
                note = capabilities.note
            )
            capabilities.value?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true ->
                CapabilityValue(FieldStatus.ADVERTISED, true, note = "Derived from REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR.")
            else -> CapabilityValue(FieldStatus.UNSUPPORTED, false, note = "Manual sensor capability is not advertised.")
        }

        val zoomRatio = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            apiUnavailable<NumericRange<Float>>(30)
        } else {
            readField("zoom_ratio_range", cameraId, issues) {
                characteristics[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.let {
                    NumericRange(it.lower, it.upper)
                }
            }
        }
        val digitalZoom = readField("maximum_digital_zoom", cameraId, issues) {
            characteristics[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM]
        }.copy(unit = "ratio")
        val effectiveZoom = when {
            zoomRatio.status == FieldStatus.ADVERTISED -> ZoomProfile(zoomRatio, digitalZoom, zoomRatio, "camera2_zoom_ratio")
            digitalZoom.status == FieldStatus.ADVERTISED && digitalZoom.value != null -> {
                val fallback = CapabilityValue(
                    FieldStatus.ADVERTISED,
                    NumericRange(1.0f, digitalZoom.value),
                    "ratio",
                    note = "Fallback derived from maximum digital zoom; not an optical zoom range."
                )
                ZoomProfile(zoomRatio, digitalZoom, fallback, "digital_zoom_fallback")
            }
            else -> ZoomProfile(zoomRatio, digitalZoom, CapabilityValue(digitalZoom.status, note = digitalZoom.note), null)
        }

        val exposureRange = readField("exposure_compensation_range", cameraId, issues) {
            characteristics[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]?.let {
                NumericRange(it.lower, it.upper)
            }
        }.copy(unit = "steps")
        val compensationStep = readField("exposure_compensation_step", cameraId, issues) {
            characteristics[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.let(::rationalValue)
        }.copy(unit = "EV per step")

        return CameraControlsProfile(
            autofocusModes = readList("autofocus_modes", cameraId, null, issues) {
                characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.map(::autofocusName)
            },
            minimumFocusDistanceDiopters = readField("minimum_focus_distance", cameraId, issues) {
                characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]
            }.copy(unit = "diopters"),
            exposureCompensationSteps = exposureRange,
            exposureCompensationStepEv = compensationStep,
            aeLockAvailable = readField("ae_lock_available", cameraId, issues) {
                characteristics[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE]
            },
            aeModes = readList("ae_modes", cameraId, null, issues) {
                characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]?.map(::aeModeName)
            },
            awbLockAvailable = readField("awb_lock_available", cameraId, issues) {
                characteristics[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE]
            },
            awbModes = readList("awb_modes", cameraId, null, issues) {
                characteristics[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]?.map(::awbModeName)
            },
            manualSensorCapability = manualSensor,
            isoSensitivityRange = readField("iso_sensitivity_range", cameraId, issues) {
                characteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.let {
                    NumericRange(it.lower, it.upper)
                }
            }.copy(unit = "ISO"),
            exposureTimeRangeNs = readField("exposure_time_range", cameraId, issues) {
                characteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]?.let {
                    NumericRange(it.lower, it.upper)
                }
            }.copy(unit = "nanoseconds"),
            zoom = effectiveZoom,
            flashUnitAvailable = flash,
            torchAvailable = when (flash.status) {
                FieldStatus.ADVERTISED -> CapabilityValue(
                    if (flash.value == true) FieldStatus.ADVERTISED else FieldStatus.UNSUPPORTED,
                    flash.value,
                    note = "Derived from FLASH_INFO_AVAILABLE; torch was not activated."
                )
                else -> flash.copy(value = null)
            },
            opticalStabilizationModes = readList("optical_stabilization_modes", cameraId, null, issues) {
                characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.map(::stabilizationName)
            },
            videoStabilizationModes = readList("video_stabilization_modes", cameraId, null, issues) {
                characteristics[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.map(::stabilizationName)
            }
        )
    }

    private fun failedCamera(
        cameraId: String,
        status: FieldStatus,
        message: String,
        issues: List<CollectionIssue>
    ): CameraProfile {
        fun <T> failed(): CapabilityValue<T> = CapabilityValue(status, note = message)
        val notTested = CapabilityValue<String>(FieldStatus.NOT_RUNTIME_TESTED, note = "No capture session was started.")
        return CameraProfile(
            cameraId = cameraId,
            metadataQuery = failed(),
            lensFacing = failed(),
            hardwareSupportLevel = failed(),
            logicalCamera = failed(),
            physicalCameras = PhysicalCameraListProfile(failed(), emptyList()),
            focalLengthsMm = failed(),
            aperturesFNumber = failed(),
            sensorSizeMm = failed(),
            sensorOrientationDegrees = failed(),
            advertisedCapabilities = failed(),
            streamFormats = failed(),
            aeTargetFpsRanges = failed(),
            constrainedHighSpeedModes = failed(),
            dynamicRangeProfiles = failed(),
            controls = CameraControlsProfile(
                autofocusModes = failed(), minimumFocusDistanceDiopters = failed(),
                exposureCompensationSteps = failed(), exposureCompensationStepEv = failed(),
                aeLockAvailable = failed(), aeModes = failed(), awbLockAvailable = failed(), awbModes = failed(),
                manualSensorCapability = failed(), isoSensitivityRange = failed(), exposureTimeRangeNs = failed(),
                zoom = ZoomProfile(failed(), failed(), failed(), null),
                flashUnitAvailable = failed(), torchAvailable = failed(),
                opticalStabilizationModes = failed(), videoStabilizationModes = failed()
            ),
            runtimeCaptureTest = notTested,
            issues = issues
        )
    }

    private fun failedPhysicalCamera(id: String, status: FieldStatus, message: String) = PhysicalCameraProfile(
        cameraId = id,
        query = CapabilityValue(status, note = message),
        lensFacing = CapabilityValue(status, note = message),
        focalLengthsMm = CapabilityValue(status, note = message),
        aperturesFNumber = CapabilityValue(status, note = message),
        sensorSizeMm = CapabilityValue(status, note = message),
        sensorOrientationDegrees = CapabilityValue(status, note = message)
    )

    private fun <T> readField(
        scope: String,
        subject: String,
        issues: MutableList<CollectionIssue>,
        getter: () -> T?
    ): CapabilityValue<T> = try {
        getter()?.let { CapabilityValue(FieldStatus.ADVERTISED, it) }
            ?: CapabilityValue(FieldStatus.UNSUPPORTED, note = "The camera did not expose this metadata field.")
    } catch (error: SecurityException) {
        val message = "Metadata access was denied."
        issues.add(CollectionIssue(scope, FieldStatus.INACCESSIBLE, subject, message))
        CapabilityValue(FieldStatus.INACCESSIBLE, note = message)
    } catch (error: Exception) {
        val message = "Metadata query failed (${error.javaClass.simpleName})."
        issues.add(CollectionIssue(scope, FieldStatus.QUERY_FAILED, subject, message))
        CapabilityValue(FieldStatus.QUERY_FAILED, note = message)
    } catch (error: LinkageError) {
        val message = "Metadata API is unavailable (${error.javaClass.simpleName})."
        issues.add(CollectionIssue(scope, FieldStatus.QUERY_FAILED, subject, message))
        CapabilityValue(FieldStatus.QUERY_FAILED, note = message)
    }

    private fun <T> readList(
        scope: String,
        subject: String,
        unit: String?,
        issues: MutableList<CollectionIssue>,
        getter: () -> List<T>?
    ): CapabilityValue<List<T>> {
        val read = readField(scope, subject, issues, getter)
        return read.copy(unit = unit).let { field ->
            if (field.status == FieldStatus.ADVERTISED && field.value.isNullOrEmpty()) {
                field.copy(status = FieldStatus.UNSUPPORTED, note = "The camera did not advertise values for this metadata field.")
            } else field
        }
    }

    private fun <T, R> mapField(field: CapabilityValue<T>, mapper: (T) -> R): CapabilityValue<R> =
        CapabilityValue(field.status, field.value?.let(mapper), field.unit, field.apiLevelRequired, field.note)

    private fun <T> apiUnavailable(required: Int): CapabilityValue<T> = CapabilityValue(
        FieldStatus.UNAVAILABLE_ON_API_LEVEL,
        apiLevelRequired = required,
        note = "This field requires Android API $required."
    )

    private fun cameraCapabilityName(value: Int): String = reflectedConstantName(
        CameraMetadata::class.java,
        "REQUEST_AVAILABLE_CAPABILITIES_",
        value
    ) ?: "CAPABILITY_$value"

    private fun imageFormatName(value: Int): String = reflectedConstantName(ImageFormat::class.java, "", value)
        ?: "ANDROID_IMAGE_FORMAT_$value"

    private fun reflectedConstantName(type: Class<*>, prefix: String, value: Int): String? = try {
        type.fields
            .asSequence()
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith(prefix) }
            .sortedBy { it.name }
            .firstOrNull { field -> field.getInt(null) == value }
            ?.name
            ?.removePrefix(prefix)
    } catch (_: Exception) {
        null
    }

    private fun lensFacingName(value: Int): String = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
        else -> "unknown_$value"
    }

    private fun hardwareLevelName(value: Int): String = when (value) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "external"
        else -> "unknown_$value"
    }

    private fun autofocusName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> "off"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "auto"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "macro"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "continuous_video"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "continuous_picture"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "edof"
        else -> "unknown_$value"
    }

    private fun aeModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AE_MODE_OFF -> "off"
        CameraMetadata.CONTROL_AE_MODE_ON -> "on"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "on_auto_flash"
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "on_always_flash"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "on_auto_flash_redeye"
        CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> "on_external_flash"
        else -> "unknown_$value"
    }

    private fun awbModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AWB_MODE_OFF -> "off"
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "auto"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "incandescent"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "fluorescent"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "warm_fluorescent"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "daylight"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "cloudy_daylight"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "twilight"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "shade"
        else -> "unknown_$value"
    }

    private fun stabilizationName(value: Int): String = when (value) {
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "off"
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "on"
        else -> "unknown_$value"
    }

    private fun dynamicRangeName(value: Long): String = when (value) {
        DynamicRangeProfiles.STANDARD -> "standard"
        DynamicRangeProfiles.HLG10 -> "hlg10"
        DynamicRangeProfiles.HDR10 -> "hdr10"
        DynamicRangeProfiles.HDR10_PLUS -> "hdr10_plus"
        else -> "profile_$value"
    }

    private fun rationalValue(value: Rational): RationalValue = RationalValue(
        value.numerator,
        value.denominator,
        value.toFloat().toDouble()
    )

    private fun physicalSize(value: SizeF): PhysicalSizeMm = PhysicalSizeMm(value.width, value.height)

    private fun isVideoRelevantFormat(format: Int): Boolean =
        format == ImageFormat.PRIVATE || format == ImageFormat.YUV_420_888 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && format == ImageFormat.YCBCR_P010)

    companion object {
        private const val MAX_CAMERAS = 32
        private const val MAX_PHYSICAL_CAMERAS = 32
        private const val MAX_STREAM_FORMATS = 32
        private const val MAX_STREAM_SIZES_PER_CAMERA = 512
        private const val MAX_HIGH_SPEED_SIZES = 128
        private const val MAX_ISSUES_PER_CAMERA = 128
    }
}
