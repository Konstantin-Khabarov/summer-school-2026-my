package com.volna.app.push

import kotlinx.browser.localStorage

// LOGIC-007 scopes push to native mobile apps (iOS/Android) only — web has no permission
// dialog to request, so this always reports Denied without ever registering a token.
actual object PlatformPushPermission {
    actual val platform: String = "web"

    actual suspend fun requestPermission(): PushPermissionResult = PushPermissionResult.Denied
}

actual object PlatformPushPreferences : PushPreferences {
    actual override suspend fun isPermissionRequested(): Boolean =
        localStorage.getItem(KEY_REQUESTED) != null

    actual override suspend fun markPermissionRequested() {
        localStorage.setItem(KEY_REQUESTED, "true")
    }

    actual override suspend fun deviceToken(): String = ""

    actual override suspend fun registeredToken(): String? = null

    actual override suspend fun setRegisteredToken(token: String?) = Unit

    private const val KEY_REQUESTED = "volna_push_permission_requested"
}
