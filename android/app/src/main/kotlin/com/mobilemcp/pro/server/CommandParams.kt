package com.mobilemcp.pro.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Typed, range-checked access to a command's parameters.
 *
 * Every accessor either produces a usable value or records a problem in [errors]; nothing
 * throws and nothing silently substitutes a default for input that was present but wrong.
 * That distinction is the point. The previous implementation used Gson's `asFloat`, which
 * throws `NumberFormatException` on a string and `IllegalStateException` on an object, so
 * a caller who sent `{"x": "500"}` got "Internal error: null" rather than a description of
 * what was wrong with their request.
 *
 * Callers validate first and read after:
 * ```
 * val x = params.requireFloat("x")
 * val y = params.requireFloat("y")
 * params.firstError()?.let { return it }
 * ```
 */
class CommandParams(private val json: JsonObject) {

    private val errors = mutableListOf<String>()

    fun firstError(): String? = errors.firstOrNull()

    val allErrors: List<String> get() = errors.toList()

    // ------------------------------------------------------------------ required

    fun requireFloat(name: String): Float {
        val primitive = primitive(name) ?: run {
            errors += "Missing required parameter '$name'"
            return 0f
        }
        return primitive.floatOrNull ?: run {
            errors += "Parameter '$name' must be a number"
            0f
        }
    }

    fun requireString(name: String, allowBlank: Boolean = false): String {
        val primitive = primitive(name) ?: run {
            errors += "Missing required parameter '$name'"
            return ""
        }
        if (primitive.isString.not() && primitive.content.isEmpty()) {
            errors += "Parameter '$name' must be a string"
            return ""
        }
        val value = primitive.content
        if (!allowBlank && value.isBlank()) {
            errors += "Parameter '$name' must not be blank"
            return ""
        }
        return value
    }

    // ------------------------------------------------------------------ optional

    /**
     * Reads an optional integer, clamping into `[min, max]`.
     *
     * Clamping rather than rejecting is deliberate for bounded tuning knobs like depth and
     * quality: a caller asking for `quality: 200` clearly wants the best available, and
     * failing the request teaches them nothing. Parameters where a wrong value means a
     * wrong action — coordinates, package names — are required and validated strictly.
     */
    fun optionalInt(name: String, default: Int, min: Int, max: Int): Int {
        val primitive = primitive(name) ?: return default
        val value = primitive.longOrNull ?: run {
            errors += "Parameter '$name' must be an integer"
            return default
        }
        return value.coerceIn(min.toLong(), max.toLong()).toInt()
    }

    fun optionalLong(name: String, default: Long, min: Long, max: Long): Long {
        val primitive = primitive(name) ?: return default
        val value = primitive.longOrNull ?: run {
            errors += "Parameter '$name' must be an integer"
            return default
        }
        return value.coerceIn(min, max)
    }

    fun optionalFloat(name: String, default: Float, min: Float, max: Float): Float {
        val primitive = primitive(name) ?: return default
        val value = primitive.floatOrNull ?: run {
            errors += "Parameter '$name' must be a number"
            return default
        }
        return value.coerceIn(min, max)
    }

    fun optionalFloatOrNull(name: String): Float? {
        val primitive = primitive(name) ?: return null
        return primitive.floatOrNull ?: run {
            errors += "Parameter '$name' must be a number"
            null
        }
    }

    fun optionalString(name: String): String? {
        val primitive = primitive(name) ?: return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    fun optionalBoolean(name: String, default: Boolean): Boolean {
        val primitive = primitive(name) ?: return default
        return primitive.booleanOrNull ?: run {
            errors += "Parameter '$name' must be true or false"
            default
        }
    }

    private fun primitive(name: String): JsonPrimitive? =
        (json[name] as? JsonPrimitive)?.takeUnless { it.content == "null" && !it.isString }
}
