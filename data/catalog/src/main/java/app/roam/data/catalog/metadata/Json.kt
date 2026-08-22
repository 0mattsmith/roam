package app.roam.data.catalog.metadata

import org.json.JSONArray
import org.json.JSONObject

/**
 * A JSON value that remembers the order it was built in.
 *
 * org.json does not: on the JVM its backing map is a HashMap, on Android a
 * LinkedHashMap, so `toString(2)` gives a different key order in a unit test
 * than on the phone -- and a file whose keys shuffle on every save produces a
 * diff that says everything changed when nothing did. These documents are meant
 * to be read and hand-edited, and the contract (docs/ALBUM_JSON.md) promises a
 * stable key order for exactly that reason.
 *
 * Escaping is delegated to [JSONObject.quote]. Only the LAYOUT is hand-rolled.
 */
sealed interface Json {
    data class Obj(val entries: List<Pair<String, Json>>) : Json
    data class Arr(val items: List<Json>) : Json
    data class Str(val value: String) : Json
    data class Num(val value: Long) : Json
    /** Kept apart from [Num] so a whole number never gains a ".0" on a re-save. */
    data class Dec(val value: Double) : Json
    data class Bool(val value: Boolean) : Json
    data object Null : Json
}

/**
 * Builds an object, dropping absent values.
 *
 * A null pair is a field Roam has nothing to say about, which is different from
 * a field it knows to be empty -- [Json.Null] says that one out loud, and the
 * reader treats the two the same anyway.
 */
fun jsonObject(vararg entries: Pair<String, Json?>): Json.Obj =
    Json.Obj(entries.mapNotNull { (key, value) -> value?.let { key to it } })

fun String?.json(): Json? = this?.takeIf { it.isNotBlank() }?.let { Json.Str(it) }
fun Int?.json(): Json? = this?.let { Json.Num(it.toLong()) }
fun Long?.json(): Json? = this?.let { Json.Num(it) }
fun Boolean.json(): Json = Json.Bool(this)
fun List<String>.jsonArray(): Json.Arr = Json.Arr(map { Json.Str(it) })

/** Two-space indent and a trailing newline, per the contract. */
fun Json.render(): String = StringBuilder().also { write(it, 0) }.append('\n').toString()

private fun Json.write(out: StringBuilder, depth: Int) {
    val pad = "  ".repeat(depth)
    val inner = "  ".repeat(depth + 1)
    when (this) {
        is Json.Obj -> {
            if (entries.isEmpty()) { out.append("{}"); return }
            out.append("{\n")
            entries.forEachIndexed { i, (key, value) ->
                out.append(inner).append(JSONObject.quote(key)).append(": ")
                value.write(out, depth + 1)
                if (i < entries.lastIndex) out.append(',')
                out.append('\n')
            }
            out.append(pad).append('}')
        }
        is Json.Arr -> {
            if (items.isEmpty()) { out.append("[]"); return }
            out.append("[\n")
            items.forEachIndexed { i, item ->
                out.append(inner)
                item.write(out, depth + 1)
                if (i < items.lastIndex) out.append(',')
                out.append('\n')
            }
            out.append(pad).append(']')
        }
        is Json.Str -> out.append(JSONObject.quote(value))
        is Json.Num -> out.append(value.toString())
        is Json.Dec -> out.append(value.toString())
        is Json.Bool -> out.append(if (value) "true" else "false")
        Json.Null -> out.append("null")
    }
}

/**
 * Everything in [source] that [known] does not name, in sorted order.
 *
 * This is what stops Roam undoing work it does not understand. You and Roam
 * will not always be on the same version, and a writer that dropped the fields
 * it did not recognise would silently delete the other's edits on every save.
 *
 * Sorted rather than as-found, because "as found" is the very ordering that
 * differs between org.json's two implementations.
 */
fun JSONObject.preserving(known: Set<String>): List<Pair<String, Json>> =
    keys().asSequence()
        .filter { it !in known }
        .sorted()
        .map { it to fromJson(opt(it)) }
        .toList()

/** An org.json value as an ordered node, so unknown subtrees survive intact. */
fun fromJson(value: Any?): Json = when (value) {
    null, JSONObject.NULL -> Json.Null
    is JSONObject -> Json.Obj(value.keys().asSequence().sorted().map { it to fromJson(value.opt(it)) }.toList())
    is JSONArray -> Json.Arr((0 until value.length()).map { fromJson(value.opt(it)) })
    is Boolean -> Json.Bool(value)
    is Int -> Json.Num(value.toLong())
    is Long -> Json.Num(value)
    is Double -> if (value == Math.floor(value) && !value.isInfinite()) Json.Num(value.toLong()) else Json.Dec(value)
    else -> Json.Str(value.toString())
}
