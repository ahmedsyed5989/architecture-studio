package de.tum.cit.aet.apollon.puml

/**
 * Classifies a `.puml` file's [DiagramFamily] from its `@startuml` body's grammar — element
 * keywords, C4 macro calls, message-arrow shape — never from the file's name or extension (plan
 * §10). Ordered so the most distinctive/least ambiguous grammar is checked first: a C4 include or
 * macro call is unmistakable, whereas a bare `class`/`object` keyword only means something once
 * more specific families have been ruled out.
 *
 * Deliberately conservative like [PlantUmlImporter]: when nothing matches, this returns [DiagramFamily.OTHER]
 * rather than guessing at [DiagramFamily.CLASS] — the entry point that consumes this
 * ([de.tum.cit.aet.apollon.workspace.ArchitectStudioWorkspace]) decides whether "recognized but not
 * editable" and "not recognized at all" need different user-facing wording, this only classifies.
 */
object DiagramTypeDetector {
    private val C4_INCLUDE = Regex("""^!include(_once|_many)?\b.*C4""", RegexOption.IGNORE_CASE)
    private val C4_CALL =
        Regex(
            """\b(Person|Person_Ext|System|System_Ext|SystemDb|SystemDb_Ext|SystemQueue|SystemQueue_Ext|""" +
                """Container|ContainerDb|ContainerDb_Ext|ContainerQueue|ContainerQueue_Ext|Component|Component_Ext|""" +
                """Rel|Rel_Back|Rel_[UDLR]|Rel_Back_[UDLR]|BiRel|Boundary|Enterprise_Boundary|System_Boundary|""" +
                """Container_Boundary|Node|Node_L|Node_R|Deployment_Node)\s*\(""",
        )

    private val STATE_MARKER = Regex("""(^|\s)\[\*]|^\s*state\s""", RegexOption.IGNORE_CASE)
    private val ACTIVITY_MARKER = Regex("""^:.*;\s*$|^(start|stop|end)\s*$|^(if|repeat|fork|partition)\b""", RegexOption.IGNORE_CASE)
    private val USE_CASE_MARKER = Regex("""^\s*usecase\s|\(.+\)\s+as\s+\S+""", RegexOption.IGNORE_CASE)
    private val OBJECT_KEYWORD = Regex("""^\s*object\s""", RegexOption.IGNORE_CASE)
    private val CLASS_KEYWORD = Regex("""^\s*(abstract\s+class|abstract|class|interface|enum|entity)\s""", RegexOption.IGNORE_CASE)
    private val PARTICIPANT_MARKER = Regex("""^\s*(participant|actor)\s""", RegexOption.IGNORE_CASE)
    private val SEQUENCE_ARROW = Regex(""".+(->>?|-->>?|<-{1,2}|\.\.>|<\.\.).+:.+""")

    private val DEPLOYMENT_KEYWORDS = Regex("""^\s*(node|device|execution\s+environment)\s""", RegexOption.IGNORE_CASE)
    private val COMPONENT_KEYWORDS = Regex("""^\s*(component|interface|package)\s|^\s*\[""", RegexOption.IGNORE_CASE)
    private val ARTIFACT_KEYWORD = Regex("""^\s*artifact\s""", RegexOption.IGNORE_CASE)

    fun detect(text: String): DiagramFamily {
        val envelope = PumlEnvelopeReader.read(text) ?: return DiagramFamily.UNKNOWN
        val body = envelope.body.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("'") }
        if (body.isEmpty()) return DiagramFamily.OTHER

        if (body.any { C4_INCLUDE.containsMatchIn(it) } || body.any { C4_CALL.containsMatchIn(it) }) {
            return DiagramFamily.C4
        }
        // Checked before SEQUENCE: a UseCase `<<include>>`/`<<extend>>` dependency line
        // (`UC1 ..> UC2 : <<extend>>`) has the exact `arrow ... : label` shape SEQUENCE_ARROW
        // matches, so the unambiguous `usecase` keyword (or bracket-`as` shorthand) must win first
        // — otherwise a UseCase diagram that also declares `actor`s gets misread as a sequence
        // diagram's participant + message pair.
        if (body.any { USE_CASE_MARKER.containsMatchIn(it) }) {
            return DiagramFamily.USE_CASE
        }
        if (body.any { PARTICIPANT_MARKER.containsMatchIn(it) } && body.any { SEQUENCE_ARROW.matches(it) }) {
            return DiagramFamily.SEQUENCE
        }
        if (body.any { STATE_MARKER.containsMatchIn(it) }) {
            return DiagramFamily.STATE
        }
        if (body.any { ACTIVITY_MARKER.containsMatchIn(it) }) {
            return DiagramFamily.ACTIVITY
        }
        if (body.any { PARTICIPANT_MARKER.containsMatchIn(it) }) {
            return DiagramFamily.USE_CASE
        }

        val deploymentHits = body.count { DEPLOYMENT_KEYWORDS.containsMatchIn(it) || ARTIFACT_KEYWORD.containsMatchIn(it) }
        val componentHits = body.count { COMPONENT_KEYWORDS.containsMatchIn(it) }
        if (deploymentHits > 0 || componentHits > 0) {
            return if (deploymentHits > componentHits) DiagramFamily.DEPLOYMENT else DiagramFamily.COMPONENT
        }

        if (body.any { OBJECT_KEYWORD.containsMatchIn(it) }) {
            return DiagramFamily.OBJECT
        }
        if (body.any { CLASS_KEYWORD.containsMatchIn(it) }) {
            return DiagramFamily.CLASS
        }
        // No declaration keyword of any kind, but at least one `A -> B : label`-shaped line —
        // PlantUML itself treats bare, undeclared message arrows as a sequence diagram by default.
        if (body.any { SEQUENCE_ARROW.matches(it) }) {
            return DiagramFamily.SEQUENCE
        }
        return DiagramFamily.OTHER
    }
}
