package de.tum.cit.aet.apollon.puml

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

data class Size(val width: Int, val height: Int)

data class Point(val x: Int, val y: Int)

data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val centerX get() = x + width / 2
    val centerY get() = y + height / 2
}

private const val HEADER_HEIGHT = 40
private const val HEADER_HEIGHT_WITH_STEREOTYPE = 50
private const val ROW_HEIGHT = 30
private const val DEFAULT_WIDTH = 160 // DROPS.DEFAULT_ELEMENT_WIDTH (library/lib/constants.ts) — the canvas auto-grows from here.
private const val GRID_SNAP = 10
private const val COL_SPACING = 260
private const val ROW_SPACING = 220

/** Deterministic geometry for nodes/edges Architect Studio creates — no external layout engine
 *  (plan §D5), so the same [PumlDiagram] always lays out the same way. */
object PumlLayout {
    fun sizeOf(type: PumlType): Size {
        val header = if (type.kind == PumlKind.INTERFACE || type.kind == PumlKind.ENUM) HEADER_HEIGHT_WITH_STEREOTYPE else HEADER_HEIGHT
        val rows = type.attributes.size + type.methods.size
        val height = ceilToGrid(header + ROW_HEIGHT * rows)
        return Size(DEFAULT_WIDTH, height)
    }

    /** Same header+rows sizing as [sizeOf], for families with no interface/enum stereotype header
     *  variant (Object/Component/Deployment/UseCase — plan §9's new families). */
    fun sizeOfRows(
        rowCount: Int,
        header: Int = HEADER_HEIGHT,
    ): Size = Size(DEFAULT_WIDTH, ceilToGrid(header + ROW_HEIGHT * rowCount))

    private fun ceilToGrid(value: Int): Int = ceil(value / GRID_SNAP.toDouble()).toInt() * GRID_SNAP

    fun gridPositions(
        count: Int,
        originX: Int,
        originY: Int,
    ): List<Point> {
        if (count <= 0) return emptyList()
        val cols = ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
        return (0 until count).map { i ->
            Point(originX + (i % cols) * COL_SPACING, originY + (i / cols) * ROW_SPACING)
        }
    }

    /** Compares rectangle centers; ties go to the vertical pair. */
    fun chooseHandles(
        source: Rect,
        target: Rect,
    ): Pair<String, String> {
        val dx = target.centerX - source.centerX
        val dy = target.centerY - source.centerY
        return if (abs(dy) >= abs(dx)) {
            if (dy >= 0) "bottom" to "top" else "top" to "bottom"
        } else {
            if (dx >= 0) "right" to "left" else "left" to "right"
        }
    }
}
