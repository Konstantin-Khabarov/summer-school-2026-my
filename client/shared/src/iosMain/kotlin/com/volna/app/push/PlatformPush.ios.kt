package com.volna.app.push

import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSUserDefaults
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter

actual object PlatformPushPermission {
    actual val platform: String = "ios"

    actual suspend fun requestPermission(): PushPermissionResult {
        val deferred = CompletableDeferred<Boolean>()
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, _ ->
            deferred.complete(granted)
        }
        return if (deferred.await()) PushPermissionResult.Authorized else PushPermissionResult.Denied
    }
}

actual object PlatformPushPreferences : PushPreferences {
    private val defaults: NSUserDefaults get() = NSUserDefaults.standardUserDefaults

    actual override suspend fun isPermissionRequested(): Boolean =
        defaults.boolForKey(KEY_REQUESTED)

    actual override suspend fun markPermissionRequested() {
        defaults.setBool(true, forKey = KEY_REQUESTED)
    }

    actual override suspend fun deviceToken(): String {
        defaults.stringForKey(KEY_TOKEN)?.let { return it }
        val generated = generateRandomPushToken()
        defaults.setObject(generated, forKey = KEY_TOKEN)
        return generated
    }

    actual override suspend fun registeredToken(): String? =
        defaults.stringForKey(KEY_REGISTERED_TOKEN)

    actual override suspend fun setRegisteredToken(token: String?) {
        if (token == null) {
            defaults.removeObjectForKey(KEY_REGISTERED_TOKEN)
        } else {
            defaults.setObject(token, forKey = KEY_REGISTERED_TOKEN)
        }
    }

    private const val KEY_REQUESTED = "volna_push_permission_requested"
    private const val KEY_TOKEN = "volna_push_device_token"
    private const val KEY_REGISTERED_TOKEN = "volna_push_registered_token"
}
