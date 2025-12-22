package com.meshcentral.agent

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

// The app operation string for PROJECT_MEDIA
// This can be set via: adb shell cmd appops set --user 0 <package> PROJECT_MEDIA allow
private const val OP_PROJECT_MEDIA = "android:project_media"
private const val TAG = "AppOpsUtils"

/**
 * Checks if the PROJECT_MEDIA app operation is set to "allowed" for this app.
 * This can be set via adb: adb shell cmd appops set --user 0 <package> PROJECT_MEDIA allow
 *
 * When PROJECT_MEDIA is pre-allowed, the app can start media projection without
 * showing the system permission dialog to the user.
 *
 * @param context The application context
 * @return true if PROJECT_MEDIA is set to MODE_ALLOWED, false otherwise
 */
fun isProjectMediaAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        // AppOpsManager.checkOpNoThrow was added in API 19, but PROJECT_MEDIA op in later versions
        if (BuildConfig.DEBUG) Log.d(TAG, "isProjectMediaAllowed: SDK < M, returning false")
        return false
    }

    return try {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                OP_PROJECT_MEDIA,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOpsManager.checkOpNoThrow(
                OP_PROJECT_MEDIA,
                Process.myUid(),
                context.packageName
            )
        }
        val result = mode == AppOpsManager.MODE_ALLOWED
        if (BuildConfig.DEBUG) Log.d(TAG, "isProjectMediaAllowed: mode=$mode (ALLOWED=${AppOpsManager.MODE_ALLOWED}), result=$result")
        result
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.e(TAG, "isProjectMediaAllowed: exception", e)
        false
    }
}