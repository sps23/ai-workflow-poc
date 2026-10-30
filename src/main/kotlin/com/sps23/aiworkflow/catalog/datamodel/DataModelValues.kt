package com.sps23.aiworkflow.catalog.datamodel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.text.Normalizer
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

class DataModelParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** A parsed numeric value plus whether the source text was a clean number/range (nothing lost). */
internal data class ParsedRange(
    val range: NumericRange,
    val lossless: Boolean,
)

internal object DataModelValues {
    private const val NUMBER = """-?\d+(?:\.\d+)?"""
    private val cleanRange = Regex("""^($NUMBER)(?:\s*[-–]\s*($NUMBER))?\s*(?:[A-Za-z%°]+(?:/[A-Za-z0-9]+)?)?$""")
    private val qualifiedRange = Regex("""^$NUMBER\s*\([^)]*\)(?:\s*/\s*$NUMBER\s*\([^)]*\))+$""")
    private val numberPattern = Regex(NUMBER)
    private val moneyAmount = """€\s*(\d[\d,]*(?:\.\d{1,2})?)"""
    private val singleMoney = Regex("""^$moneyAmount$""")
    private val moneyRange = Regex("""^$moneyAmount\s*[-–]\s*$moneyAmount$""")
    private val effectiveDateFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

    fun parseRange(text: String): ParsedRange? {
        val trimmed = text.trim()
        cleanRange.matchEntire(trimmed)?.let { match ->
            val first = match.groupValues[1].toDouble()
            val second = match.groupValues[2].takeIf { it.isNotEmpty() }?.toDouble() ?: first
            return ParsedRange(NumericRange(minOf(first, second), maxOf(first, second)), lossless = true)
        }
        if (qualifiedRange.matches(trimmed)) {
            val values =
                trimmed
                    .split("/")
                    .map { part -> numberPattern.find(part.trim())!!.value.toDouble() }
            return ParsedRange(NumericRange(values.min(), values.max()), lossless = false)
        }
        return null
    }

    fun parseMoney(text: String): Pair<Money, Money?>? {
        val trimmed = text.trim()
        if (trimmed.equals("No Cost", ignoreCase = true)) return Money(0) to null
        singleMoney.matchEntire(trimmed)?.let { return toMoney(it.groupValues[1]) to null }
        moneyRange.matchEntire(trimmed)?.let { return toMoney(it.groupValues[1]) to toMoney(it.groupValues[2]) }
        return null
    }

    fun priceFact(
        basis: PriceBasis,
        text: String,
    ): PriceFact {
        val money = parseMoney(text)
        return PriceFact(basis = basis, amount = money?.first, maxAmount = money?.second, rawText = text)
    }

    /** Parses availability text such as "Standard", "N/A", "Optional (Ahead Pack)" or a price. */
    fun fitment(text: String): Pair<FitmentState, String?> {
        val trimmed = text.trim()
        val note =
            Regex("""\(([^)]*)\)""")
                .find(trimmed)
                ?.groupValues
                ?.get(1)
                ?.trim()
        val lower = trimmed.lowercase()
        val state =
            when {
                lower.startsWith("standard") -> FitmentState.STANDARD
                lower == "n/a" || lower.startsWith("not available") -> FitmentState.NOT_AVAILABLE
                lower.startsWith("optional") || lower.startsWith("part of") -> FitmentState.OPTIONAL
                parseMoney(trimmed) != null -> FitmentState.OPTIONAL
                else -> FitmentState.UNKNOWN
            }
        return state to note
    }

    fun inferPowertrain(vararg texts: String?): PowertrainType {
        val text = texts.filterNotNull().joinToString(" ").lowercase()
        val words = text.split(Regex("""[^a-z0-9-]+""")).toSet()
        return when {
            "phev" in words || "plug-in" in text -> PowertrainType.PLUG_IN_HYBRID
            "mhev" in words || "mild hybrid" in text || "mild-hybrid" in text -> PowertrainType.MILD_HYBRID
            "hev" in words || "hybrid" in text -> PowertrainType.HYBRID
            "electric" in text || "ev" in words || "bev" in words -> PowertrainType.ELECTRIC
            "diesel" in text || "tdi" in words -> PowertrainType.DIESEL
            "petrol" in text || "tsi" in words -> PowertrainType.PETROL
            else -> PowertrainType.UNKNOWN
        }
    }

    fun effectivePeriod(text: String?): YearMonth? =
        text?.let {
            try {
                YearMonth.parse(it.trim(), effectiveDateFormat)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    fun slug(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')

    fun shiftDecimal(
        value: Double,
        places: Int,
    ): Double = BigDecimal(value.toString()).movePointRight(places).toDouble()

    private fun toMoney(amount: String): Money =
        Money(
            BigDecimal(amount.replace(",", ""))
                .movePointRight(2)
                .longValueExact(),
        )
}

/**
 * Reads fields from a JSON object and remembers which keys were mapped, so every unmapped
 * field can be preserved via [remaining]. Typed accessors only mark a key as consumed when the
 * value was converted without losing information.
 */
internal class JsonFields(
    private val obj: JsonObject,
    private val path: String,
) {
    private val consumed = mutableSetOf<String>()

    fun has(key: String): Boolean = obj[key] != null && obj[key] !is JsonNull

    fun peekString(key: String): String? = (obj[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    fun string(key: String): String? = peekString(key)?.also { consumed += key }

    fun string(vararg keys: String): String? = keys.firstNotNullOfOrNull { string(it) }

    fun requiredString(vararg keys: String): String =
        string(*keys) ?: throw DataModelParseException("$path: missing required field ${keys.joinToString(" or ")}")

    fun range(vararg keys: String): NumericRange? =
        keys.firstNotNullOfOrNull { key ->
            peekString(key)?.let(DataModelValues::parseRange)?.also { if (it.lossless) consumed += key }?.range
        }

    /** A single exact number; ranges or qualified values are left as raw attributes. */
    fun number(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { key ->
            peekString(key)
                ?.let(DataModelValues::parseRange)
                ?.takeIf { it.lossless && it.range.isSingleValue }
                ?.also { consumed += key }
                ?.range
                ?.min
        }

    fun int(vararg keys: String): Int? = number(*keys)?.toInt()

    /** Extracts a number with [regex] from a richer text; the raw text is kept as an attribute. */
    fun extract(
        key: String,
        regex: Regex,
    ): Double? =
        peekString(key)
            ?.let {
                regex
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toDouble()
            }

    fun array(key: String): JsonArray? = (obj[key] as? JsonArray)?.also { consumed += key }

    fun obj(key: String): JsonObject? = (obj[key] as? JsonObject)?.also { consumed += key }

    fun fields(key: String): JsonFields? = obj(key)?.let { JsonFields(it, "$path.$key") }

    fun objects(key: String): List<JsonFields> =
        array(key)
            ?.mapIndexed { index, element ->
                val item = element as? JsonObject ?: throw DataModelParseException("$path.$key[$index]: expected an object")
                JsonFields(item, "$path.$key[$index]")
            }.orEmpty()

    fun stringList(key: String): List<String> = array(key)?.map(::render).orEmpty()

    fun consume(key: String) {
        consumed += key
    }

    fun unconsumedKeys(): Set<String> = obj.keys - consumed

    fun remaining(): Map<String, String> =
        obj
            .filterKeys { it !in consumed }
            .filterValues { it !is JsonNull }
            .mapValues { render(it.value) }

    fun location(): String = path

    companion object {
        fun render(element: JsonElement): String = (element as? JsonPrimitive)?.content ?: element.toString()
    }
}
