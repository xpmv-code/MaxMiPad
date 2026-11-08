package com.yifeplayte.maxmipadinput.hook.hooks.singlepackage

import com.yifeplayte.maxmipadinput.hook.hooks.BasePackage
import com.yifeplayte.maxmipadinput.hook.hooks.singlepackage.fenbi.FenbiScratchStylusKeys

object Fenbi : BasePackage() {
    override val packageName = "com.fenbi.android.servant"
    override val hooks = setOf(
        FenbiScratchStylusKeys,
    )
}
