package com.camsure.profiler.collector

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.camsure.profiler.model.CapabilityValue
import com.camsure.profiler.model.CollectionIssue
import com.camsure.profiler.model.CodecProfileLevel
import com.camsure.profiler.model.EncoderBitrateMode
import com.camsure.profiler.model.EncoderProfile
import com.camsure.profiler.model.EncoderSizeRateCheck
import com.camsure.profiler.model.FieldStatus
import com.camsure.profiler.model.NumericRange
import com.camsure.profiler.model.PixelSize
import com.camsure.profiler.model.VideoEncoderConstraints
import java.lang.reflect.Modifier

data class EncoderCollectionResult(
    val encoders: CapabilityValue<List<EncoderProfile>>,
    val issues: List<CollectionIssue>
)

class EncoderCapabilityCollector(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun collect(onProgress: (String) -> Unit = {}): EncoderCollectionResult {
        val codecInfos = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (error: Exception) {
            val issue = CollectionIssue(
                "codec_inventory",
                FieldStatus.QUERY_FAILED,
                message = "MediaCodecList enumeration failed (${error.javaClass.simpleName})."
            )
            return EncoderCollectionResult(CapabilityValue(FieldStatus.QUERY_FAILED, note = issue.message), listOf(issue))
        }

        val encoders = ArrayList<EncoderProfile>()
        val inventoryIssues = mutableListOf<CollectionIssue>()
        var foundRelevantCodec = false
        var truncated = false
        for (codecInfo in codecInfos) {
            val isEncoder = try {
                codecInfo.isEncoder
            } catch (error: Exception) {
                inventoryIssues.add(CollectionIssue("codec_inventory", FieldStatus.QUERY_FAILED, codecInfo.name, "Encoder classification failed (${error.javaClass.simpleName})."))
                continue
            }
            if (!isEncoder) continue
            val relevantTypes = try {
                codecInfo.supportedTypes
                    .map(String::lowercase)
                    .filter { it == MIME_AVC || it == MIME_HEVC }
                    .distinct()
            } catch (error: Exception) {
                inventoryIssues.add(CollectionIssue("codec_types", FieldStatus.QUERY_FAILED, codecInfo.name, "Codec MIME type query failed (${error.javaClass.simpleName})."))
                continue
            }
            if (relevantTypes.isEmpty()) continue
            foundRelevantCodec = true
            for (mimeType in relevantTypes) {
                if (encoders.size >= MAX_ENCODER_ENTRIES) {
                    truncated = true
                    break
                }
                onProgress("Reading ${mimeLabel(mimeType)} encoder ${codecInfo.name}…")
                encoders.add(collectCodec(codecInfo, mimeType))
            }
            if (truncated) break
        }

        val status = when {
            truncated -> FieldStatus.PARTIAL
            encoders.isEmpty() -> FieldStatus.UNSUPPORTED
            else -> FieldStatus.ADVERTISED
        }
        val note = when {
            truncated -> "Encoder inventory was limited to $MAX_ENCODER_ENTRIES codec/MIME entries."
            !foundRelevantCodec -> "No regular H.264 or HEVC encoders were returned by MediaCodecList."
            encoders.isEmpty() -> "No relevant encoder entries could be collected."
            else -> null
        }
        if (truncated) inventoryIssues.add(CollectionIssue("codec_inventory", FieldStatus.PARTIAL, message = note!!))
        return EncoderCollectionResult(CapabilityValue(status, encoders, note = note), inventoryIssues)
    }

    // classification() invokes these API 29 lambdas only after checking SDK_INT.
    @SuppressLint("NewApi")
    private fun collectCodec(codec: MediaCodecInfo, mimeType: String): EncoderProfile {
        val issues = mutableListOf<CollectionIssue>()
        val hardware = classification(codec, "hardware_accelerated", issues) { it.isHardwareAccelerated }
        val software = classification(codec, "software_only", issues) { it.isSoftwareOnly }
        val vendor = classification(codec, "vendor_codec", issues) { it.isVendor }
        val alias = classification(codec, "codec_alias", issues) { it.isAlias }

        val capabilities = try {
            codec.getCapabilitiesForType(mimeType)
        } catch (error: Exception) {
            issues.add(CollectionIssue("codec_capabilities", FieldStatus.QUERY_FAILED, codec.name, "Capabilities for $mimeType failed (${error.javaClass.simpleName})."))
            null
        } catch (error: LinkageError) {
            issues.add(CollectionIssue("codec_capabilities", FieldStatus.QUERY_FAILED, codec.name, "Codec capability API failed (${error.javaClass.simpleName})."))
            null
        }

        val profileLevels = if (capabilities == null) {
            CapabilityValue<List<CodecProfileLevel>>(FieldStatus.QUERY_FAILED, note = "Codec capability query failed.")
        } else {
            val values = try {
                capabilities.profileLevels.map {
                    CodecProfileLevel(
                        profile = it.profile,
                        profileName = enumName(it.profile, mimeType, profile = true),
                        level = it.level,
                        levelName = enumName(it.level, mimeType, profile = false)
                    )
                }
            } catch (error: Exception) {
                issues.add(CollectionIssue("codec_profile_levels", FieldStatus.QUERY_FAILED, codec.name, "Profile/level query failed (${error.javaClass.simpleName})."))
                null
            }
            when {
                values == null -> CapabilityValue(FieldStatus.QUERY_FAILED, note = "Codec profile/level query failed.")
                values.isEmpty() -> CapabilityValue(FieldStatus.UNSUPPORTED, values, note = "The codec exposes no profile/level entries for this MIME type.")
                else -> CapabilityValue(FieldStatus.ADVERTISED, values)
            }
        }

        val video = capabilities?.videoCapabilities
        val constraints = if (capabilities == null) {
            CapabilityValue<VideoEncoderConstraints>(FieldStatus.QUERY_FAILED, note = "Codec capability query failed.")
        } else if (video == null) {
            CapabilityValue<VideoEncoderConstraints>(FieldStatus.UNSUPPORTED, note = "Video capability metadata is unavailable for this codec type.")
        } else {
            CapabilityValue(FieldStatus.ADVERTISED, collectVideoConstraints(video, capabilities.encoderCapabilities, codec.name, issues))
        }

        return EncoderProfile(
            codecName = codec.name,
            mimeType = mimeType,
            hardwareAccelerated = hardware,
            softwareOnly = software,
            vendorCodec = vendor,
            alias = alias,
            profileLevels = profileLevels,
            videoConstraints = constraints,
            runtimeEncodeTest = CapabilityValue(FieldStatus.NOT_RUNTIME_TESTED, note = "No MediaCodec encoder was created or run."),
            issues = issues.take(MAX_ISSUES_PER_CODEC)
        )
    }

    private fun collectVideoConstraints(
        video: MediaCodecInfo.VideoCapabilities,
        encoder: MediaCodecInfo.EncoderCapabilities?,
        codecName: String,
        issues: MutableList<CollectionIssue>
    ): VideoEncoderConstraints {
        fun <T> read(scope: String, unit: String? = null, query: () -> T?): CapabilityValue<T> = try {
            query()?.let { CapabilityValue(FieldStatus.ADVERTISED, it, unit) }
                ?: CapabilityValue(FieldStatus.UNSUPPORTED, unit = unit, note = "The codec did not expose this metadata.")
        } catch (error: Exception) {
            val message = "Codec metadata query failed (${error.javaClass.simpleName})."
            issues.add(CollectionIssue(scope, FieldStatus.QUERY_FAILED, codecName, message))
            CapabilityValue(FieldStatus.QUERY_FAILED, unit = unit, note = message)
        } catch (error: LinkageError) {
            val message = "Codec metadata API failed (${error.javaClass.simpleName})."
            issues.add(CollectionIssue(scope, FieldStatus.QUERY_FAILED, codecName, message))
            CapabilityValue(FieldStatus.QUERY_FAILED, unit = unit, note = message)
        }

        val bitrateModeTypes = listOf(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR to "cbr",
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR to "vbr",
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ to "constant_quality"
        )
        val bitrateModes = bitrateModeTypes.map { (mode, name) ->
            val support = if (encoder == null) {
                CapabilityValue(FieldStatus.UNSUPPORTED, note = "Encoder-specific bitrate metadata is unavailable.")
            } else {
                read("bitrate_mode_$name") { encoder.isBitrateModeSupported(mode) }
            }
            EncoderBitrateMode(mode, name, support)
        }

        val candidateModes = listOf(
            PixelSize(1280, 720) to 30,
            PixelSize(1920, 1080) to 30,
            PixelSize(1920, 1080) to 60,
            PixelSize(3840, 2160) to 30,
            PixelSize(3840, 2160) to 60
        )
        val checks = candidateModes.map { (size, fps) ->
            val supported = read("mode_check_${size.width}x${size.height}_$fps") {
                video.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
            }
            val frameRateRange = if (supported.value == true) {
                read("mode_frame_rate_range_${size.width}x${size.height}", "frames_per_second") {
                    video.getSupportedFrameRatesFor(size.width, size.height)
                        ?.let { NumericRange(it.lower, it.upper) }
                }
            } else {
                CapabilityValue(
                    if (supported.status == FieldStatus.ADVERTISED) FieldStatus.UNSUPPORTED else supported.status,
                    note = if (supported.status == FieldStatus.ADVERTISED) "This exact size/FPS point is outside the advertised codec constraints." else supported.note
                )
            }
            EncoderSizeRateCheck(size, fps, supported.copy(
                status = if (supported.value == false && supported.status == FieldStatus.ADVERTISED) FieldStatus.UNSUPPORTED else supported.status,
                note = if (supported.value == false && supported.status == FieldStatus.ADVERTISED) "This exact size/FPS point is outside the advertised codec constraints." else supported.note
            ), frameRateRange)
        }

        return VideoEncoderConstraints(
            widthRange = read<NumericRange<Int>>("supported_width_range", "pixels") {
                NumericRange(video.supportedWidths.lower, video.supportedWidths.upper)
            },
            heightRange = read<NumericRange<Int>>("supported_height_range", "pixels") {
                NumericRange(video.supportedHeights.lower, video.supportedHeights.upper)
            },
            widthAlignmentPixels = read("width_alignment", "pixels") { video.widthAlignment },
            heightAlignmentPixels = read("height_alignment", "pixels") { video.heightAlignment },
            bitrateRangeBps = read<NumericRange<Int>>("bitrate_range", "bits_per_second") {
                NumericRange(video.bitrateRange.lower, video.bitrateRange.upper)
            },
            frameRateRangeFps = read<NumericRange<Int>>("frame_rate_range", "frames_per_second") {
                NumericRange(video.supportedFrameRates.lower, video.supportedFrameRates.upper)
            },
            bitrateModes = if (encoder == null) {
                CapabilityValue(FieldStatus.UNSUPPORTED, bitrateModes, note = "Encoder-specific bitrate metadata is unavailable.")
            } else if (bitrateModes.any { it.supported.status == FieldStatus.QUERY_FAILED }) {
                CapabilityValue(FieldStatus.PARTIAL, bitrateModes, note = "One or more bitrate mode checks failed.")
            } else CapabilityValue(FieldStatus.ADVERTISED, bitrateModes),
            representativeModeChecks = CapabilityValue(
                FieldStatus.ADVERTISED,
                checks,
                note = "Point queries only for 720p30, 1080p30, and 1080p60; this is not an exhaustive size/FPS matrix or runtime test."
            )
        )
    }

    private fun classification(
        codec: MediaCodecInfo,
        field: String,
        issues: MutableList<CollectionIssue>,
        query: (MediaCodecInfo) -> Boolean
    ): CapabilityValue<Boolean> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return CapabilityValue(FieldStatus.UNAVAILABLE_ON_API_LEVEL, apiLevelRequired = 29, note = "$field classification requires Android API 29.")
        }
        return try {
            CapabilityValue(FieldStatus.ADVERTISED, query(codec))
        } catch (error: Exception) {
            val message = "$field query failed (${error.javaClass.simpleName})."
            issues.add(CollectionIssue(field, FieldStatus.QUERY_FAILED, codec.name, message))
            CapabilityValue(FieldStatus.QUERY_FAILED, note = message)
        } catch (error: LinkageError) {
            val message = "$field API failed (${error.javaClass.simpleName})."
            issues.add(CollectionIssue(field, FieldStatus.QUERY_FAILED, codec.name, message))
            CapabilityValue(FieldStatus.QUERY_FAILED, note = message)
        }
    }

    private fun enumName(value: Int, mime: String, profile: Boolean): String {
        val codecPrefix = if (mime == MIME_AVC) "AVC" else "HEVC"
        val prefixes = when {
            profile && mime == MIME_AVC -> listOf("AVCProfile")
            profile -> listOf("HEVCProfile")
            mime == MIME_AVC -> listOf("AVCLevel")
            else -> listOf("HEVCMainTierLevel", "HEVCHighTierLevel")
        }
        val name = try {
            MediaCodecInfo.CodecProfileLevel::class.java.fields
                .asSequence()
                .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && prefixes.any(it.name::startsWith) }
                .sortedBy { it.name }
                .firstOrNull { it.getInt(null) == value }
                ?.name
        } catch (_: Exception) {
            null
        }
        return name?.removePrefix("${codecPrefix}Profile")
            ?.removePrefix("${codecPrefix}MainTierLevel")
            ?.removePrefix("${codecPrefix}HighTierLevel")
            ?: "UNKNOWN_$value"
    }

    private fun mimeLabel(mimeType: String) = if (mimeType == MIME_AVC) "H.264" else "HEVC"

    companion object {
        private const val MIME_AVC = "video/avc"
        private const val MIME_HEVC = "video/hevc"
        private const val MAX_ENCODER_ENTRIES = 256
        private const val MAX_ISSUES_PER_CODEC = 128
    }
}
