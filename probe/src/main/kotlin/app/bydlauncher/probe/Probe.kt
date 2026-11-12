package app.bydlauncher.probe

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Read-only compatibility probe for BYD DiLink head units.
 *
 * It discovers the `android.hardware.bydauto` framework classes, obtains each
 * `*Device` singleton and calls its getters, recording the value or the exact
 * failure. It never calls anything that is not a `get*` / `is*` / `has*` method.
 */
class Probe(
    private val context: Context,
    private val emit: (String) -> Unit,
    private val status: (String) -> Unit,
) {
    class Summary {
        var classesFound = 0
        var classesFromJarScan = false
        var devicesOk = 0
        var devicesFailed = 0
        var gettersOk = 0
        var gettersFailed = 0
        var gettersDenied = 0
        var bydPermissionsDefined = 0
        var bydPermissionsGranted = 0
        val deviceLines = ArrayList<String>()
    }

    val summary = Summary()
    private val pool = Executors.newCachedThreadPool()

    /** jar path for classes that were found on disk (used when Class.forName fails). */
    private val classToJar = HashMap<String, String>()
    private val extraLoaders = HashMap<String, ClassLoader>()

    fun run() {
        try {
            section("DEVICE") { deviceInfo() }
            section("SYSTEM PROPERTIES (filtered)") { systemProps() }
            section("SHARED LIBRARIES") { sharedLibraries() }
            section("BYD-RELATED PACKAGES") { bydPackages() }
            section("BYD PERMISSIONS") { bydPermissions() }
            val classes = sectionResult("BYDAUTO CLASS DISCOVERY") { discoverClasses() }
            section("DEVICE PROBES") { probeDevices(classes) }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- sections

    private fun section(title: String, body: () -> Unit) {
        sectionResult(title) { body() }
    }

    private fun <T> sectionResult(title: String, body: () -> T): T? {
        emit("")
        emit("===== $title =====")
        status(title)
        return try {
            body()
        } catch (t: Throwable) {
            emit("!! section failed: ${describe(t)}")
            null
        }
    }

    private fun deviceInfo() {
        emit("manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND}")
        emit("model=${Build.MODEL} device=${Build.DEVICE} product=${Build.PRODUCT}")
        emit("hardware=${Build.HARDWARE} board=${Build.BOARD}")
        emit("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        emit("build.display=${Build.DISPLAY}")
        emit("build.fingerprint=${Build.FINGERPRINT}")
        emit("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        emit("locale=${Locale.getDefault()}")

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION") val display = wm.defaultDisplay
        val real = DisplayMetrics()
        @Suppress("DEPRECATION") display.getRealMetrics(real)
        val app = context.resources.displayMetrics
        emit("screen.real=${real.widthPixels}x${real.heightPixels} px")
        emit("screen.app=${app.widthPixels}x${app.heightPixels} px")
        emit("screen.densityDpi=${real.densityDpi} density=${real.density}")
        emit(
            "screen.dp=${(real.widthPixels / real.density).toInt()}x" +
                "${(real.heightPixels / real.density).toInt()} dp"
        )
        emit("screen.xdpi=${real.xdpi} ydpi=${real.ydpi}")
        @Suppress("DEPRECATION") emit("screen.refreshHz=${display.refreshRate}")
        val cfg = context.resources.configuration
        emit("config.smallestWidthDp=${cfg.smallestScreenWidthDp} orientation=${cfg.orientation}")
        emit("config.uiMode=0x${Integer.toHexString(cfg.uiMode)}")
    }

    private fun systemProps() {
        val keep = Regex("byd|dilink|ro\\.build\\.|ro\\.product\\.|ro\\.hardware|ro\\.board|ro\\.vendor\\.build", RegexOption.IGNORE_CASE)
        val drop = Regex("serial|imei|meid|mac|vin|iccid|imsi", RegexOption.IGNORE_CASE)
        val proc = Runtime.getRuntime().exec("getprop")
        val lines = proc.inputStream.bufferedReader().readLines()
        proc.waitFor()
        var shown = 0
        for (line in lines) {
            if (keep.containsMatchIn(line) && !drop.containsMatchIn(line)) {
                emit(line)
                shown++
            }
        }
        emit("(${lines.size} properties readable, $shown shown)")
    }

    private fun sharedLibraries() {
        val libs = context.packageManager.systemSharedLibraryNames ?: emptyArray()
        libs.sorted().forEach { emit(it) }
    }

    private fun installedPackages(flags: Int): List<PackageInfo> = try {
        @Suppress("DEPRECATION") context.packageManager.getInstalledPackages(flags)
    } catch (t: Throwable) {
        emit("!! getInstalledPackages failed: ${describe(t)}")
        emptyList()
    }

    private fun bydPackages() {
        val hits = installedPackages(0).filter {
            val n = it.packageName.lowercase(Locale.ROOT)
            n.contains("byd") || n.contains("dilink")
        }.sortedBy { it.packageName }
        for (p in hits) emit("${p.packageName}  v${p.versionName}")
        emit("(${hits.size} packages)")
    }

    private fun bydPermissions() {
        val pm = context.packageManager
        val defined = LinkedHashMap<String, PermissionInfo>()
        // Asking for every package's permissions at once can exceed the binder
        // transaction limit, so walk the packages one by one.
        for (p in installedPackages(0)) {
            val info = try {
                @Suppress("DEPRECATION") pm.getPackageInfo(p.packageName, PackageManager.GET_PERMISSIONS)
            } catch (t: Throwable) {
                continue
            }
            for (perm in info.permissions ?: continue) {
                if (perm.name.contains("BYD", ignoreCase = true)) defined[perm.name] = perm
            }
        }
        summary.bydPermissionsDefined = defined.size
        for ((name, perm) in defined.toSortedMap()) {
            val granted = pm.checkPermission(name, context.packageName) == PackageManager.PERMISSION_GRANTED
            if (granted) summary.bydPermissionsGranted++
            emit("${if (granted) "[GRANTED]" else "[  --   ]"} $name  level=${protection(perm)}  by=${perm.packageName}")
        }
        emit("(${defined.size} BYD permissions defined on this system, ${summary.bydPermissionsGranted} granted to this app)")

        emit("-- requested by this app:")
        val mine = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        } catch (t: Throwable) {
            null
        }
        val names = mine?.requestedPermissions ?: emptyArray()
        val flags = mine?.requestedPermissionsFlags ?: IntArray(0)
        for (i in names.indices) {
            val granted = (flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            val known = defined.containsKey(names[i]) || !names[i].contains("BYD")
            emit("${if (granted) "[GRANTED]" else "[  --   ]"} ${names[i]}${if (known) "" else "  (not defined on this system)"}")
        }
    }

    private fun protection(p: PermissionInfo): String {
        @Suppress("DEPRECATION") val level = p.protectionLevel
        val base = when (level and PermissionInfo.PROTECTION_MASK_BASE) {
            PermissionInfo.PROTECTION_NORMAL -> "normal"
            PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
            PermissionInfo.PROTECTION_SIGNATURE -> "signature"
            3 -> "signatureOrSystem"
            else -> "base${level and PermissionInfo.PROTECTION_MASK_BASE}"
        }
        val flags = level and PermissionInfo.PROTECTION_MASK_BASE.inv()
        return if (flags == 0) base else "$base|flags=0x${Integer.toHexString(flags)}"
    }

    // ------------------------------------------------------- class discovery

    private fun discoverClasses(): List<String> {
        val found = sortedSetOf<String>()
        val bootJars = (System.getenv("BOOTCLASSPATH") ?: "").split(':').filter { it.isNotBlank() }
        val systemServerJars = (System.getenv("SYSTEMSERVERCLASSPATH") ?: "").split(':').toSet()
        emit("BOOTCLASSPATH entries: ${bootJars.size}")

        val jars = LinkedHashSet<String>(bootJars)
        for (dir in listOf("/system/framework", "/system_ext/framework", "/product/framework", "/vendor/framework")) {
            File(dir).listFiles()?.filter { it.name.endsWith(".jar") }?.sortedBy { it.name }
                ?.forEach { jars.add(it.absolutePath) }
        }
        jars.removeAll(systemServerJars)

        var scanned = 0
        var unreadable = 0
        val otherBydPackages = sortedSetOf<String>()
        for (jar in jars) {
            status("scanning ${File(jar).name}")
            try {
                @Suppress("DEPRECATION") val dex = dalvik.system.DexFile(jar)
                try {
                    val entries = dex.entries()
                    var hits = 0
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement()
                        if (name.startsWith(BYD_PREFIX)) {
                            found.add(name)
                            classToJar[name] = jar
                            hits++
                        } else if (name.contains("byd", ignoreCase = true)) {
                            otherBydPackages.add(name.substringBeforeLast('.', ""))
                        }
                    }
                    if (hits > 0) {
                        emit("$jar: $hits bydauto classes${if (jar in bootJars) " (on BOOTCLASSPATH)" else " (NOT on BOOTCLASSPATH)"}")
                    }
                } finally {
                    dex.close()
                }
                scanned++
            } catch (t: Throwable) {
                unreadable++
            }
        }
        emit("jars scanned=$scanned unreadable=$unreadable")
        summary.classesFromJarScan = found.isNotEmpty()

        if (otherBydPackages.isNotEmpty()) {
            emit("-- other packages with 'byd' in the name:")
            otherBydPackages.forEach { emit("   $it") }
        }

        // Fallback / cross-check: names known from other DiLink versions.
        var fallbackHits = 0
        for (name in KNOWN_DEVICE_CLASSES) {
            if (name !in found && loadClass(name) != null) {
                found.add(name)
                fallbackHits++
            }
        }
        if (fallbackHits > 0) emit("found by known-name lookup only: $fallbackHits")

        summary.classesFound = found.size
        emit("TOTAL bydauto classes: ${found.size}")
        return found.toList()
    }

    private fun loadClass(name: String): Class<*>? {
        try {
            return Class.forName(name)
        } catch (_: Throwable) {
        }
        val jar = classToJar[name] ?: return null
        return try {
            val loader = extraLoaders.getOrPut(jar) { PathClassLoader(jar, context.classLoader) }
            Class.forName(name, true, loader)
        } catch (_: Throwable) {
            null
        }
    }

    // ----------------------------------------------------------- device probes

    private fun probeDevices(allClasses: List<String>?) {
        val classes = allClasses ?: emptyList()
        if (classes.isEmpty()) {
            emit("No android.hardware.bydauto classes are visible to this app.")
            return
        }
        val deviceNames = classes.filter { !it.contains('$') && it.substringAfterLast('.').endsWith("Device") }
        emit("device classes: ${deviceNames.size}")

        emit("-- all non-device classes (listeners, events, constants):")
        classes.filter { it !in deviceNames }.forEach { emit("   $it") }

        for (name in deviceNames) {
            status(name.substringAfterLast('.'))
            try {
                probeDevice(name)
            } catch (t: Throwable) {
                emit("!! probe crashed: ${describe(t)}")
            }
        }
    }

    private fun probeDevice(name: String) {
        val short = name.substringAfterLast('.')
        emit("")
        emit("--- $name ---")
        val cls = loadClass(name)
        if (cls == null) {
            emit("!! class could not be loaded")
            summary.devicesFailed++
            summary.deviceLines.add("✗ $short: class not loadable")
            return
        }

        // Constants first: they are what makes raw integer readings decodable.
        val constants = cls.fields.filter {
            Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
                (it.type.isPrimitive || it.type == String::class.java)
        }.sortedBy { it.name }
        if (constants.isNotEmpty()) {
            emit("constants:")
            for (f in constants) {
                val v = try { f.get(null) } catch (t: Throwable) { "?" }
                emit("   ${f.name} = $v")
            }
        }

        val getInstance = cls.methods.firstOrNull {
            it.name == "getInstance" && Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(Context::class.java)
        }
        if (getInstance == null) {
            emit("!! no static getInstance(Context)")
            cls.declaredMethods.sortedBy { it.name }.forEach { emit("   ${signature(it)}") }
            summary.devicesFailed++
            summary.deviceLines.add("✗ $short: no getInstance")
            return
        }

        // Called on this (looper) thread: the singletons usually create a Handler.
        val instance = try {
            getInstance.invoke(null, context.applicationContext)
        } catch (t: Throwable) {
            emit("!! getInstance failed: ${describe(unwrap(t))}")
            summary.devicesFailed++
            summary.deviceLines.add("✗ $short: ${unwrap(t).javaClass.simpleName}")
            return
        }
        if (instance == null) {
            emit("!! getInstance returned null")
            summary.devicesFailed++
            summary.deviceLines.add("✗ $short: null instance")
            return
        }
        summary.devicesOk++

        var ok = 0
        var failed = 0
        var denied = 0
        val others = ArrayList<String>()
        val methods = cls.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .sortedWith(compareBy({ it.name }, { it.parameterTypes.size }))

        for (m in methods) {
            val isGetter = GETTER.containsMatchIn(m.name) && m.returnType != Void.TYPE
            val params = m.parameterTypes
            when {
                isGetter && params.isEmpty() -> {
                    val r = call(m, instance)
                    emit("${m.name}() -> ${r.text}")
                    if (r.ok) ok++ else if (r.denied) denied++ else failed++
                }
                isGetter && params.size == 1 && params[0] == Int::class.javaPrimitiveType -> {
                    val parts = ArrayList<String>()
                    var anyOk = false
                    var anyDenied = false
                    for (arg in 0..4) {
                        val r = call(m, instance, arg)
                        parts.add("[$arg]=${r.text}")
                        anyOk = anyOk || r.ok
                        anyDenied = anyDenied || r.denied
                        if (r.denied || r.timedOut) break
                    }
                    emit("${m.name}(int) -> ${parts.joinToString("  ")}")
                    if (anyOk) ok++ else if (anyDenied) denied++ else failed++
                }
                else -> others.add(signature(m))
            }
        }
        if (others.isNotEmpty()) {
            emit("not called (${others.size}):")
            others.forEach { emit("   $it") }
        }
        summary.gettersOk += ok
        summary.gettersFailed += failed
        summary.gettersDenied += denied
        val mark = if (ok > 0) "✓" else "✗"
        summary.deviceLines.add("$mark $short: ok=$ok denied=$denied failed=$failed")
    }

    private class CallResult(val text: String, val ok: Boolean, val denied: Boolean = false, val timedOut: Boolean = false)

    private fun call(m: Method, target: Any, vararg args: Any?): CallResult {
        val future = pool.submit(Callable { m.invoke(target, *args) })
        return try {
            val value = future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            CallResult(format(m.name, value), ok = true)
        } catch (t: TimeoutException) {
            future.cancel(true)
            CallResult("TIMEOUT", ok = false, timedOut = true)
        } catch (t: ExecutionException) {
            val cause = unwrap(t.cause ?: t)
            CallResult("ERR ${describe(cause)}", ok = false, denied = cause is SecurityException)
        } catch (t: Throwable) {
            CallResult("ERR ${describe(t)}", ok = false)
        }
    }

    private fun format(method: String, value: Any?): String {
        val text = when (value) {
            null -> "null"
            is IntArray -> value.contentToString()
            is LongArray -> value.contentToString()
            is FloatArray -> value.contentToString()
            is DoubleArray -> value.contentToString()
            is ByteArray -> value.contentToString()
            is ShortArray -> value.contentToString()
            is BooleanArray -> value.contentToString()
            is CharArray -> value.contentToString()
            is Array<*> -> value.contentToString()
            else -> value.toString()
        }
        // The report is meant to be shared, so identifiers are masked.
        if (SENSITIVE.containsMatchIn(method) && text.length > 6 && value !is Number && value !is Boolean) {
            return text.take(3) + "*".repeat(8) + " (masked, length=${text.length})"
        }
        return if (text.length > 300) text.take(300) + "…(${text.length} chars)" else text
    }

    private fun signature(m: Method): String =
        "${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})"

    private fun unwrap(t: Throwable): Throwable {
        var cur = t
        while (cur is InvocationTargetException || cur is ExecutionException) cur = cur.cause ?: break
        return cur
    }

    private fun describe(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message}"

    companion object {
        private const val BYD_PREFIX = "android.hardware.bydauto."
        private const val CALL_TIMEOUT_SECONDS = 3L
        private val GETTER = Regex("^(get|is|has)[A-Z0-9_]")
        private val SENSITIVE = Regex("vin|serial|imei|iccid|imsi|mac|sn$|account|phone|token", RegexOption.IGNORE_CASE)

        /** Class names seen on other DiLink versions; used only if jar scanning finds nothing. */
        private val KNOWN_DEVICE_CLASSES = listOf(
            "ac.BYDAutoAcDevice",
            "adas.BYDAutoADASDevice",
            "audio.BYDAutoAudioDevice",
            "bodywork.BYDAutoBodyworkDevice",
            "charge.BYDAutoChargingDevice",
            "collision.BYDAutoCollisionDevice",
            "doorlock.BYDAutoDoorLockDevice",
            "energy.BYDAutoEnergyDevice",
            "engine.BYDAutoEngineDevice",
            "gearbox.BYDAutoGearboxDevice",
            "instrument.BYDAutoInstrumentDevice",
            "light.BYDAutoLightDevice",
            "motor.BYDAutoMotorDevice",
            "multimedia.BYDAutoMultimediaDevice",
            "ota.BYDAutoOtaDevice",
            "panorama.BYDAutoPanoramaDevice",
            "pm2p5.BYDAutoPM2p5Device",
            "power.BYDAutoPowerDevice",
            "radar.BYDAutoRadarDevice",
            "safetybelt.BYDAutoSafetyBeltDevice",
            "sensor.BYDAutoSensorDevice",
            "setting.BYDAutoSettingDevice",
            "speed.BYDAutoSpeedDevice",
            "statistic.BYDAutoStatisticDevice",
            "time.BYDAutoTimeDevice",
            "tyre.BYDAutoTyreDevice",
            "version.BYDAutoVersionDevice",
        ).map { BYD_PREFIX + it }
    }
}
