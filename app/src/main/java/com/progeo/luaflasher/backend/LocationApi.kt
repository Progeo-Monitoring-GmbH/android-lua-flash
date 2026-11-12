package com.progeo.luaflasher.backend

import com.progeo.core.auth.BackendClient
import com.progeo.core.json.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.Normalizer

/** A location (project) from `GET /v1/location/min/`. [id] is what `?location=` takes. */
data class RemoteLocation(
    val id: Long,
    val projectId: Long?,
    val name: String?,
    val address: String?,
    val plz: String?,
    val city: String?,
) {
    val title: String get() = name ?: address ?: "Location $id"

    /** "Street 1, 12345 City"; empty without an address. */
    val addressLine: String
        get() = listOfNotNull(address, listOfNotNull(plz, city).joinToString(" ").ifEmpty { null }).joinToString(", ")

    /** The searchable fields, folded once (the list is filtered on every keystroke). */
    internal val searchText: String by lazy {
        LocationApi.fold(listOfNotNull(projectId?.toString(), name, address, plz, city).joinToString(" "))
    }

    /** True if every word of [query] appears in name, address, postcode, city or project id. */
    fun matches(query: String): Boolean {
        val words = LocationApi.fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.all { it in searchText }
    }
}

/** The locations the logged-in user sees, across all of their accounts. */
class LocationApi(private val backend: BackendClient) {

    suspend fun locations(): List<RemoteLocation> = parseList(backend.get("v1/location/min/"))

    companion object {
        private val DIACRITICS = Regex("\\p{Mn}+")

        /** Lower case without diacritics, so "muller" finds "Müller" and "strasse" finds "Straße" (like the web portal). */
        fun fold(text: String): String =
            DIACRITICS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase().replace("ß", "ss")

        fun parseList(json: JsonElement): List<RemoteLocation> {
            val array = json as? JsonArray ?: (json as? JsonObject)?.get("results") as? JsonArray ?: return emptyList()
            return array.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                RemoteLocation(
                    id = id,
                    projectId = (o["project_id"] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.trim().toLongOrNull() },
                    name = o.str("name")?.trim()?.ifEmpty { null },
                    address = o.str("address")?.trim()?.ifEmpty { null },
                    plz = o.str("plz")?.trim()?.ifEmpty { null },
                    city = o.str("city")?.trim()?.ifEmpty { null },
                )
            }.sortedBy { it.title.lowercase() }
        }
    }
}
