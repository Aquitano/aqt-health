package me.aquitano.health.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class CursorTest {
    @Test
    fun `previously issued cursors decode and re-encode byte for byte`() {
        val issued =
            mapOf(
                "eyJzIjoiMjAyNi0wNC0wMlQwODowNTowMFoiLCJpZCI6MTIzLCJvIjoiYXNjIn0" to
                    Cursor("2026-04-02T08:05:00Z", 123, SortDirection.Asc),
                "eyJzIjoiMjAyNi0wNC0wMlQwODoxNTozMFoiLCJpZCI6MTIzLCJvIjoiZGVzYyJ9" to
                    Cursor("2026-04-02T08:15:30Z", 123, SortDirection.Desc),
            )
        issued.forEach { (encoded, cursor) ->
            assertEquals(cursor, Cursor.decode(encoded, expectedOrder = cursor.order))
            assertEquals(encoded, cursor.encode())
        }
    }
}
