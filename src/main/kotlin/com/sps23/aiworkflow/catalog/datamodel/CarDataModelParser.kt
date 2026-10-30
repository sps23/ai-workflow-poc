package com.sps23.aiworkflow.catalog.datamodel

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Parses one per-car "Data Model" JSON file into a normalized [CarDataModel].
 *
 * The parser tolerates the field-name variants used by the different brand files and keeps
 * anything it does not map in the `additional` attribute maps instead of dropping it.
 */
object CarDataModelParser {
    private val kwPattern = Regex("""(\d+(?:\.\d+)?)\s*kW\b""", RegexOption.IGNORE_CASE)
    private val hpPattern = Regex("""(\d+(?:\.\d+)?)\s*HP\b""", RegexOption.IGNORE_CASE)
    private val nmPattern = Regex("""(\d+(?:\.\d+)?)\s*Nm\b""", RegexOption.IGNORE_CASE)
    private val hpKwPattern = Regex("""^\s*(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)\s*$""")

    fun parse(path: Path): CarDataModel = parse(path.readText(), path.toString())

    fun parse(
        json: String,
        sourcePath: String,
    ): CarDataModel {
        val root =
            try {
                Json.parseToJsonElement(json.removePrefix("\uFEFF"))
            } catch (exception: SerializationException) {
                throw DataModelParseException("$sourcePath: invalid JSON", exception)
            }
        if (root !is JsonObject) throw DataModelParseException("$sourcePath: expected a JSON object at the root")
        return try {
            parseRoot(JsonFields(root, "$"), sourcePath)
        } catch (exception: DataModelParseException) {
            throw DataModelParseException("$sourcePath: ${exception.message}", exception)
        }
    }

    private fun parseRoot(
        fields: JsonFields,
        sourcePath: String,
    ): CarDataModel {
        val title = fields.requiredString("model")
        val effectiveDate = fields.string("effective_date")
        val trims = fields.objects("trims").map(::parseTrim)
        val trimNames = trims.map { it.name }
        val options = parseOptions(fields, trimNames)
        val technical =
            fields.fields("technical_specifications")?.let(::parseTechnicalSpecifications)
                ?: TechnicalSpecifications(emptyDimensions(), emptyList())
        val make = title.substringBefore(' ').trim()
        val model = title.substringAfter(' ', "").replace(Regex("""\s*\([^)]*\)"""), "").trim()
        return CarDataModel(
            id = DataModelValues.slug("$make $model"),
            sourcePath = sourcePath,
            title = title,
            make = make,
            model = model,
            effectiveDate = effectiveDate,
            effectivePeriod = DataModelValues.effectivePeriod(effectiveDate),
            trims = trims,
            options = options,
            technicalSpecifications = technical,
            additional = fields.remaining(),
        )
    }

    private fun parseTrim(fields: JsonFields): Trim {
        val name = fields.requiredString("trim_level")
        val variants = fields.objects("pricing").map { parseVariant(it, name) }
        val equipment = fields.obj("standard_equipment")?.let { flattenEquipment(it, prefix = "") }.orEmpty()
        return Trim(name = name, variants = variants, standardEquipment = equipment, additional = fields.remaining())
    }

    private fun flattenEquipment(
        obj: JsonObject,
        prefix: String,
    ): List<EquipmentGroup> =
        obj.entries.flatMap { (key, value) ->
            val category = if (prefix.isEmpty()) key else "$prefix/$key"
            when (value) {
                is JsonArray -> {
                    listOf(
                        EquipmentGroup(
                            category = category,
                            isAddition = "addition" in category,
                            items = value.map(JsonFields::render),
                        ),
                    )
                }

                is JsonObject -> {
                    flattenEquipment(value, category)
                }

                else -> {
                    listOf(EquipmentGroup(category, "addition" in category, listOf(JsonFields.render(value))))
                }
            }
        }

