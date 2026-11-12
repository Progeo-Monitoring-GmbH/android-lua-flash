package com.progeo.luaflasher.backend

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationApiTest {

    private val locations = LocationApi.parseList(
        Json.parseToJsonElement(
            """
            [
              {"project_id": 4711, "id": 3, "clazz": "ProgeoLocation", "name": "Müller GmbH", "city": "Berlin", "plz": "10115", "address": "Hauptstraße 1", "country": "DE", "manager": null, "telefon": null, "mail": null, "project_type": 1},
              {"project_id": 12, "id": 9, "name": "", "city": "München", "plz": 80331, "address": "Marienplatz 8"},
              {"project_id": null, "id": 5, "name": "Archiv", "city": null, "plz": null, "address": null},
              {"name": "no id, skipped"}
            ]
            """,
        ),
    )

    @Test
    fun `min list is parsed and sorted by title`() {
        assertEquals(listOf(5L, 9L, 3L), locations.map { it.id })
        val munich = locations.first { it.id == 9L }
        assertEquals("Marienplatz 8", munich.title, "an empty name falls back to the address")
        assertEquals("Marienplatz 8, 80331 München", munich.addressLine)
        assertEquals(12L, munich.projectId)
        assertEquals("", locations.first { it.id == 5L }.addressLine)
    }

    @Test
    fun `search matches every word in name, address, postcode, city or project id`() {
        val mueller = locations.first { it.id == 3L }
        assertTrue(mueller.matches(""))
        assertTrue(mueller.matches("muller berlin"), "diacritics are ignored")
        assertTrue(mueller.matches("HAUPTSTRASSE"), "ß matches ss")
        assertTrue(mueller.matches("4711"))
        assertTrue(mueller.matches("101"))
        assertFalse(mueller.matches("müller münchen"))
        assertTrue(locations.first { it.id == 9L }.matches("munchen 80331"))
    }
}
