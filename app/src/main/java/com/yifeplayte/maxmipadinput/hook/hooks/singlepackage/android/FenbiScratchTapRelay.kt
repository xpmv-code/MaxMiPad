package com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.github.kyuubiran.ezxhelper.ClassUtils.loadClass
import com.github.kyuubiran.ezxhelper.HookFactory.`-Static`.createHooks
import com.github.kyuubiran.ezxhelper.finders.MethodFinder.`-Static`.methodFinder
import com.yifeplayte.maxmipadinput.hook.hooks.BaseHook
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Stylus side-button control for the Fenbi (粉笔) draft paper, entirely inside
 * system_server. Fenbi's risk-control SDK detects any LSPosed injection in its
 * own process within seconds of launch (verified on device: module scoped to
 * com.fenbi.android.servant → "hook environment" dialog, clean exit ~2s after
 * start), so nothing may hook that process. Instead:
 *
 *  - PAGE_UP/PAGE_DOWN from the himax digitizer are dropped here in
 *    interceptKeyBeforeQueueing while the focused task belongs to Fenbi;
 *  - the draft-paper state is verified by capturing Fenbi's own window surface
 *    (ScreenCapture.captureLayers on the focused WindowState's SurfaceControl)
 *    and scanning the toolbar strip: open = dark #353842 bar + a blue
 *    selected-tool icon, closed = light page;
 *  - the mapped toolbar button press is injected as a real touchscreen event
 *    via InputManagerGlobal — indistinguishable from a finger tap.
 *
 *  - PAGE_UP (upper button)   → scratch_delete (2472, 108): clear the paper
 *  - PAGE_DOWN (lower button) → scratch_erase  (1038, 108): toggle pen/eraser
 *
 * Button identity verified on device (PAGE_UP=92=BTN_STYLUS2=upper,
 * PAGE_DOWN=93=BTN_STYLUS=lower); the h7-stylus-fix README has them swapped.
 *
 * Coordinates are for dagu (2560x1600) landscape, measured from the
 * accessibility tree (button bounds) and screencap pixels (bar/icon colors).
 * All WMS/capture/inject APIs are internal, so every step reflects over
 * several known shapes and degrades to pass-through when nothing works —
 * never to a wrong tap.
 *
 * Self-test: `adb shell setprop debug.fenbi.selftest 1` makes injected
 * keyevents (device "Virtual") drive the full probe path for diagnostics.
 */
object FenbiScratchTapRelay : BaseHook() {
    override val key = "fenbi_scratch_tap_relay"
    private const val TAG = "FenbiScratchRelay"
    private const val TARGET_PACKAGE = "com.fenbi.android.servant"
    private const val DRAW_ANYWHERE_AUTHORITY =
        "content://com.shezik.drawanywhere.keystate"

    /** Press→release window for a hold to count as a tap. */
    private const val TAP_WINDOW_MS = 350L

    /** Draft-state cache: the state only changes on user taps. */
    private const val STATE_CACHE_MS = 300L

    /** Retry window for one-shot reflective lookups that failed early. */
    private const val RETRY_MS = 30_000L

    // dagu landscape (2560x1600) toolbar geometry, from a11y dump + screencap:
    private const val SCREEN_W = 2560
    private const val SCREEN_H = 1600
    private const val ERASE_X = 1038 // scratch_erase center → PAGE_UP (lower)
    private const val ERASE_Y = 108
    private const val DELETE_X = 2472 // scratch_delete center → PAGE_DOWN (upper)
    private const val DELETE_Y = 108
    private const val BAR_BG = 0x353842

    private val downAtMs = mutableMapOf<Int, Long>()

    /**
     * Injection runs on a dedicated neutral thread: injecting from inside the
     * input dispatch path (real hardware keys) silently no-ops even though
     * injectInputEvent returns true, while the same injection from a binder
     * thread (injected keyevents) lands fine. A own HandlerThread matches the
     * working context and keeps tap DOWN/UP sequencing off the dispatchers.
     */
    private val injectHandler by lazy {
        val t = android.os.HandlerThread("FenbiScratchInject")
        t.start()
        Handler(t.looper)
    }
    private val loggedKeys: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    private var cachedOpen: Boolean? = null
    private var cachedAtMs = 0L

    private var wmsInstance: Any? = null
    private var wmsEverAttempted = false
    private var wmsLastAttemptMs = 0L

    private var cachedDrawAnywhereActive = false
    private var cachedDrawAnywhereAtMs = 0L

    // Reflective lookups cached on the key path.
    private val screencapClass by lazy { Class.forName("android.window.ScreenCapture") }
    private val surfaceControlClass by lazy { Class.forName("android.view.SurfaceControl") }
    private val captureLayersMethod by lazy {
        screencapClass.getMethod(
            "captureLayers",
            surfaceControlClass,
            Rect::class.java,
            Float::class.javaPrimitiveType,
        )
    }
    private val imGlobalInject by lazy {
        runCatching {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            val inst = cls.getMethod("getInstance").invoke(null)
            // The declared parameter is InputEvent (MotionEvent's superclass);
            // getMethod needs the exact type, invoke accepts the subclass.
            val m = cls.methods.filter {
                it.name == "injectInputEvent" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].isAssignableFrom(MotionEvent::class.java)
            }.firstOrNull() ?: return@runCatching null
            m to inst
        }.getOrNull()
    }
    private val imLegacyInject by lazy {
        runCatching {
            val cls = Class.forName("android.hardware.input.InputManager")
            val inst = cls.getMethod("getInstance").invoke(null)
            val m = cls.methods.filter {
                it.name == "injectInputEvent" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].isAssignableFrom(MotionEvent::class.java)
            }.firstOrNull() ?: return@runCatching null
            m to inst
        }.getOrNull()
    }
    private val atmGetFocusedRootTaskInfo by lazy {
        runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null)
            atm.javaClass.getMethod("getFocusedRootTaskInfo") to atm
        }.getOrNull()
    }
    private val systemContext: android.content.Context? by lazy {
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("currentActivityThread").invoke(null)
                ?: return@runCatching null
            atClass.getMethod("getSystemContext").invoke(at) as android.content.Context
        }.getOrNull()
    }

    private val sysPropGet by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        }.getOrNull()
    }

    private fun selfTestEnabled(): Boolean = runCatching {
        sysPropGet?.invoke(null, "debug.fenbi.selftest") == "1"
    }.getOrDefault(false)

    override fun hook() {
        // Same candidate chain as RelayStylusKeysToDrawAnywhere: HyperOS's
        // runtime policy is MiuiPhoneWindowManager, which overrides
        // interceptKeyBeforeQueueing; EzXHelper's methodFinder() also yields
        // inherited interface methods, so abstract members must be filtered.
        val candidates = listOf(
            "com.android.server.policy.MiuiPhoneWindowManager" to "interceptKeyBeforeQueueing",
            "com.android.server.policy.BaseMiuiPhoneWindowManager" to "interceptKeyBeforeQueueingInternal",
            "com.android.server.policy.PhoneWindowManager" to "interceptKeyBeforeQueueing",
        )
        for ((className, methodName) in candidates) {
            val clazz = runCatching { loadClass(className) }.getOrNull() ?: continue
            val methods = clazz.methodFinder().filter {
                name == methodName &&
                    parameterTypes.size >= 2 &&
                    java.lang.reflect.Modifier.isAbstract(modifiers).not()
            }.toList()
            if (methods.isEmpty()) continue
            methods.createHooks {
                before { param ->
                    val event = param.args.getOrNull(0) as? KeyEvent ?: return@before
                    handleKey(param, event)
                }
            }
            Log.d(TAG, "hooked $className#$methodName (${methods.size} method(s))")
            return
        }
        throw NoSuchMethodException("no candidate interceptKeyBeforeQueueing found")
    }

    private fun handleKey(param: XC_MethodHook.MethodHookParam, event: KeyEvent) {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_PAGE_UP && keyCode != KeyEvent.KEYCODE_PAGE_DOWN) return
        val deviceName = event.device?.name ?: return
        val selfTest = selfTestEnabled()
        if (!selfTest && !deviceName.contains("himax", ignoreCase = true)) return

        // Drop stale pairs: a DOWN whose UP never arrived must not pair with a
        // much later UP.
        val nowMs = SystemClock.uptimeMillis()
        downAtMs.entries.removeAll { nowMs - it.value > 10_000L }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    if (downAtMs.containsKey(keyCode)) param.result = 0
                    return
                }
                if (isDrawAnywhereCanvasActive()) return
                when (isDraftOpen()) {
                    true -> {
                        downAtMs[keyCode] = SystemClock.uptimeMillis()
                        param.result = 0
                        Log.d(TAG, "down key=$keyCode (draft open)")
                    }
                    else -> return // draft closed or state unknown: pass through
                }
            }
            KeyEvent.ACTION_UP -> {
                // Act only on an UP paired with a DOWN we consumed; a stray UP
                // passes through untouched (asymmetric swallow desyncs system
                // gesture state).
                val down = downAtMs.remove(keyCode) ?: return
                param.result = 0
                val heldMs = SystemClock.uptimeMillis() - down
                if (heldMs <= TAP_WINDOW_MS && isDraftOpen() == true) {
                    // User-verified button identity on this unit: PAGE_UP (92,
                    // BTN_STYLUS2) is the UPPER button, PAGE_DOWN (93,
                    // BTN_STYLUS) the LOWER — the h7-stylus-fix README has the
                    // two swapped. Upper = clear, lower = toggle pen/eraser.
                    val (x, y) = if (keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                        DELETE_X to DELETE_Y
                    } else {
                        ERASE_X to ERASE_Y
                    }
                    Log.d(TAG, "tap key=$keyCode held=${heldMs}ms -> inject tap ($x,$y)")
                    // Delay past the original key's dispatch cycle: injecting
                    // while the real key event is still being processed gets
                    // the touch silently swallowed inside InputDispatcher
                    // (verified: the MotionEvent never reaches the app).
                    injectHandler.postDelayed({ injectTap(x, y) }, 150L)
                } else {
                    Log.d(TAG, "hold key=$keyCode held=${heldMs}ms -> ignored")
                }
            }
        }
    }

    private fun isDrawAnywhereCanvasActive(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedDrawAnywhereAtMs < 200L) return cachedDrawAnywhereActive
        cachedDrawAnywhereActive = runCatching {
            val ctx = systemContext ?: return@runCatching false
            ctx.contentResolver
                .call(Uri.parse(DRAW_ANYWHERE_AUTHORITY), "intercept_active", null, null)
                ?.getBoolean("active", false) ?: false
        }.getOrDefault(false)
        cachedDrawAnywhereAtMs = now
        return cachedDrawAnywhereActive
    }

    /**
     * Three-state draft probe: true = open, false = closed, null = unknown
     * (capture failed — always pass keys through on unknown).
     */
    private fun isDraftOpen(): Boolean? = runCatching {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAtMs >= STATE_CACHE_MS) {
            cachedOpen = probeDraftOpen()
            cachedAtMs = now
        }
        cachedOpen
    }.getOrElse {
        cachedOpen = null
        null
    }

    /**
     * Focused task must be a Fenbi task, and its window surface must show the
     * dark toolbar with a selected blue tool icon.
     */
    private fun probeDraftOpen(): Boolean? {
        val focused = focusedWindowState()
        if (focused == null) {
            logOnce("noFocusedWindow", "probe: focusedWindowState null")
            return null
        }
        val isFenbi = windowIsFenbi(focused) || focusedTaskIsFenbi()
        if (!isFenbi) {
            logOnce("notFenbi", "probe: focus not fenbi")
            return false
        }
        logOnce("focusInfo") {
            val title = runCatching {
                fieldOf(focused, "mAttrs")?.let { attrs ->
                    attrs.javaClass.getMethod("getTitle").invoke(attrs)
                }?.toString() ?: "?"
            }.getOrDefault("?")
            val frame = runCatching {
                focused.javaClass.getMethod("getFrame").invoke(focused)?.toString()
            }.getOrNull() ?: runCatching {
                fieldOf(focused, "mFrame")?.toString()
            }.getOrNull() ?: "?"
            "focused window title=$title frame=$frame"
        }
        val surface = fieldOf(focused, "mSurfaceControl")
        if (surface == null) {
            logOnce("noSurface", "probe: mSurfaceControl unavailable")
            return null
        }
        val bmp = captureLayer(surface)
        if (bmp == null) {
            logOnce("noCapture", "probe: capture failed")
            return null
        }
        val w = bmp.width
        val h = bmp.height
        val result = try {
            draftBarVisible(bmp)
        } finally {
            bmp.recycle()
        }
        logOnce("probeResult_${w}x${h}_$result") { "probe: bmp=${w}x${h} -> $result" }
        return result
    }

    // ---- WindowManagerService focus access --------------------------------

    private fun windowManagerService(): Any? {
        wmsInstance?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (wmsEverAttempted && now - wmsLastAttemptMs < RETRY_MS) return null
        wmsEverAttempted = true
        wmsLastAttemptMs = now
        return runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "window")
                as? IBinder ?: return@runCatching null
            val stub = Class.forName("android.view.IWindowManager\$Stub")
            wmsInstance = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            Log.d(TAG, "wms resolved: ${wmsInstance!!.javaClass.name}")
            wmsInstance
        }.getOrNull()
    }

    /**
     * Focus lives on the DisplayContent on HyperOS A15 (not on WMS or the
     * root container — verified by field dumps), under one of several names
     * across builds. Each miss dumps the candidate object's fields once so
     * the real name is visible in logcat.
     */
    private fun focusedWindowState(): Any? = runCatching {
        val wms = windowManagerService() ?: return@runCatching null
        val root = fieldOf(wms, "mRoot")
        if (root == null) {
            diagnoseOnce(wms, "root", "WMS")
            return@runCatching null
        }
        val display = fieldOf(root, "mDefaultDisplay")
            ?: (fieldOf(root, "mDisplayContents") as? android.util.SparseArray<*>)?.get(0)
            ?: runCatching { root.javaClass.getMethod("getDefaultDisplay").invoke(root) }.getOrNull()
        if (display == null) {
            diagnoseOnce(root, "display", "Root")
            return@runCatching null
        }
        for (name in listOf("mCurrentFocus", "mFocusedWindow", "mLastFocus")) {
            fieldOf(display, name)?.let { return@runCatching it }
        }
        diagnoseOnce(display, "focus", "DisplayContent")
        null
    }.getOrNull()

    /**
     * Activity windows carry "package/activity" as their LayoutParams title
     * (same string MIUIInput logs as windowName).
     */
    private fun windowIsFenbi(focused: Any): Boolean = runCatching {
        val attrs = fieldOf(focused, "mAttrs") ?: return@runCatching false
        val title = attrs.javaClass.getMethod("getTitle").invoke(attrs)?.toString()
            ?: return@runCatching false
        title.contains(TARGET_PACKAGE)
    }.getOrDefault(false)

    /**
     * Fallback foreground check via the local ActivityTaskManagerService
     * (getService() returns the in-process service; getFocusedRootTaskInfo
     * verified present on this build).
     */
    private fun focusedTaskIsFenbi(): Boolean = runCatching {
        val (method, atm) = atmGetFocusedRootTaskInfo ?: return@runCatching false
        val info = method.invoke(atm) ?: return@runCatching false
        val top = info.javaClass.getField("topActivity").get(info) as? android.content.ComponentName
            ?: return@runCatching false
        top.packageName == TARGET_PACKAGE
    }.getOrDefault(false)

    // ---- window surface capture -------------------------------------------

    /**
     * captureLayers(SurfaceControl, Rect, float) — window-local coordinates.
     * The toolbar sits within the top rows regardless of the window's
     * status-bar inset, so scan instead of sampling fixed rows.
     */
    private fun captureLayer(surface: Any): Bitmap? = runCatching {
        val buffer = captureLayersMethod.invoke(
            null, surface, Rect(0, 0, SCREEN_W, SCREEN_H / 3), 1.0f,
        )
        bufferToBitmap(buffer)
    }.onFailure {
        logOnce("captureThrew") { "captureLayers threw: $it" }
    }.getOrNull()

    private fun bufferToBitmap(buffer: Any?): Bitmap? {
        if (buffer == null) return null
        for (name in listOf("asBitmap", "getBitmap", "createBitmap")) {
            val raw = runCatching {
                buffer.javaClass.getMethod(name).invoke(buffer) as? Bitmap
            }.getOrNull() ?: continue
            // getPixel throws on hardware bitmaps — force a software copy.
            return if (raw.config == Bitmap.Config.HARDWARE) {
                try {
                    raw.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    raw.recycle()
                }
            } else {
                raw
            }
        }
        return null
    }

    /**
     * Bar = a band of #353842 rows across (nearly) the full window width, with
     * Fenbi's brand-blue selected-tool icon inside it. Everything else (closed
     * page, unrendered black, other screens) reads as non-bar → closed.
     */
    private fun draftBarVisible(bmp: Bitmap): Boolean {
        if (bmp.width < SCREEN_W * 3 / 5) return false // wrong orientation
        val cols = intArrayOf(300, 700, 1100, 1500, 1900, 2300).filter { it < bmp.width }
        var firstDarkY = -1
        var lastDarkY = -1
        var darkRows = 0
        var y = 0
        while (y < bmp.height) {
            var dark = 0
            for (x in cols) if (isBarBg(bmp.getPixel(x, y))) dark++
            if (dark >= cols.size - 1) {
                if (firstDarkY < 0) firstDarkY = y
                lastDarkY = y
                darkRows++
            }
            y += 8
        }
        if (darkRows < 4) return false // no convincing bar
        // Selected tool: the brand-blue icon is the ONLY blue element in the
        // bar (icons otherwise render white/dim on #353842), so scan the whole
        // band instead of trusting fixed coordinates — the window surface may
        // be inset/offset relative to the screen (measured: +64px vertical).
        var blueCount = 0
        var sy = firstDarkY
        while (sy <= lastDarkY) {
            var sx = 300
            val xEnd = minOf(1400, bmp.width - 1)
            while (sx <= xEnd) {
                if (isBlue(bmp.getPixel(sx, sy)) && ++blueCount >= 20) return true
                sx += 4
            }
            sy += 4
        }
        logOnce("noBlueIcon") {
            // Every non-background pixel inside the band reveals where the
            // icons actually render (x, y and color) in one shot.
            val found = LinkedHashSet<String>()
            var sx = 0
            while (sx < bmp.width && found.size < 60) {
                var yy = firstDarkY
                while (yy <= lastDarkY && found.size < 60) {
                    val c = bmp.getPixel(sx, yy) and 0xFFFFFF
                    val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                    if (c != BAR_BG && !isBarBg(c) && maxOf(r, g, b) > 40) {
                        found += String.format("#%06X@(%d,%d)", c, sx, yy)
                    }
                    yy += 6
                }
                sx += 8
            }
            "no blue; band=[$firstDarkY..$lastDarkY] non-bg(${found.size}): ${found.joinToString()}"
        }
        return false
    }

    private fun isBarBg(c: Int): Boolean {
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        // Bar color #353842; pure black (unrendered surface) must NOT count.
        return r in 30..95 && g in 30..95 && b in 40..115 &&
            maxOf(r, g, b) - minOf(r, g, b) < 40
    }

    private fun isBlue(c: Int): Boolean {
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        return b > 150 && (b - r) > 50 && (b - g) > 25
    }

    // ---- diagnostics -------------------------------------------------------

    private fun diagnoseOnce(obj: Any, keyword: String, label: String) {
        logOnce("fields_$label") {
            val names = mutableListOf<String>()
            var c: Class<*>? = obj.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (f.name.contains(keyword, ignoreCase = true)) {
                        names += "${c.simpleName}.${f.name}"
                    }
                }
                c = c.superclass
            }
            if (names.isEmpty()) {
                // Keyword missed everything — dump all fields to find the real name.
                var cc: Class<*>? = obj.javaClass
                while (cc != null && cc != Any::class.java) {
                    for (f in cc.declaredFields) names += "${cc.simpleName}.${f.name}"
                    cc = cc.superclass
                }
            }
            "fields[$label] class=${obj.javaClass.name} (${names.size}): ${names.joinToString().take(4000)}"
        }
    }

    // ---- touch injection --------------------------------------------------

    private fun injectTap(x: Int, y: Int) {
        runCatching {
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(),
                1f, 1f, 0, 1f, 1f, 0, 0
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            // WAIT_FOR_RESULT makes the return value reflect the real dispatch
            // outcome; async injections were silently swallowed intermittently.
            var ok = injectWait(down)
            var retry = 0
            while (!ok && retry < 3) {
                SystemClock.sleep(40L)
                ok = injectWait(down)
                retry++
            }
            if (retry > 0) Log.d(TAG, "inject retries=$retry ok=$ok")
            if (ok) {
                injectHandler.postDelayed({
                    val upEvent = MotionEvent.obtain(
                        now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                        x.toFloat(), y.toFloat(), 1f, 1f, 0, 1f, 1f, 0, 0
                    ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    try {
                        if (!injectWait(upEvent)) Log.d(TAG, "injectTap: UP inject failed")
                    } finally {
                        upEvent.recycle()
                        down.recycle()
                    }
                }, 60L)
            } else {
                down.recycle()
                logOnce("noInject", "injectTap: injectInputEvent unavailable")
            }
        }.onFailure { logOnce("injectThrew") { "injectTap failed: $it" } }
    }

    private fun injectWait(event: MotionEvent): Boolean = inject(event, 1)

    private fun inject(event: MotionEvent, mode: Int): Boolean {
        imGlobalInject?.let { (method, inst) ->
            val r = runCatching { method.invoke(inst, event, 0) as Boolean }
                .onFailure { logOnce("injectThrow") { "inject threw: $it" } }
                .getOrDefault(false)
            if (!r) logOnce("injectFalse_${event.action}") { "inject returned false (global, action=${event.action})" }
            return r
        }
        imLegacyInject?.let { (method, inst) ->
            val r = runCatching { method.invoke(inst, event, 0) as Boolean }
                .onFailure { logOnce("injectThrow") { "inject threw: $it" } }
                .getOrDefault(false)
            if (!r) logOnce("injectFalse_${event.action}") { "inject returned false (legacy, action=${event.action})" }
            return r
        }
        return false
    }

    // ---- helpers -----------------------------------------------------------

    private fun logOnce(key: String, message: () -> String) {
        if (loggedKeys.add(key)) Log.d(TAG, message())
    }

    private fun logOnce(key: String, message: String) {
        if (loggedKeys.add(key)) Log.d(TAG, message)
    }

    private fun fieldOf(obj: Any, name: String): Any? = runCatching {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            val cur: Class<*> = c
            runCatching {
                val f = cur.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            }
            c = cur.superclass
        }
        null
    }.getOrNull()
}
