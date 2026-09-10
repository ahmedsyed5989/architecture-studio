package de.tum.cit.aet.apollon.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitignoreEditorTest {
    @Test
    fun `no gitignore yet creates one with just the entry`() {
        assertEquals(".architect-studio/\n", ensureArchitectStudioGitignoreEntry(null))
    }

    @Test
    fun `blank gitignore is treated the same as missing`() {
        assertEquals(".architect-studio/\n", ensureArchitectStudioGitignoreEntry("   \n  "))
    }

    @Test
    fun `already present entry is left untouched`() {
        assertNull(ensureArchitectStudioGitignoreEntry("node_modules/\n.architect-studio/\n"))
    }

    @Test
    fun `entry without a trailing slash still counts as present`() {
        assertNull(ensureArchitectStudioGitignoreEntry(".architect-studio\n"))
    }

    @Test
    fun `entry with a leading slash still counts as present`() {
        assertNull(ensureArchitectStudioGitignoreEntry("/.architect-studio/\n"))
    }

    @Test
    fun `never duplicates the entry on a second call`() {
        val first = ensureArchitectStudioGitignoreEntry("node_modules/\n")
        assertTrue(first != null)
        val second = ensureArchitectStudioGitignoreEntry(first)
        assertNull(second)
    }

    @Test
    fun `a negated entry is respected and not overridden`() {
        assertNull(ensureArchitectStudioGitignoreEntry("!.architect-studio/\n"))
    }

    @Test
    fun `appends after existing content, preserving it byte for byte`() {
        val existing = "node_modules/\ndist/\n"
        val updated = ensureArchitectStudioGitignoreEntry(existing)!!
        assertTrue(updated.startsWith(existing))
        assertTrue(updated.endsWith(".architect-studio/\n"))
    }

    @Test
    fun `crlf files get a crlf-terminated entry`() {
        val existing = "node_modules/\r\n"
        val updated = ensureArchitectStudioGitignoreEntry(existing)!!
        assertTrue(updated.contains("\r\n.architect-studio/\r\n"))
        assertTrue(!updated.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun `a file with no trailing newline still gets a well-formed entry appended`() {
        val existing = "node_modules/"
        val updated = ensureArchitectStudioGitignoreEntry(existing)!!
        assertTrue(updated.startsWith("node_modules/"))
        assertTrue(updated.endsWith(".architect-studio/\n"))
    }

    @Test
    fun `a comment-only line naming the entry does not count as present`() {
        val updated = ensureArchitectStudioGitignoreEntry("# .architect-studio/\n")
        assertTrue(updated != null)
    }
}
