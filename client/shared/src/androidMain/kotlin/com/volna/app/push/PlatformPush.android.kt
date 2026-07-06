package com.volna.app.push

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

actual object PlatformPushPermission {
    actual val platform: String = "android"

    private var appContext: Context? = null
    private var launcher: ActivityResultLauncher<String>? = null
    private var pending: CompletableDeferred<Boolean>? = null

    fun initialize(activity: ComponentActivity) {
        appContext = activity.applicationContext
        launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending?.complete(granted)
            pending = null
        }
    }

    actual suspend fun requestPermission(): PushPermissionResult {
        val context = appContext ?: return PushPermissionResult.Denied
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Below Android 13 notifications don't require a runtime permission.
            return PushPermissionResult.Authorized
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return PushPermissionResult.Authorized
        }
        val activeLauncher = launcher ?: return PushPermissionResult.Denied
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        activeLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return if (deferred.await()) PushPermissionResult.Authorized else PushPermissionResult.Denied
    }
}

actual object PlatformPushPreferences : PushPreferences {
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    actual override suspend fun isPermissionRequested(): Boolean =
        preferences?.getBoolean(KEY_REQUESTED, false) ?: false

    actual override suspend fun markPermissionRequested() {
        preferences?.edit()?.putBoolean(KEY_REQUESTED, true)?.apply()
    }

    actual override suspend fun deviceToken(): String {
        preferences?.getString(KEY_TOKEN, null)?.let { return it }
        val generated = generateRandomPushToken()
        preferences?.edit()?.putString(KEY_TOKEN, generated)?.apply()
        return generated
    }

    actual override suspend fun registeredToken(): String? =
        preferences?.getString(KEY_REGISTERED_TOKEN, null)

    actual override suspend fun setRegisteredToken(token: String?) {
        preferences?.edit()?.apply {
            if (token == null) remove(KEY_REGISTERED_TOKEN) else putString(KEY_REGISTERED_TOKEN, token)
        }?.apply()
    }

    private const val PREFERENCES_NAME = "volna_push"
    private const val KEY_REQUESTED = "permission_requested"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_REGISTERED_TOKEN = "registered_token"
}
