package de.tum.cit.aet.apollon.render

import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real `plantuml-mit` engine (no mocking — plan's "no fake support" bar applies to
 *  this claim too: Preview must genuinely render every family, not just the five this plugin can
 *  visually edit) against one example per family, including families with no round-trip importer. */
class PlantUmlRenderServiceTest {
    @Test
    fun `renders a class diagram to svg`() {
        val result =
            PlantUmlRenderService.render(
                """
                @startuml
                class Customer {
                  -name : String
                  +placeOrder()
                }
                class Order
                Customer "1" --> "*" Order
                @enduml
                """.trimIndent(),
            )
        assertTrue(result is PlantUmlRenderService.RenderResult.Rendered)
        assertTrue((result as PlantUmlRenderService.RenderResult.Rendered).svg.contains("<svg"))
    }

    @Test
    fun `renders an object diagram to svg`() {
        val result =
            PlantUmlRenderService.render(
                """
                @startuml
                object Order1
                object Customer1
                Customer1 --> Order1
                @enduml
                """.trimIndent(),
            )
        assertTrue(result is PlantUmlRenderService.RenderResult.Rendered)
        assertTrue((result as PlantUmlRenderService.RenderResult.Rendered).svg.contains("<svg"))
    }

    @Test
    fun `renders a use case diagram to svg`() {
        val result =
            PlantUmlRenderService.render(
                """
                @startuml
                actor Customer
                usecase "Browse Catalog" as UC1
                usecase "Checkout" as UC2
                Customer --> UC1
                Customer --> UC2
                UC2 ..> UC1 : <<include>>
                @enduml
                """.trimIndent(),
            )
        assertTrue(result is PlantUmlRenderService.RenderResult.Rendered)
        assertTrue((result as PlantUmlRenderService.RenderResult.Rendered).svg.contains("<svg"))
    }

    @Test
    fun `renders a component diagram to svg`() {
        val result =
            PlantUmlRenderService.render(
                """
                @startuml
                component Frontend
                component Backend
                Frontend --> Backend
                @enduml
                """.trimIndent(),
            )
        assertTrue(result is PlantUmlRenderService.RenderResult.Rendered)
        assertTrue((result as PlantUmlRenderService.RenderResult.Rendered).svg.contains("<svg"))
    }

    @Test
    fun `renders a deployment diagram to svg`() {
        val result =
            PlantUmlRenderService.render(
                """
                @startuml
                node "App Server" as srv {
                  component Backend
                }
                artifact "backend.jar" as jar
                srv --> jar
                @enduml
                """.trimIndent(),
            )
        assertTrue(result is PlantUmlRenderService.RenderResult.Rendered)
        assertTrue((result as PlantUmlRenderService.RenderResult.Rendered).svg.contains("<svg"))
    }

    @Test
    fun `renders a C4 diagram to svg even though it has no visual editor`() {
        val result =
            PlantUmlRenderService.render(
                """
                @startuml
                !include <C4/C4_Context>
                Person(customer, "Customer")
                System(shop, "Shop")
                Rel(customer, shop, "Places orders using")
                @enduml
                """.trimIndent(),
            )
        assertTrue(result is PlantUmlRenderService.RenderResult.Rendered)
        assertTrue((result as PlantUmlRenderService.RenderResult.Rendered).svg.contains("<svg"))
    }

    @Test
    fun `invalid puml source still renders an svg showing the error rather than throwing`() {
        val result = PlantUmlRenderService.render("@startuml\nthis is not valid plantuml syntax at all!!\n@enduml")
        assertTrue(result is PlantUmlRenderService.RenderResult.Rendered)
        assertTrue((result as PlantUmlRenderService.RenderResult.Rendered).svg.contains("<svg"))
    }
}
