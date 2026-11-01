# 0001 – Extract into the normalized Kotlin model with a generated JSON Schema

Status: accepted, 2026-10-31.

## Context
Brand JSON files under `car-pricelists/` use a loose snake_case **source shape** that
`CarDataModelParser` normalizes. A model asked for that shape has no contract to follow.
kotlinx-serialization ships no JSON-Schema generator.

## Decision
- Gemini returns `ExtractedVehicle` (`catalog/extraction/ExtractedVehicle.kt`): the
  **normalized artifact** minus code-owned identity (`id`, `sourcePath`, `sourcePdfPath`).
- `JsonSchemaGenerator` walks `SerialDescriptor`s and produces the `responseJsonSchema`
  (objects with `additionalProperties: false`, lists, `Map<String,String>`, enums, nullables
  as `anyOf` with `null`).
- The response is accepted only after a strict decode (`ExtractedVehicle.strictJson`,
  unknown keys rejected).

## Consequences
- Changing a `@Serializable` class changes the schema with no hand edits; the generator's tests
  pin the supported descriptor kinds.
- Gemini accepted the full schema (evidence Run 2); a deeper or recursive model may hit its
  schema-subset limits.
- Stage 0 showed plain JSON mode already decodes strictly; the schema adds robustness.
