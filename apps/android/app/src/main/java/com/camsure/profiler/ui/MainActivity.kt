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
import android.view.WindowInsets
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
    private lateinit var usbStatus: TextView
    private var usbCandidates = emptyList<com.camsure.profiler.phase4.UsbNetworkLink>()
    private var receiverDiscovery: NsdReceiverDiscovery? = null
    private var discoveredReceivers: List<DiscoveredReceiver> = emptyList()
    private var selectedDiscoveredReceiver: DiscoveredReceiver? = null
    private lateinit var phase4ReceiverChoices: ArrayAdapter<ReceiverChoice>

    private val snapshotListener: (ProfilerSnapshot) -> Unit = { snapshot -> render(snapshot) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        ProfilerSession.attach(snapshotListener)
    }

    override fun onStop() {
        stopReceiverDiscovery()
        if (!phase3Snapshot.isTerminal) phase3Experiment?.stopForBackground()
        ProfilerSession.detach(snapshotListener)
        super.onStop()
    }

    override fun onDestroy() {
        activityDestroyed = true
        stopReceiverDiscovery()
        if (!phase3Snapshot.isTerminal) phase3Experiment?.stopForBackground()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F6F7.toInt())
            clipToPadding = false
            setPadding(dp(16), dp(14), dp(16), dp(16))
            setOnApplyWindowInsetsListener { view, insets ->
                val topInset: Int
                val bottomInset: Int
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    topInset = bars.top
                    bottomInset = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    val top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    val bottom = insets.systemWindowInsetBottom
                    topInset = top
                    bottomInset = bottom
                }
                view.setPadding(dp(16), dp(14) + topInset, dp(16), dp(16) + bottomInset)
                insets
            }
        }

        val title = TextView(this).apply {
            text = getString(R.string.profiler_title)
            textSize = 24f
            setTextColor(0xFF182022.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title, matchWrap())
        root.addView(TextView(this).apply {
            text = getString(R.string.profiler_subtitle)
            textSize = 14f
            setTextColor(0xFF536164.toInt())
            setPadding(0, dp(3), 0, dp(10))
        }, matchWrap())

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
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
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
            "Camera permission is needed to prepare and run the Phase 3 Camera2 capture experiment."
        } else {
            "Camera permission is needed only when scanning Camera2 metadata. Grant it to continue."
        }
        permissionMessage = if (permanentlyDenied) {
            "Camera permission is blocked. Open app settings, allow Camera, then retry the selected action."
        } else {
            actionMessage
        }
        render(ProfilerSession.current())
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
                    "Camera permission was denied. Tap Prepare camera routes to retry."
                } else {
                    "Camera permission was denied. Tap Scan capabilities to retry."
                }
            }
            render(ProfilerSession.current())
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
                val defaultIndex = cameraRoutes.indexOfFirst { it.physicalCameraId == null }
                    .coerceAtLeast(0)
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
    }

    private fun refreshPhase3Plans(route: CameraRoute?) {
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
            if (exact1080Trial.isChecked) directModePlans = directModePlans.filter { it.width == 1920 && it.height == 1080 }
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
        val route = selectedCameraRoute ?: return
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
        val selectedUsb = if (usbMode.isChecked) usbCandidates.getOrNull(usbAddresses.selectedItemPosition - 1) else null
        if (usbMode.isChecked && (selectedUsb == null || !selectedUsb.isPresent() || receiverEndpoint == null || !selectedUsb.hasExclusivePeerRoute(receiverEndpoint.address))) {
            phase3PlanMessage = "USB not ready: enable USB tethering, refresh and explicitly select its local address, then enter the PC address on that link."
            renderPhase3(); return
        }
        val plan = directModePlans.firstOrNull()
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
        if (phase3Snapshot.finishedAt != null && !runtimeReportExported) {
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
                "running" -> if (snapshot.transport != null) {
                    "The ten-minute RTP/H.264 run is active for " + snapshot.transport.destination + "."
                } else "The five-minute direct-surface run is active."
                "stopping" -> "The encoder is draining final output."
                "completed", "stopped", "failed" -> "Export the runtime JSON before starting another run."
                else -> snapshot.message
            }
            renderPhase3()
            },
            receiverEndpoint = receiverEndpoint,
            usbLink = selectedUsb
        ).also { it.start() }
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
            text = "Camera to hardware encoder and RTP"
            textSize = 17f
            setTextColor(0xFF182022.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrap())
        panel.addView(TextView(this).apply {
            text = "Prepare permission-gated camera routes, then run the highest exact 30 fps mode shared by Camera2 and a hardware H.264 encoder. Find a local receiver with DNS-SD or enter a fixed IPv4 address. Capture-only runs stop after five minutes; LAN and USB runs stop after ten."
            textSize = 13f
            setTextColor(0xFF536164.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }, matchWrap())
        phase3Routes = Spinner(this).apply { isEnabled = false }
        panel.addView(phase3Routes, matchWrap())
        highResolutionTrial = android.widget.CheckBox(this).apply {
            text = "High-resolution trial (largest supported mode up to 4K/30)"
            setOnCheckedChangeListener { _, _ -> refreshPhase3Plans(selectedCameraRoute); renderPhase3() }
        }
        panel.addView(highResolutionTrial, matchWrap())
        exact1080Trial = android.widget.CheckBox(this).apply {
            text = "1080p configuration trial (overrides 4K trial)"
            setOnCheckedChangeListener { _, _ -> refreshPhase3Plans(selectedCameraRoute); renderPhase3() }
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
            text = "USB Network Mode (unchecked = LAN / Wi-Fi)"
            setOnCheckedChangeListener { _, checked ->
                if (checked) { stopReceiverDiscovery(); selectedDiscoveredReceiver = null }
                usbStatus.text = if (checked) "Enable USB tethering manually. Refresh and confirm its local address; enter the Windows USB adapter IPv4 below." else "LAN / Wi-Fi selected."
                renderPhase3()
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
        panel.addView(actionButton("Refresh USB network addresses") {
            try {
                usbCandidates = com.camsure.profiler.phase4.UsbNetworkLink.candidates(this)
                usbAddresses.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Select USB local address") + usbCandidates.map { it.toString() })
                usbStatus.text = if (usbCandidates.isEmpty()) "USB network not available. Enable USB tethering and connect the device to the PC." else "Confirm the tethering interface; candidates are not proof of USB. Select explicitly, even if only one."
            } catch (error: Exception) { usbStatus.text = "Network inventory failed: " + error.javaClass.simpleName }
        }, matchWrap())
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
        startPhase3Button = actionButton("Start highest direct mode · capture 5 min / stream 10 min") {
            startPhase3Experiment()
        }.apply { isEnabled = false }
        stopPhase3Button = actionButton("Stop camera run") {
            stopPhase3Experiment()
        }.apply { isEnabled = false }
        exportPhase3Button = actionButton("Export runtime JSON") {
            openRuntimeReportPicker()
        }.apply { isEnabled = false }
        panel.addView(preparePhase3Button, matchWrap())
        panel.addView(startPhase3Button, matchWrap(dp(6)))
        panel.addView(stopPhase3Button, matchWrap(dp(6)))
        panel.addView(exportPhase3Button, matchWrap(dp(6)))
        phase3StatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF31524F.toInt())
            setPadding(dp(2), dp(9), dp(2), dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        panel.addView(phase3StatusText, matchWrap())
        phase3ReportText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF202A2C.toInt())
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFFF5F6F7.toInt())
        }
        panel.addView(phase3ReportText, matchWrap())
        phase3Routes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                refreshPhase3Plans(null)
                renderPhase3()
            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val route = parent?.getItemAtPosition(position) as? CameraRoute
                refreshPhase3Plans(route)
                renderPhase3()
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
            }
        }
        renderPhase3()
        return panel
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
        discoveredReceivers = emptyList()
        selectedDiscoveredReceiver = null
        setReceiverChoices(emptyList(), null)
        phase4DiscoveryStatus.text = "Receiver discovery stopped."
        renderPhase3()
    }

    private fun updateDiscoveredReceivers(receivers: List<DiscoveredReceiver>, message: String) {
        if (activityDestroyed || !::phase4ReceiverChoices.isInitialized) return
        val previousName = selectedDiscoveredReceiver?.serviceName
        discoveredReceivers = receivers
        selectedDiscoveredReceiver = receivers.firstOrNull { it.serviceName == previousName }
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
            "Stop receiver discovery"
        } else {
            "Find receivers on local network"
        }
        usbPort.isEnabled = !active && usbMode.isChecked
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
        phase3ReportText.text = renderPhase3Snapshot(phase3Snapshot, directModePlans)
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

    private fun number(value: Double?): String = value?.let { String.format("%.2f", it) } ?: "—"

    private data class ReceiverChoice(val receiver: DiscoveredReceiver?) {
        override fun toString(): String = receiver?.displayName ?: "Select a discovered receiver"
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
