package com.volna.app.push.data

import com.volna.app.core.network.VolnaApiClient
import com.volna.app.push.PushRepository
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod

class KtorPushRepository(
    private val apiClient: VolnaApiClient,
) : PushRepository {
    override suspend fun registerToken(token: String, platform: String): Result<Unit> =
        apiClient.sendUnit("/auth/push-tokens", authorized = true) {
            method = HttpMethod.Post
            setBody(PushTokenRequestDto(token, platform))
        }

    override suspend fun deleteToken(token: String, platform: String): Result<Unit> =
        apiClient.sendUnit("/auth/push-tokens", authorized = true) {
            method = HttpMethod.Delete
            setBody(PushTokenRequestDto(token, platform))
        }
}