    private fun parseVariant(
        fields: JsonFields,
        trimName: String,
    ): TrimVariant {
        val name = fields.requiredString("variant", "powertrain")
        val powertrainText = fields.string("powertrain")
        val battery = fields.number("battery_capacity", "battery")
        val consumption =
            fields.number("fuel_economy_wltp")
                ?: fields.number("wltp_consumption_wh_km")?.let { DataModelValues.shiftDecimal(it, -1) }
        val prices =
            PRICE_FIELDS.mapNotNull { (key, basis) -> fields.string(key)?.let { DataModelValues.priceFact(basis, it) } }
        val inferred = DataModelValues.inferPowertrain(name, powertrainText)
        return TrimVariant(
            name = name,
            trimName = trimName,
            modelCode = fields.string("model_code"),
            powertrainType = if (inferred == PowertrainType.UNKNOWN && battery != null) PowertrainType.ELECTRIC else inferred,
            batteryCapacityKwh = battery,
            powerHp = fields.int("power_hp"),
            powerKw = fields.int("power_kw"),
            co2GPerKm = fields.range("co2_emissions", "co2_emissions_g_km"),
            wltpRangeKm = fields.range("wltp_range_km"),
            electricConsumptionKwhPer100Km = consumption,
            taxBand = fields.string("tax_band"),
            vrtRatePercent = fields.number("vrt_rate"),
            prices = prices,
            additional = fields.remaining(),
        )
    }

    private fun parseOptions(
        root: JsonFields,
        trimNames: List<String>,
    ): List<VehicleOption> =
        buildList {
            root.objects("wheels_and_tyres").forEach { add(parseOption(it, OptionCategory.WHEELS, "wheels_and_tyres", trimNames)) }
            root.objects("interior_options").forEach { add(parseOption(it, OptionCategory.INTERIOR, "interior_options", trimNames)) }
            root.objects("design_selections").forEach { add(parseOption(it, OptionCategory.INTERIOR, "design_selections", trimNames)) }
            parseColourOptions(root, trimNames)?.let(::addAll)
            root.objects("optional_packages_and_extras").forEach { item ->
                val category = if (item.has("items")) OptionCategory.PACKAGE else OptionCategory.SINGLE_OPTION
                add(parseOption(item, category, "optional_packages_and_extras", trimNames))
            }
            root.objects("optional_packages").forEach { add(parseOption(it, OptionCategory.PACKAGE, "optional_packages", trimNames)) }
            root.objects("single_options").forEach { add(parseOption(it, OptionCategory.SINGLE_OPTION, "single_options", trimNames)) }
            root.objects("accessory_packs").forEach { add(parseOption(it, OptionCategory.ACCESSORY_PACK, "accessory_packs", trimNames)) }
            root.objects("optional_equipment").forEach { group ->
                val label = group.requiredString("category")
                val category = categoryFromLabel(label)
                group.objects("options").forEach { add(parseOption(it, category, label, trimNames)) }
                val unmapped = group.remaining()
                if (unmapped.isNotEmpty()) {
                    throw DataModelParseException("${group.location()}: unsupported fields ${unmapped.keys}")
                }
            }
        }

    private fun parseColourOptions(
        root: JsonFields,
        trimNames: List<String>,
    ): List<VehicleOption>? {
        root.array("color_options")?.let { array ->
            return array.mapIndexed { index, element ->
                parseOption(requireObject(element, "color_options[$index]"), OptionCategory.PAINT, "color_options", trimNames)
            }
        }
        val colours = root.obj("color_options") ?: return null
        return colours.entries.flatMap { (section, value) ->
            val category = if ("roof" in section) OptionCategory.ROOF_COLOUR else OptionCategory.PAINT
            val array = value as? JsonArray ?: throw DataModelParseException("color_options.$section: expected an array")
            array.mapIndexed { index, element ->
                parseOption(requireObject(element, "color_options.$section[$index]"), category, section, trimNames)
            }
        }
    }

    private fun requireObject(
        element: JsonElement,
        path: String,
    ): JsonFields = JsonFields(element as? JsonObject ?: throw DataModelParseException("$path: expected an object"), path)

    private fun categoryFromLabel(label: String): OptionCategory {
        val lower = label.lowercase()
        return when {
            "paint" in lower || "colour" in lower || "color" in lower -> OptionCategory.PAINT
            "wheel" in lower -> OptionCategory.WHEELS
            "pack" in lower -> OptionCategory.PACKAGE
            "interior" in lower || "upholstery" in lower -> OptionCategory.INTERIOR
            else -> OptionCategory.OTHER
        }
    }

