package com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
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
    private const val TARGET_PACKAGE = "com.shezik.drawanywhere"
    private const val AUTHORITY = "content://com.shezik.drawanywhere.keystate"
    private const val ACTION_STYLUS_KEY = "com.shezik.drawanywhere.STYLUS_KEY"
    private const val CACHE_TTL_MS = 200L
    private val stylusKeyCodes =
        setOf(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)

    private var cachedActive = false
    private var cachedAtMs = 0L

    private val systemContext: Context? by lazy {
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
                .getMethod("systemMain").invoke(null)
            activityThread.javaClass.getMethod("getSystemContext")
                .invoke(activityThread) as Context
        }.getOrNull()
    }

    private fun isInterceptActive(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAtMs < CACHE_TTL_MS) return cachedActive
        val active = runCatching {
            systemContext?.contentResolver
                ?.call(Uri.parse(AUTHORITY), "intercept_active", null, null)
                ?.getBoolean("active", false) ?: false
        }.getOrDefault(false)
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
        val pwm = loadClass("com.android.server.policy.PhoneWindowManager")
        val methods = pwm.methodFinder().filter {
            name == "interceptKeyBeforeQueueing" &&
                parameterTypes.size == 2 &&
                java.lang.reflect.Modifier.isAbstract(modifiers).not()
        }.toList()
        if (methods.isEmpty()) {
            throw NoSuchMethodException(
                "interceptKeyBeforeQueueing(KeyEvent, int) not found on PhoneWindowManager")
        }
        methods.createHooks {
            before { param ->
                val event = param.args.getOrNull(0) as? KeyEvent ?: return@before
                if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
                    return@before
                }
                if (event.keyCode !in stylusKeyCodes) return@before
                val deviceName = event.device?.name ?: return@before
                if (!deviceName.contains("himax", ignoreCase = true)) return@before

                if (isInterceptActive()) {
                    // Returning 0 clears ACTION_PASS_TO_USER: InputDispatcher
                    // drops the event before queuing it.
                    param.result = 0
                    if (event.repeatCount == 0) relayKey(event)
                }
            }
        }
    }
}
