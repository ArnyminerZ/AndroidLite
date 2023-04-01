package com.arnyminerz.filmagentaproto.implementation

import com.arnyminerz.filamagenta.core.database.data.woo.InStock
import com.arnyminerz.filamagenta.core.database.data.woo.event.EventType
import com.arnyminerz.filamagenta.core.utils.now
import com.arnyminerz.filmagentaproto.database.data.woo.Event
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.verify
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TestEvent {
    @Before
    fun mock_now() {
        // Mock the now() function to return specific time
        mockkStatic(::now)
        every { now() } returns Date(1680176818000)
        assertEquals(1680176818000, now().time)
        verify { now() }
    }

    @Before
    fun mock_calendar() {
        mockkStatic(Calendar::class)
        every { Calendar.getInstance() } returns Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid"), Locale.ENGLISH)
        assertEquals("Europe/Madrid", Calendar.getInstance().timeZone.id)
        verify { Calendar.getInstance() }
    }

    private val datesEvent = Event(
        id = 1,
        name = "14 SAN JORGE, cena",
        slug = "san-jorge-cena",
        permalink = "",
        dateCreated = Date(1680176818000),
        dateModified = Date(1680176818000),
        description = "",
        shortDescription = "",
        price = 0.0,
        attributes = emptyList(),
        stockStatus = InStock,
        stockQuantity = 128,
    )

    @Test
    fun test_event() {
        assertEquals(EventType.Dinner, datesEvent.type)
        assertEquals("SAN JORGE, cena", datesEvent.title)
        assertEquals(14, datesEvent.index)
    }

    @Test
    fun test_lunchNoDate() {
        val event = datesEvent.copy(
            shortDescription = "RESERVAS hasta el domingo 2 de abril.\nComida organizada por mesas. No se estregan tickets.\nIndicar el responsable de mesa.\nEn DETALLES DE FACTURACION, abajo pone INFORAMCION ADICIONAL, anotar el responsable de mesa.",
        )
        assertFalse(event.hasPassed)
        assertEquals(Date(1680472740000), event.acceptsReservationsUntil)
        assertNull(event.eventDate)
    }

    @Test
    fun test_lunchNoLimit() {
        val event = datesEvent.copy(
            shortDescription = "Domingo 2 de abril.\nComida organizada por mesas. No se estregan tickets.\nIndicar el responsable de mesa.\nEn DETALLES DE FACTURACION, abajo pone INFORAMCION ADICIONAL, anotar el responsable de mesa.",
        )
        assertFalse(event.hasPassed)
        assertNull(event.acceptsReservationsUntil)
        assertEquals(Date(1680386400000), event.eventDate)
    }

    @Test
    fun test_lunchDateAndLimit() {
        val event = datesEvent.copy(
            shortDescription = "RESERVAS hasta el domingo 2 de abril.\nDía 25 de abril.\nComida organizada por mesas. No se estregan tickets.\nIndicar el responsable de mesa.\nEn DETALLES DE FACTURACION, abajo pone INFORAMCION ADICIONAL, anotar el responsable de mesa.",
        )
        assertFalse(event.hasPassed)
        assertEquals(Date(1680472740000), event.acceptsReservationsUntil)
        assertEquals(Date(1682373600000), event.eventDate)
    }
}