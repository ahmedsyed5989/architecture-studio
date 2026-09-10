package de.tum.cit.aet.apollon.puml

/** File extensions Architect Studio treats as PlantUML source. `.iuml` is deliberately excluded:
 *  by convention it is an `!include` fragment with no `@startuml`, so "Edit" on one would import
 *  a partial diagram and rewrite it as a whole one. */
val PLANT_UML_EXTENSIONS = setOf("puml", "plantuml", "pu", "wsd")

fun isPlantUmlExtension(extension: String?): Boolean = extension != null && extension.lowercase() in PLANT_UML_EXTENSIONS

/** The four classifier kinds the Apollon canvas can render (see [PumlKind] to [ClassStereotype] mapping in ApollonModelMapper). */
enum class PumlKind { CLASS, ABSTRACT_CLASS, INTERFACE, ENUM, ENTITY }

enum class PumlRelationKind { INHERITANCE, REALIZATION, COMPOSITION, AGGREGATION, UNIDIRECTIONAL, BIDIRECTIONAL, DEPENDENCY }

/**
 * One attribute or method line. [apollonName] is already in Apollon's own free-text member
 * format (e.g. `"+ name: Type"`) — Apollon has no separate visibility/type fields, see
 * `library/lib/types/nodes/NodeProps.ts`. [isAbstract] is the one member-level fact Apollon
 * *does* model as a real field (methods only).
 */
data class PumlMember(
    val apollonName: String,
    val isMethod: Boolean,
    val isAbstract: Boolean,
)

data class PumlType(
    val name: String,
    val kind: PumlKind,
    val attributes: List<PumlMember>,
    val methods: List<PumlMember>,
    /** The exact source keyword (`"class"`, `"abstract class"`, `"abstract"`, `"interface"`, `"enum"`, `"entity"`). */
    val keyword: String,
)

data class PumlRelation(
    val sourceName: String,
    val targetName: String,
    val kind: PumlRelationKind,
    val sourceMultiplicity: String = "",
    val sourceRole: String = "",
    val targetMultiplicity: String = "",
    val targetRole: String = "",
    /** Text after a trailing `: label` on the relation line. Carried, never rendered on a class edge. */
    val label: String = "",
    /** The exact arrow token from the source, e.g. `"-->"`, `"--->"`, `"<|.."`. */
    val arrowToken: String,
)

data class PumlDiagram(
    val name: String?,
    val types: List<PumlType>,
    val relations: List<PumlRelation>,
)
