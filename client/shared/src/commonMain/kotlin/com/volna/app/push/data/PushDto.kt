package com.volna.app.push.data

import kotlinx.serialization.Serializable

@Serializable
data class PushTokenRequestDto(
    val token: String,
    val platform: String,
)