    private fun parseOption(
        fields: JsonFields,
        category: OptionCategory,
        categoryLabel: String,
        trimNames: List<String>,
    ): VehicleOption {
        val code = fields.string("code")
        val name = fields.requiredString("name", "description", "pack_name", "trim_name")
        val type = fields.string("type")
        val items = fields.stringList("items")
        val availability = parseAvailability(fields, trimNames)
        return VehicleOption(
            code = code,
            name = name,
            category = category,
            categoryLabel = categoryLabel,
            type = type,
            items = items,
            availability = availability,
            additional = fields.remaining(),
        )
    }

    private fun parseAvailability(
        fields: JsonFields,
        trimNames: List<String>,
    ): List<OptionAvailability> {
        val result = mutableListOf<OptionAvailability>()
        val sharedPriceText = fields.string("rrp", "price")
        val sharedPrice = sharedPriceText?.let { DataModelValues.priceFact(PriceBasis.OPTION, it) }

        // Per-scope availability with a shared price, e.g. "v1_availability": "Optional", "rrp": "€280".
        fields.unconsumedKeys().filter { it.endsWith("_availability") }.sorted().forEach { key ->
            val text = fields.requiredString(key)
            val (state, note) = DataModelValues.fitment(text)
            val price = sharedPrice.takeIf { state != FitmentState.NOT_AVAILABLE }
            result += OptionAvailability(scope = key.removeSuffix("_availability").uppercase(), state = state, price = price, note = note)
        }

        // Per-trim combined state/price, e.g. "selection_price": "€1,806" or "sportline": "STANDARD".
        fields.unconsumedKeys().sorted().forEach { key ->
            val scope = trimNames.firstOrNull { trim -> key == trim.lowercase() || key == "${trim.lowercase()}_price" }
            if (scope != null) {
                val text = fields.requiredString(key)
                val (state, note) = DataModelValues.fitment(text)
                val price = if (DataModelValues.parseMoney(text) != null) DataModelValues.priceFact(PriceBasis.OPTION, text) else null
                result += OptionAvailability(scope = scope, state = state, price = price, note = note)
            }
        }

        // Free-text availability, e.g. "availability": "GT, GT EXCLUSIVE (Electric Version Only)".
        fields.string("availability")?.let { text ->
            result += OptionAvailability(scope = text, state = FitmentState.OPTIONAL, price = sharedPrice)
        }

        // Standard fitment by trim, e.g. "status": "STANDARD (Selection)".
        fields.string("status")?.let { text ->
            val (state, note) = DataModelValues.fitment(text)
            result += OptionAvailability(scope = note ?: "All Trims", state = state)
        }

        if (result.isEmpty() && sharedPrice != null) {
            result += OptionAvailability(scope = "All Trims", state = FitmentState.OPTIONAL, price = sharedPrice)
        }
        return result
    }

    private fun parseTechnicalSpecifications(fields: JsonFields): TechnicalSpecifications =
        TechnicalSpecifications(
            dimensions = fields.fields("dimensions")?.let(::parseDimensions) ?: emptyDimensions(),
            powertrains = fields.objects("powertrains").map(::parsePowertrain),
            additional = fields.remaining(),
        )

    private fun parseDimensions(fields: JsonFields): Dimensions {
        fun millimetres(name: String): NumericRange? =
            fields.range("${name}_mm")
                ?: fields.range("${name}_m")?.let {
                    NumericRange(DataModelValues.shiftDecimal(it.min, 3), DataModelValues.shiftDecimal(it.max, 3))
                }

        return Dimensions(
            lengthMm = millimetres("length"),
            widthMm = millimetres("width"),
            heightMm = millimetres("height"),
            wheelbaseMm = millimetres("wheelbase"),
            bootCapacityLitres = fields.range("boot_capacity_litres", "trunk_volume_7_seater_row3_down_l"),
            maxBootCapacityLitres =
                fields.range("max_boot_capacity_litres", "max_luggage_capacity_litres", "max_trunk_volume_all_down_l"),
            frontTrunkCapacityLitres = fields.range("front_trunk_capacity_litres"),
            brakedTowingKg = fields.range("braked_towing_kg"),
            additional = fields.remaining(),
        )
    }

