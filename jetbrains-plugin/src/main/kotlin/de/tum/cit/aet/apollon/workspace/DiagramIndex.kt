package de.tum.cit.aet.apollon.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** `.architect-studio/index.json` cannot be parsed at all — malformed JSON, wrong shape. The
 *  caller backs the file up and starts fresh rather than refusing to work (plan §B1). */
class CorruptIndexException(message: String) : Exception(message)

/** The index was written by a newer version of Architect Studio (schema `version` ahead of what
 *  this plugin understands). Unlike [CorruptIndexException], the caller must NOT overwrite this —
 *  doing so would silently discard diagrams a newer plugin version already migrated. */
class UnsupportedIndexVersionException(message: String) : Exception(message)

/**
 * One tracked `.puml` <-> working-`.apollon` binding. [extra] holds any JSON keys this version of
 * the plugin does not recognise, preserved verbatim so a future schema version's fields survive a
 * round-trip through this one (plan's forward-compatibility requirement).
 */
data class DiagramEntry(
    val id: String,
    val format: String,
    /** Project-relative, forward-slash path to the `.puml` source (plan §4: never absolute). */
    val source: String,
    /** Project-relative path to the working `.apollon` file under `.architect-studio/`. */
    val workingFile: String,
    /** Project-relative path to the sidecar residual JSON. */
    val residualFile: String,
    val sourceHash: String,
    val sourceSyncedAt: String,
    val updatedAt: String,
    val extra: JsonObject = JsonObject(emptyMap()),
)

data class DiagramIndex(val version: Int, val diagrams: List<DiagramEntry>)

private val KNOWN_KEYS = setOf("id", "format", "source", "workingFile", "residualFile", "sourceHash", "sourceSyncedAt", "updatedAt")

object DiagramIndexCodec {
    const val CURRENT_VERSION = 1

    fun empty(): DiagramIndex = DiagramIndex(CURRENT_VERSION, emptyList())

    fun parse(text: String): DiagramIndex {
        if (text.isBlank()) return empty()
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw CorruptIndexException("index.json is not valid JSON")
            }
        if (root !is JsonObject) throw CorruptIndexException("index.json is not a JSON object")
        val version =
            (root["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: throw CorruptIndexException("index.json is missing a numeric \"version\"")
        if (version > CURRENT_VERSION) {
            throw UnsupportedIndexVersionException(
                "index.json was written by a newer version of Architect Studio (schema $version)",
            )
        }
        val diagramsArray = root["diagrams"] as? JsonArray ?: throw CorruptIndexException("index.json is missing a \"diagrams\" array")
        val entries = diagramsArray.map { element -> parseEntry(element as? JsonObject ?: throw CorruptIndexException("a diagram entry is not an object")) }
        return DiagramIndex(version, entries)
    }

    private fun parseEntry(obj: JsonObject): DiagramEntry =
        DiagramEntry(
            id = requireString(obj, "id"),
            format = requireString(obj, "format"),
            source = requireString(obj, "source"),
            workingFile = requireString(obj, "workingFile"),
            residualFile = requireString(obj, "residualFile"),
            sourceHash = requireString(obj, "sourceHash"),
            sourceSyncedAt = requireString(obj, "sourceSyncedAt"),
            updatedAt = requireString(obj, "updatedAt"),
            extra = JsonObject(obj.filterKeys { it !in KNOWN_KEYS }),
        )

    private fun requireString(
        obj: JsonObject,
        key: String,
    ): String = (obj[key] as? JsonPrimitive)?.contentOrNull ?: throw CorruptIndexException("a diagram entry is missing \"$key\"")

    /** Sorted by [DiagramEntry.source] so the file is deterministic and diffs cleanly under VCS. */
    fun render(index: DiagramIndex): String {
        val sorted = index.diagrams.sortedBy { it.source }
        val root =
            buildJsonObject {
                put("version", index.version)
                put(
                    "diagrams",
                    buildJsonArray {
                        sorted.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("id", entry.id)
                                    put("format", entry.format)
                                    put("source", entry.source)
                                    put("workingFile", entry.workingFile)
                                    put("residualFile", entry.residualFile)
                                    put("sourceHash", entry.sourceHash)
                                    put("sourceSyncedAt", entry.sourceSyncedAt)
                                    put("updatedAt", entry.updatedAt)
                                    entry.extra.forEach { (key, value) -> put(key, value) }
                                },
                            )
                        }
                    },
                )
            }
        val printer = Json { prettyPrint = true; prettyPrintIndent = "  " }
        return printer.encodeToString(JsonObject.serializer(), root) + "\n"
    }
}
