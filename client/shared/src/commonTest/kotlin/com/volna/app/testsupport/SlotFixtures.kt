package com.volna.app.testsupport

import com.volna.app.domain.model.GeoPoint
import com.volna.app.domain.model.Instructor
import com.volna.app.domain.model.InstructorId
import com.volna.app.domain.model.MeetingPoint
import com.volna.app.domain.model.MoneyRub
import com.volna.app.domain.model.Route
import com.volna.app.domain.model.RouteId
import com.volna.app.domain.model.RouteType
import com.volna.app.domain.model.Slot
import com.volna.app.domain.model.SlotId
import com.volna.app.domain.model.SlotStatus
import kotlinx.datetime.Instant

internal fun slot(
    freeSeats: Int = 5,
    capacityCap: Int = 8,
    freeRentalBoards: Int = 5,
    price: Int = 2_500,
    rentalPrice: Int = 800,
    status: SlotStatus = SlotStatus.Scheduled,
): Slot = Slot(
    id = SlotId("slot-1"),
    startAt = Instant.parse("2026-07-01T12:00:00Z"),
    route = Route(
        id = RouteId("route-1"),
        name = "Острова и каналы",
        type = RouteType.Novice,
        capacityCap = capacityCap,
        durationMin = 90,
    ),
    instructor = Instructor(InstructorId("instructor-1"), "Мария"),
    totalSeats = capacityCap,
    freeSeats = freeSeats,
    freeRentalBoards = freeRentalBoards,
    price = MoneyRub(price),
    rentalPrice = MoneyRub(rentalPrice),
    meetingPoint = MeetingPoint("Лодочная станция", GeoPoint(59.978, 30.262)),
    status = status,
)
