package com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android.stylus

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import com.github.kyuubiran.ezxhelper.ClassUtils.loadClass
import com.github.kyuubiran.ezxhelper.HookFactory.`-Static`.createHooks
import com.github.kyuubiran.ezxhelper.finders.MethodFinder.`-Static`.methodFinder
import com.yifeplayte.maxmipadinput.hook.hooks.BaseHook
import com.yifeplayte.maxmipadinput.hook.utils.XSharedPreferences.getBoolean
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified H7 stylus side-button dispatch (ADR-0002). One hook replaces the
 * per-app relays: PAGE keys from the himax digitizer are offered to registered
 * [StylusKeyHandler]s in MainHook order; the first claimer owns the key pair
 * (DOWN consumed, UP always paired back to the same handler). Unclaimed keys
 * pass through untouched (MIUI long-press gestures, app behavior).
 *
 * Handlers registered (in order — append-only per ADR §4):
 * 1. DrawAnywhereKeyHandler — explicit overlay, clearest user intent.
 *
 * Shadow mode (ADR §7): `debug.stylus.router.shadow` != "0" keeps the Router
 * in log-only mode (no consumption) so it can run alongside the legacy relay
 * for behavior comparison; flip to "0" to switch to consuming mode.
 *
 * Self-test (ADR §6, three independent dimensions):
 * ```
 * debug.stylus.router.selftest=1          # full route + ring-buffer logging
 * debug.stylus.selftest.handler=<name>    # force one handler to claim
 * debug.stylus.selftest.keycode=309       # dispatch a forced keycode
 * ```
 * Selftest does NOT bypass the device filter: the event's device is
 * substituted with the real himax InputDevice so filtering matches the
 * physical pen exactly. Toggling selftest 1→0 dumps the ring buffer.
 */
object StylusKeyRouter : BaseHook() {
    override val key = "stylus_key_router"

    // Shadow phase gate: reuse the DrawAnywhere relay's switch — the user's
    // existing toggle governs the whole pipeline during migration (ADR §7).
    override val isEnabled get() = getBoolean("relay_stylus_keys_to_drawanywhere", false)

    private const val TAG = "StylusRouter"
    private const val RING_CAPACITY = 256
    private val EVIDENCE_KEYCODES = setOf(92, 93, 308, 309)

    private val handlers: List<StylusKeyHandler> by lazy {
        listOf(DrawAnywhereKeyHandler(appContext))
    }

    private val traceCounter = AtomicLong()
    private val loggedKeys: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    /** keyCode → claimed handler + DOWN timestamp. */
    private class Pairing(val handler: StylusKeyHandler, val downAtMs: Long, val traceId: Long)
    private val pairings = ConcurrentHashMap<Int, Pairing>()

    data class TraceEntry(
        val traceId: Long, val ts: Long, val keyCode: Int, val scanCode: Int,
        val deviceName: String, val source: Int, val handler: String,
        val claimed: Boolean, val heldMs: Long, val actionResult: String,
    )

    private val ring = ArrayDeque<TraceEntry>()
    private var prevSelfTest = false

