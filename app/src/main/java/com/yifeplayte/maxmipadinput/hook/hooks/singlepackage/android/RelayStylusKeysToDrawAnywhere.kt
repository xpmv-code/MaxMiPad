package com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.github.kyuubiran.ezxhelper.ClassUtils.loadClass
import com.github.kyuubiran.ezxhelper.HookFactory.`-Static`.createHooks
import com.github.kyuubiran.ezxhelper.finders.MethodFinder.`-Static`.methodFinder
import com.yifeplayte.maxmipadinput.hook.hooks.BaseHook

/**
 * Relays stylus side-button keys (PAGE_UP / PAGE_DOWN from the himax digitizer,
 * mapped by himax-stylus.kl) to DrawAnywhere when its overlay canvas is active.
 *
 * DrawAnywhere's own accessibility key filter cannot receive key events on
 * HyperOS (key filtering is restricted to privileged apps), so this hook runs
 * in system_server: it asks DrawAnywhere via a ContentProvider whether the
 * overlay canvas is visible; if so, the key event is dropped and forwarded by
 * broadcast to DrawAnywhere's StylusKeyReceiver, which implements
 * hold-for-eraser / tap-to-switch-tool. While the canvas is hidden or in
 * passthrough mode the key is untouched, so page turning in Mi Notes and every
 * other app keeps working.
 *
 * Suppression happens in interceptKeyBeforeQueueing by returning 0 (clearing
 * ACTION_PASS_TO_USER), which makes InputDispatcher discard the event before
 * it is queued — and before MIUI's stylus page-key handling inside
 * interceptKeyBeforeDispatching can run. Note: EzXHelper's methodFinder()
 * also yields inherited interface methods, so abstract members must be
 * filtered out explicitly.
 */
object RelayStylusKeysToDrawAnywhere : BaseHook() {
    override val key = "relay_stylus_keys_to_drawanywhere"
    private const val TAG = "KeyRelay"
    private const val TARGET_PACKAGE = "com.shezik.drawanywhere"
    private const val AUTHORITY = "content://com.shezik.drawanywhere.keystate"
    private const val ACTION_STYLUS_KEY = "com.shezik.drawanywhere.STYLUS_KEY"
    private const val CACHE_TTL_MS = 200L
    private val stylusKeyCodes =
        setOf(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)

    /** ADR-0002 §9.1: keycodes whose arrival metadata is logged verbatim. */
    private val EVIDENCE_KEYCODES =
        setOf(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY)

    private var cachedActive = false
    private var cachedAtMs = 0L

    private val systemContext: Context? by lazy {
        runCatching {
            // ActivityThread.systemMain() would create a NEW ActivityThread
            // instance every call and blows up inside system_server — use the
            // running singleton instead.
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("currentActivityThread").invoke(null)
                ?: return@runCatching null
            atClass.getMethod("getSystemContext").invoke(at) as Context
        }.getOrNull()
    }

    private fun isInterceptActive(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAtMs < CACHE_TTL_MS) return cachedActive
        val ctx = systemContext
        val active = if (ctx == null) {
            Log.d(TAG, "isInterceptActive: systemContext unavailable")
            false
        } else {
            runCatching {
                ctx.contentResolver
                    .call(Uri.parse(AUTHORITY), "intercept_active", null, null)
                    ?.getBoolean("active", false) ?: false
            }.onFailure {
                Log.d(TAG, "isInterceptActive: provider call failed: $it")
            }.getOrDefault(false)
        }
        cachedActive = active
        cachedAtMs = now
        return active
    }

    private fun relayKey(event: KeyEvent) {
        val context = systemContext ?: return
        val intent = Intent(ACTION_STYLUS_KEY)
            .setPackage(TARGET_PACKAGE)
            .putExtra("keycode", event.keyCode)
            .putExtra("action", event.action)
            .putExtra("eventTime", event.eventTime)
        context.sendBroadcast(intent)
    }

    override fun hook() {
        // HyperOS's runtime policy is MiuiPhoneWindowManager (miui-services.jar),
        // which OVERRIDES interceptKeyBeforeQueueing — hooking the AOSP
        // PhoneWindowManager implementation never fires. Try the concrete MIUI
        // class first, then fall back to older/AOSP shapes for other ROMs.
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
                    // ADR-0002 §9.1 keyIn evidence: record exactly what the
                    // physical pen delivers here (keycode/scanCode/source/
                    // device) before any filtering — decides the Router's
                    // keycode contract (92/93 vs 308/309). No behavior change.
                    if (event.keyCode in EVIDENCE_KEYCODES && event.repeatCount == 0) {
                        Log.d(TAG, "keyIn keyCode=${event.keyCode} scanCode=${event.scanCode} " +
                            "source=0x${Integer.toHexString(event.source)} " +
                            "device=${event.device?.name} deviceId=${event.deviceId} " +
                            "action=${event.action} flags=0x${Integer.toHexString(event.flags)}")
                    }
                    if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
                        return@before
                    }
                    if (event.keyCode !in stylusKeyCodes) return@before
                    val deviceName = event.device?.name ?: "null"
                    if (!deviceName.contains("himax", ignoreCase = true)) {
                        Log.d(TAG, "ignore key=${event.keyCode} action=${event.action} device=$deviceName")
                        return@before
                    }
                    val active = isInterceptActive()
                    Log.d(TAG, "key=${event.keyCode} action=${event.action} device=$deviceName active=$active")
                    if (active) {
                        // Returning 0 clears ACTION_PASS_TO_USER: InputDispatcher
                        // drops the event before queuing it.
                        param.result = 0
                        if (event.repeatCount == 0) relayKey(event)
                    }
                }
            }
            Log.d(TAG, "hooked $className#$methodName (${methods.size} method(s))")
            return
        }
        throw NoSuchMethodException("no candidate interceptKeyBeforeQueueing found on Miui/Base/AOSP policy")
    }
}
