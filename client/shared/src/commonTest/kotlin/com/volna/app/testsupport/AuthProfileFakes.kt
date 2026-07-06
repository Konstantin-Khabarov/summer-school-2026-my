package com.volna.app.testsupport

import com.volna.app.auth.AuthRepository
import com.volna.app.auth.RequestCodeResult
import com.volna.app.auth.VerifyCodeResult
import com.volna.app.domain.model.Client
import com.volna.app.domain.model.ClientId
import com.volna.app.domain.model.Phone
import com.volna.app.profile.ProfileRepository
import kotlinx.datetime.Instant

internal fun client(name: String?, phone: String = "+79991234567"): Client = Client(
    id = ClientId("client-1"),
    name = name,
    phone = Phone(phone),
    createdAt = Instant.parse("2026-06-01T00:00:00Z"),
)

internal class FakeAuthRepository(
    private val logoutResult: Result<Unit> = Result.success(Unit),
) : AuthRepository {
    private var requestCodeResult: Result<RequestCodeResult>? = null
    private var verifyCodeResult: Result<VerifyCodeResult>? = null
    var requestCodeCalls: Int = 0
        private set
    var verifyCodeCalls: Int = 0
        private set
    var logoutCalls: Int = 0
        private set

    fun enqueueRequestCode(result: Result<RequestCodeResult>) {
        requestCodeResult = result
    }

    fun enqueueVerifyCode(result: Result<VerifyCodeResult>) {
        verifyCodeResult = result
    }

    override suspend fun requestCode(phone: Phone): Result<RequestCodeResult> {
        requestCodeCalls += 1
        return requestCodeResult ?: error("FakeAuthRepository.requestCode() called without enqueueRequestCode()")
    }

    override suspend fun verifyCode(phone: Phone, code: String): Result<VerifyCodeResult> {
        verifyCodeCalls += 1
        return verifyCodeResult ?: error("FakeAuthRepository.verifyCode() called without enqueueVerifyCode()")
    }

    override suspend fun logout(): Result<Unit> {
        logoutCalls += 1
        return logoutResult
    }
}

internal class FakeProfileRepository : ProfileRepository {
    private var getProfileResult: Result<Client> = Result.failure(UnsupportedOperationException())
    private var updateNameResult: Result<Client> = Result.failure(UnsupportedOperationException())
    private var requestPhoneChangeCodeResult: Result<RequestCodeResult> = Result.failure(UnsupportedOperationException())
    private var confirmPhoneChangeResult: Result<Client> = Result.failure(UnsupportedOperationException())
    private var deleteAccountResult: Result<Unit> = Result.failure(UnsupportedOperationException())

    val callLog = mutableListOf<String>()
    val updateNameCalls: Int
        get() = callLog.count { it == "updateName" }

    fun enqueueGetProfile(result: Result<Client>) {
        getProfileResult = result
    }

    fun enqueueUpdateName(result: Result<Client>) {
        updateNameResult = result
    }

    fun enqueueRequestPhoneChangeCode(result: Result<RequestCodeResult>) {
        requestPhoneChangeCodeResult = result
    }

    fun enqueueConfirmPhoneChange(result: Result<Client>) {
        confirmPhoneChangeResult = result
    }

    fun enqueueDeleteAccount(result: Result<Unit>) {
        deleteAccountResult = result
    }

    override suspend fun getProfile(): Result<Client> {
        callLog += "getProfile"
        return getProfileResult
    }

    override suspend fun updateName(name: String): Result<Client> {
        callLog += "updateName"
        return updateNameResult
    }

    override suspend fun deleteAccount(): Result<Unit> {
        callLog += "deleteAccount"
        return deleteAccountResult
    }

    override suspend fun requestPhoneChangeCode(newPhone: Phone): Result<RequestCodeResult> {
        callLog += "requestPhoneChangeCode"
        return requestPhoneChangeCodeResult
    }

    override suspend fun confirmPhoneChange(newPhone: Phone, code: String): Result<Client> {
        callLog += "confirmPhoneChange"
        return confirmPhoneChangeResult
    }
}
