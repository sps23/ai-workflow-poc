# ExtractedVehicle model definition

JSON keys are camelCase exactly as listed. `?` marks a nullable field (use `null` when not printed).
Fields without `?` are required. Lists may be empty (`[]`), maps may be empty (`{}`).
Integer fields must be JSON integers (no decimals). Numbers carry no units or thousands separators.

## Types

ExtractedVehicle
- title: string - model name exactly as published, e.g. "Dacia Jogger"
- make: string - manufacturer, e.g. "Dacia"
- model: string - model without make, e.g. "Jogger"
- effectiveDate?: string - printed validity text, e.g. "August 2026"
- effectivePeriod?: string - "YYYY-MM"
- trims: Trim[] (at least one)
- options: VehicleOption[]
- technicalSpecifications: TechnicalSpecifications
- additional: map<string,string>

Trim
- name: string
- variants: TrimVariant[] (at least one)
- standardEquipment: EquipmentGroup[]
- additional: map<string,string>

TrimVariant
- name: string - e.g. "TCe 110" or "Raval Urban 116hp V1"
- trimName: string - equals parent Trim.name
- modelCode?: string
- powertrainType: PowertrainType
- batteryCapacityKwh?: number
- powerHp?: integer
- powerKw?: integer
- co2GPerKm?: NumericRange
- wltpRangeKm?: NumericRange
- electricConsumptionKwhPer100Km?: number
- taxBand?: string
- vrtRatePercent?: number - 7.00% -> 7.0
- prices: PriceFact[] (at least one)
- additional: map<string,string>

PriceFact
- basis: PriceBasis
- amount?: Money
- maxAmount?: Money
- rawText: string - printed price text

Money
- minorUnits: integer - cents, €26,990 -> 2699000
- currency: string - ISO 4217, default "EUR"

NumericRange
- min: number
- max: number - equal to min for a single value

EquipmentGroup
- category: string - snake_case section name
- isAddition: boolean
- items: string[]

VehicleOption
- code?: string
- name: string
- category: OptionCategory
- categoryLabel: string - printed section/category label
- type?: string
- items: string[] - pack contents
- availability: OptionAvailability[]
- additional: map<string,string>

OptionAvailability
- scope: string - trim name or printed scope
- state: FitmentState
- price?: PriceFact
- note?: string

TechnicalSpecifications
- dimensions: Dimensions
- powertrains: PowertrainSpec[]
- additional: map<string,string>

Dimensions (all NumericRange?, millimetres / litres / kilograms)
- lengthMm?, widthMm?, heightMm?, wheelbaseMm?, bootCapacityLitres?, maxBootCapacityLitres?,
  frontTrunkCapacityLitres?, brakedTowingKg?
- additional: map<string,string>

PowertrainSpec
- name: string
- type: PowertrainType
- modelCode?: string, drivetrain?: string, transmission?: string
- powerKw?: number, powerHp?: number, torqueNm?: number, topSpeedKmh?: number
- acceleration0To100S?: NumericRange, wltpRangeKm?: NumericRange,
  electricConsumptionKwhPer100Km?: NumericRange, fuelConsumptionLPer100Km?: NumericRange,
  co2GPerKm?: NumericRange
- batteryCapacityKwh?: number, batteryGrossKwh?: number, batteryType?: string
- displacementCc?: integer
- dcMaxChargingKw?: number, grossVehicleWeightKg?: number, brakedTowingKg?: number,
  unbrakedTowingKg?: number
- trimsAvailable: string[] - trim names
- charging: map<string,string>
- additional: map<string,string>

## Enums

- PowertrainType: ELECTRIC, PLUG_IN_HYBRID, HYBRID, MILD_HYBRID, PETROL, DIESEL, OTHER, UNKNOWN
- PriceBasis: PRICE, RRP, RRP_FROM, RRP_EXCLUDING_GRANTS, RRP_INCLUDING_GRANTS, ON_THE_ROAD,
  VRT_REBATE, SEAI_GRANT, ANNUAL_ROAD_TAX, OPTION
- OptionCategory: PAINT, ROOF_COLOUR, WHEELS, INTERIOR, PACKAGE, SINGLE_OPTION, ACCESSORY_PACK, OTHER
- FitmentState: STANDARD, OPTIONAL, NOT_AVAILABLE, UNKNOWN

## Example (abbreviated)

{
  "title": "Dacia Jogger",
  "make": "Dacia",
  "model": "Jogger",
  "effectiveDate": "August 2026",
  "effectivePeriod": "2026-08",
  "trims": [
    {
      "name": "Essential",
      "variants": [
        {
          "name": "TCe 110",
          "trimName": "Essential",
          "modelCode": null,
          "powertrainType": "PETROL",
          "powerHp": 110,
          "co2GPerKm": { "min": 135, "max": 135 },
          "prices": [
            { "basis": "PRICE", "amount": { "minorUnits": 2699000, "currency": "EUR" }, "rawText": "€26,990" },
            { "basis": "ANNUAL_ROAD_TAX", "amount": { "minorUnits": 21000, "currency": "EUR" }, "rawText": "€210" }
          ],
          "additional": {}
        }
      ],
      "standardEquipment": [
        { "category": "safety_and_security", "isAddition": false, "items": ["Lane keep assist"] }
      ],
      "additional": {}
    }
  ],
  "options": [
    {
      "code": null,
      "name": "Metallic paint",
      "category": "PAINT",
      "categoryLabel": "Paint",
      "items": [],
      "availability": [
        {
          "scope": "Essential",
          "state": "OPTIONAL",
          "price": { "basis": "OPTION", "amount": { "minorUnits": 60000, "currency": "EUR" }, "rawText": "€600" }
        }
      ],
      "additional": {}
    }
  ],
  "technicalSpecifications": {
    "dimensions": { "lengthMm": { "min": 4547, "max": 4547 }, "bootCapacityLitres": { "min": 160, "max": 160 }, "additional": {} },
    "powertrains": [
      {
        "name": "TCe 110",
        "type": "PETROL",
        "powerHp": 110,
        "trimsAvailable": ["Essential"],
        "charging": {},
        "additional": {}
      }
    ],
    "additional": {}
  },
  "additional": {}
}
