package com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.fenbi

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import com.github.kyuubiran.ezxhelper.HookFactory.`-Static`.createHooks
import com.github.kyuubiran.ezxhelper.finders.MethodFinder.`-Static`.methodFinder
import com.yifeplayte.maxmipadinput.hook.hooks.BaseHook
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Modifier

/**
 * Stylus side-button control for the Fenbi (粉笔) scratch paper (草稿纸).
 *
 * The himax-stylus buttons arrive here as ordinary PAGE_UP / PAGE_DOWN key
 * events (mapped by himax-stylus.kl; verified to reach the exercise activity's
 * ViewRootImpl). The scratch toolbar is a plain ViewGroup of ImageViews with
 * stable resource ids, so instead of touching the obfuscated drawing logic,
 * this hook looks the buttons up by id and performs their click handlers:
 *
 *  - PAGE_UP (lower button): scratch_erase — toggle pen/eraser
 *  - PAGE_DOWN (upper button): scratch_delete — clear the scratch paper
 *
 * Keys are only consumed while the toolbar is actually on screen (isShown +
 * visible rect + window focus), so every other Fenbi screen keeps its stock
 * key behavior. Actions fire on UP within TAP_WINDOW_MS to stay clear of
 * MIUI's ~380ms stylus long-press gesture, which intercepts holds in
 * system_server before the app ever sees the event.
 */
object FenbiScratchStylusKeys : BaseHook() {
    override val key = "fenbi_scratch_stylus_keys"
    private const val TAG = "FenbiScratchKeys"

    /** Press→release window for a hold to count as a tap. MIUI's stylus
     *  long-press gesture triggers at ~380ms in system_server; stay below. */
    private const val TAP_WINDOW_MS = 350L

    private val downAtMs = mutableMapOf<Int, Long>()
    private var eraseId = View.NO_ID
    private var deleteId = View.NO_ID
    private var idsResolved = false

    override fun hook() {
        // EzXHelper's methodFinder() also yields inherited interface methods,
        // so abstract members must be filtered out explicitly.
        Activity::class.java.methodFinder().filter {
            name == "dispatchKeyEvent" &&
                parameterTypes.size == 1 &&
                !Modifier.isAbstract(modifiers)
        }.toList().createHooks {
            before { param ->
                val event = param.args.getOrNull(0) as? KeyEvent ?: return@before
                handleKey(param, event)
            }
        }
        Log.d(TAG, "hooked Activity#dispatchKeyEvent")
    }

    private fun handleKey(param: XC_MethodHook.MethodHookParam, event: KeyEvent) {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_PAGE_UP && keyCode != KeyEvent.KEYCODE_PAGE_DOWN) return
        if (event.repeatCount > 0) {
            // Auto-repeat while held: consume so it never scrolls anything.
            if (event.action == KeyEvent.ACTION_DOWN) param.result = true
            return
        }
        val deviceName = event.device?.name ?: return
        if (!deviceName.contains("himax", ignoreCase = true)) return
        val activity = param.thisObject as? Activity ?: return
        if (!resolveIds(activity)) return
        if (!activity.hasWindowFocus()) return
        val targetId = if (keyCode == KeyEvent.KEYCODE_PAGE_UP) eraseId else deleteId
        val target = activity.window?.decorView?.findViewById<View>(targetId) ?: return
        if (!target.isShown || !target.getGlobalVisibleRect(Rect())) return

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                downAtMs[keyCode] = SystemClock.uptimeMillis()
                param.result = true
                Log.d(TAG, "down key=$keyCode")
            }
            KeyEvent.ACTION_UP -> {
                val down = downAtMs.remove(keyCode) ?: return // stray UP — don't consume
                val heldMs = SystemClock.uptimeMillis() - down
                param.result = true
                if (heldMs <= TAP_WINDOW_MS) {
                    val name = if (keyCode == KeyEvent.KEYCODE_PAGE_UP) "scratch_erase" else "scratch_delete"
                    Log.d(TAG, "tap key=$keyCode held=${heldMs}ms -> $name.performClick")
                    target.performClick()
                } else {
                    Log.d(TAG, "hold key=$keyCode held=${heldMs}ms -> ignored")
                }
            }
        }
    }

    private fun resolveIds(activity: Activity): Boolean {
        if (idsResolved) return eraseId != View.NO_ID && deleteId != View.NO_ID
        eraseId = activity.resources.getIdentifier("scratch_erase", "id", activity.packageName)
        deleteId = activity.resources.getIdentifier("scratch_delete", "id", activity.packageName)
        idsResolved = true
        if (eraseId == View.NO_ID || deleteId == View.NO_ID) {
            Log.d(TAG, "scratch ids not found — Fenbi update renamed them; keys pass through")
            return false
        }
        return true
    }
}
