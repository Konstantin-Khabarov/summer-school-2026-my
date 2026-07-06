package com.volna.app.testsupport

import com.volna.app.push.PushPreferences
import com.volna.app.push.PushRepository

internal class RecordingPushRepository : PushRepository {
    var deleteTokenCalls: Int = 0
        private set
    var lastDeletedToken: String? = null
        private set

    override suspend fun registerToken(token: String, platform: String): Result<Unit> = Result.success(Unit)

    override suspend fun deleteToken(token: String, platform: String): Result<Unit> {
        deleteTokenCalls += 1
        lastDeletedToken = token
        return Result.success(Unit)
    }
}

internal class FakePushPreferences(
    private var registeredTokenValue: String? = null,
) : PushPreferences {
    override suspend fun isPermissionRequested(): Boolean = true
    override suspend fun markPermissionRequested() {}
    override suspend fun deviceToken(): String = "device-token"
    override suspend fun registeredToken(): String? = registeredTokenValue
    override suspend fun setRegisteredToken(token: String?) {
        registeredTokenValue = token
    }
}
