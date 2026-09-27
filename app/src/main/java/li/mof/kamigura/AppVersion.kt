package li.mof.kamigura

import android.content.Context

/**
 * versionName of the installed package, so a release never has to be written down
 * in the UI by hand.
 */
internal fun appVersionName(context: Context): String = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}.getOrDefault("")
