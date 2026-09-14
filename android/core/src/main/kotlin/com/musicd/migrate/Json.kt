package com.musicd.migrate

import org.json.JSONArray
import org.json.JSONObject

/*
 * Json.kt — reading JSON that came off a network.
 *
 * `optString` MUST NOT be used on anything from a service, because the two
 * org.json implementations disagree about JSON null:
 *
 *   {"isrc": null}
 *     desktop org.json   -> optString("isrc") == ""
 *     Android's org.json -> optString("isrc") == "null"   (the LITERAL text)
 *
 * Android routes through JSON.toString, which is String.valueOf for any
 * non-null reference — and JSONObject.NULL.toString() is "null".
 *
 * This is not theoretical and it is not cosmetic here. Both services send JSON
 * null for absent fields: a null `isrc` read as the string "null" would be
 * SEARCHED FOR on the other service, and a null album name would become an
 * album called "null" that the album tie-break then scores against. The unit
 * tests cannot catch it — they run on the JVM against the desktop
 * implementation, where the bug does not exist — so JsonSafeTest scans the
 * source and fails if optString comes back.
 */

fun JSONObject.str(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else (opt(key)?.toString() ?: fallback)

fun JSONObject.strOrNull(key: String): String? = str(key).takeIf { it.isNotEmpty() }

fun JSONObject.longOrNull(key: String): Long? =
    if (isNull(key)) null else (opt(key) as? Number)?.toLong()
        ?: str(key).toLongOrNull()

fun JSONObject.intOrNull(key: String): Int? =
    if (isNull(key)) null else (opt(key) as? Number)?.toInt() ?: str(key).toIntOrNull()

fun JSONObject.objOrNull(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)

fun JSONObject.arrOrNull(key: String): JSONArray? = if (isNull(key)) null else optJSONArray(key)

/** Every object in an array, skipping nulls and non-objects. */
fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { if (isNull(it)) null else optJSONObject(it) }

fun JSONArray.strings(): List<String> =
    (0 until length()).mapNotNull {
        if (isNull(it)) null else opt(it)?.toString()?.takeIf { s -> s.isNotEmpty() }
    }

fun parseObject(text: String): JSONObject? =
    try {
        JSONObject(text)
    } catch (e: Exception) {
        null
    }

/** A JSON string literal, escaped. Used to build responses by hand — there is
 *  no serialiser here and the shapes are small and fixed. */
fun jsonQuote(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
