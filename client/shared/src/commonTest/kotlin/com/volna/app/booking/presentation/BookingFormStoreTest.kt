package com.volna.app.booking.presentation

import com.volna.app.booking.BookingRepository
import com.volna.app.booking.IdempotencyKey
import com.volna.app.booking.IdempotencyKeyFactory
import com.volna.app.catalog.Page
import com.volna.app.catalog.PageRequest
import com.volna.app.core.error.ApiErrorCode
import com.volna.app.core.error.AppFailure
import com.volna.app.core.error.AppFailureException
import com.volna.app.core.error.ErrorDetails
import com.volna.app.core.ui.ActionStatus
import com.volna.app.domain.model.Booking
import com.volna.app.domain.model.BookingId
import com.volna.app.domain.model.BookingStatus
import com.volna.app.domain.model.MoneyRub
import com.volna.app.domain.model.Slot
import com.volna.app.domain.model.SlotStatus
import com.volna.app.push.PushPreferences
import com.volna.app.push.PushRepository
import com.volna.app.testsupport.FakePushPreferences
import com.volna.app.testsupport.RecordingPushRepository
import com.volna.app.testsupport.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BookingFormStoreTest {
    @Test
    fun openClampsSeatsAndBoardsToMaxAvailability() = runTest {
        val store = newStore()

        store.accept(BookingFormIntent.Open(slot(freeSeats = 1, capacityCap = 8, freeRentalBoards = 5)))

        val state = store.state.value
        assertEquals(1, state.seatsCount)
        assertEquals(listOf(BoardSelection.Own), state.boardSelections)
    }

    @Test
    fun incrementAndDecrementSeatsClampToAvailableRange() = runTest {
        val store = newStore()
        store.accept(BookingFormIntent.Open(slot(freeSeats = 2, capacityCap = 8, freeRentalBoards = 5)))

        store.accept(BookingFormIntent.IncrementSeats)
        assertEquals(2, store.state.value.seatsCount)

        store.accept(BookingFormIntent.IncrementSeats)
        assertEquals(2, store.state.value.seatsCount)

        store.accept(BookingFormIntent.DecrementSeats)
        store.accept(BookingFormIntent.DecrementSeats)
        assertEquals(1, store.state.value.seatsCount)
    }

    @Test
    fun setBoardSelectionDemotesExcessRentalSelectionsToOwn() = runTest {
        val store = newStore()
        store.accept(BookingFormIntent.Open(slot(freeSeats = 2, capacityCap = 8, freeRentalBoards = 1)))
        store.accept(BookingFormIntent.IncrementSeats)

        store.accept(BookingFormIntent.SetBoardSelection(seatIndex = 0, selection = BoardSelection.Rental))
        store.accept(BookingFormIntent.SetBoardSelection(seatIndex = 1, selection = BoardSelection.Rental))

        assertEquals(
            listOf(BoardSelection.Rental, BoardSelection.Own),
            store.state.value.boardSelections,
        )
    }

    @Test
    fun setBoardSelectionIgnoresOutOfRangeIndex() = runTest {
        val store = newStore()
        store.accept(BookingFormIntent.Open(slot(freeSeats = 2, capacityCap = 8, freeRentalBoards = 5)))
        val before = store.state.value

        store.accept(BookingFormIntent.SetBoardSelection(seatIndex = 5, selection = BoardSelection.Rental))

        assertEquals(before, store.state.value)
    }

    @Test
    fun submitDoesNotCallRepositoryWhenValidationFails() = runTest {
        val repository = FakeBookingRepository()
        val store = newStore(bookingRepository = repository)
        store.accept(BookingFormIntent.Open(slot(status = SlotStatus.Cancelled)))

        store.accept(BookingFormIntent.Submit)
        yield()

        assertEquals(0, repository.createCalls)
        assertEquals("Прогулка отменена", store.state.value.message)
    }

    @Test
    fun submitSuccessStoresBookingAndClearsIdempotencyState() = runTest {
        val slot = slot(freeSeats = 3, capacityCap = 8, freeRentalBoards = 5)
        val repository = FakeBookingRepository()
        repository.enqueue(Result.success(booking(slot)))
        val store = newStore(bookingRepository = repository)
        store.accept(BookingFormIntent.Open(slot))

        store.accept(BookingFormIntent.Submit)
        yield()
        yield()

        val state = store.state.value
        assertEquals(booking(slot), state.createdBooking)
        assertNull(state.idempotencyKey)
        assertNull(state.idempotencyPayload)
        assertEquals(ActionStatus.Idle, state.actionStatus)
        assertEquals(1, repository.createCalls)
    }

    @Test
    fun submitReusesIdempotencyKeyForUnchangedPayloadAndIssuesNewKeyAfterChange() = runTest {
        val slot = slot(freeSeats = 3, capacityCap = 8, freeRentalBoards = 5)
        val repository = FakeBookingRepository()
        repeat(2) {
            repository.enqueue(
                Result.failure(AppFailureException(AppFailure.Api(ApiErrorCode.InternalError, "boom"))),
            )
        }
        repository.enqueue(Result.success(booking(slot)))
        val store = newStore(bookingRepository = repository)
        store.accept(BookingFormIntent.Open(slot))

        store.accept(BookingFormIntent.Submit)
        yield()
        yield()
        val firstKey = repository.lastIdempotencyKey

        store.accept(BookingFormIntent.Submit)
        yield()
        yield()
        val secondKey = repository.lastIdempotencyKey
        assertEquals(firstKey, secondKey)

        store.accept(BookingFormIntent.IncrementSeats)
        store.accept(BookingFormIntent.Submit)
        yield()
        yield()
        val thirdKey = repository.lastIdempotencyKey
        assertNotEquals(firstKey, thirdKey)
        assertEquals(3, repository.createCalls)
    }

    @Test
    fun submitFailureUnauthorizedEmitsSignedOutEffect() = runTest {
        val slot = slot(freeSeats = 2, capacityCap = 8, freeRentalBoards = 2)
        val repository = FakeBookingRepository()
        repository.enqueue(Result.failure(AppFailureException(AppFailure.Unauthorized)))
        val store = newStore(bookingRepository = repository)
        store.accept(BookingFormIntent.Open(slot))

        store.accept(BookingFormIntent.Submit)
        yield()
        yield()
        val effect = store.effects()

        assertEquals(BookingFormEffect.SignedOut, effect)
        assertEquals(ActionStatus.Idle, store.state.value.actionStatus)
        assertNull(store.state.value.createdBooking)
    }

    @Test
    fun submitFailureSlotFullUpdatesAvailabilityAndClampsSelection() = runTest {
        val slot = slot(freeSeats = 3, capacityCap = 8, freeRentalBoards = 3)
        val repository = FakeBookingRepository()
        repository.enqueue(
            Result.failure(
                AppFailureException(
                    AppFailure.Api(
                        code = ApiErrorCode.SlotFull,
                        message = "Мест не осталось",
                        details = ErrorDetails(availableSeats = 1, availableRentalBoards = 0),
                    ),
                ),
            ),
        )
        val store = newStore(bookingRepository = repository)
        store.accept(BookingFormIntent.Open(slot))
        store.accept(BookingFormIntent.IncrementSeats)
        store.accept(BookingFormIntent.IncrementSeats)

        store.accept(BookingFormIntent.Submit)
        yield()
        yield()

        val state = store.state.value
        assertEquals(1, state.slot?.freeSeats)
        assertEquals(0, state.slot?.freeRentalBoards)
        assertEquals(1, state.seatsCount)
        assertEquals("Мест не осталось", state.message)
        assertEquals(ActionStatus.Idle, state.actionStatus)
    }

    @Test
    fun submitIsNoOpWhileAlreadySubmitting() = runTest {
        val repository = FakeBookingRepository()
        val store = newStore(bookingRepository = repository)
        store.accept(BookingFormIntent.Open(slot(freeSeats = 2, capacityCap = 8, freeRentalBoards = 2)))

        store.accept(BookingFormIntent.Submit)
        yield()
        assertEquals(ActionStatus.Submitting, store.state.value.actionStatus)

        store.accept(BookingFormIntent.Submit)
        yield()

        val slot = store.state.value.slot!!
        repository.enqueue(Result.success(booking(slot)))
        yield()
        yield()

        assertEquals(1, repository.createCalls)
    }

    private suspend fun newStore(
        bookingRepository: BookingRepository = FakeBookingRepository(),
        keyFactory: IdempotencyKeyFactory = FakeIdempotencyKeyFactory(),
        pushRepository: PushRepository = RecordingPushRepository(),
        pushPreferences: PushPreferences = FakePushPreferences(),
    ): BookingFormStore = BookingFormStore(
        bookingRepository = bookingRepository,
        keyFactory = keyFactory,
        pushRepository = pushRepository,
        pushPreferences = pushPreferences,
        scope = CoroutineScope(coroutineContext),
    )

    private class FakeBookingRepository : BookingRepository {
        private val results = Channel<Result<Booking>>(Channel.UNLIMITED)
        var createCalls: Int = 0
            private set
        var lastIdempotencyKey: IdempotencyKey? = null
            private set

        fun enqueue(result: Result<Booking>) {
            results.trySend(result)
        }

        override suspend fun createBooking(draft: com.volna.app.domain.model.BookingDraft, idempotencyKey: IdempotencyKey): Result<Booking> {
            createCalls += 1
            lastIdempotencyKey = idempotencyKey
            return results.receive()
        }

        override suspend fun listBookings(status: BookingStatus?, page: PageRequest): Result<Page<Booking>> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getBooking(bookingId: BookingId): Result<Booking> =
            Result.failure(UnsupportedOperationException())

        override suspend fun cancelBooking(bookingId: BookingId): Result<Booking> =
            Result.failure(UnsupportedOperationException())
    }

    private class FakeIdempotencyKeyFactory : IdempotencyKeyFactory {
        private var counter = 0

        override fun next(): IdempotencyKey {
            counter += 1
            return IdempotencyKey("key-$counter")
        }
    }

    private fun booking(slot: Slot, seatsCount: Int = 1, rentalCount: Int = 0): Booking = Booking(
        id = BookingId("booking-1"),
        slotId = slot.id,
        clientId = null,
        seatsCount = seatsCount,
        rentalCount = rentalCount,
        status = BookingStatus.Active,
        priceTotal = MoneyRub(1),
        createdAt = Instant.parse("2026-06-01T09:00:00Z"),
        cancelledAt = null,
        slot = slot,
        isFirstBooking = null,
    )
}