    private fun parsePowertrain(fields: JsonFields): PowertrainSpec {
        val name = fields.requiredString("version", "name", "engine_version")
        val typeText = fields.string("type")
        val charging = fields.obj("charging")?.mapValues { JsonFields.render(it.value) }.orEmpty()

        val hpKw = fields.peekString("max_power_hp_kw")?.let { hpKwPattern.matchEntire(it) }
        if (hpKw != null) fields.consume("max_power_hp_kw")
        val powerKw =
            fields.number("max_power_kw")
                ?: hpKw?.groupValues?.get(2)?.toDouble()
                ?: fields.extract("max_power", kwPattern)
        val powerHp =
            fields.number("power_hp")
                ?: hpKw?.groupValues?.get(1)?.toDouble()
                ?: fields.extract("max_power", hpPattern)
        val torque = fields.number("torque_nm", "max_torque_nm") ?: fields.extract("max_torque", nmPattern)
        val battery = fields.number("battery_capacity_kwh", "usable_battery_capacity_kwh", "battery_net_kwh")
        val electricConsumption = fields.range("electric_consumption", "wltp_mixed_consumption_kwh_100km")

        val inferred = DataModelValues.inferPowertrain(typeText, name, fields.peekString("motor_type"))
        val type =
            when {
                inferred != PowertrainType.UNKNOWN -> inferred
                battery != null || electricConsumption != null -> PowertrainType.ELECTRIC
                else -> PowertrainType.UNKNOWN
            }

        return PowertrainSpec(
            name = name,
            type = type,
            modelCode = fields.string("model_code"),
            drivetrain = fields.string("drivetrain"),
            transmission = fields.string("transmission"),
            powerKw = powerKw,
            powerHp = powerHp,
            torqueNm = torque,
            topSpeedKmh = fields.number("top_speed_kmh"),
            acceleration0To100S = fields.range("acceleration_0_100_kmh_s"),
            wltpRangeKm = fields.range("range_wltp_km", "wltp_range_km", "wltp_mixed_range_km"),
            electricConsumptionKwhPer100Km = electricConsumption,
            fuelConsumptionLPer100Km = fields.range("wltp_combined_consumption_l_100km", "wltp_combined_fuel_consumption_l_100km"),
            co2GPerKm = fields.range("co2_emissions_g_km", "wltp_combined_co2_g_km"),
            batteryCapacityKwh = battery,
            batteryGrossKwh = fields.number("battery_gross_kwh"),
            batteryType = fields.string("battery_type", "battery_type_cells"),
            displacementCc = fields.int("displacement_cc"),
            dcMaxChargingKw =
                fields.number("dc_fast_charging_max_kw")
                    ?: charging["dc_fast_charger_max_kw"]?.let(DataModelValues::parseRange)?.range?.min,
            grossVehicleWeightKg = fields.number("max_allowable_weight_kg", "gross_vehicle_weight_kg"),
            brakedTowingKg = fields.number("max_towing_weight_braked_12_pct_kg", "max_towing_capacity_braked_kg"),
            unbrakedTowingKg = fields.number("max_towing_weight_unbraked_kg"),
            trimsAvailable = fields.stringList("trims_available"),
            charging = charging,
            additional = fields.remaining(),
        )
    }

    private fun emptyDimensions() = Dimensions(null, null, null, null, null, null, null, null)

    private val PRICE_FIELDS =
        listOf(
            "price" to PriceBasis.PRICE,
            "rrp" to PriceBasis.RRP,
            "rrp_from" to PriceBasis.RRP_FROM,
            "rrp_excl_grants" to PriceBasis.RRP_EXCLUDING_GRANTS,
            "rrp_incl_grants" to PriceBasis.RRP_INCLUDING_GRANTS,
            "rotr_price" to PriceBasis.ON_THE_ROAD,
            "vrt_rebate" to PriceBasis.VRT_REBATE,
            "seai_grant" to PriceBasis.SEAI_GRANT,
            "annual_road_tax" to PriceBasis.ANNUAL_ROAD_TAX,
        )
}
