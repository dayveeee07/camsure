package com.camsure.profiler.session

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.camsure.profiler.collector.CapabilityProfiler
import com.camsure.profiler.model.CapabilityReport
import com.camsure.profiler.report.CapabilityReportJson
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

data class ProfilerSnapshot(
    val scanning: Boolean = false,
    val progress: String = "Ready. Scan when you are ready.",
    val report: CapabilityReport? = null,
    val error: String? = null,
    val exporting: Boolean = false,
    val exportMessage: String? = null
)

object ProfilerSession {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "camsure-capability-profiler").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var snapshot = ProfilerSnapshot()
    private var observer: ((ProfilerSnapshot) -> Unit)? = null

    fun current(): ProfilerSnapshot = synchronized(lock) { snapshot }

    fun attach(listener: (ProfilerSnapshot) -> Unit) {
        val current = synchronized(lock) {
            observer = listener
            snapshot
        }
        mainHandler.post { listener(current) }
    }

    fun detach(listener: (ProfilerSnapshot) -> Unit) {
        synchronized(lock) {
            if (observer === listener) observer = null
        }
    }

    fun start(context: Context) {
        var started = false
        update { current ->
            if (current.scanning) current else current.copy(
                scanning = true,
                progress = "Starting metadata scan…",
                error = null,
                exportMessage = null
            ).also { started = true }
        }
        if (!started) return

        val appContext = context.applicationContext
        worker.execute {
            try {
                val report = CapabilityProfiler(appContext).collect { progress ->
                    update { current -> if (current.scanning) current.copy(progress = progress) else current }
                }
                update { current ->
                    current.copy(
                        scanning = false,
                        progress = "Scan complete.",
                        report = report,
                        error = null
                    )
                }
            } catch (error: Exception) {
                update { current ->
                    current.copy(
                        scanning = false,
                        progress = "Scan stopped.",
                        error = "Capability scan failed (${error.javaClass.simpleName})."
                    )
                }
            }
        }
    }

    fun export(context: Context, uri: Uri, report: CapabilityReport) {
        var started = false
        update { current ->
            if (current.exporting) current else current.copy(
                exporting = true,
                exportMessage = "Writing JSON report…"
            ).also { started = true }
        }
        if (!started) return

        val resolver = context.applicationContext.contentResolver
        worker.execute {
            try {
                val json = CapabilityReportJson.encode(report)
                val output = resolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("The selected document cannot be opened for writing.")
                OutputStreamWriter(output, Charsets.UTF_8).use { writer -> writer.write(json) }
                update { it.copy(exporting = false, exportMessage = "JSON report exported successfully.") }
            } catch (error: Exception) {
                update {
                    it.copy(
                        exporting = false,
                        exportMessage = "Export failed (${error.javaClass.simpleName}). Choose another destination and retry."
                    )
                }
            }
        }
    }

    private fun update(transform: (ProfilerSnapshot) -> ProfilerSnapshot): ProfilerSnapshot {
        val (listener, value) = synchronized(lock) {
            snapshot = transform(snapshot)
            observer to snapshot
        }
        listener?.let { callback -> mainHandler.post { callback(value) } }
        return value
    }
}
