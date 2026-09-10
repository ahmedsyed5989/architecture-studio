package de.tum.cit.aet.apollon.workspace

/** The line added to the project root `.gitignore` (spec §15). */
const val ARCHITECT_STUDIO_GITIGNORE_ENTRY = ".architect-studio/"

/**
 * Computes the new `.gitignore` text, or `null` when no change is needed. Pure string logic
 * (spec §9) so it is unit-testable without touching a real file:
 * - `existing == null` (no `.gitignore` yet): create one containing just the entry.
 * - The entry (or a negation of it, `!.architect-studio/`) already appears, ignoring a leading
 *   `/` or trailing `/` and surrounding whitespace: no change — never duplicate it, and never
 *   fight a user who deliberately un-ignored it.
 * - Otherwise: append the entry, preserving the file's existing bytes and line-ending style
 *   (`\r\n` if any line already uses it) exactly, with one blank line separating it from
 *   whatever was already there.
 */
fun ensureArchitectStudioGitignoreEntry(existing: String?): String? {
    if (existing == null || existing.isBlank()) {
        return "$ARCHITECT_STUDIO_GITIGNORE_ENTRY\n"
    }
    val eol = if (existing.contains("\r\n")) "\r\n" else "\n"
    val alreadyPresent =
        existing.split(Regex("\r\n|\n")).any { line ->
            val normalized = normalizeGitignoreLine(line)
            normalized == ".architect-studio" || normalized == "!.architect-studio"
        }
    if (alreadyPresent) return null

    val withTrailingNewline = if (existing.endsWith(eol)) existing else existing + eol
    val withBlankSeparator = if (withTrailingNewline.endsWith("$eol$eol")) withTrailingNewline else withTrailingNewline + eol
    return withBlankSeparator + ARCHITECT_STUDIO_GITIGNORE_ENTRY + eol
}

private fun normalizeGitignoreLine(line: String): String {
    var t = line.trim()
    if (t.isEmpty() || t.startsWith("#")) return ""
    val negated = t.startsWith("!")
    if (negated) t = t.substring(1).trim()
    if (t.startsWith("/")) t = t.substring(1)
    if (t.endsWith("/")) t = t.substring(0, t.length - 1)
    return if (negated) "!$t" else t
}
