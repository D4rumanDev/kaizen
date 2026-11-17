package app.kaizen.source

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

// Lectura tolerante de JSON ajeno (AniList): un campo ausente, nulo o de otro tipo da null o vacío, nunca una excepción.

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonElement?.asArrayOrEmpty(): List<JsonElement> = this?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty()

internal fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()

internal fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()

internal fun JsonObject.long(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asLong }?.getOrNull()
