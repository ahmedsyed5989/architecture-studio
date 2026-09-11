package de.tum.cit.aet.apollon.puml

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** The Apollon model schema version this converter writes — matches `DiagramDocument.scaffoldModel`. */
private const val MODEL_SCHEMA_VERSION = "4.0.0"

data class MappedModel(
    val model: JsonObject,
    val typeKeywords: Map<String, String>,
    val arrowTokens: Map<String, String>,
    /** nodeId -> source `as <alias>` (plan §9's new families only — see [PumlResidual.elementAliases]). */
    val elementAliases: Map<String, String> = emptyMap(),
)

data class PumlExport(
    val diagram: PumlDiagram,
    val typeKeywords: Map<String, String>,
    val arrowTokens: Map<String, String>,
)

private fun obj(e: JsonElement?) = e as? JsonObject

private fun arr(e: JsonElement?) = e as? JsonArray

private fun text(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun flag(e: JsonElement?) = (e as? JsonPrimitive)?.content == "true"

private fun number(e: JsonElement?): Int? = (e as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()

/**
 * The only file that knows both the [PumlDiagram] vocabulary and the Apollon `UMLModel` JSON
 * shape (plan §1, context §1). [toApollonModel] additionally performs the plan's §A6 merge: a
 * re-import keeps every existing node's id/position/size/colour when its classifier name still
 * exists in the new parse, so editing one line of a `.puml` file never discards the user's canvas
 * layout.
 */
object ApollonModelMapper {
    fun toApollonModel(
        diagram: PumlDiagram,
        previous: JsonObject?,
        title: String,
    ): MappedModel {
        val prevNodes = arr(previous?.get("nodes"))?.mapNotNull { obj(it) } ?: emptyList()
        val prevEdges = arr(previous?.get("edges"))?.mapNotNull { obj(it) } ?: emptyList()

        val prevIdByName = mutableMapOf<String, String>()
        val prevNodeById = mutableMapOf<String, JsonObject>()
        prevNodes.forEach { node ->
            val id = text(node["id"]) ?: return@forEach
            val name = text(obj(node["data"])?.get("name")) ?: return@forEach
            prevIdByName[name] = id
            prevNodeById[id] = node
        }
        val prevNameById = prevIdByName.entries.associate { (name, id) -> id to name }

        val prevEdgesByKey = LinkedHashMap<String, MutableList<JsonObject>>()
        prevEdges.forEach { edge ->
            val srcName = prevNameById[text(edge["source"])] ?: return@forEach
            val tgtName = prevNameById[text(edge["target"])] ?: return@forEach
            val type = text(edge["type"]) ?: return@forEach
            prevEdgesByKey.getOrPut("$srcName|$tgtName|$type") { mutableListOf() }.add(edge)
        }

        var maxBottom = 0
        prevNodes.forEach { node ->
            val y = number(obj(node["position"])?.get("y")) ?: 0
            val h = number(node["height"]) ?: 0
            maxBottom = maxOf(maxBottom, y + h)
        }
        val originY = if (prevNodes.isEmpty()) 60 else maxBottom + 120
        val freshCount = diagram.types.count { prevIdByName[it.name] == null }
        val freshPositions = PumlLayout.gridPositions(freshCount, 60, originY).iterator()

        val typeKeywords = mutableMapOf<String, String>()
        val idByName = mutableMapOf<String, String>()
        val rectById = mutableMapOf<String, Rect>()
        val newNodes = mutableListOf<JsonObject>()

        diagram.types.forEach { type ->
            val existingId = prevIdByName[type.name]
            val existing = existingId?.let { prevNodeById[it] }
            val id = existingId ?: UUID.randomUUID().toString()
            typeKeywords[id] = type.keyword
            idByName[type.name] = id

            val size = PumlLayout.sizeOf(type)
            val position =
                if (existing != null) {
                    obj(existing["position"]) ?: buildJsonObject { put("x", 0); put("y", 0) }
                } else {
                    val p = freshPositions.next()
                    buildJsonObject { put("x", p.x); put("y", p.y) }
                }
            val width = if (existing != null) number(existing["width"]) ?: size.width else size.width
            val height = if (existing != null) number(existing["height"]) ?: size.height else size.height
            val existingData = existing?.let { obj(it["data"]) }

            val data =
                buildJsonObject {
                    put("name", type.name)
                    when (type.kind) {
                        PumlKind.INTERFACE -> put("stereotype", "interface")
                        PumlKind.ENUM -> put("stereotype", "enumeration")
                        PumlKind.ABSTRACT_CLASS -> put("isAbstract", true)
                        else -> {}
                    }
                    put("attributes", buildJsonArray { type.attributes.forEachIndexed { i, m -> add(memberJson(m, existingData, "attributes", i)) } })
                    put("methods", buildJsonArray { type.methods.forEachIndexed { i, m -> add(memberJson(m, existingData, "methods", i)) } })
                    existingData?.get("fillColor")?.let { put("fillColor", it) }
                    existingData?.get("strokeColor")?.let { put("strokeColor", it) }
                    existingData?.get("textColor")?.let { put("textColor", it) }
                    existingData?.get("tags")?.let { put("tags", it) }
                }
            val node =
                buildJsonObject {
                    put("id", id)
                    put("width", width)
                    put("height", height)
                    put("type", "class")
                    put("position", position)
                    put("data", data)
                    put("measured", buildJsonObject { put("width", width); put("height", height) })
                }
            newNodes += node
            rectById[id] = Rect(number(position["x"]) ?: 0, number(position["y"]) ?: 0, width, height)
        }

        val arrowTokens = mutableMapOf<String, String>()
        val consumed = mutableMapOf<String, Int>()
        val newEdges = mutableListOf<JsonObject>()
        diagram.relations.forEach { rel ->
            val sourceId = idByName[rel.sourceName] ?: return@forEach
            val targetId = idByName[rel.targetName] ?: return@forEach
            val apollonType = apollonEdgeType(rel.kind)
            val key = "${rel.sourceName}|${rel.targetName}|$apollonType"
            val idx = consumed.getOrDefault(key, 0)
            consumed[key] = idx + 1
            val existing = prevEdgesByKey[key]?.getOrNull(idx)
            val id = existing?.let { text(it["id"]) } ?: UUID.randomUUID().toString()
            arrowTokens[id] = rel.arrowToken

            val handles =
                if (existing != null) {
                    (text(existing["sourceHandle"]) ?: "bottom") to (text(existing["targetHandle"]) ?: "top")
                } else {
                    val sr = rectById[sourceId]
                    val tr = rectById[targetId]
                    if (sr != null && tr != null) PumlLayout.chooseHandles(sr, tr) else "bottom" to "top"
                }
            val existingData = existing?.let { obj(it["data"]) }
            val edge =
                buildJsonObject {
                    put("id", id)
                    put("source", sourceId)
                    put("target", targetId)
                    put("type", apollonType)
                    put("sourceHandle", handles.first)
                    put("targetHandle", handles.second)
                    put(
                        "data",
                        buildJsonObject {
                            put("points", existingData?.get("points") ?: buildJsonArray {})
                            if (rel.sourceMultiplicity.isNotEmpty()) put("sourceMultiplicity", rel.sourceMultiplicity)
                            if (rel.sourceRole.isNotEmpty()) put("sourceRole", rel.sourceRole)
                            if (rel.targetMultiplicity.isNotEmpty()) put("targetMultiplicity", rel.targetMultiplicity)
                            if (rel.targetRole.isNotEmpty()) put("targetRole", rel.targetRole)
                            if (rel.label.isNotEmpty()) put("label", rel.label)
                            existingData?.get("fillColor")?.let { put("fillColor", it) }
                            existingData?.get("strokeColor")?.let { put("strokeColor", it) }
                            existingData?.get("textColor")?.let { put("textColor", it) }
                        },
                    )
                }
            newEdges += edge
        }

        val model =
            buildJsonObject {
                put("version", MODEL_SCHEMA_VERSION)
                put("id", text(previous?.get("id")) ?: UUID.randomUUID().toString())
                put("title", title)
                put("type", "ClassDiagram")
                put("nodes", JsonArray(newNodes))
                put("edges", JsonArray(newEdges))
                put("assessments", obj(previous?.get("assessments")) ?: buildJsonObject {})
            }
        return MappedModel(model, typeKeywords, arrowTokens)
    }

    /** Also returns the [PumlResidual.typeKeywords]/[PumlResidual.arrowTokens] maps narrowed to
     *  the ids still present in [model] — callers persist these back onto the residual so a
     *  deleted node/edge's keyword or arrow-token choice doesn't linger forever (spec §11). */
    fun toPumlDiagram(
        model: JsonObject,
        residual: PumlResidual,
    ): PumlExport {
        val nodes = arr(model["nodes"])?.mapNotNull { obj(it) } ?: emptyList()
        val edges = arr(model["edges"])?.mapNotNull { obj(it) } ?: emptyList()
        val nameById = mutableMapOf<String, String>()
        val typeKeywords = mutableMapOf<String, String>()
        val arrowTokens = mutableMapOf<String, String>()

        val types =
            nodes.map { node ->
                val id = text(node["id"]) ?: UUID.randomUUID().toString()
                val data = obj(node["data"]) ?: JsonObject(emptyMap())
                val name = text(data["name"]) ?: "Unnamed"
                nameById[id] = name
                val stereotype = text(data["stereotype"])
                val isAbstractClass = flag(data["isAbstract"])
                val kind =
                    when (stereotype) {
                        "interface" -> PumlKind.INTERFACE
                        "enumeration" -> PumlKind.ENUM
                        else -> if (isAbstractClass) PumlKind.ABSTRACT_CLASS else PumlKind.CLASS
                    }
                val keyword = residual.typeKeywords[id] ?: defaultKeyword(kind)
                typeKeywords[id] = keyword
                val attributes = arr(data["attributes"])?.mapNotNull { memberFromJson(it, isMethod = false) } ?: emptyList()
                val methods = arr(data["methods"])?.mapNotNull { memberFromJson(it, isMethod = true) } ?: emptyList()
                PumlType(name, kind, attributes, methods, keyword)
            }

        val relations =
            edges.mapNotNull { edge ->
                val id = text(edge["id"]) ?: return@mapNotNull null
                val sourceName = nameById[text(edge["source"])] ?: return@mapNotNull null
                val targetName = nameById[text(edge["target"])] ?: return@mapNotNull null
                val kind = pumlRelationKind(text(edge["type"]) ?: return@mapNotNull null) ?: return@mapNotNull null
                val data = obj(edge["data"]) ?: JsonObject(emptyMap())
                val arrowToken = residual.arrowTokens[id] ?: canonicalArrowToken(kind)
                arrowTokens[id] = arrowToken
                PumlRelation(
                    sourceName = sourceName,
                    targetName = targetName,
                    kind = kind,
                    sourceMultiplicity = text(data["sourceMultiplicity"]) ?: "",
                    sourceRole = text(data["sourceRole"]) ?: "",
                    targetMultiplicity = text(data["targetMultiplicity"]) ?: "",
                    targetRole = text(data["targetRole"]) ?: "",
                    label = text(data["label"]) ?: "",
                    arrowToken = arrowToken,
                )
            }

        return PumlExport(PumlDiagram(null, types, relations), typeKeywords, arrowTokens)
    }

    /** The set of classifier names + `(source,target,type)` relation triples in [model] — used by
     *  [RoundTripValidator] to check that an exported-then-reparsed candidate agrees with it. */
    fun signature(model: JsonObject): Pair<Set<String>, List<Triple<String, String, String>>> {
        val nodes = arr(model["nodes"])?.mapNotNull { obj(it) } ?: emptyList()
        val nameById = mutableMapOf<String, String>()
        val names =
            nodes.mapNotNull { node ->
                val id = text(node["id"]) ?: return@mapNotNull null
                val name = text(obj(node["data"])?.get("name")) ?: return@mapNotNull null
                nameById[id] = name
                name
            }.toSet()
        val relations =
            (arr(model["edges"])?.mapNotNull { obj(it) } ?: emptyList()).mapNotNull { edge ->
                val src = nameById[text(edge["source"])] ?: return@mapNotNull null
                val tgt = nameById[text(edge["target"])] ?: return@mapNotNull null
                val type = text(edge["type"]) ?: return@mapNotNull null
                Triple(src, tgt, type)
            }
        return names to relations
    }

    private fun defaultKeyword(kind: PumlKind) =
        when (kind) {
            PumlKind.INTERFACE -> "interface"
            PumlKind.ENUM -> "enum"
            PumlKind.ABSTRACT_CLASS -> "abstract class"
            PumlKind.ENTITY -> "entity"
            PumlKind.CLASS -> "class"
        }

    private fun memberJson(
        member: PumlMember,
        existingData: JsonObject?,
        arrayKey: String,
        index: Int,
    ): JsonObject {
        val existing = arr(existingData?.get(arrayKey))?.getOrNull(index) as? JsonObject
        val id = existing?.let { text(it["id"]) } ?: UUID.randomUUID().toString()
        return buildJsonObject {
            put("id", id)
            put("name", member.apollonName)
            if (member.isAbstract) put("isAbstract", true)
        }
    }

    private fun memberFromJson(
        element: JsonElement,
        isMethod: Boolean,
    ): PumlMember? {
        val obj = element as? JsonObject ?: return null
        val name = text(obj["name"]) ?: return null
        return PumlMember(name, isMethod, flag(obj["isAbstract"]))
    }

    fun apollonEdgeType(kind: PumlRelationKind) =
        when (kind) {
            PumlRelationKind.INHERITANCE -> "ClassInheritance"
            PumlRelationKind.REALIZATION -> "ClassRealization"
            PumlRelationKind.COMPOSITION -> "ClassComposition"
            PumlRelationKind.AGGREGATION -> "ClassAggregation"
            PumlRelationKind.UNIDIRECTIONAL -> "ClassUnidirectional"
            PumlRelationKind.BIDIRECTIONAL -> "ClassBidirectional"
            PumlRelationKind.DEPENDENCY -> "ClassDependency"
        }

    private fun pumlRelationKind(apollonType: String): PumlRelationKind? =
        when (apollonType) {
            "ClassInheritance" -> PumlRelationKind.INHERITANCE
            "ClassRealization" -> PumlRelationKind.REALIZATION
            "ClassComposition" -> PumlRelationKind.COMPOSITION
            "ClassAggregation" -> PumlRelationKind.AGGREGATION
            "ClassUnidirectional" -> PumlRelationKind.UNIDIRECTIONAL
            "ClassBidirectional" -> PumlRelationKind.BIDIRECTIONAL
            "ClassDependency" -> PumlRelationKind.DEPENDENCY
            else -> null
        }
}
