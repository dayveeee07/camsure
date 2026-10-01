package com.camsure.profiler

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.text.InputType
import com.camsure.profiler.model.CameraProfile
import com.camsure.profiler.model.CapabilityReport
import com.camsure.profiler.model.CapabilityValue
import com.camsure.profiler.model.EncoderProfile
import com.camsure.profiler.model.FieldStatus
import com.camsure.profiler.model.HighSpeedModeProfile
import com.camsure.profiler.model.NumericRange
import com.camsure.profiler.phase3.CameraEncoderExperimentDiscovery
import com.camsure.profiler.phase3.CameraRoute
import com.camsure.profiler.phase3.CameraToEncoderExperiment
import com.camsure.profiler.phase3.DirectModePlan
import com.camsure.profiler.phase3.ExperimentSnapshot
import com.camsure.profiler.phase3.RuntimeExperimentReportJson
import com.camsure.profiler.phase4.DiscoveredReceiver
import com.camsure.profiler.phase4.FixedReceiverEndpoint
import com.camsure.profiler.phase4.NsdReceiverDiscovery
import com.camsure.profiler.session.ProfilerSession
import com.camsure.profiler.session.ProfilerSnapshot
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private lateinit var scanButton: Button
    private lateinit var exportButton: Button
    private lateinit var settingsButton: Button
    private lateinit var statusText: TextView
    private lateinit var reportText: TextView
    private lateinit var content: LinearLayout
    private lateinit var phase3Routes: Spinner
    private lateinit var highResolutionTrial: android.widget.CheckBox
    private lateinit var exact1080Trial: android.widget.CheckBox
    private lateinit var phase4DiscoveredReceivers: Spinner
    private lateinit var phase4ReceiverIp: EditText
    private lateinit var phase4DiscoveryButton: Button
    private lateinit var phase4DiscoveryStatus: TextView
    private lateinit var preparePhase3Button: Button
    private lateinit var startPhase3Button: Button
    private lateinit var stopPhase3Button: Button
    private lateinit var exportPhase3Button: Button
    private lateinit var phase3StatusText: TextView
    private lateinit var phase3ReportText: TextView
    private var permissionMessage: String? = null
    private var permanentlyDenied = false
    private var pendingCameraAction = CameraAction.SCAN
    private var cameraRoutes: List<CameraRoute> = emptyList()
    private var directModePlans: List<DirectModePlan> = emptyList()
    private var selectedCameraRoute: CameraRoute? = null
    private var phase3Experiment: CameraToEncoderExperiment? = null
    private var phase3Snapshot = ExperimentSnapshot()
    private var phase3PlanMessage = "Prepare camera routes to inspect direct modes."
    private var runtimeReportExported = false
    private var activityDestroyed = false
    private lateinit var usbMode: android.widget.CheckBox
    private lateinit var usbAddresses: Spinner
    private lateinit var usbPort: EditText
    private lateinit var usbRefreshButton: Button
    private lateinit var usbStatus: TextView
    private var usbCandidates = emptyList<com.camsure.profiler.phase4.UsbNetworkLink>()
    private var receiverDiscovery: NsdReceiverDiscovery? = null
    private var discoveredReceivers: List<DiscoveredReceiver> = emptyList()
    private var selectedDiscoveredReceiver: DiscoveredReceiver? = null
    private lateinit var phase4ReceiverChoices: ArrayAdapter<ReceiverChoice>

    private lateinit var preview: android.view.TextureView
    private var previewSurface: android.view.Surface? = null
    private var previewRun: CameraToEncoderExperiment? = null
    private var previewStopping = false
    private var afterPreviewStop: (() -> Unit)? = null
    private var foreground = false
    private var normalRun = false
    private lateinit var homeStatus: TextView
    private lateinit var homeStart: Button
    private lateinit var settingsPage: ScrollView
    private lateinit var modes: Spinner
    private lateinit var diagnostics: LinearLayout
    private lateinit var connectionModes: android.widget.RadioGroup
    private lateinit var wirelessOption: android.widget.RadioButton
    private lateinit var usbOption: android.widget.RadioButton
    private lateinit var wirelessSettings: LinearLayout
    private lateinit var usbSettings: LinearLayout
    private lateinit var settingsNotice: TextView
    private lateinit var pcAddressLabel: TextView
    private lateinit var resolutionHint: TextView
    private var settingsOpen = false
    private var settingsLoaded = false
    private var previewSize = android.util.Size(1280, 720)
    private var lanMonitor: android.net.ConnectivityManager.NetworkCallback? = null
    private val previewDisplayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (::preview.isInitialized && preview.display?.displayId == displayId) preview.post { transformPreview() }
        }
    }
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private val prefs by lazy { getSharedPreferences("camera-settings", MODE_PRIVATE) }

    private val snapshotListener: (ProfilerSnapshot) -> Unit = { snapshot -> render(snapshot) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false) else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = android.window.OnBackInvokedCallback { leaveSettingsOrFinish() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback!!)
        }
        requestCameraPermission(CameraAction.PREPARE_PHASE3)
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        getSystemService(android.hardware.display.DisplayManager::class.java)
            .registerDisplayListener(previewDisplayListener, android.os.Handler(mainLooper))
        ProfilerSession.attach(snapshotListener)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permissionMessage = null
            permanentlyDenied = false
            if (cameraRoutes.isEmpty()) preparePhase3Routes() else ensurePreview()
        }
        if (usbMode.isChecked && !isStreamActive()) refreshUsbNetworks()
        if (phase4ReceiverIp.text.isBlank() && selectedDiscoveredReceiver == null) phase4ReceiverIp.setText(prefs.getString("address", ""))
    }

    override fun onStop() {
        foreground = false
        getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(previewDisplayListener)
        saveSettings()
        stopLanMonitor()
        stopPreview()
        stopReceiverDiscovery()
        if (!phase3Snapshot.isTerminal) phase3Experiment?.stopForBackground()
        ProfilerSession.detach(snapshotListener)
        super.onStop()
    }

    override fun onDestroy() {
        activityDestroyed = true
        stopLanMonitor()
        stopPreview()
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        stopReceiverDiscovery()
        if (!phase3Snapshot.isTerminal) phase3Experiment?.stopForBackground()
        if (previewRun == null && !isStreamActive()) { previewSurface?.release(); previewSurface = null }
        super.onDestroy()
    }

    private fun buildUi() {
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scanButton = actionButton("Scan capabilities") { requestCameraPermissionOrScan() }
        exportButton = actionButton("Export JSON") { openDocumentPicker() }
        exportButton.isEnabled = false
        settingsButton = actionButton("Open app settings") { openAppSettings() }
        settingsButton.visibility = View.GONE
        content.addView(scanButton, matchWrap())
        content.addView(exportButton, matchWrap(dp(8)))
        content.addView(settingsButton, matchWrap(dp(8)))

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF31524F.toInt())
            setPadding(dp(2), dp(10), dp(2), dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(statusText, matchWrap())

        reportText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF202A2C.toInt())
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
            text = getString(R.string.scan_explanation)
        }
        content.addView(reportText, matchWrap())
        content.addView(buildPhase3Panel(), matchWrap(dp(18)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val frame = FrameLayout(this)
        val home = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val gear = overlayButton("⚙") { showSettings(true) }.apply {
            contentDescription = "Settings"
            textSize = 28f
            setPadding(0, 0, 0, 0)
        }
        preview = android.view.TextureView(this).apply {
            surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) { ensurePreview() }
                override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) { transformPreview() }
                override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) {}
                override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                    stopPreview()
                    if (isStreamActive()) phase3Experiment?.stopForBackground()
                    return true
                }
            }
        }
        home.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        homeStatus = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), android.graphics.Color.BLACK)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        footer.addView(homeStatus, matchWrap())
        homeStart = overlayButton("Start") {
            if (isStreamActive()) stopPhase3Experiment()
            else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                if (permanentlyDenied) openAppSettings() else requestCameraPermission(CameraAction.PREPARE_PHASE3)
            } else {
                normalRun = true
                runtimeReportExported = true
                stopPreview { startPhase3Experiment() }
            }
        }
        footer.addView(homeStart, LinearLayout.LayoutParams(-2, dp(56)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        home.addView(gear, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END))
        home.addView(footer, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        home.setOnApplyWindowInsetsListener { _, insets ->
            val left: Int; val top: Int; val right: Int; val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom
            } else {
                @Suppress("DEPRECATION")
                val safe = android.graphics.Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom
            }
            gear.layoutParams = (gear.layoutParams as FrameLayout.LayoutParams).apply { topMargin = top + dp(8); marginEnd = right + dp(12) }
            footer.layoutParams = (footer.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = left + dp(12); rightMargin = right + dp(12); bottomMargin = bottom + dp(8)
            }
            insets
        }
        frame.addView(home)
        settingsPage = scroll.apply { setBackgroundColor(0xFFF5F6F7.toInt()); visibility = View.GONE }
        settingsPage.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(dp(16), dp(8) + insets.systemWindowInsetTop, dp(16), dp(8) + insets.systemWindowInsetBottom)
            insets
        }
        content.addView(actionButton("Done · Back to camera") { saveSettings(); showSettings(false); ensurePreview() }, 0)
        frame.addView(settingsPage)
        setContentView(frame)
        phase4ReceiverIp.setText(prefs.getString("address", ""))
        usbPort.setText(prefs.getString("port", "5004"))
        highResolutionTrial.isChecked = prefs.getBoolean("experimental4k", false)
        exact1080Trial.isChecked = prefs.getBoolean("exact1080", false)
        usbMode.isChecked = prefs.getBoolean("usb", false)
        settingsLoaded = true
        val saveText = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { validateSettingsInputs(); saveSettings() }
        }
        phase4ReceiverIp.addTextChangedListener(saveText)
        usbPort.addTextChangedListener(saveText)
        val saveSelection = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { saveSettings() }
        }
        modes.onItemSelectedListener = saveSelection
        usbAddresses.onItemSelectedListener = saveSelection
        renderHome()
    }

    private fun requestCameraPermissionOrScan() {
        requestCameraPermission(CameraAction.SCAN)
    }

    private fun requestCameraPermission(action: CameraAction) {
        pendingCameraAction = action
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permissionMessage = null
            permanentlyDenied = false
            executePendingCameraAction()
            return
        }

        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val askedBefore = preferences.getBoolean(KEY_ASKED_CAMERA_PERMISSION, false)
        permanentlyDenied = askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        preferences.edit().putBoolean(KEY_ASKED_CAMERA_PERMISSION, true).apply()
        val actionMessage = if (action == CameraAction.PREPARE_PHASE3) {
            "Allow Camera to show the preview and stream."
        } else {
            "Camera permission is needed only when scanning Camera2 metadata. Grant it to continue."
        }
        permissionMessage = if (permanentlyDenied) {
            "Camera permission is blocked. Open app settings, allow Camera, then retry the selected action."
        } else {
            actionMessage
        }
        render(ProfilerSession.current())
        renderHome()
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
    }

    @Deprecated("Uses the platform runtime permission callback for the minimum SDK.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            permissionMessage = null
            permanentlyDenied = false
            executePendingCameraAction()
        } else {
            val askedBefore = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ASKED_CAMERA_PERMISSION, false)
            permanentlyDenied = askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            permissionMessage = if (permanentlyDenied) {
                "Camera permission is blocked. Open app settings, allow Camera, then retry the selected action."
            } else {
                if (pendingCameraAction == CameraAction.PREPARE_PHASE3) {
                    "Camera permission was denied. Tap Allow Camera to retry."
                } else {
                    "Camera permission was denied. Tap Scan capabilities to retry."
                }
            }
            render(ProfilerSession.current())
            renderHome()
        }
    }

    private fun executePendingCameraAction() {
        when (pendingCameraAction) {
            CameraAction.SCAN -> ProfilerSession.start(applicationContext)
            CameraAction.PREPARE_PHASE3 -> preparePhase3Routes()
        }
    }

    private fun preparePhase3Routes() {
        try {
            cameraRoutes = CameraEncoderExperimentDiscovery.discoverRoutes(this)
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cameraRoutes)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            phase3Routes.adapter = adapter
            phase3Routes.isEnabled = cameraRoutes.isNotEmpty()
            if (cameraRoutes.isEmpty()) {
                selectedCameraRoute = null
                directModePlans = emptyList()
                phase3PlanMessage = "Camera2 returned no camera routes."
                if (phase3Snapshot.finishedAt == null) {
                    phase3Snapshot = ExperimentSnapshot(state = "ready", message = phase3PlanMessage)
                }
            } else {
                val defaultIndex = cameraRoutes.indexOfFirst { it.cameraId + ":" + it.physicalCameraId == prefs.getString("route", "") }
                    .takeIf { it >= 0 } ?: cameraRoutes.indexOfFirst { it.physicalCameraId == null }.coerceAtLeast(0)
                phase3Routes.setSelection(defaultIndex)
                selectedCameraRoute = cameraRoutes[defaultIndex]
                refreshPhase3Plans(selectedCameraRoute)
            }
        } catch (error: Exception) {
            cameraRoutes = emptyList()
            directModePlans = emptyList()
            phase3PlanMessage = "Camera route query failed (" + error.javaClass.simpleName + "): " + error.message
            if (phase3Snapshot.finishedAt == null) {
                phase3Snapshot = ExperimentSnapshot(
                    state = "failed",
                    message = phase3PlanMessage,
                    error = error.message
                )
            }
        }
        renderPhase3()
        ensurePreview()
    }

    private fun refreshPhase3Plans(route: CameraRoute?) {
        if (isStreamActive()) return
        selectedCameraRoute = route
        if (route == null) {
            directModePlans = emptyList()
            phase3PlanMessage = "Select a Camera2 route."
            return
        }
        try {
            directModePlans = CameraEncoderExperimentDiscovery.discoverDirectModes(this, route,
                include4k = highResolutionTrial.isChecked && !exact1080Trial.isChecked,
                trial1080 = exact1080Trial.isChecked)
            val choices = directModePlans.distinctBy { it.width to it.height }
            modes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                choices.map { "${it.width}×${it.height} · 30 fps" + if (it.width > 1920) " · Experimental" else "" })
            modes.setSelection(choices.indexOfFirst { "${it.width}x${it.height}" == prefs.getString("resolution", "") }.coerceAtLeast(0))
            val summary = directModePlans
                .distinctBy { it.width to it.height }
                .joinToString { it.width.toString() + "×" + it.height + "@30" }
            val notes = directModePlans.firstOrNull()?.diagnosticNotes.orEmpty()
            phase3PlanMessage = if (summary.isBlank()) {
                    CameraEncoderExperimentDiscovery.noDirectModeNotes(this, route).joinToString("\n")
                } else {
                    "Direct hardware H.264 modes for this route: " + summary +
                        if (notes.isEmpty()) "" else "\n" + notes.joinToString("\n")
                }
            if (phase3Snapshot.finishedAt == null) {
                phase3Snapshot = ExperimentSnapshot(
                    state = "ready",
                    message = phase3PlanMessage,
                    appVersionName = BuildConfig.VERSION_NAME,
                    routeLabel = route.label,
                    cameraId = route.cameraId,
                    physicalCameraId = route.physicalCameraId,
                    planNotes = notes
                )
            }
        } catch (error: Exception) {
            directModePlans = emptyList()
            phase3PlanMessage = "Camera/encoder mode query failed (" + error.javaClass.simpleName + "): " + error.message
            if (phase3Snapshot.finishedAt == null) {
                phase3Snapshot = ExperimentSnapshot(
                    state = "failed",
                    message = phase3PlanMessage,
                    appVersionName = BuildConfig.VERSION_NAME,
                    routeLabel = route.label,
                    cameraId = route.cameraId,
                    physicalCameraId = route.physicalCameraId,
                    error = error.message
                )
            }
        }
    }

    private fun startPhase3Experiment() {
        if (!foreground || isStreamActive()) return
        saveSettings()
        val route = selectedCameraRoute ?: run {
            phase3Snapshot = phase3Snapshot.copy(state = "failed", message = "No camera route is available. Allow Camera or retry in Diagnostics.")
            renderPhase3(); return
        }
        if (normalRun && previewSurface == null) {
            phase3Snapshot = phase3Snapshot.copy(state = "failed", message = "Preview is not ready. Return to the camera and retry.")
            renderPhase3(); return
        }
        val receiverText = phase4ReceiverIp.text?.toString()?.trim().orEmpty()
        var receiverEndpoint = if (receiverText.isBlank()) {
            selectedDiscoveredReceiver?.endpoint
        } else {
            FixedReceiverEndpoint.parseIPv4(receiverText)
        }
        if (receiverText.isNotBlank() && receiverEndpoint == null) {
            phase3PlanMessage = "Enter a numeric IPv4 address for the fixed receiver on UDP port 5004, or leave it blank for capture only."
            renderPhase3()
            return
        }
        if (usbMode.isChecked) {
            val port = usbPort.text.toString().toIntOrNull()
            if (port == null || port !in 1..65535) { phase3PlanMessage = "Enter a USB media port from 1 to 65535."; renderPhase3(); return }
            receiverEndpoint = receiverEndpoint?.copy(port = port)
        }
        if (!usbMode.isChecked && receiverText.isNotBlank() && receiverText == prefs.getString("discoveredAddress", null)) {
            receiverEndpoint = receiverEndpoint?.copy(port = prefs.getInt("discoveredPort", 5004))
        }
        val selectedUsb = if (usbMode.isChecked) usbCandidates.getOrNull(usbAddresses.selectedItemPosition - 1) else null
        if (usbMode.isChecked && (selectedUsb == null || !selectedUsb.isPresent() || receiverEndpoint == null || !selectedUsb.hasExclusivePeerRoute(receiverEndpoint.address))) {
            phase3PlanMessage = "USB not ready: enable USB tethering, refresh and explicitly select its local address, then enter the PC address on that link."
            renderPhase3(); return
        }
        if (normalRun && receiverEndpoint == null) {
            phase3Snapshot = phase3Snapshot.copy(state = "failed", message = "Select a PC in Settings or enter its IPv4 address.")
            renderPhase3(); ensurePreview(); return
        }
        val plan = directModePlans.distinctBy { it.width to it.height }.getOrNull(modes.selectedItemPosition)
        if (plan == null) {
            phase3PlanMessage = "No direct hardware H.264 mode is available for this camera route. Review the plan notes."
            if (phase3Snapshot.finishedAt == null) {
                phase3Snapshot = phase3Snapshot.copy(
                    state = "failed",
                    message = phase3PlanMessage,
                    error = "No exact camera output and hardware H.264 encoder mode at 30 fps."
                )
            }
            renderPhase3()
            return
        }
        if (!normalRun && phase3Snapshot.finishedAt != null && !runtimeReportExported) {
            phase3PlanMessage = "Export the previous runtime JSON before starting another run."
            renderPhase3()
            return
        }
        phase3Experiment?.stopByUser()
        runtimeReportExported = false
        phase3PlanMessage = "Starting the selected route."
        phase3Snapshot = ExperimentSnapshot(
            state = "preparing",
            message = "Preparing " + route.label + " at " + plan.width + "×" + plan.height + "@30.",
            appVersionName = BuildConfig.VERSION_NAME,
            routeLabel = route.label,
            cameraId = route.cameraId,
            physicalCameraId = route.physicalCameraId,
            width = plan.width,
            height = plan.height,
            encoderName = plan.encoderName,
            hardwareAccelerated = true,
            encoderConfigurationSucceeded = null,
            captureSessionConfigured = null,
            encoderWidthAlignmentPixels = plan.widthAlignment,
            encoderHeightAlignmentPixels = plan.heightAlignment,
            cameraAdvertises1080p = plan.cameraAdvertises1080p,
            exactDirect1080pSupported = plan.direct1080pSupported,
            requestedBitrateBps = plan.bitrateBps,
            requestedBitrateMode = plan.bitrateModeName,
            cameraAeFpsRange = plan.fpsRange.lower.toString() + "–" + plan.fpsRange.upper,
            planNotes = plan.diagnosticNotes
        )
        phase3Experiment = CameraToEncoderExperiment(
            context = this,
            route = route,
            plan = plan,
            onSnapshot = { snapshot ->
            phase3Snapshot = snapshot
            phase3PlanMessage = when (snapshot.state) {
                "running" -> if (normalRun) "Continuous streaming; stop when finished." else if (snapshot.transport != null) {
                    "The ten-minute RTP/H.264 run is active for " + snapshot.transport.destination + "."
                } else "The five-minute direct-surface run is active."
                "stopping" -> "The encoder is draining final output."
                "completed", "stopped", "failed" -> "Export the runtime JSON before starting another run."
                else -> snapshot.message
            }
            renderPhase3()
            if (snapshot.isTerminal) {
                stopLanMonitor()
                if (activityDestroyed) { previewSurface?.release(); previewSurface = null } else ensurePreview()
            }
            },
            receiverEndpoint = receiverEndpoint,
            usbLink = selectedUsb,
            previewSurface = if (normalRun) previewSurface else null,
            continuous = normalRun
        ).also { it.start() }
        if (receiverEndpoint != null && selectedUsb == null) startLanMonitor(receiverEndpoint)
        renderPhase3()
    }

    private fun stopPhase3Experiment() {
        phase3Experiment?.stopByUser()
        phase3Snapshot = phase3Snapshot.copy(state = "stopping", message = "Stopping and draining the encoder…")
        renderPhase3()
    }

    private fun openRuntimeReportPicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, runtimeReportFileName())
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, CREATE_RUNTIME_REPORT_REQUEST)
    }

    private fun exportRuntimeReport(uri: Uri) {
        val snapshot = phase3Snapshot
        Thread({
            val message = try {
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("The selected document cannot be opened for writing.")
                OutputStreamWriter(output, Charsets.UTF_8).use {
                    it.write(RuntimeExperimentReportJson.encode(snapshot))
                }
                "Runtime JSON exported."
            } catch (error: Exception) {
                "Runtime report export failed (" + error.javaClass.simpleName + ")."
            }
            runOnUiThread {
                if (message == "Runtime JSON exported.") runtimeReportExported = true
                phase3PlanMessage = message
                renderPhase3()
            }
        }, "camsure-phase3-report-export").start()
    }

    private fun buildPhase3Panel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        panel.addView(TextView(this).apply {
            text = "Camera and connection settings"
            textSize = 17f
            setTextColor(0xFF182022.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrap())
        panel.addView(TextView(this).apply {
            text = "Stop streaming to change camera, resolution or connection. USB: connect a cable and manually enable USB tethering in Android Settings. USB debugging is not required."
            textSize = 13f
            setTextColor(0xFF536164.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }, matchWrap())
        phase3Routes = Spinner(this).apply { isEnabled = false }
        panel.addView(phase3Routes, matchWrap())
        modes = Spinner(this)
        panel.addView(modes, matchWrap())
        highResolutionTrial = android.widget.CheckBox(this).apply {
            text = "Show experimental modes through 4K/30 (grain and drops remain unresolved)"
            setOnCheckedChangeListener { _, _ -> refreshPhase3Plans(selectedCameraRoute); renderPhase3(); saveSettings() }
        }
        panel.addView(highResolutionTrial, matchWrap())
        exact1080Trial = android.widget.CheckBox(this).apply {
            text = "Exact 1080p trial despite encoder metadata rejection (explicit opt-in)"
            setOnCheckedChangeListener { _, _ -> refreshPhase3Plans(selectedCameraRoute); renderPhase3(); saveSettings() }
        }
        panel.addView(exact1080Trial, matchWrap())
        phase4ReceiverChoices = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf(ReceiverChoice(null))
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        phase4DiscoveredReceivers = Spinner(this).apply {
            adapter = phase4ReceiverChoices
            isEnabled = false
        }
        panel.addView(phase4DiscoveredReceivers, matchWrap(dp(6)))
        phase4DiscoveryButton = actionButton("Find receivers on local network") {
            toggleReceiverDiscovery()
        }
        panel.addView(phase4DiscoveryButton, matchWrap(dp(6)))
        phase4DiscoveryStatus = TextView(this).apply {
            text = "Receiver discovery is off."
            textSize = 12f
            setTextColor(0xFF536164.toInt())
            setPadding(dp(2), dp(4), dp(2), dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        panel.addView(phase4DiscoveryStatus, matchWrap())
        usbMode = android.widget.CheckBox(this).apply {
            text = "USB Network Mode (off = Wireless)"
            setOnCheckedChangeListener { _, checked ->
                if (checked) { stopReceiverDiscovery(); selectedDiscoveredReceiver = null }
                usbStatus.text = if (checked) "Enable USB tethering manually. Refresh and confirm its local address; enter the Windows USB adapter IPv4 below." else "LAN / Wi-Fi selected."
                renderPhase3()
                saveSettings()
            }
        }
        panel.addView(usbMode, matchWrap())
        usbAddresses = Spinner(this)
        usbAddresses.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Select USB local address"))
        panel.addView(usbAddresses, matchWrap())
        usbStatus = TextView(this).apply { text = "LAN / Wi-Fi selected."; textSize = 12f }
        panel.addView(usbStatus, matchWrap())
        usbPort = EditText(this).apply { hint = "USB media port"; setText("5004"); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true) }
        panel.addView(usbPort, matchWrap())
        usbRefreshButton = actionButton("Refresh USB network addresses") { refreshUsbNetworks() }
        panel.addView(usbRefreshButton, matchWrap())
        phase4ReceiverIp = EditText(this).apply {
            hint = "Optional fixed receiver IPv4 · blank = selected receiver or capture only"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            textSize = 14f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFFF5F6F7.toInt())
        }
        panel.addView(phase4ReceiverIp, matchWrap(dp(6)))
        preparePhase3Button = actionButton("Prepare camera routes") {
            requestCameraPermission(CameraAction.PREPARE_PHASE3)
        }
        startPhase3Button = actionButton("Run timed test · capture 5 min / stream 10 min") {
            normalRun = false
            stopPreview { startPhase3Experiment() }
        }.apply { isEnabled = false }
        stopPhase3Button = actionButton("Stop camera run") {
            stopPhase3Experiment()
        }.apply { isEnabled = false }
        exportPhase3Button = actionButton("Export runtime JSON") {
            openRuntimeReportPicker()
        }.apply { isEnabled = false }
        diagnostics = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        panel.addView(actionButton("Diagnostics") { diagnostics.visibility = if (diagnostics.visibility == View.VISIBLE) View.GONE else View.VISIBLE }, matchWrap())
        panel.addView(diagnostics, matchWrap())
        for (v in listOf(scanButton, exportButton, settingsButton, statusText, reportText)) {
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            diagnostics.addView(v, matchWrap())
        }
        diagnostics.addView(preparePhase3Button, matchWrap())
        diagnostics.addView(startPhase3Button, matchWrap(dp(6)))
        diagnostics.addView(stopPhase3Button, matchWrap(dp(6)))
        diagnostics.addView(exportPhase3Button, matchWrap(dp(6)))
        phase3StatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF31524F.toInt())
            setPadding(dp(2), dp(9), dp(2), dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        diagnostics.addView(phase3StatusText, matchWrap())
        phase3ReportText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF202A2C.toInt())
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFFF5F6F7.toInt())
        }
        diagnostics.addView(phase3ReportText, matchWrap())
        phase3Routes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                refreshPhase3Plans(null)
                renderPhase3()
            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val route = parent?.getItemAtPosition(position) as? CameraRoute
                val changed = route != selectedCameraRoute
                refreshPhase3Plans(route)
                if (changed) stopPreview { ensurePreview() } else ensurePreview()
                renderPhase3()
                saveSettings()
            }
        }
        phase4DiscoveredReceivers.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedDiscoveredReceiver = null
            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val choice = parent?.getItemAtPosition(position) as? ReceiverChoice
                selectedDiscoveredReceiver = choice?.receiver
                if (selectedDiscoveredReceiver != null && phase4ReceiverIp.text.isNotBlank()) {
                    phase4ReceiverIp.text.clear()
                }
                renderPhase3()
                saveSettings()
            }
        }
        arrangeSettings(panel)
        renderPhase3()
        return panel
    }

    private fun arrangeSettings(panel: LinearLayout) {
        // Reuse the existing controls/listeners; only their presentation changes.
        panel.removeAllViews()
        panel.addView(settingsLabel("Settings", heading = true), matchWrap())
        settingsNotice = settingsLabel("Changes are saved automatically.")
        panel.addView(settingsNotice, matchWrap(dp(6)))
        panel.addView(settingsLabel("Connection", heading = true), matchWrap(dp(20)))
        wirelessOption = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Wireless"; minHeight = dp(48) }
        usbOption = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "USB Network"; minHeight = dp(48) }
        connectionModes = android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(wirelessOption, LinearLayout.LayoutParams(0, -2, 1f))
            addView(usbOption, LinearLayout.LayoutParams(0, -2, 1f))
            check(wirelessOption.id)
            setOnCheckedChangeListener { _, id ->
                val selectedUsb = id == usbOption.id
                if (usbMode.isChecked != selectedUsb) usbMode.isChecked = selectedUsb
            }
        }
        panel.addView(connectionModes, matchWrap(dp(4)))

        wirelessSettings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wirelessSettings.addView(settingsLabel("Keep the phone and PC on the same local network. Start the CamSure receiver on your PC, then find it below."), matchWrap(dp(6)))
        addSettingsField(wirelessSettings, "OBS PC", phase4DiscoveredReceivers)
        (phase4DiscoveryButton.parent as? android.view.ViewGroup)?.removeView(phase4DiscoveryButton)
        wirelessSettings.addView(phase4DiscoveryButton, matchWrap(dp(6)))
        (phase4DiscoveryStatus.parent as? android.view.ViewGroup)?.removeView(phase4DiscoveryStatus)
        wirelessSettings.addView(phase4DiscoveryStatus, matchWrap())
        panel.addView(wirelessSettings, matchWrap())

        usbSettings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        usbSettings.addView(settingsLabel("1. Connect the USB cable and enable USB tethering in Android Settings.\n2. Choose the phone's tethering interface/address.\n3. Enter the PC's USB adapter IPv4 below.\nUSB debugging is not required."), matchWrap(dp(6)))
        addSettingsField(usbSettings, "Phone USB interface / address", usbAddresses)
        usbSettings.addView(usbRefreshButton, matchWrap(dp(6)))
        usbSettings.addView(usbStatus, matchWrap())
        addSettingsField(usbSettings, "Media port · match the PC receiver", usbPort)
        panel.addView(usbSettings, matchWrap())

        phase4ReceiverIp.hint = "e.g. 192.168.1.10"
        pcAddressLabel = settingsLabel("PC IPv4 · manual fallback")
        if (phase4ReceiverIp.id == View.NO_ID) phase4ReceiverIp.id = View.generateViewId()
        pcAddressLabel.labelFor = phase4ReceiverIp.id
        panel.addView(pcAddressLabel, matchWrap(dp(12)))
        panel.addView(phase4ReceiverIp, matchWrap(dp(4)))

        panel.addView(settingsLabel("Camera", heading = true), matchWrap(dp(20)))
        addSettingsField(panel, "Camera / lens", phase3Routes)
        addSettingsField(panel, "Resolution · 30 fps", modes)
        resolutionHint = settingsLabel("Only eligible modes for this camera are shown.")
        panel.addView(resolutionHint, matchWrap(dp(6)))

        // Trials remain opt-in, beneath the existing Diagnostics entry point.
        diagnostics.addView(highResolutionTrial, 0, matchWrap())
        diagnostics.addView(exact1080Trial, 1, matchWrap())
        diagnostics.addView(settingsLabel("Trials do not guarantee runtime support. Exact 1080p does not override metadata for other modes. 4K remains experimental. Leave Wireless PC selection/address blank for a capture-only timed test."), 2, matchWrap(dp(6)))
        panel.addView(actionButton("Diagnostics ▸") {
            diagnostics.visibility = if (diagnostics.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }, matchWrap(dp(20)))
        panel.addView(diagnostics, matchWrap(dp(6)))
    }

    private fun settingsLabel(label: String, heading: Boolean = false) = TextView(this).apply {
        text = label
        textSize = if (heading) 18f else 13f
        setTextColor(if (heading) 0xFF182022.toInt() else 0xFF536164.toInt())
        if (heading) typeface = Typeface.DEFAULT_BOLD
    }

    private fun addSettingsField(parent: LinearLayout, label: String, field: View) {
        (field.parent as? android.view.ViewGroup)?.removeView(field)
        if (field.id == View.NO_ID) field.id = View.generateViewId()
        parent.addView(settingsLabel(label).apply { labelFor = field.id }, matchWrap(dp(12)))
        parent.addView(field, matchWrap(dp(4)))
    }

    private fun validateSettingsInputs() {
        val address = phase4ReceiverIp.text.toString().trim()
        phase4ReceiverIp.error = if (address.isNotEmpty() && FixedReceiverEndpoint.parseIPv4(address) == null)
            "Enter a numeric IPv4 address, such as 192.168.1.10." else null
        val port = usbPort.text.toString().toIntOrNull()
        usbPort.error = if (usbMode.isChecked && (port == null || port !in 1..65535)) "Use a port from 1 to 65535." else null
    }

    private fun toggleReceiverDiscovery() {
        if (usbMode.isChecked) { usbStatus.text = "USB mode uses explicit PC address selection."; return }
        if (receiverDiscovery?.isRunning == true) {
            stopReceiverDiscovery()
            return
        }
        receiverDiscovery?.close()
        receiverDiscovery = null
        try {
            receiverDiscovery = NsdReceiverDiscovery(this) { receivers, message ->
                updateDiscoveredReceivers(receivers, message)
            }.also { it.start() }
            phase4DiscoveryStatus.text = "Searching the local network for CamSure receivers…"
        } catch (error: Exception) {
            receiverDiscovery = null
            phase4DiscoveryStatus.text = "Receiver discovery is unavailable (${error.javaClass.simpleName})."
        }
        renderPhase3()
    }

    private fun stopReceiverDiscovery() {
        receiverDiscovery?.close()
        receiverDiscovery = null
        if (!::phase4ReceiverChoices.isInitialized || activityDestroyed) return
        selectedDiscoveredReceiver?.let { if (phase4ReceiverIp.text.isBlank()) phase4ReceiverIp.setText(it.endpoint.address.hostAddress) }
        discoveredReceivers = emptyList()
        selectedDiscoveredReceiver = null
        setReceiverChoices(emptyList(), null)
        phase4DiscoveryStatus.text = "Receiver discovery stopped."
        renderPhase3()
    }

    private fun updateDiscoveredReceivers(receivers: List<DiscoveredReceiver>, message: String) {
        if (activityDestroyed || !::phase4ReceiverChoices.isInitialized) return
        val previous = selectedDiscoveredReceiver
        val previousName = previous?.serviceName
        discoveredReceivers = receivers
        selectedDiscoveredReceiver = receivers.firstOrNull { it.serviceName == previousName }
        if (previous != null && selectedDiscoveredReceiver == null && phase4ReceiverIp.text.isBlank()) phase4ReceiverIp.setText(previous.endpoint.address.hostAddress)
        setReceiverChoices(receivers, selectedDiscoveredReceiver)
        phase4DiscoveryStatus.text = message
        renderPhase3()
    }

    private fun setReceiverChoices(receivers: List<DiscoveredReceiver>, selected: DiscoveredReceiver?) {
        phase4ReceiverChoices.clear()
        phase4ReceiverChoices.add(ReceiverChoice(null))
        receivers.forEach { phase4ReceiverChoices.add(ReceiverChoice(it)) }
        val selectedPosition = selected?.let { receiver ->
            receivers.indexOfFirst { it.serviceName == receiver.serviceName }.takeIf { it >= 0 }?.plus(1)
        } ?: 0
        phase4DiscoveredReceivers.setSelection(selectedPosition)
    }

    private fun renderPhase3() {
        if (activityDestroyed || !::phase3StatusText.isInitialized) return
        val active = phase3Snapshot.state == "preparing" ||
            phase3Snapshot.state == "opening_camera" ||
            phase3Snapshot.state == "running" ||
            phase3Snapshot.state == "stopping"
        phase3Routes.isEnabled = !active && cameraRoutes.isNotEmpty()
        highResolutionTrial.isEnabled = !active
        exact1080Trial.isEnabled = !active
        phase4DiscoveredReceivers.isEnabled = !active && discoveredReceivers.isNotEmpty()
        phase4DiscoveryButton.text = if (receiverDiscovery?.isRunning == true) {
            "Stop searching"
        } else {
            "Find PCs"
        }
        usbPort.isEnabled = !active && usbMode.isChecked
        usbRefreshButton.isEnabled = !active && usbMode.isChecked
        phase4DiscoveryButton.isEnabled = !active && !usbMode.isChecked
        usbMode.isEnabled = !active
        usbAddresses.isEnabled = !active && usbMode.isChecked
        phase4ReceiverIp.isEnabled = !active
        preparePhase3Button.isEnabled = !active
        startPhase3Button.isEnabled = !active && selectedCameraRoute != null &&
            directModePlans.isNotEmpty() &&
            (phase3Snapshot.finishedAt == null || runtimeReportExported)
        stopPhase3Button.isEnabled = phase3Snapshot.state == "preparing" ||
            phase3Snapshot.state == "opening_camera" || phase3Snapshot.state == "running"
        exportPhase3Button.isEnabled = phase3Snapshot.isTerminal && phase3Snapshot.finishedAt != null
        if (active) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val exportReminder = if (phase3Snapshot.finishedAt != null && !runtimeReportExported) {
            "Export this run before starting another."
        } else ""
        phase3StatusText.text = listOf(phase3Snapshot.message, phase3PlanMessage, exportReminder)
            .filter { it.isNotBlank() }.distinct().joinToString("\n")
        modes.isEnabled = !active
        if (::connectionModes.isInitialized) {
            wirelessOption.isEnabled = !active
            usbOption.isEnabled = !active
            connectionModes.check(if (usbMode.isChecked) usbOption.id else wirelessOption.id)
            wirelessSettings.visibility = if (usbMode.isChecked) View.GONE else View.VISIBLE
            usbSettings.visibility = if (usbMode.isChecked) View.VISIBLE else View.GONE
            pcAddressLabel.text = if (usbMode.isChecked) "PC USB adapter IPv4 · required" else "PC IPv4 · manual fallback"
            settingsNotice.text = if (active) "Streaming is active. Stop on the camera screen to change these settings." else "Changes are saved automatically. Tap Done to return to the camera."
            resolutionHint.text = if (directModePlans.isEmpty()) "No eligible encoder mode found. Open Diagnostics to inspect or retry."
                else if (directModePlans.first().cameraAdvertises1080p && directModePlans.none { it.width == 1920 && it.height == 1080 })
                    "1080p is camera-advertised but encoder metadata rejects it. The explicit trial is in Diagnostics."
                else "Eligible modes for this camera. Experimental options are in Diagnostics."
        }
        phase3ReportText.text = renderPhase3Snapshot(phase3Snapshot, directModePlans)
        renderHome()
        ensurePreview()
    }

    private fun renderPhase3Snapshot(snapshot: ExperimentSnapshot, plans: List<DirectModePlan>): String = buildString {
        appendLine("DEVICE")
        appendLine("  " + snapshot.deviceManufacturer + " " + snapshot.deviceModel +
            " · Android " + snapshot.androidVersion + " (API " + snapshot.apiLevel + ")")
        appendLine("  Build " + snapshot.buildId + " · app " + snapshot.appVersionName)
        appendLine()
        appendLine("DIRECT CAMERA → HARDWARE H.264")
        appendLine("  State: " + snapshot.state)
        appendLine("  Route: " + (snapshot.routeLabel ?: selectedCameraRoute?.label ?: "not prepared"))
        appendLine("  Encoder configured: " + (snapshot.encoderConfigurationSucceeded?.toString() ?: "pending") +
            " · capture session configured: " + (snapshot.captureSessionConfigured?.toString() ?: "pending"))
        appendLine("  Encoder alignment: " +
            (snapshot.encoderWidthAlignmentPixels?.toString() ?: "—") + "×" +
            (snapshot.encoderHeightAlignmentPixels?.toString() ?: "—") +
            " · camera 1080p advertised " + (snapshot.cameraAdvertises1080p?.toString() ?: "unknown") +
            " · exact direct 1080p supported " + (snapshot.exactDirect1080pSupported?.toString() ?: "unknown"))
        if (snapshot.width != null && snapshot.height != null) {
            appendLine("  Requested: " + snapshot.width + "×" + snapshot.height + " @ " + snapshot.requestedFps +
                " fps · AE range " + (snapshot.cameraAeFpsRange ?: "not selected") +
                " · " + (snapshot.encoderName ?: "encoder not started") +
                " · " + (snapshot.requestedBitrateMode ?: "bitrate mode pending") +
                " · " + (snapshot.requestedBitrateBps?.let { (it / 1000).toString() + " kbps requested" } ?: "bitrate pending"))
        }
        appendLine("  Capture: " + snapshot.captureFrames + " frames · " + number(snapshot.captureFps) + " fps · dropped estimate " + snapshot.captureDroppedEstimate)
        appendLine("  Encoded: " + snapshot.encodedFrames + " frames · " + number(snapshot.encodedFps) + " fps · dropped estimate " + snapshot.encodedDroppedEstimate)
        appendLine("  Capture failures: " + snapshot.captureFailureCount)
        appendLine("  Keyframes: " + snapshot.keyframes + " · average interval " + number(snapshot.averageKeyframeIntervalSeconds) + " s")
        appendLine("  Encoded average bitrate: " + (snapshot.measuredBitrateBps?.let { (it / 1000).toString() + " kbps" } ?: "not available"))
        appendLine("  Encoder output: " + (snapshot.outputWidth?.toString() ?: "—") + "×" + (snapshot.outputHeight?.toString() ?: "—") +
            " · visible crop " + (snapshot.visibleCrop ?: "pending"))
        appendLine("  Profile/level: " + (snapshot.outputProfile?.toString() ?: "not reported") + "/" + (snapshot.outputLevel?.toString() ?: "not reported"))
        appendLine("  Timestamp order errors: capture " + snapshot.nonMonotonicCaptureTimestamps +
            " · encoder " + snapshot.nonMonotonicEncodedTimestamps)
        appendLine("  End of stream: " + (snapshot.endOfStreamSeen?.toString() ?: "pending") +
            " · app frame queue " + snapshot.appFrameQueueDepth +
            " · codec queue " + snapshot.codecInternalQueueDepth)
        appendLine("  Process CPU: avg " + number(snapshot.processCpuAveragePercent) + "% / peak " +
            number(snapshot.processCpuPeakPercent) + "% · thermal " +
            (snapshot.thermalStatusEnd ?: "not available"))
        appendLine("  Battery: " + (snapshot.batteryPercentEnd?.toString() ?: "—") + "% · " +
            (snapshot.batteryTemperatureCEnd?.let { number(it) + " °C" } ?: "temperature unavailable") +
            " · charging " + (snapshot.chargingEnd?.toString() ?: "unknown"))
        appendLine("  Duration: " + number(snapshot.durationSeconds) + " s")
        snapshot.transport?.let { transport ->
            appendLine()
            appendLine("PHASE 4 · RTP/H.264 UDP")
            appendLine("  Transport: " + transport.transportMode + " · link " + transport.linkState + " · local " + transport.localInterface + " / " + transport.localAddress + " · effective send buffer " + transport.effectiveSendBufferBytes)
            appendLine("  State: " + transport.state + " · receiver " + transport.destination +
                " · payload type " + transport.payloadType + " · packetization mode " + transport.packetizationMode)
            appendLine("  SSRC: " + transport.ssrc + " · RTP clock " + transport.timestampClockHz + " Hz · datagram cap " + transport.rtpDatagramLimitBytes + " bytes")
            appendLine("  Sent: " + transport.accessUnitsSent + " access units / " + transport.packetsSent + " packets · send errors " + transport.sendFailures + " · stale in-flight " + transport.inFlightStaleDrops)
            appendLine("  Current sender: " + transport.currentSenderFps + " FPS · RTP bitrate " + transport.currentRtpBitrateBps + " bps (includes RTP headers)")
            appendLine("  Source PTS: origin " + (transport.timestampOriginPresentationTimeUs?.toString() ?: "pending") +
                " µs · latest " + (transport.lastSentPresentationTimeUs?.toString() ?: "pending") + " µs")
            appendLine("  Sender queue: " + transport.queue.depth + " units / " + transport.queue.currentBytes + " bytes · high water " +
                transport.queue.highWaterDepth + " units / " + transport.queue.highWaterBytes + " bytes · oldest " +
                transport.queue.oldestItemAgeMs + " ms · age high water " + transport.queue.highWaterAgeMs +
                " ms / max " + transport.queue.maxQueueAgeMs + " ms")
            appendLine("  Sender drops: overflow " + transport.queue.droppedForOverflow + " · stale " + transport.queue.droppedAsStale +
                " · awaiting keyframe " + transport.queue.droppedWhileWaitingForKeyFrame + " · oversized " + transport.queue.droppedOversized +
                " · recovery flush " + transport.queue.recoveryFlushUnits)
            appendLine("  Sink: " + transport.sink.completedAccessUnits + " complete access units · incomplete " +
                transport.sink.incompleteUnitsDropped + " · oversized " + transport.sink.oversizedUnitsDropped +
                " · malformed " + transport.sink.malformedOutputDropped)
            appendLine("  Codec config: version " + transport.sink.codecConfigurationVersion + " · SPS " +
                transport.sink.codecConfigurationSpsCount + " / PPS " + transport.sink.codecConfigurationPpsCount +
                " · missing-config drops " + transport.missingCodecConfigurationDrops)
            appendLine("  Receiver delivery counters are reported by the Windows receiver; sender counters do not prove packet receipt.")
        }
        if (plans.isNotEmpty()) {
            appendLine()
            appendLine("Exact direct 30 fps modes for " +
                (selectedCameraRoute?.label ?: "selected route") + ": " +
                plans.distinctBy { it.width to it.height }
                    .joinToString { it.width.toString() + "×" + it.height + " " + "H.264" })
        }
        if (snapshot.planNotes.isNotEmpty()) {
            appendLine()
            appendLine("PLAN NOTES")
            snapshot.planNotes.forEach { appendLine("  " + it) }
        }
        appendLine()
        appendLine(snapshot.pathDescription)
        appendLine(snapshot.timestampCaveat)
        if (snapshot.error != null) appendLine("Error: " + snapshot.error)
        appendLine("GPU utilization is not exposed by the public app APIs used here.")
    }

    private fun isStreamActive() = phase3Experiment != null && com.camsure.profiler.session.CameraRunPolicy.active(phase3Snapshot.state)

    private fun showSettings(show: Boolean) {
        settingsOpen = show
        settingsPage.visibility = if (show) View.VISIBLE else View.GONE
    }

    @Deprecated("Platform back navigation for API 26")
    @android.annotation.SuppressLint("GestureBackNavigation") // API 33+ uses the registered platform callback.
    override fun onBackPressed() {
        leaveSettingsOrFinish()
    }

    private fun leaveSettingsOrFinish() {
        if (settingsOpen) { saveSettings(); showSettings(false); ensurePreview() } else finish()
    }

    private fun saveSettings() {
        if (!settingsLoaded || !::modes.isInitialized) return
        val address = phase4ReceiverIp.text.toString().trim()
        val editor = prefs.edit().putBoolean("usb", usbMode.isChecked)
            .putBoolean("experimental4k", highResolutionTrial.isChecked).putBoolean("exact1080", exact1080Trial.isChecked)
        if (address.isBlank() || FixedReceiverEndpoint.parseIPv4(address) != null) editor.putString("address", address)
        usbPort.text.toString().toIntOrNull()?.takeIf { it in 1..65535 }?.let { editor.putString("port", it.toString()) }
        selectedCameraRoute?.let { editor.putString("route", it.cameraId + ":" + it.physicalCameraId) }
        directModePlans.distinctBy { it.width to it.height }.getOrNull(modes.selectedItemPosition)?.let {
            editor.putString("resolution", "${it.width}x${it.height}")
        }
        selectedDiscoveredReceiver?.let {
            editor.putString("address", it.endpoint.address.hostAddress)
            editor.putString("discoveredAddress", it.endpoint.address.hostAddress)
            editor.putInt("discoveredPort", it.endpoint.port)
        }
        usbCandidates.getOrNull(usbAddresses.selectedItemPosition - 1)?.let { editor.putString("usbLocal", it.toString() + ":" + it.interfaceIndex) }
        editor.apply()
    }

    private fun renderHome() {
        if (!::homeStatus.isInitialized) return
        val active = isStreamActive()
        homeStart.text = if (active) "Stop" else if (permissionMessage != null) "Allow Camera" else "Start"
        homeStart.isEnabled = phase3Snapshot.state != "stopping" && !previewStopping
        homeStatus.text = permissionMessage ?: when {
            phase3Snapshot.state == "failed" -> "Connection failure: ${phase3Snapshot.message}"
            phase3Snapshot.transport?.state == "send_error" -> "Connection failure: UDP send error. Stop, check the network and restart."
            phase3Snapshot.state == "running" && phase3Snapshot.transport == null -> "Timed camera test · preview resumes when stopped."
            phase3Snapshot.state == "running" && phase3Snapshot.encodedFrames > 0 -> "Streaming · ${phase3Snapshot.transport?.destination ?: "timed capture"}\nSender active; check OBS for reception."
            active -> if (phase3Snapshot.state == "stopping") "Stopping…" else "Connecting…"
            else -> "Stopped · ${if (usbMode.isChecked) "USB Network Mode" else "Wireless"}" +
                if (phase3PlanMessage.startsWith("Enter") || phase3PlanMessage.startsWith("USB not ready") || phase3PlanMessage.startsWith("No direct")) "\nConnection failure: $phase3PlanMessage"
                else if (phase3Snapshot.state == "stopped") "\n${phase3Snapshot.message}" else ""
        }
    }

    private fun stopPreview(then: (() -> Unit)? = null) {
        if (previewRun == null && !previewStopping) { then?.invoke(); return }
        afterPreviewStop = then
        if (!previewStopping) { previewStopping = true; previewRun?.stopByUser() }
        renderHome()
    }

    private fun ensurePreview() {
        if (!foreground || activityDestroyed || !::preview.isInitialized || !preview.isAvailable ||
            previewRun != null || previewStopping || isStreamActive() ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val route = selectedCameraRoute ?: return
        try {
            val manager = getSystemService(android.hardware.camera2.CameraManager::class.java)
            val info = manager.getCameraCharacteristics(route.characteristicsCameraId)
            val sizes = info.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
            previewSize = sizes.filter { it.width <= 1280 && it.height <= 720 }.maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height } ?: return
            preview.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface?.release()
            previewSurface = android.view.Surface(preview.surfaceTexture)
            transformPreview()
            val ranges = info.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val fps = ranges.firstOrNull { it.upper == 30 } ?: ranges.firstOrNull() ?: return
            val plan = DirectModePlan(previewSize.width, previewSize.height, "preview", 1, 1, 0, "preview", 0, fps, false, false, emptyList())
            previewRun = CameraToEncoderExperiment(this, route, plan, { snapshot ->
                if (snapshot.isTerminal) {
                    previewRun = null; previewStopping = false
                    val continuation = afterPreviewStop; afterPreviewStop = null
                    renderHome()
                    if (snapshot.state == "failed") homeStatus.text = "Preview unavailable: ${snapshot.message}"
                    if (activityDestroyed) { previewSurface?.release(); previewSurface = null }
                    if (continuation != null) continuation() else if (snapshot.state != "failed") ensurePreview()
                }
            }, previewSurface = previewSurface, previewOnly = true)
            previewRun?.start()
        } catch (error: Exception) { homeStatus.text = "Preview unavailable: ${error.message}" }
    }

    private fun transformPreview() {
        val route = selectedCameraRoute ?: return
        val info = getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics(route.characteristicsCameraId)
        val sensor = info.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation * 90
        if (preview.width == 0 || preview.height == 0) return
        val geometry = PreviewGeometry.centerCrop(preview.width, preview.height,
            previewSize.width, previewSize.height, sensor, rotation)
        val centerX = preview.width / 2f; val centerY = preview.height / 2f
        val matrix = android.graphics.Matrix()
        matrix.setScale(geometry.scaleX, geometry.scaleY, centerX, centerY)
        matrix.postRotate(geometry.rotationDegrees, centerX, centerY)
        preview.setTransform(matrix)
    }

    @Suppress("DEPRECATION") // API 26+ inventory includes local Wi-Fi without internet validation.
    private fun startLanMonitor(endpoint: FixedReceiverEndpoint) {
        stopLanMonitor()
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val selected = cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps != null && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                cm.getLinkProperties(network)?.routes?.any { it.matches(endpoint.address) } == true
        }
        val owner = phase3Experiment
        if (selected == null) { owner?.stopForLinkLoss(); return }
        val localAddresses = cm.getLinkProperties(selected)?.linkAddresses
        lanMonitor = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onLost(network: android.net.Network) {
                if (network == selected) runOnUiThread { owner?.stopForLinkLoss() }
            }
            override fun onLinkPropertiesChanged(network: android.net.Network, properties: android.net.LinkProperties) {
                if (network == selected && (properties.linkAddresses != localAddresses || properties.routes.none { it.matches(endpoint.address) }))
                    runOnUiThread { owner?.stopForLinkLoss() }
            }
        }.also { callback ->
            val wifi = cm.getNetworkCapabilities(selected)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            try {
                cm.registerNetworkCallback(android.net.NetworkRequest.Builder().addTransportType(
                    if (wifi) android.net.NetworkCapabilities.TRANSPORT_WIFI else android.net.NetworkCapabilities.TRANSPORT_ETHERNET
                ).build(), callback)
            } catch (_: Exception) { owner?.stopForLinkLoss() }
        }
    }

    private fun stopLanMonitor() {
        lanMonitor?.let { callback ->
            try { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
        lanMonitor = null
    }

    private fun refreshUsbNetworks() {
        if (isStreamActive()) return
        try {
            usbCandidates = com.camsure.profiler.phase4.UsbNetworkLink.candidates(this)
            usbAddresses.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listOf("Select USB local address") + usbCandidates.map { it.toString() })
            val restored = com.camsure.profiler.session.CameraRunPolicy.restoredIndex(
                usbCandidates.map { it.toString() + ":" + it.interfaceIndex }, prefs.getString("usbLocal", null))
            usbAddresses.setSelection(restored?.plus(1) ?: 0)
            usbStatus.text = if (usbCandidates.isEmpty()) "Enable USB tethering and connect the PC. USB debugging is not required."
                else "Confirm the tethering interface/address. Changed or missing selections require explicit reselection."
        } catch (error: Exception) { usbStatus.text = "Network inventory failed: ${error.javaClass.simpleName}" }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        preview.post { transformPreview() }
    }

    private fun number(value: Double?): String = value?.let { String.format("%.2f", it) } ?: "—"

    private data class ReceiverChoice(val receiver: DiscoveredReceiver?) {
        override fun toString(): String = receiver?.displayName ?: "Select a PC or enter its address below"
    }

    private fun runtimeReportFileName(): String {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val prefix = if (phase3Snapshot.transport?.transportMode == "USB_NETWORK") "camsure-usb-network-" else if (phase3Snapshot.transport != null) "camsure-phase4-lan-" else "camsure-phase3-runtime-"
        return prefix + stamp + ".json"
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun openDocumentPicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, reportFileName())
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, CREATE_REPORT_REQUEST)
    }

    @Deprecated("Uses the platform document picker result callback without an AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == CREATE_RUNTIME_REPORT_REQUEST) {
            exportRuntimeReport(uri)
            return
        }
        if (requestCode == CREATE_REPORT_REQUEST) {
            val report = ProfilerSession.current().report
            if (report == null) {
                statusText.text = getString(R.string.export_no_report)
                return
            }
            ProfilerSession.export(applicationContext, uri, report)
        }
    }

    private fun render(snapshot: ProfilerSnapshot) {
        if (activityDestroyed || !::statusText.isInitialized) return
        scanButton.isEnabled = !snapshot.scanning
        scanButton.text = if (snapshot.scanning) "Scanning…" else if (snapshot.report == null) "Scan capabilities" else "Rescan capabilities"
        exportButton.isEnabled = snapshot.report != null && !snapshot.scanning && !snapshot.exporting
        exportButton.text = if (snapshot.exporting) "Exporting…" else "Export JSON"
        settingsButton.visibility = if (permanentlyDenied) View.VISIBLE else View.GONE

        val statusParts = listOfNotNull(
            permissionMessage,
            snapshot.error,
            snapshot.progress.takeIf { it.isNotBlank() },
            snapshot.exportMessage
        )
        statusText.text = statusParts.joinToString("\n")
        snapshot.report?.let { reportText.text = renderReport(it) }
        renderPhase3()
    }

    private fun renderReport(report: CapabilityReport): String = buildString {
        appendLine("DEVICE")
        appendLine("  ${report.device.manufacturer} ${report.device.model}")
        appendLine("  Android ${report.device.androidVersion} (API ${report.device.apiLevel})")
        appendLine("  App ${report.device.appVersionName}  •  ${report.collectionTime}")
        appendLine()

        val cameras = report.cameras.value.orEmpty()
        appendLine("CAMERAS — ${cameras.size} (${statusLabel(report.cameras.status)})")
        cameras.forEachIndexed { index, camera ->
            appendCamera(index + 1, camera)
        }
        if (cameras.isEmpty()) {
            appendLine("  ${describe(report.cameras) { it.toString() }}")
        }
        appendLine()

        val encoders = report.videoEncoders.value.orEmpty()
        appendLine("H.264 / HEVC ENCODERS — ${encoders.size} (${statusLabel(report.videoEncoders.status)})")
        encoders.forEachIndexed { index, encoder ->
            appendEncoder(index + 1, encoder)
        }
        if (encoders.isEmpty()) appendLine("  ${describe(report.videoEncoders) { it.toString() }}")

        if (report.issues.isNotEmpty()) {
            appendLine()
            appendLine("COLLECTION ISSUES")
            report.issues.take(MAX_UI_ISSUES).forEach { issue ->
                append("  ${statusLabel(issue.status)} · ${issue.scope}")
                issue.subject?.let { append(" · $it") }
                appendLine(": ${issue.message}")
            }
            if (report.issues.size > MAX_UI_ISSUES) appendLine("  … ${report.issues.size - MAX_UI_ISSUES} more in JSON.")
        }

        appendLine()
        appendLine("Metadata is not a capture test. Camera and encoder lists do not prove a compatible end-to-end mode.")
    }

    private fun StringBuilder.appendCamera(index: Int, camera: CameraProfile) {
        appendLine("  [$index] Camera ${camera.cameraId} · ${describe(camera.lensFacing) { it }} · ${describe(camera.hardwareSupportLevel) { it }}")
        appendLine("      Logical: ${describe(camera.logicalCamera) { it.toString() }} · physical IDs: ${describe(camera.physicalCameras.ids) { it.joinToString() }}")
        camera.physicalCameras.cameras.forEach { physical ->
            appendLine("      Physical ${physical.cameraId}: facing ${describe(physical.lensFacing) { it }}, focal ${describe(physical.focalLengthsMm) { it.joinToString() + " mm" }}")
        }
        appendLine("      Focal lengths: ${describe(camera.focalLengthsMm) { it.joinToString { value -> "$value mm" } }}")
        appendLine("      Apertures: ${describe(camera.aperturesFNumber) { it.joinToString { value -> "f/$value" } }}")
        appendLine("      Sensor: ${describe(camera.sensorSizeMm) { "${it.width} × ${it.height} mm" }} · orientation ${describe(camera.sensorOrientationDegrees) { "$it°" }}")
        appendLine("      Capabilities: ${describe(camera.advertisedCapabilities) { it.joinToString() }}")
        appendLine("      AE FPS ranges: ${describe(camera.aeTargetFpsRanges) { formatRanges(it) }}")
        appendLine("      AF: ${describe(camera.controls.autofocusModes) { it.joinToString() }} · min focus ${describe(camera.controls.minimumFocusDistanceDiopters) { "$it D" }}")
        appendLine("      Exposure steps: ${describe(camera.controls.exposureCompensationSteps) { formatRange(it) }}; step ${describe(camera.controls.exposureCompensationStepEv) { "${it.decimal} EV" }}")
        appendLine("      AE lock ${describe(camera.controls.aeLockAvailable) { it.toString() }}; modes ${describe(camera.controls.aeModes) { it.joinToString() }}")
        appendLine("      AWB lock ${describe(camera.controls.awbLockAvailable) { it.toString() }}; modes ${describe(camera.controls.awbModes) { it.joinToString() }}")
        appendLine("      Manual sensor ${describe(camera.controls.manualSensorCapability) { it.toString() }}; ISO ${describe(camera.controls.isoSensitivityRange) { formatRange(it) }}; exposure ${describe(camera.controls.exposureTimeRangeNs) { formatRange(it) + " ns" }}")
        appendLine("      Zoom: ${describe(camera.controls.zoom.effectiveRange) { formatRange(it) }} via ${camera.controls.zoom.effectiveRangeSource ?: statusLabel(camera.controls.zoom.effectiveRange.status)}")
        appendLine("      Flash ${describe(camera.controls.flashUnitAvailable) { it.toString() }}; torch ${describe(camera.controls.torchAvailable) { it.toString() }}")
        appendLine("      OIS ${describe(camera.controls.opticalStabilizationModes) { it.joinToString() }}; video stabilization ${describe(camera.controls.videoStabilizationModes) { it.joinToString() }}")
        appendLine("      Dynamic range: ${describe(camera.dynamicRangeProfiles) { it.joinToString { profile -> profile.second } }}")

        val formats = camera.streamFormats.value.orEmpty()
        if (formats.isEmpty()) {
            appendLine("      Stream formats: ${describe(camera.streamFormats) { it.toString() }}")
        } else {
            appendLine("      Stream formats (${formats.size}):")
            formats.forEach { format ->
                val allSizes = format.sizes.value.orEmpty()
                val sizes = allSizes.take(MAX_UI_STREAM_SIZES)
                append("        ${format.formatName} code ${format.formatCode}${if (format.relevantToVideoCapture) " · video-relevant" else ""}: ")
                if (sizes.isEmpty()) append(statusLabel(format.sizes.status))
                else append(sizes.joinToString { streamSize ->
                    val size = "${streamSize.size.width}×${streamSize.size.height}"
                    val duration = streamSize.minimumFrameDurationNs.value?.let { ", min ${it} ns" }.orEmpty()
                    val stall = streamSize.stallDurationNs.value?.let { ", stall ${it} ns" }.orEmpty()
                    "$size$duration$stall"
                })
                if (allSizes.size > sizes.size) append(" … +${allSizes.size - sizes.size} sizes in JSON")
                appendLine()
            }
        }
        val highSpeed = camera.constrainedHighSpeedModes.value.orEmpty()
        appendLine("      Constrained high speed: ${if (highSpeed.isEmpty()) describe(camera.constrainedHighSpeedModes) { it.toString() } else highSpeed.joinToString { formatHighSpeed(it) }}")
        if (camera.issues.isNotEmpty()) appendLine("      Collection issues: ${camera.issues.size} (details in JSON)")
    }

    private fun StringBuilder.appendEncoder(index: Int, encoder: EncoderProfile) {
        appendLine("  [$index] ${encoder.codecName} · ${encoder.mimeType}")
        appendLine("      Hardware ${describe(encoder.hardwareAccelerated) { it.toString() }}; software ${describe(encoder.softwareOnly) { it.toString() }}; vendor ${describe(encoder.vendorCodec) { it.toString() }}; alias ${describe(encoder.alias) { it.toString() }}")
        appendLine("      Profiles/levels: ${describe(encoder.profileLevels) { values -> values.joinToString { "${it.profileName}/${it.levelName}" } }}")
        val constraints = encoder.videoConstraints.value
        if (constraints == null) {
            appendLine("      Video constraints: ${statusLabel(encoder.videoConstraints.status)}")
            return
        }
        appendLine("      Width ${describe(constraints.widthRange) { formatRange(it) }} px; height ${describe(constraints.heightRange) { formatRange(it) }} px")
        appendLine("      Alignment ${describe(constraints.widthAlignmentPixels) { "$it" }}×${describe(constraints.heightAlignmentPixels) { "$it" }} px; bitrate ${describe(constraints.bitrateRangeBps) { formatRange(it) + " bps" }}")
        appendLine("      Frame-rate range ${describe(constraints.frameRateRangeFps) { formatRange(it) + " fps" }}; bitrate modes ${describe(constraints.bitrateModes) { values -> values.joinToString { "${it.name}=${describe(it.supported) { flag -> flag.toString() }}" } }}")
        appendLine("      Exact point checks: ${describe(constraints.representativeModeChecks) { values -> values.joinToString { check -> "${check.size.width}×${check.size.height}@${check.frameRate}=${describe(check.supported) { it.toString() }}" } }}")
        if (encoder.issues.isNotEmpty()) appendLine("      Collection issues: ${encoder.issues.size} (details in JSON)")
    }

    private fun <T> describe(field: CapabilityValue<T>, format: (T) -> String): String {
        val value = field.value?.let(format)
        val status = statusLabel(field.status)
        return when {
            value.isNullOrBlank() -> field.note?.let { "$status — $it" } ?: status
            field.status == FieldStatus.ADVERTISED -> value
            else -> "$value ($status)"
        }
    }

    private fun statusLabel(status: FieldStatus): String = status.wireValue.replace('_', ' ')

    private fun formatRanges(values: List<NumericRange<Int>>): String = values.joinToString { formatRange(it) }

    private fun <T : Number> formatRange(value: NumericRange<T>): String = "${value.lower}–${value.upper}"

    private fun formatHighSpeed(mode: HighSpeedModeProfile): String =
        "${mode.size.width}×${mode.size.height} ${describe(mode.fpsRanges) { formatRanges(it) }} fps"

    private fun reportFileName(): String {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "camsure-capabilities-$stamp.json"
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setOnClickListener { action() }
    }

    private fun overlayButton(label: String, action: () -> Unit) = actionButton(label, action).apply {
        background = null
        stateListAnimator = null
        elevation = 0f
        minWidth = dp(48)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0x80FFFFFF.toInt(), android.graphics.Color.WHITE)
        ))
        setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), android.graphics.Color.BLACK)
    }

    private fun matchWrap(topMargin: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        if (topMargin > 0) setMargins(0, topMargin, 0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 4101
        private const val CREATE_REPORT_REQUEST = 4102
        private const val CREATE_RUNTIME_REPORT_REQUEST = 4103
        private const val PREFERENCES_NAME = "camsure_profiler_preferences"
        private const val KEY_ASKED_CAMERA_PERMISSION = "asked_camera_permission"
        private const val MAX_UI_ISSUES = 16
        private const val MAX_UI_STREAM_SIZES = 12
    }

    private enum class CameraAction {
        SCAN,
        PREPARE_PHASE3
    }
}
