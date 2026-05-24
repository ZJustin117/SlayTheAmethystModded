package io.stamethyst.backend.mods.importing

import android.content.Context
import androidx.annotation.StringRes

internal fun Context.importString(@StringRes resId: Int, vararg formatArgs: Any?): String {
    return if (formatArgs.isEmpty()) {
        resources.getString(resId)
    } else {
        resources.getString(resId, *formatArgs)
    }
}
