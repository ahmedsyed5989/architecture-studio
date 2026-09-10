package de.tum.cit.aet.apollon.puml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Everything a `.puml` file's own PlantUML source contains that the Apollon canvas cannot
 * represent — the sidecar that makes round-tripping lossless (spec §11). Written next to the
 * working `.apollon` file as `source.residual.json`; never shown to the user.
 *
 * `@Serializable` is not available here — the `kotlin-serialization` compiler plugin is not
 * applied to this module (only the runtime library is on the classpath) — so this hand-rolls
 * the JSON the same way `document/DiagramDocument.kt` and `protocol/Protocol.kt` already do.
 */
data class PumlResidual(
    val startLine: String,
    val endLine: String,
    val eol: String,
    val indent: String,
    val preamble: List<String> = emptyList(),
    val postamble: List<String> = emptyList(),
    val unsupported: List<String> = emptyList(),
    /** nodeId -> the exact source keyword ("class", "abstract class", "interface", "enum", "entity"). */
    val typeKeywords: Map<String, String> = emptyMap(),
    /** edgeId -> the exact source arrow token ("-->", "--->", "<|..", ...). */
    val arrowTokens: Map<String, String> = emptyMap(),
) {
    fun toJson(): String {
        val obj =
            buildJsonObject {
                put("version", 1)
                put("startLine", startLine)
                put("endLine", endLine)
                put("eol", eol)
                put("indent", indent)
                put("preamble", stringArray(preamble))
                put("postamble", stringArray(postamble))
                put("unsupported", stringArray(unsupported))
                put("typeKeywords", stringMap(typeKeywords))
                put("arrowTokens", stringMap(arrowTokens))
            }
        return Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(JsonObject.serializer(), obj) + "\n"
    }

    companion object {
        private const val CURRENT_VERSION = 1

        fun empty(): PumlResidual = PumlResidual(startLine = "@startuml", endLine = "@enduml", eol = "\n", indent = "  ")

        fun fromJson(text: String): PumlResidual {
            if (text.isBlank()) return empty()
            val root = Json.parseToJsonElement(text).jsonObject
            val version = (root["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: CURRENT_VERSION
            if (version > CURRENT_VERSION) {
                throw IllegalStateException("residual schema version $version is newer than this plugin supports")
            }
            return PumlResidual(
                startLine = root.stringOrDefault("startLine", "@startuml"),
                endLine = root.stringOrDefault("endLine", "@enduml"),
                eol = root.stringOrDefault("eol", "\n"),
                indent = root.stringOrDefault("indent", "  "),
                preamble = root.stringArray("preamble"),
                postamble = root.stringArray("postamble"),
                unsupported = root.stringArray("unsupported"),
                typeKeywords = root.stringMap("typeKeywords"),
                arrowTokens = root.stringMap("arrowTokens"),
            )
        }

        private fun stringArray(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

        private fun stringMap(values: Map<String, String>) =
            buildJsonObject { values.forEach { (k, v) -> put(k, v) } }

        private fun JsonObject.stringOrDefault(
            key: String,
            default: String,
        ): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: default

        private fun JsonObject.stringArray(key: String): List<String> =
            ((this[key] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        private fun JsonObject.stringMap(key: String): Map<String, String> =
            ((this[key] as? JsonObject) ?: JsonObject(emptyMap())).entries.associate { (k, v) -> k to v.jsonPrimitive.content }
    }
}
