package com.yifeplayte.maxmipadinput.hook.hooks.singlepackage

import com.yifeplayte.maxmipadinput.hook.hooks.BasePackage
import com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android.DisableFixedOrientation
import com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android.FenbiScratchTapRelay
import com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android.IgnoreStylusKeyGesture
import com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android.NoMagicPointer
import com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android.RelayStylusKeysToDrawAnywhere
import com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android.RemoveStylusBluetoothRestriction
import com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android.RestoreEsc
import com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.android.stylus.StylusKeyRouter

object Android : BasePackage() {
    override val packageName = "android"
    override val hooks = setOf(
        DisableFixedOrientation,
        FenbiScratchTapRelay,
        // ADR-0002: shadow-phase router — runs log-only alongside the legacy
        // relay; flip debug.stylus.router.shadow to 0 to switch consumption.
        StylusKeyRouter,
        IgnoreStylusKeyGesture,
        NoMagicPointer,
        RelayStylusKeysToDrawAnywhere,
        RemoveStylusBluetoothRestriction,
        RestoreEsc,
    )
}