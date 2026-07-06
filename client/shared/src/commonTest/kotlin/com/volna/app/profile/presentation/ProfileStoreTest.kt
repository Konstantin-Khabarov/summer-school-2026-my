package com.volna.app.profile.presentation

import com.volna.app.auth.AuthRepository
import com.volna.app.auth.RequestCodeResult
import com.volna.app.core.error.ApiErrorCode
import com.volna.app.core.error.AppFailure
import com.volna.app.core.error.AppFailureException
import com.volna.app.core.ui.Loadable
import com.volna.app.domain.model.Client
import com.volna.app.domain.model.Phone
import com.volna.app.profile.ProfileRepository
import com.volna.app.push.PushPreferences
import com.volna.app.push.PushRepository
import com.volna.app.testsupport.FakeAuthRepository
import com.volna.app.testsupport.FakePushPreferences
import com.volna.app.testsupport.FakeProfileRepository
import com.volna.app.testsupport.RecordingPushRepository
import com.volna.app.testsupport.client
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileStoreTest {
    @Test
    fun loadSuccessPopulatesNameAndPhoneInputs() = runTest {
        val client = client(name = "Мария", phone = "+79991234567")
        val store = loadedStore(client)

        val state = store.state.value
        assertEquals(Loadable.Content(client), state.profile)
        assertEquals("Мария", state.nameInput)
        assertEquals("9991234567", state.phoneInput)
    }

    @Test
    fun loadFailureUnauthorizedEmitsSignedOutEffect() = runTest {
        val repository = FakeProfileRepository()
        repository.enqueueGetProfile(Result.failure(AppFailureException(AppFailure.Unauthorized)))
        val store = newStore(profileRepository = repository)

        store.accept(ProfileIntent.Load)
        yield()
        yield()
        val effect = store.effects()

        assertEquals(ProfileEffect.SignedOut, effect)
    }

    @Test
    fun loadFailureOtherSetsErrorState() = runTest {
        val repository = FakeProfileRepository()
        repository.enqueueGetProfile(Result.failure(AppFailureException(AppFailure.NetworkUnavailable)))
        val store = newStore(profileRepository = repository)

        store.accept(ProfileIntent.Load)
        yield()
        yield()

        assertEquals(Loadable.Error(AppFailure.NetworkUnavailable), store.state.value.profile)
    }

    @Test
    fun saveClickedWithSamePhoneOnlyUpdatesName() = runTest {
        val original = client(name = "Мария", phone = "+79991234567")
        val updated = original.copy(name = "Маша")
        val repository = FakeProfileRepository()
        val store = loadedStore(original, repository)
        repository.enqueueUpdateName(Result.success(updated))

        store.accept(ProfileIntent.NameChanged("Маша"))
        store.accept(ProfileIntent.SaveClicked)
        yield()
        yield()

        assertEquals(listOf("getProfile", "updateName"), repository.callLog)
        val state = store.state.value
        assertEquals(ProfileMode.View, state.mode)
        assertEquals("Профиль обновлён", state.message)
        assertEquals(Loadable.Content(updated), state.profile)
    }

    @Test
    fun saveClickedWithSameNameDifferentPhoneOnlyRequestsPhoneChangeCode() = runTest {
        val original = client(name = "Мария", phone = "+79991234567")
        val repository = FakeProfileRepository()
        val store = loadedStore(original, repository)
        repository.enqueueRequestPhoneChangeCode(Result.success(RequestCodeResult(ttlSeconds = 300, resendAfterSeconds = 60)))

        store.accept(ProfileIntent.PhoneChanged("9997654321"))
        store.accept(ProfileIntent.SaveClicked)
        yield()
        yield()

        assertEquals(listOf("getProfile", "requestPhoneChangeCode"), repository.callLog)
        val state = store.state.value
        assertEquals(ProfileMode.ConfirmPhone, state.mode)
        assertEquals("+79997654321", state.pendingPhone)
    }

    @Test
    fun saveClickedWithBothNameAndPhoneChangedUpdatesNameThenRequestsPhoneChangeCode() = runTest {
        val original = client(name = "Мария", phone = "+79991234567")
        val afterNameUpdate = original.copy(name = "Маша")
        val repository = FakeProfileRepository()
        val store = loadedStore(original, repository)
        repository.enqueueUpdateName(Result.success(afterNameUpdate))
        repository.enqueueRequestPhoneChangeCode(Result.success(RequestCodeResult(ttlSeconds = 300, resendAfterSeconds = 60)))

        store.accept(ProfileIntent.NameChanged("Маша"))
        store.accept(ProfileIntent.PhoneChanged("9997654321"))
        store.accept(ProfileIntent.SaveClicked)
        yield()
        yield()
        yield()

        assertEquals(listOf("getProfile", "updateName", "requestPhoneChangeCode"), repository.callLog)
        val state = store.state.value
        assertEquals(ProfileMode.ConfirmPhone, state.mode)
        assertEquals("Маша", state.nameInput)
        assertEquals("+79997654321", state.pendingPhone)
    }

    @Test
    fun saveClickedRejectsInvalidNameWithoutCallingRepository() = runTest {
        val original = client(name = "Мария", phone = "+79991234567")
        val repository = FakeProfileRepository()
        val store = loadedStore(original, repository)

        store.accept(ProfileIntent.NameChanged("   "))
        store.accept(ProfileIntent.SaveClicked)

        assertEquals("Проверьте имя — кажется, тут лишние символы", store.state.value.fieldError)
        assertEquals(listOf("getProfile"), repository.callLog)
    }

    @Test
    fun saveClickedRejectsInvalidPhoneWithoutCallingRepository() = runTest {
        val original = client(name = "Мария", phone = "+79991234567")
        val repository = FakeProfileRepository()
        val store = loadedStore(original, repository)

        store.accept(ProfileIntent.PhoneChanged("999"))
        store.accept(ProfileIntent.SaveClicked)

        assertEquals("Похоже, номер введён не полностью", store.state.value.fieldError)
        assertEquals(listOf("getProfile"), repository.callLog)
    }

    @Test
    fun confirmPhoneClickedSuccessReturnsToViewModeWithUpdatedProfile() = runTest {
        val original = client(name = "Мария", phone = "+79991234567")
        val updated = original.copy(phone = Phone("+79997654321"))
        val (store, repository) = storeAwaitingPhoneConfirmation(original)
        repository.enqueueConfirmPhoneChange(Result.success(updated))

        store.accept(ProfileIntent.CodeChanged("1234"))
        store.accept(ProfileIntent.ConfirmPhoneClicked)
        yield()
        yield()

        val state = store.state.value
        assertEquals(ProfileMode.View, state.mode)
        assertEquals("Изменения сохранены", state.message)
        assertEquals(Loadable.Content(updated), state.profile)
        assertNull(state.pendingPhone)
    }

    @Test
    fun confirmPhoneClickedFailureInvalidCodeShowsFieldError() = runTest {
        val (store, repository) = storeAwaitingPhoneConfirmation(client(name = "Мария", phone = "+79991234567"))
        repository.enqueueConfirmPhoneChange(
            Result.failure(AppFailureException(AppFailure.Api(ApiErrorCode.InvalidCode, "bad"))),
        )

        store.accept(ProfileIntent.CodeChanged("0000"))
        store.accept(ProfileIntent.ConfirmPhoneClicked)
        yield()
        yield()

        val state = store.state.value
        assertEquals("Неверный код. Проверьте и введите ещё раз", state.fieldError)
        assertNull(state.message)
    }

    @Test
    fun confirmPhoneClickedFailurePhoneConflictShowsMessage() = runTest {
        val (store, repository) = storeAwaitingPhoneConfirmation(client(name = "Мария", phone = "+79991234567"))
        repository.enqueueConfirmPhoneChange(
            Result.failure(AppFailureException(AppFailure.Api(ApiErrorCode.PhoneConflict, "conflict"))),
        )

        store.accept(ProfileIntent.CodeChanged("0000"))
        store.accept(ProfileIntent.ConfirmPhoneClicked)
        yield()
        yield()

        val state = store.state.value
        assertNull(state.fieldError)
        assertEquals("Этот номер уже используется. Укажите другой", state.message)
    }

    @Test
    fun logoutConfirmedDeletesRegisteredPushTokenThenLogsOutAndSignsOut() = runTest {
        val original = client(name = "Мария", phone = "+79991234567")
        val pushRepository = RecordingPushRepository()
        val pushPreferences = FakePushPreferences(registeredTokenValue = "device-token-1")
        val authRepository = FakeAuthRepository(logoutResult = Result.success(Unit))
        val store = loadedStore(
            original,
            pushRepository = pushRepository,
            pushPreferences = pushPreferences,
            authRepository = authRepository,
        )

        store.accept(ProfileIntent.LogoutClicked)
        store.accept(ProfileIntent.LogoutConfirmed)
        yield()
        yield()
        val effect = store.effects()

        assertEquals(ProfileEffect.SignedOut, effect)
        assertEquals(1, pushRepository.deleteTokenCalls)
        assertEquals("device-token-1", pushRepository.lastDeletedToken)
        assertEquals(1, authRepository.logoutCalls)
        assertNull(pushPreferences.registeredToken())
    }

    @Test
    fun logoutConfirmedSignsOutEvenWhenRepositoryLogoutFails() = runTest {
        val original = client(name = "Мария", phone = "+79991234567")
        val authRepository = FakeAuthRepository(logoutResult = Result.failure(AppFailureException(AppFailure.NetworkUnavailable)))
        val store = loadedStore(original, authRepository = authRepository)

        store.accept(ProfileIntent.LogoutClicked)
        store.accept(ProfileIntent.LogoutConfirmed)
        yield()
        yield()
        val effect = store.effects()

        assertEquals(ProfileEffect.SignedOut, effect)
        assertEquals(1, authRepository.logoutCalls)
    }

    @Test
    fun deleteConfirmedSuccessEmitsSignedOutEffect() = runTest {
        val original = client(name = "Мария", phone = "+79991234567")
        val repository = FakeProfileRepository()
        val store = loadedStore(original, repository)
        repository.enqueueDeleteAccount(Result.success(Unit))

        store.accept(ProfileIntent.DeleteClicked)
        store.accept(ProfileIntent.DeleteConfirmed)
        yield()
        yield()
        val effect = store.effects()

        assertEquals(ProfileEffect.SignedOut, effect)
    }

    @Test
    fun deleteConfirmedFailureUnauthorizedEmitsSignedOutEffect() = runTest {
        val original = client(name = "Мария", phone = "+79991234567")
        val repository = FakeProfileRepository()
        val store = loadedStore(original, repository)
        repository.enqueueDeleteAccount(Result.failure(AppFailureException(AppFailure.Unauthorized)))

        store.accept(ProfileIntent.DeleteClicked)
        store.accept(ProfileIntent.DeleteConfirmed)
        yield()
        yield()
        val effect = store.effects()

        assertEquals(ProfileEffect.SignedOut, effect)
    }

    @Test
    fun deleteConfirmedFailureOtherShowsMessageAndStaysInPlace() = runTest {
        val original = client(name = "Мария", phone = "+79991234567")
        val repository = FakeProfileRepository()
        val store = loadedStore(original, repository)
        repository.enqueueDeleteAccount(Result.failure(AppFailureException(AppFailure.NetworkUnavailable)))

        store.accept(ProfileIntent.DeleteClicked)
        store.accept(ProfileIntent.DeleteConfirmed)
        yield()
        yield()

        val state = store.state.value
        assertEquals("Нет соединения. Проверьте подключение", state.message)
        assertEquals(Loadable.Content(original), state.profile)
    }

    private suspend fun newStore(
        profileRepository: ProfileRepository = FakeProfileRepository(),
        authRepository: AuthRepository = FakeAuthRepository(),
        pushRepository: PushRepository = RecordingPushRepository(),
        pushPreferences: PushPreferences = FakePushPreferences(),
    ): ProfileStore = ProfileStore(
        profileRepository = profileRepository,
        authRepository = authRepository,
        pushRepository = pushRepository,
        pushPreferences = pushPreferences,
        scope = CoroutineScope(coroutineContext),
    )

    private suspend fun loadedStore(
        client: Client,
        profileRepository: FakeProfileRepository = FakeProfileRepository(),
        authRepository: AuthRepository = FakeAuthRepository(),
        pushRepository: PushRepository = RecordingPushRepository(),
        pushPreferences: PushPreferences = FakePushPreferences(),
    ): ProfileStore {
        profileRepository.enqueueGetProfile(Result.success(client))
        val store = newStore(profileRepository, authRepository, pushRepository, pushPreferences)
        store.accept(ProfileIntent.Load)
        yield()
        yield()
        return store
    }

    private suspend fun storeAwaitingPhoneConfirmation(
        original: Client,
        newLocalPhone: String = "9997654321",
    ): Pair<ProfileStore, FakeProfileRepository> {
        val repository = FakeProfileRepository()
        val store = loadedStore(original, repository)
        repository.enqueueRequestPhoneChangeCode(Result.success(RequestCodeResult(ttlSeconds = 300, resendAfterSeconds = 60)))
        store.accept(ProfileIntent.PhoneChanged(newLocalPhone))
        store.accept(ProfileIntent.SaveClicked)
        yield()
        yield()
        return store to repository
    }
}
