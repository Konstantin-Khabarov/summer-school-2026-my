package com.volna.app.auth.presentation

import com.volna.app.auth.AuthRepository
import com.volna.app.auth.RequestCodeResult
import com.volna.app.auth.VerifyCodeResult
import com.volna.app.core.error.ApiErrorCode
import com.volna.app.core.error.AppFailure
import com.volna.app.core.error.AppFailureException
import com.volna.app.core.ui.ActionStatus
import com.volna.app.profile.ProfileRepository
import com.volna.app.testsupport.FakeAuthRepository
import com.volna.app.testsupport.FakeProfileRepository
import com.volna.app.testsupport.client
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthStoreTest {
    @Test
    fun phoneChangedSanitizesInputAndClearsErrors() = runTest {
        val store = newStore()
        store.accept(AuthIntent.RequestCode)
        assertEquals("Похоже, номер введён не полностью", store.state.value.fieldError)

        store.accept(AuthIntent.PhoneChanged("+7 (999) 123-45-67"))

        val state = store.state.value
        assertEquals("9991234567", state.phoneInput)
        assertNull(state.fieldError)
        assertNull(state.message)
    }

    @Test
    fun requestCodeWithIncompletePhoneSetsFieldErrorWithoutCallingRepository() = runTest {
        val repository = FakeAuthRepository()
        val store = newStore(authRepository = repository)
        store.accept(AuthIntent.PhoneChanged("999"))

        store.accept(AuthIntent.RequestCode)

        assertEquals("Похоже, номер введён не полностью", store.state.value.fieldError)
        assertEquals(0, repository.requestCodeCalls)
    }

    @Test
    fun requestCodeSuccessMovesToOtpStepAndStartsResendTimer() = runTest {
        val repository = FakeAuthRepository()
        repository.enqueueRequestCode(Result.success(RequestCodeResult(ttlSeconds = 300, resendAfterSeconds = 60)))
        val store = newStore(authRepository = repository)
        store.accept(AuthIntent.PhoneChanged("9991234567"))

        store.accept(AuthIntent.RequestCode)
        yield()
        yield()

        val state = store.state.value
        assertEquals(AuthStep.Otp, state.step)
        assertEquals(300, state.ttlSeconds)
        assertEquals(60, state.resendAfterSeconds)
        assertEquals(60, state.resendSecondsRemaining)
        assertEquals(ActionStatus.Idle, state.actionStatus)
    }

    @Test
    fun requestCodeFailureTooManyRequestsStartsTimerFromDefaultFallback() = runTest {
        val repository = FakeAuthRepository()
        repository.enqueueRequestCode(
            Result.failure(AppFailureException(AppFailure.Api(ApiErrorCode.TooManyRequests, "too many"))),
        )
        val store = newStore(authRepository = repository)
        store.accept(AuthIntent.PhoneChanged("9991234567"))

        store.accept(AuthIntent.RequestCode)
        yield()
        yield()

        val state = store.state.value
        assertEquals(60, state.resendSecondsRemaining)
        assertEquals("Повторная отправка будет доступна после таймера", state.message)
        assertEquals(ActionStatus.Idle, state.actionStatus)
    }

    @Test
    fun verifyCodeWithInvalidFormatSetsFieldErrorWithoutCallingRepository() = runTest {
        val repository = FakeAuthRepository()
        val store = newStore(authRepository = repository)
        store.accept(AuthIntent.CodeChanged("12"))

        store.accept(AuthIntent.VerifyCode)

        assertEquals("Введите код из SMS", store.state.value.fieldError)
        assertEquals(0, repository.verifyCodeCalls)
    }

    @Test
    fun verifyCodeSuccessForNewClientMovesToNameStep() = runTest {
        val client = client(name = null)
        val repository = FakeAuthRepository()
        repository.enqueueVerifyCode(Result.success(VerifyCodeResult(token = "token", client = client, isNew = true)))
        val store = newStore(authRepository = repository)
        store.accept(AuthIntent.PhoneChanged("9991234567"))
        store.accept(AuthIntent.CodeChanged("1234"))

        store.accept(AuthIntent.VerifyCode)
        yield()
        yield()

        val state = store.state.value
        assertEquals(AuthStep.Name, state.step)
        assertEquals(client, state.client)
        assertEquals(ActionStatus.Idle, state.actionStatus)
    }

    @Test
    fun verifyCodeSuccessForExistingClientEmitsAuthenticatedEffect() = runTest {
        val client = client(name = "Мария")
        val repository = FakeAuthRepository()
        repository.enqueueRequestCode(Result.success(RequestCodeResult(ttlSeconds = 300, resendAfterSeconds = 60)))
        repository.enqueueVerifyCode(Result.success(VerifyCodeResult(token = "token", client = client, isNew = false)))
        val store = newStore(authRepository = repository)
        store.accept(AuthIntent.PhoneChanged("9991234567"))
        store.accept(AuthIntent.RequestCode)
        yield()
        yield()
        store.accept(AuthIntent.CodeChanged("1234"))

        store.accept(AuthIntent.VerifyCode)
        yield()
        yield()
        val effect = store.effects()

        assertEquals(AuthEffect.Authenticated, effect)
        assertEquals(AuthStep.Otp, store.state.value.step)
        assertEquals(ActionStatus.Idle, store.state.value.actionStatus)
    }

    @Test
    fun verifyCodeFailureShowsMessagePerErrorCode() = runTest {
        val cases = listOf(
            ApiErrorCode.InvalidCode to "Код неверен или просрочен. Запросите новый код",
            ApiErrorCode.TooManyRequests to "Слишком много попыток. Запросите новый код",
        )
        for ((code, expectedMessage) in cases) {
            val repository = FakeAuthRepository()
            repository.enqueueVerifyCode(Result.failure(AppFailureException(AppFailure.Api(code, "err"))))
            val store = newStore(authRepository = repository)
            store.accept(AuthIntent.PhoneChanged("9991234567"))
            store.accept(AuthIntent.CodeChanged("1234"))

            store.accept(AuthIntent.VerifyCode)
            yield()
            yield()

            assertEquals(expectedMessage, store.state.value.message)
        }
    }

    @Test
    fun continueWithNameWithEmptyNameSetsFieldErrorWithoutCallingRepository() = runTest {
        val profileRepository = FakeProfileRepository()
        val store = newStore(profileRepository = profileRepository)

        store.accept(AuthIntent.ContinueWithName)

        assertEquals("Укажите, как к вам обращаться", store.state.value.fieldError)
        assertEquals(0, profileRepository.updateNameCalls)
    }

    @Test
    fun continueWithNameSuccessEmitsAuthenticatedEffect() = runTest {
        val client = client(name = "Мария")
        val profileRepository = FakeProfileRepository()
        profileRepository.enqueueUpdateName(Result.success(client))
        val store = newStore(profileRepository = profileRepository)
        store.accept(AuthIntent.NameChanged("Мария"))

        store.accept(AuthIntent.ContinueWithName)
        yield()
        yield()
        val effect = store.effects()

        assertEquals(AuthEffect.Authenticated, effect)
        assertEquals(client, store.state.value.client)
    }

    @Test
    fun continueWithNameUnauthorizedResetsToPhoneStepPreservingPhoneInput() = runTest {
        val profileRepository = FakeProfileRepository()
        profileRepository.enqueueUpdateName(Result.failure(AppFailureException(AppFailure.Unauthorized)))
        val store = newStore(profileRepository = profileRepository)
        store.accept(AuthIntent.PhoneChanged("9991234567"))
        store.accept(AuthIntent.NameChanged("Мария"))

        store.accept(AuthIntent.ContinueWithName)
        yield()
        yield()

        val state = store.state.value
        assertEquals(AuthStep.Phone, state.step)
        assertEquals("9991234567", state.phoneInput)
        assertEquals("Сессия истекла, войдите снова", state.message)
    }

    @Test
    fun backToPhoneResetsOtpRelatedState() = runTest {
        val repository = FakeAuthRepository()
        repository.enqueueRequestCode(Result.success(RequestCodeResult(ttlSeconds = 300, resendAfterSeconds = 60)))
        val store = newStore(authRepository = repository)
        store.accept(AuthIntent.PhoneChanged("9991234567"))
        store.accept(AuthIntent.RequestCode)
        yield()
        yield()

        store.accept(AuthIntent.BackToPhone)

        val state = store.state.value
        assertEquals(AuthStep.Phone, state.step)
        assertEquals(0, state.resendSecondsRemaining)
        assertEquals("", state.codeInput)
    }

    @Test
    fun resetClearsEntireState() = runTest {
        val store = newStore()
        store.accept(AuthIntent.PhoneChanged("9991234567"))

        store.accept(AuthIntent.Reset)

        assertEquals(AuthState(), store.state.value)
    }

    private suspend fun newStore(
        authRepository: AuthRepository = FakeAuthRepository(),
        profileRepository: ProfileRepository = FakeProfileRepository(),
    ): AuthStore = AuthStore(
        authRepository = authRepository,
        profileRepository = profileRepository,
        scope = CoroutineScope(coroutineContext),
    )
}
