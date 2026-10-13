package com.spinecare.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Install housekeeping. The app id changed from com.spinecare.app to
 *  com.spinecare.claude, so the old build stays installed next to this one
 *  until the user removes it; these helpers open the system uninstall dialog
 *  (the user confirms there) so nobody needs `adb uninstall`. */
object AppPackages {
    const val OLD_PACKAGE = "com.spinecare.app"

    fun installed(c: Context, pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION") c.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    fun oldInstalled(c: Context) = c.packageName != OLD_PACKAGE && installed(c, OLD_PACKAGE)

    /** System uninstall confirmation for [pkg]; falls back to its App info page. */
    fun uninstall(c: Context, pkg: String) {
        val uri = Uri.parse("package:$pkg")
        val delete = Intent(Intent.ACTION_DELETE, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { c.startActivity(delete) }.onFailure {
            runCatching { c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Android's text-to-speech settings (engine, voice data); the generic settings page if hidden. */
    fun openTtsSettings(c: Context) {
        runCatching { c.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { c.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}