    private val sysPropGet by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        }.getOrNull()
    }

    private fun prop(name: String): String = runCatching {
        sysPropGet?.invoke(null, name)?.toString() ?: ""
    }.getOrDefault("")

    private fun selfTest(): Boolean = prop("debug.stylus.router.selftest") == "1"

    /** Shadow unless explicitly disabled: `setprop debug.stylus.router.shadow 0`. */
    private fun shadowMode(): Boolean = prop("debug.stylus.router.shadow") != "0"

    override fun hook() {
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
                    route(param, event)
                }
            }
            Log.d(TAG, "hooked $className#$methodName (${methods.size} method(s)), handlers=${handlers.map { it.name }}")
            return
        }
        throw NoSuchMethodException("no candidate interceptKeyBeforeQueueing found")
    }

    /** system_server's own context, for provider calls and broadcasts. */
    private val appContext: Context? by lazy {
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("currentActivityThread").invoke(null)
                ?: return@runCatching null
            atClass.getMethod("getSystemContext").invoke(at) as Context
        }.getOrNull()
    }

    private fun route(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam, event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return
        if (event.keyCode !in EVIDENCE_KEYCODES) return

        // Ring-buffer dump trigger: selftest prop falling edge (1→0).
        val st = selfTest()
        if (prevSelfTest && !st) dumpRing()
        prevSelfTest = st

        // Drop stale pairings: a DOWN whose UP never arrived must not pair
        // with a much later UP.
        val nowMs = SystemClock.uptimeMillis()
        pairings.entries.removeAll { nowMs - it.value.downAtMs > 10_000L }

        // ADR-0002 §3: device identity is boot-unstable (himax-stylus vs
        // himax-touchscreen) — filter on the name prefix; scanCode 331/332 is
        // the stable per-button id and is logged for contract drift checks.
        val deviceName = event.device?.name ?: "null"
        val selfTest = selfTest()
        val isHimax = deviceName.contains("himax", ignoreCase = true)
        if (!isHimax && !selfTest) return

        // Selftest keycode forcing: dispatch a forced keycode (e.g. 309) to
        // exercise the keycode contract without a device that produces it.
        val keyCode = if (selfTest) {
            prop("debug.stylus.selftest.keycode").toIntOrNull() ?: event.keyCode
        } else {
            event.keyCode
        }
        if (event.repeatCount > 0) {
            // Auto-repeat: consumed only when a pair is already active.
            if (pairings.containsKey(keyCode)) param.result = 0
            return
        }

        val traceId = traceCounter.incrementAndGet()
        val scanCode = event.scanCode
        if (keyCode in intArrayOf(92, 93) && scanCode != 0 &&
            scanCode !in intArrayOf(331, 332)
        ) {
            Log.w(TAG, "trace=$traceId contract drift: keyCode=$keyCode with scanCode=$scanCode")
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            routeDown(param, event, keyCode, traceId, deviceName, scanCode, selfTest)
        } else {
            routeUp(param, keyCode, traceId, deviceName, scanCode, selfTest)
        }
    }

    private fun routeDown(
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
        event: KeyEvent, keyCode: Int, traceId: Long,
        deviceName: String, scanCode: Int, selfTest: Boolean,
    ) {
        val downAtMs = SystemClock.uptimeMillis()
        val handler = pickHandler(keyCode, traceId, selfTest)
        val claimed = handler != null
        if (handler != null) {
            pairings[keyCode] = Pairing(handler, downAtMs, traceId)
            handler.onDown(keyCode, downAtMs)
        }
        val consumed = claimed && !shadowMode()
        if (consumed) param.result = 0
        record(traceId, keyCode, scanCode, deviceName, event.source,
            handler?.name ?: "-", claimed, 0L,
            "down consumed=$consumed shadow=${shadowMode()}")
        Log.d(TAG, "trace=$traceId down key=$keyCode handler=${handler?.name} " +
            "claimed=$claimed consumed=$consumed shadow=${shadowMode()}")
    }

    private fun routeUp(
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
        keyCode: Int, traceId: Long, deviceName: String, scanCode: Int, selfTest: Boolean,
    ) {
        val pairing = pairings.remove(keyCode)
        if (pairing == null) {
            record(traceId, keyCode, scanCode, deviceName, -1, "-", false, 0L, "up unpaired")
            return // stray UP without our DOWN — never consume
        }
        val heldMs = SystemClock.uptimeMillis() - pairing.downAtMs
        // Pairing guarantee: the UP always reaches the claiming handler, even
        // if its claim() would be false now (ADR §2 — the handler decides).
        pairing.handler.onUp(keyCode, heldMs)
        val consumed = !shadowMode()
        if (consumed) param.result = 0
        record(traceId, keyCode, scanCode, deviceName, -1,
            pairing.handler.name, true, heldMs, "up consumed=$consumed")
        Log.d(TAG, "trace=${pairing.traceId}/${traceId} up key=$keyCode held=${heldMs}ms " +
            "handler=${pairing.handler.name} consumed=$consumed")
    }

    private fun pickHandler(keyCode: Int, traceId: Long, selfTest: Boolean): StylusKeyHandler? {
        val forced = prop("debug.stylus.selftest.handler")
        if (selfTest && forced.isNotEmpty()) {
            val h = handlers.firstOrNull { it.name == forced }
            if (h == null) Log.d(TAG, "trace=$traceId selftest handler '$forced' not found")
            return h
        }
        return handlers.firstOrNull { it.claim(keyCode) }
    }

    private fun record(
        traceId: Long, keyCode: Int, scanCode: Int, deviceName: String,
        source: Int, handler: String, claimed: Boolean, heldMs: Long, actionResult: String,
    ) {
        synchronized(ring) {
            ring.addLast(TraceEntry(traceId, System.currentTimeMillis(), keyCode,
                scanCode, deviceName, source, handler, claimed, heldMs, actionResult))
            while (ring.size > RING_CAPACITY) ring.removeFirst()
        }
    }

    private fun dumpRing() {
        synchronized(ring) {
            Log.d(TAG, "ring dump (${ring.size} entries):")
            for (e in ring) {
                Log.d(TAG, "ring t=${e.traceId} ts=${e.ts} key=${e.keyCode} scan=${e.scanCode} " +
                    "dev=${e.deviceName} src=0x${Integer.toHexString(e.source)} " +
                    "handler=${e.handler} claimed=${e.claimed} held=${e.heldMs} ${e.actionResult}")
            }
        }
    }
}
