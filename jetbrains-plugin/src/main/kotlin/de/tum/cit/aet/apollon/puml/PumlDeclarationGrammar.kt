package de.tum.cit.aet.apollon.puml

/**
 * `<keyword> ("Name"|Name) [as alias] [{]` — the element-declaration grammar shared by
 * UseCase/Component/Deployment (plan §9). Unlike Class (where a bare identifier is both the
 * display name and the relation-matching key, so aliasing was out of scope entirely — see
 * [PlantUmlImporter]'s doc comment), these families' display names are routinely quoted, spaced
 * phrases (`usecase "Place Order" as UC1`), so `as <alias>` genuinely needs to round-trip: it's
 * what relation lines reference. [alias] is carried through [PumlResidual.elementAliases] the same
 * way `typeKeywords`/`arrowTokens` already carry per-id source facts. An explicit `<<stereotype>>`
 * on the declaration is still out of scope (these families have no stereotype field to hold it) —
 * [matchDeclaration] returns `null` for one, same conservative punt Class makes.
 */
data class ParsedDeclaration(val keyword: String, val displayName: String, val alias: String?, val opensBody: Boolean)

fun declarationRegex(keywordAlternation: String): Regex =
    Regex(
        """^($keywordAlternation)\s+(?:"([^"]+)"|([A-Za-z_][\w.$]*))(?:\s+as\s+(\S+))?\s*(<<[^>]*>>)?\s*(\{)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

fun matchDeclaration(
    regex: Regex,
    trimmed: String,
): ParsedDeclaration? {
    val m = regex.find(trimmed) ?: return null
    val keyword = m.groupValues[1].lowercase().replace(Regex("\\s+"), " ")
    val displayName = m.groupValues[2].ifEmpty { m.groupValues[3] }
    if (displayName.isEmpty()) return null
    val alias = m.groupValues[4].ifEmpty { null }
    val hasStereotype = m.groupValues[5].isNotEmpty()
    val opensBody = m.groupValues[6] == "{"
    if (hasStereotype) return null
    return ParsedDeclaration(keyword, displayName, alias, opensBody)
}

/** Renders `keyword "Name" as alias {`/`keyword Name` — the inverse of [matchDeclaration]. Quotes
 *  the name whenever it isn't a legal bare identifier by itself, same rule
 *  [PumlRelationGrammar.quoteIfNeeded] uses for relation endpoints. */
fun renderDeclarationHeader(
    keyword: String,
    displayName: String,
    alias: String?,
): String {
    val name = PumlRelationGrammar.quoteIfNeeded(displayName)
    return if (alias != null) "$keyword $name as $alias" else "$keyword $name"
}
