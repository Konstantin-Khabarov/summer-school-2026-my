package com.volna.app.domain.policy

import com.volna.app.domain.model.Booking
import com.volna.app.domain.model.BookingId
import com.volna.app.domain.model.BookingStatus
import com.volna.app.domain.model.MoneyRub
import com.volna.app.domain.model.SlotId
import com.volna.app.domain.model.SlotStatus
import com.volna.app.testsupport.slot
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class DomainPolicyTest {
    @Test
    fun availabilityIsLimitedByFreeSeatsRouteCapAndClientMaximum() {
        val slot = slot(freeSeats = 10, capacityCap = 8, freeRentalBoards = 12)

        val availability = AvailabilityPolicy.availability(slot)

        assertEquals(3, availability.maxSeatsForBooking)
        assertEquals(12, availability.freeRentalBoards)
    }

    @Test
    fun rentalBoardsCannotExceedFreeRentalBoards() {
        val draft = com.volna.app.domain.model.BookingDraft(
            slot = slot(freeSeats = 3, capacityCap = 8, freeRentalBoards = 1),
            seatsCount = 2,
            rentalCount = 2,
        )

        assertEquals(
            AvailabilityViolation.TooManyRentalBoards(1),
            AvailabilityPolicy.validate(draft),
        )
    }

    @Test
    fun validDraftHasNoAvailabilityViolation() {
        val draft = com.volna.app.domain.model.BookingDraft(
            slot = slot(freeSeats = 3, capacityCap = 8, freeRentalBoards = 2),
            seatsCount = 2,
            rentalCount = 1,
        )

        assertNull(AvailabilityPolicy.validate(draft))
    }

    @Test
    fun cancelledSlotIsNotAvailableEvenWithFreeSeats() {
        val cancelledSlot = slot(freeSeats = 5, capacityCap = 8, status = SlotStatus.Cancelled)

        val availability = AvailabilityPolicy.availability(cancelledSlot)

        assertEquals(0, availability.maxSeatsForBooking)
        assertFalse(availability.canBook)
    }

    @Test
    fun cancelledSlotViolatesAvailabilityRegardlessOfSeats() {
        val draft = com.volna.app.domain.model.BookingDraft(
            slot = slot(freeSeats = 5, capacityCap = 8, status = SlotStatus.Cancelled),
            seatsCount = 1,
            rentalCount = 0,
        )

        assertEquals(AvailabilityViolation.SlotCancelled, AvailabilityPolicy.validate(draft))
    }

    @Test
    fun scheduledSlotWithNoFreeSeatsViolatesAvailability() {
        val draft = com.volna.app.domain.model.BookingDraft(
            slot = slot(freeSeats = 0, capacityCap = 8),
            seatsCount = 1,
            rentalCount = 0,
        )

        assertEquals(AvailabilityViolation.NoSeats, AvailabilityPolicy.validate(draft))
    }

    @Test
    fun seatsCountOutsideAvailableRangeViolatesAvailability() {
        val availableSlot = slot(freeSeats = 2, capacityCap = 8)

        val tooFew = com.volna.app.domain.model.BookingDraft(slot = availableSlot, seatsCount = 0, rentalCount = 0)
        val tooMany = com.volna.app.domain.model.BookingDraft(slot = availableSlot, seatsCount = 3, rentalCount = 0)

        assertEquals(AvailabilityViolation.TooManySeats(2), AvailabilityPolicy.validate(tooFew))
        assertEquals(AvailabilityViolation.TooManySeats(2), AvailabilityPolicy.validate(tooMany))
    }

    @Test
    fun bookingPriceUsesSeatAndRentalPrices() {
        val total = BookingPriceCalculator.calculate(
            slot = slot(price = 2_500, rentalPrice = 800),
            seatsCount = 2,
            rentalCount = 1,
        )

        assertEquals(MoneyRub(5_800), total)
    }

    @Test
    fun bookingPriceForExistingBookingIsDerivedFromSlotPrices() {
        val booking = Booking(
            id = BookingId("booking-1"),
            slotId = SlotId("slot-1"),
            clientId = null,
            seatsCount = 3,
            rentalCount = 2,
            status = BookingStatus.Active,
            priceTotal = MoneyRub(1),
            createdAt = Instant.parse("2026-06-01T12:00:00Z"),
            cancelledAt = null,
            slot = slot(price = 2_000, rentalPrice = 500),
            isFirstBooking = null,
        )

        assertEquals(MoneyRub(7_000), BookingPriceCalculator.calculate(booking))
    }

    @Test
    fun bookingPriceForBookingWithoutSlotFallsBackToStoredTotal() {
        val booking = Booking(
            id = BookingId("booking-1"),
            slotId = SlotId("slot-1"),
            clientId = null,
            seatsCount = 2,
            rentalCount = 1,
            status = BookingStatus.Active,
            priceTotal = MoneyRub(4_200),
            createdAt = Instant.parse("2026-06-01T12:00:00Z"),
            cancelledAt = null,
            slot = null,
            isFirstBooking = null,
        )

        assertEquals(MoneyRub(4_200), BookingPriceCalculator.calculate(booking))
    }

    @Test
    fun bookingPriceRejectsSeatsCountOutsideAllowedRange() {
        assertFailsWith<IllegalArgumentException> {
            BookingPriceCalculator.calculate(slot(), seatsCount = 0, rentalCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BookingPriceCalculator.calculate(slot(), seatsCount = 4, rentalCount = 0)
        }
    }

    @Test
    fun bookingPriceRejectsRentalCountAboveSeatsCount() {
        assertFailsWith<IllegalArgumentException> {
            BookingPriceCalculator.calculate(slot(), seatsCount = 2, rentalCount = 3)
        }
    }

    @Test
    fun exactlyTwoHoursBeforeStartIsEarlyCancellation() {
        val startAt = Instant.parse("2026-07-01T12:00:00Z")

        assertEquals(CancellationKind.Early, CancellationPolicy.classify(startAt - 2.hours, startAt))
        assertEquals(CancellationKind.Early, CancellationPolicy.classify(startAt - 2.hours - 1.seconds, startAt))
        assertEquals(CancellationKind.Late, CancellationPolicy.classify(startAt - 2.hours + 1.seconds, startAt))
        assertEquals(CancellationKind.UnavailableAfterStart, CancellationPolicy.classify(startAt, startAt))
    }
}
