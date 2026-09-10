package de.tum.cit.aet.apollon.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DiagramMappingRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(
        id: String,
        source: String,
        workingFile: String = "diagrams/$id/x.apollon",
    ) = DiagramEntry(id, "puml", source, workingFile, "diagrams/$id/source.residual.json", "sha256:abc", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")

    @Test
    fun `an empty or missing index file reads as an empty index`() {
        val root = tmp.root.toPath()
        val repo = DiagramMappingRepository(root)
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `upsert then save then reload round-trips an entry`() {
        val root = tmp.root.toPath()
        val repo = DiagramMappingRepository(root)
        repo.upsert(entry("id-1", "src/customer.puml"))
        repo.save()

        val reloaded = DiagramMappingRepository(root)
        val found = reloaded.findBySource("src/customer.puml")
        assertEquals("id-1", found?.id)
        assertEquals("diagrams/id-1/x.apollon", found?.workingFile)
    }

    @Test
    fun `finds an entry by its working file path`() {
        val root = tmp.root.toPath()
        val repo = DiagramMappingRepository(root)
        repo.upsert(entry("id-1", "src/customer.puml"))
        assertEquals("id-1", repo.findByWorkingFile("diagrams/id-1/x.apollon")?.id)
        assertNull(repo.findByWorkingFile("diagrams/does-not-exist/x.apollon"))
    }

    @Test
    fun `upsert with an existing id replaces rather than duplicates`() {
        val root = tmp.root.toPath()
        val repo = DiagramMappingRepository(root)
        repo.upsert(entry("id-1", "src/a.puml"))
        repo.upsert(entry("id-1", "src/a.puml").copy(sourceHash = "sha256:changed"))
        assertEquals(1, repo.all().size)
        assertEquals("sha256:changed", repo.findById("id-1")?.sourceHash)
    }

    @Test
    fun `distinct entries can share the same file name in different directories`() {
        val root = tmp.root.toPath()
        val repo = DiagramMappingRepository(root)
        repo.upsert(entry("id-1", "src/a/customer.puml"))
        repo.upsert(entry("id-2", "src/b/customer.puml"))
        assertEquals(2, repo.all().size)
        assertEquals("id-1", repo.findBySource("src/a/customer.puml")?.id)
        assertEquals("id-2", repo.findBySource("src/b/customer.puml")?.id)
    }

    @Test
    fun `remove drops the entry`() {
        val root = tmp.root.toPath()
        val repo = DiagramMappingRepository(root)
        repo.upsert(entry("id-1", "src/a.puml"))
        repo.remove("id-1")
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `render output is sorted by source for deterministic diffs`() {
        val index =
            DiagramIndex(
                DiagramIndexCodec.CURRENT_VERSION,
                listOf(entry("id-2", "src/z.puml"), entry("id-1", "src/a.puml")),
            )
        val text = DiagramIndexCodec.render(index)
        assertTrue(text.indexOf("src/a.puml") < text.indexOf("src/z.puml"))
    }

    @Test
    fun `unknown keys on a diagram entry survive a parse-then-render round trip`() {
        val text =
            """
            {
              "version": 1,
              "diagrams": [
                {
                  "id": "id-1",
                  "format": "puml",
                  "source": "src/a.puml",
                  "workingFile": "diagrams/id-1/x.apollon",
                  "residualFile": "diagrams/id-1/source.residual.json",
                  "sourceHash": "sha256:abc",
                  "sourceSyncedAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z",
                  "futureField": "kept"
                }
              ]
            }
            """.trimIndent()
        val index = DiagramIndexCodec.parse(text)
        val rendered = DiagramIndexCodec.render(index)
        assertTrue(rendered.contains("futureField"))
        assertTrue(rendered.contains("kept"))
    }

    @Test(expected = UnsupportedIndexVersionException::class)
    fun `an index from a newer schema version is refused, not silently reset`() {
        val root = tmp.root.toPath()
        Files.createDirectories(root)
        Files.writeString(root.resolve("index.json"), """{"version": 999, "diagrams": []}""")
        DiagramMappingRepository(root)
    }

    @Test
    fun `corrupt json is backed up and the repository starts empty`() {
        val root = tmp.root.toPath()
        Files.createDirectories(root)
        Files.writeString(root.resolve("index.json"), "not json at all")
        val repo = DiagramMappingRepository(root)
        assertTrue(repo.all().isEmpty())
        assertTrue(Files.exists(root.resolve("index.json.bak")))
        assertEquals("not json at all", Files.readString(root.resolve("index.json.bak")))
    }
}
