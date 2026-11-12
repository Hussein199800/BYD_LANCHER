package app.bydlauncher.probe

import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var summaryView: TextView
    private lateinit var logView: TextView
    private lateinit var runButton: Button

    private var worker: HandlerThread? = null
    private var server: ReportServer? = null
    private val reportFile: File by lazy { File(getExternalFilesDir(null) ?: filesDir, REPORT_NAME) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        server = ReportServer(PORT) { reportFile }.also { it.start() }

        val missing = missingPermissions()
        if (Build.VERSION.SDK_INT >= 23 && missing.isNotEmpty()) {
            // Only "dangerous" permissions show a dialog; the rest return immediately.
            requestPermissions(missing, 1)
        } else {
            startProbe()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        startProbe()
    }

    override fun onDestroy() {
        server?.stop()
        worker?.quit()
        super.onDestroy()
    }

    private fun missingPermissions(): Array<String> {
        val info = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        } catch (t: Throwable) {
            return emptyArray()
        }
        val names = info.requestedPermissions ?: return emptyArray()
        val flags = info.requestedPermissionsFlags ?: return emptyArray()
        return names.filterIndexed { i, _ ->
            (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0
        }.toTypedArray()
    }

    // ------------------------------------------------------------------ probe

    private fun startProbe() {
        if (worker != null) return
        runButton.isEnabled = false
        summaryView.text = ""
        logView.text = ""
        statusView.text = "جارٍ الفحص…"

        val thread = HandlerThread("probe").also { it.start() }
        worker = thread
        Handler(thread.looper).post {
            val lines = StringBuilder()
            val writer = try { reportFile.bufferedWriter() } catch (t: Throwable) { null }
            val emit: (String) -> Unit = { line ->
                lines.append(line).append('\n')
                // Flushed line by line so a hang or crash still leaves a partial report.
                try { writer?.apply { write(line); newLine(); flush() } } catch (_: Throwable) {}
            }
            emit("BYD Probe v${BuildConfig.VERSION_NAME}  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            val probe = Probe(this, emit) { s -> ui.post { statusView.text = "جارٍ الفحص: $s" } }
            try {
                probe.run()
            } catch (t: Throwable) {
                emit("!! probe aborted: ${t.javaClass.simpleName}: ${t.message}")
            }
            emit("")
            emit("===== END =====")
            try { writer?.close() } catch (_: Throwable) {}

            val text = lines.toString()
            val downloads = copyToDownloads(text)
            val summary = summaryText(probe.summary, downloads)
            ui.post {
                summaryView.text = summary
                logView.text = text
                statusView.text = "اكتمل الفحص"
                runButton.isEnabled = true
            }
            worker = null
            thread.quitSafely()
        }
    }

    private fun summaryText(s: Probe.Summary, downloads: String?): String = buildString {
        append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) — ${Build.MANUFACTURER} ${Build.MODEL}\n")
        val dm = resources.displayMetrics
        append("الشاشة: ${dm.widthPixels}×${dm.heightPixels} بكسل، ${dm.densityDpi}dpi\n")
        append("أصناف bydauto المكتشفة: ${s.classesFound}\n")
        append("أذونات BYD المعرّفة: ${s.bydPermissionsDefined} — الممنوحة لهذا التطبيق: ${s.bydPermissionsGranted}\n")
        append("الواجهات: تعمل ${s.devicesOk} — فشلت ${s.devicesFailed}\n")
        append("القراءات: نجحت ${s.gettersOk} — مرفوضة بالصلاحيات ${s.gettersDenied} — فشلت ${s.gettersFailed}\n")
        append("\n")
        append(
            when {
                s.classesFound == 0 -> "النتيجة: واجهات السيارة غير ظاهرة لهذا التطبيق."
                s.gettersOk == 0 -> "النتيجة: الواجهات موجودة لكن لم تنجح أي قراءة."
                else -> "النتيجة: توجد قراءات حقيقية متاحة ✓"
            }
        )
        append("\n\n")
        s.deviceLines.forEach { append(it).append('\n') }
        append("\nالتقرير الكامل:\n")
        append("• الملف: ${reportFile.absolutePath}\n")
        if (downloads != null) append("• نسخة في التنزيلات: $downloads\n")
        val ips = localAddresses()
        if (ips.isEmpty()) {
            append("• لا يوجد اتصال شبكة محلي لتنزيل التقرير من الهاتف.\n")
        } else {
            append("• من هاتف على نفس الشبكة (أو نقطة اتصال السيارة) افتح:\n")
            ips.forEach { append("    http://$it:$PORT\n") }
        }
    }

    private fun copyToDownloads(text: String): String? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, REPORT_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) null else {
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                "Download/$REPORT_NAME"
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val out = File(dir, REPORT_NAME)
            out.writeText(text)
            out.absolutePath
        }
    } catch (t: Throwable) {
        null
    }

    private fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (t: Throwable) {
        emptyList()
    }

    // --------------------------------------------------------------------- ui

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#071326"))
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusView = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.parseColor("#F4F7FF"))
        }
        runButton = Button(this).apply {
            text = "إعادة الفحص"
            textSize = 18f
            setOnClickListener { startProbe() }
        }
        header.addView(statusView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(runButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        summaryView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.parseColor("#F4F7FF"))
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
            setPadding(0, pad, 0, pad)
        }
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#A9B8D0"))
            setTextIsSelectable(true)
            // The log is English/technical; keep it left-to-right inside the RTL screen.
            textDirection = View.TEXT_DIRECTION_LTR
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.LEFT
        }
        body.addView(summaryView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.addView(logView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private companion object {
        const val REPORT_NAME = "byd_probe_report.txt"
        const val PORT = 8765
    }
}
