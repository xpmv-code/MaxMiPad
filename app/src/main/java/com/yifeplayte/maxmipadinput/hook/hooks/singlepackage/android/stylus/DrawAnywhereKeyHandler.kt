package com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android.stylus

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * DrawAnywhere side-button handler (ADR-0002): while the DrawAnywhere overlay
 * canvas is active, stylus PAGE keys are forwarded to its StylusKeyReceiver
 * (hold = eraser, tap = switch tool); otherwise keys pass through untouched.
 *
 * Logic migrated verbatim from RelayStylusKeysToDrawAnywhere (the standalone
 * hook this handler replaces). One intentional deviation: the UP broadcast is
 * now ALWAYS sent for a claimed pair — the old hook skipped the UP when the
 * canvas closed mid-press, leaving DrawAnywhere's hold state dangling.
 *
 * Keycodes: PAGE_UP(92)/PAGE_DOWN(93) only — 308/309 deliberately not
 * claimed (ADR-0002 §3 "行为不变" boundary).
 */
class DrawAnywhereKeyHandler(private val appContext: Context?) : StylusKeyHandler {
    override val name = "drawanywhere"

    companion object {
        private const val TAG = "StylusRouter"
        private const val TARGET_PACKAGE = "com.shezik.drawanywhere"
        private const val AUTHORITY = "content://com.shezik.drawanywhere.keystate"
        private const val ACTION_STYLUS_KEY = "com.shezik.drawanywhere.STYLUS_KEY"
        private const val CACHE_TTL_MS = 200L
    }

    private var cachedActive = false
    private var cachedAtMs = 0L

    /** Provider check with a 200ms cache (verbatim from the old relay). */
    private fun isCanvasActive(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAtMs < CACHE_TTL_MS) return cachedActive
        val ctx = appContext
        val active = if (ctx == null) {
            Log.d(TAG, "claim: appContext unavailable")
            false
        } else {
            runCatching {
                ctx.contentResolver
                    .call(android.net.Uri.parse(AUTHORITY), "intercept_active", null, null)
                    ?.getBoolean("active", false) ?: false
            }.onFailure {
                Log.d(TAG, "claim: provider call failed: $it")
            }.getOrDefault(false)
        }
        cachedActive = active
        cachedAtMs = now
        return active
    }

    /** Claim = PAGE keycodes AND the DrawAnywhere canvas currently active. */
    override fun claim(keyCode: Int): Boolean =
        (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) &&
            isCanvasActive()

    override fun onDown(keyCode: Int, downAtMs: Long) {
        relay(keyCode, KeyEvent.ACTION_DOWN, downAtMs)
    }

    override fun onUp(keyCode: Int, heldMs: Long) {
        // Always relay the UP: DrawAnywhere's controller owns hold/tap timing
        // and needs the pair to close even if the canvas closed mid-press.
        relay(keyCode, KeyEvent.ACTION_UP, SystemClock.uptimeMillis())
    }

    private fun relay(keyCode: Int, action: Int, eventTime: Long) {
        val context = appContext ?: return
        val intent = Intent(ACTION_STYLUS_KEY)
            .setPackage(TARGET_PACKAGE)
            .putExtra("keycode", keyCode)
            .putExtra("action", action)
            .putExtra("eventTime", eventTime)
        context.sendBroadcast(intent)
    }
}
