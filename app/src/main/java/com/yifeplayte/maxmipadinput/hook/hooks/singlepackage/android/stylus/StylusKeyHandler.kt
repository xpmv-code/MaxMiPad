package com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android.stylus

/**
 * A consumer of the H7 stylus side-button key pairs, registered with
 * [StylusKeyRouter] (ADR-0002).
 *
 * Pairing contract: the Router asks [claim] once per key DOWN; a handler that
 * claims owns that key pair — the matching UP is ALWAYS delivered to
 * [onUp], even if claim() would return false by then. Whether to actually
 * perform the action at UP time is the handler's decision (state may have
 * changed mid-press); the Router only guarantees the pairing.
 *
 * Registered handlers must be appended to the Router's list in MainHook
 * order, with the "why here" justification in the ADR.
 */
interface StylusKeyHandler {
    val name: String

    /** DOWN time: does this handler want this key pair right now? */
    fun claim(keyCode: Int): Boolean

    /** The DOWN was consumed on this handler's behalf. */
    fun onDown(keyCode: Int, downAtMs: Long) {}

    /**
     * The UP paired with this handler's DOWN. heldMs = UP - DOWN. The handler
     * decides whether the action still applies.
     */
    fun onUp(keyCode: Int, heldMs: Long) {}
}
