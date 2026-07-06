package com.volna.app.push

import kotlin.random.Random

interface PushRepository {
    suspend fun registerToken(token: String, platform: String): Result<Unit>
    suspend fun deleteToken(token: String, platform: String): Result<Unit>
}

// LOGIC-007 local storage: push_permission_requested must survive logout/relogin (it's a
// per-device flag, not a per-account one), so it lives outside SessionStorage.
interface PushPreferences {
    suspend fun isPermissionRequested(): Boolean
    suspend fun markPermissionRequested()
    suspend fun deviceToken(): String
    suspend fun registeredToken(): String?
    suspend fun setRegisteredToken(token: String?)
}

expect object PlatformPushPreferences : PushPreferences {
    override suspend fun isPermissionRequested(): Boolean
    override suspend fun markPermissionRequested()
    override suspend fun deviceToken(): String
    override suspend fun registeredToken(): String?
    override suspend fun setRegisteredToken(token: String?)
}

enum class PushPermissionResult {
    Authorized,
    Denied,
}

expect object PlatformPushPermission {
    val platform: String
    suspend fun requestPermission(): PushPermissionResult
}

// No FCM/APNs project is wired up for this demo backend (same spirit as OTP being returned
// in the API response instead of sent by SMS) — this stands in for a real platform push token.
internal fun generateRandomPushToken(): String {
    val chars = "0123456789abcdef"
    return buildString { repeat(32) { append(chars[Random.nextInt(chars.length)]) } }
}
