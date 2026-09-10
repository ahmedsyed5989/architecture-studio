package de.tum.cit.aet.apollon.puml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantUmlImporterTest {
    @Test
    fun `rejects text with no @startuml block`() {
        val result = PlantUmlImporter.parse("class Foo")
        assertTrue(result is PumlParseResult.Rejected)
    }

    @Test
    fun `rejects a non-class diagram`() {
        val text =
            """
            @startuml
            actor User
            User -> System : request
            @enduml
            """.trimIndent()
        assertTrue(PlantUmlImporter.parse(text) is PumlParseResult.Rejected)
    }

    @Test
    fun `parses the customer-order example end to end`() {
        val text =
            """
            @startuml
            class Customer {
              -name : String
              +placeOrder()
            }
            class Order
            Customer "1" --> "*" Order
            @enduml
            """.trimIndent()
        val result = PlantUmlImporter.parse(text) as PumlParseResult.Parsed

        assertEquals(2, result.diagram.types.size)
        val customer = result.diagram.types.single { it.name == "Customer" }
        assertEquals(PumlKind.CLASS, customer.kind)
        assertEquals(1, customer.attributes.size)
        assertEquals(1, customer.methods.size)
        assertEquals("- name: String", customer.attributes[0].apollonName)
        assertEquals("+ placeOrder()", customer.methods[0].apollonName)

        assertEquals(1, result.diagram.relations.size)
        val relation = result.diagram.relations.single()
        assertEquals("Customer", relation.sourceName)
        assertEquals("Order", relation.targetName)
        assertEquals(PumlRelationKind.UNIDIRECTIONAL, relation.kind)
        assertEquals("1", relation.sourceMultiplicity)
        assertEquals("*", relation.targetMultiplicity)
        assertEquals(0, result.unsupportedCount)
    }

    @Test
    fun `interface, enum and abstract class keywords map to the right kinds`() {
        val text =
            """
            @startuml
            interface Shape
            enum Color
            abstract class Animal
            abstract Vehicle
            @enduml
            """.trimIndent()
        val result = PlantUmlImporter.parse(text) as PumlParseResult.Parsed
        val kindByName = result.diagram.types.associate { it.name to it.kind }
        assertEquals(PumlKind.INTERFACE, kindByName["Shape"])
        assertEquals(PumlKind.ENUM, kindByName["Color"])
        assertEquals(PumlKind.ABSTRACT_CLASS, kindByName["Animal"])
        assertEquals(PumlKind.ABSTRACT_CLASS, kindByName["Vehicle"])
    }

    @Test
    fun `every relation arrow token maps to its expected kind`() {
        val cases =
            mapOf(
                "<|--" to PumlRelationKind.INHERITANCE,
                "--|>" to PumlRelationKind.INHERITANCE,
                "<|.." to PumlRelationKind.REALIZATION,
                "..|>" to PumlRelationKind.REALIZATION,
                "*--" to PumlRelationKind.COMPOSITION,
                "--*" to PumlRelationKind.COMPOSITION,
                "o--" to PumlRelationKind.AGGREGATION,
                "--o" to PumlRelationKind.AGGREGATION,
                "-->" to PumlRelationKind.UNIDIRECTIONAL,
                "<--" to PumlRelationKind.UNIDIRECTIONAL,
                "--" to PumlRelationKind.BIDIRECTIONAL,
                "..>" to PumlRelationKind.DEPENDENCY,
                "<.." to PumlRelationKind.DEPENDENCY,
                ".." to PumlRelationKind.DEPENDENCY,
            )
        for ((token, expectedKind) in cases) {
            val text =
                """
                @startuml
                class A
                class B
                A $token B
                @enduml
                """.trimIndent()
            val result = PlantUmlImporter.parse(text) as PumlParseResult.Parsed
            assertEquals("token $token", expectedKind, result.diagram.relations.single().kind)
        }
    }

    @Test
    fun `a relation label after the colon is preserved`() {
        val text =
            """
            @startuml
            class A
            class B
            A --> B : uses
            @enduml
            """.trimIndent()
        val result = PlantUmlImporter.parse(text) as PumlParseResult.Parsed
        assertEquals("uses", result.diagram.relations.single().label)
    }

    @Test
    fun `aliased or stereotyped type declarations are preserved as unsupported, not misread`() {
        val text =
            """
            @startuml
            class Foo as F
            class Bar <<entity>>
            @enduml
            """.trimIndent()
        val result = PlantUmlImporter.parse(text) as PumlParseResult.Parsed
        assertTrue(result.diagram.types.isEmpty())
        assertEquals(2, result.unsupportedCount)
        assertTrue(result.residual.unsupported.any { it.contains("Foo as F") })
        assertTrue(result.residual.unsupported.any { it.contains("Bar <<entity>>") })
    }

    @Test
    fun `a note block is preserved verbatim between its markers`() {
        val text =
            """
            @startuml
            class A
            note left of A
              some text
            end note
            @enduml
            """.trimIndent()
        val result = PlantUmlImporter.parse(text) as PumlParseResult.Parsed
        assertTrue(result.residual.unsupported.any { it.contains("note left of A") })
        assertTrue(result.residual.unsupported.any { it.contains("some text") })
        assertTrue(result.residual.unsupported.any { it.contains("end note") })
    }

    @Test
    fun `skinparam and title lines before any diagram content are preamble`() {
        val text =
            """
            @startuml
            skinparam classAttributeIconSize 0
            title My Diagram
            class A
            @enduml
            """.trimIndent()
        val result = PlantUmlImporter.parse(text) as PumlParseResult.Parsed
        assertTrue(result.residual.preamble.any { it.contains("skinparam") })
        assertTrue(result.residual.preamble.any { it.contains("title My Diagram") })
        assertTrue(result.residual.unsupported.isEmpty())
    }

    @Test
    fun `tier-A lines trailing after the last mapped construct become postamble, not unsupported`() {
        val text =
            """
            @startuml
            class A
            skinparam shadowing false
            @enduml
            """.trimIndent()
        val result = PlantUmlImporter.parse(text) as PumlParseResult.Parsed
        assertTrue(result.residual.postamble.any { it.contains("skinparam shadowing false") })
        assertTrue(result.residual.unsupported.isEmpty())
    }

    @Test
    fun `a tier-A run that turns out to be mid-body is demoted to unsupported`() {
        val text =
            """
            @startuml
            class A
            title not actually trailing
            class B
            @enduml
            """.trimIndent()
        val result = PlantUmlImporter.parse(text) as PumlParseResult.Parsed
        assertEquals(2, result.diagram.types.size)
        assertTrue(result.residual.postamble.isEmpty())
        assertTrue(result.residual.unsupported.any { it.contains("title not actually trailing") })
    }

    @Test
    fun `crlf documents are detected and preserved in eol`() {
        val text = "@startuml\r\nclass A\r\n@enduml\r\n"
        val result = PlantUmlImporter.parse(text) as PumlParseResult.Parsed
        assertEquals("\r\n", result.residual.eol)
    }
}
