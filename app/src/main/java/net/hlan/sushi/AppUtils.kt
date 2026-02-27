package net.hlan.sushi

import android.content.Context
import android.os.Build

object AppUtils {
    fun getAppVersionInfo(context: Context): AppVersionInfo {
        return runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.debug_info_unknown)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            AppVersionInfo(versionName, versionCode.toString())
        }.getOrElse {
            AppVersionInfo(
                context.getString(R.string.debug_info_unknown),
                context.getString(R.string.debug_info_unknown)
            )
        }
    }
}

data class AppVersionInfo(
    val name: String,
    val code: String
)
