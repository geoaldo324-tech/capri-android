package io.github.emmanuelquintana.capri

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * (1.14.0, redesigned — Aldo: «no me gusta este diseño, hazlo otra vez pero sin perder la
 * estética») Her mind as a constellation, not a grid: every bright node IS one of her real
 * memories (memory.json's facts, the same list her memory manager shows — one data, two
 * views), placed organically by a hash of its own text, so a memory keeps its place while
 * it lives. Memories that share a word are tied with a curved wire. Node size follows the
 * order of her notes (memory.js keeps the most important first; there are no per-memory
 * dates, so nothing here pretends to know which is newest). If she has few memories, the
 * drawing is completed with small dim nodes for your conversations (ChatLog's count):
 * those are secondary — they are not memories and can't be tapped or edited.
 *
 * Tap a memory node and MainActivity shows the memory itself (read it, edit it, forget it)
 * through CapriSettings — the same road everything else about her memory takes. When she
 * learns something (her chats, «Enseñarle un texto») MainActivity's refresh hands the new
 * list here and the new node simply appears.
 *
 * Plain Canvas, her colours (Hud), no libraries; positions and wires are worked out when
 * the data or the size changes, never per frame.
 */
class MindView(context: Context) : View(context) {

    /** A memory node was tapped: its index in the facts list given to setData. */
    var onTapFact: ((Int) -> Unit)? = null

    private class Node(val fact: Int, val fx: Float, val fy: Float, val r: Float, val seed: Float)

    private var nodes: List<Node> = emptyList()      // fact nodes first (fact >= 0), then conversation sparks (fact = -1)
    private var wires: List<Triple<Int, Int, Float>> = emptyList() // node indexes + the curve's bend
    private var selected = -1                        // node index, or -1
    private var phase = 0f

    private val tick = object : Runnable {
        override fun run() {
            if (isShown) {
                phase += 0.055f
                invalidate()
                postDelayed(this, 50)
            } else postDelayed(this, 400) // off screen: check again later, draw nothing
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /**
     * Her memories and how many conversations complete the drawing. Called by MainActivity
     * on every refresh, with CapriSettings.facts() — the real list, never a copy of it.
     */
    fun setData(facts: List<String>, chats: Int) {
        val shown = facts.take(MAX_FACTS)
        val list = ArrayList<Node>()
        for ((i, f) in shown.withIndex()) {
            // a memory's place comes from its own words: stable while the memory lives
            val h = f.hashCode()
            val angle = (h and 0xFFFF) / 65535f * (Math.PI * 2).toFloat()
            val dist = 0.16f + 0.78f * (((h ushr 16) and 0xFF) / 255f)
            // her first notes are the ones that matter most (memory.js keeps them first): bigger
            val r = (7.5f - 3.5f * (i.toFloat() / maxOf(1, shown.size))) * d
            list += Node(i, 0.5f + dist * cos(angle) * 0.94f, 0.5f + dist * sin(angle) * 0.88f, r, (h % 628) / 100f)
        }
        // conversations complete the constellation: small, dim, not memories (fact = -1).
        // Their count is the real conversation count (capped): never invented for looks.
        val sparks = minOf(chats, MAX_SPARKS)
        for (i in 0 until sparks) {
            val angle = i * 2.39996f + 0.7f // the golden angle: an even, organic scatter
            val dist = 0.2f + 0.72f * sqrt((i + 0.5f) / MAX_SPARKS)
            list += Node(-1, 0.5f + dist * cos(angle) * 0.94f, 0.5f + dist * sin(angle) * 0.88f, 2.4f * d, i * 1.37f)
        }
        // wires between memories that share a word (4+ letters, not filler): related thoughts
        val words = shown.map { wordSet(it) }
        val found = ArrayList<Triple<Int, Int, Float>>()
        val degree = IntArray(shown.size)
        for (a in shown.indices) for (b in a + 1 until shown.size) {
            if (degree[a] >= 3 || degree[b] >= 3) continue
            if (words[a].intersect(words[b]).isNotEmpty()) {
                found += Triple(a, b, ((a * 31 + b * 17) % 40 - 20) / 100f)
                degree[a]++; degree[b]++
                if (found.size >= 70) break
            }
        }
        nodes = list
        wires = found
        if (selected >= list.size) selected = -1
        invalidate()
    }

    /** The words a memory can be tied by: its own, minus the little filler ones. */
    private fun wordSet(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 && it !in FILLER }.toSet()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) return super.onTouchEvent(event)
        var best = -1
        var bestD = 30f * d // a finger's reach
        for ((i, n) in nodes.withIndex()) {
            if (n.fact < 0) continue // conversation sparks aren't memories: nothing to open
            val dx = event.x - n.fx * w
            val dy = event.y - n.fy * h
            val dd = sqrt(dx * dx + dy * dy)
            if (dd < bestD) { bestD = dd; best = i }
        }
        selected = best
        invalidate()
        if (best >= 0) {
            performClick()
            onTapFact?.invoke(nodes[best].fact)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private val d = resources.displayMetrics.density
    private val wire = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Hud.CYAN; strokeWidth = 1.1f * d }
    private val wireHot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Hud.YELLOW; strokeWidth = 1.4f * d }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hud.PAPER_2 }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hud.INK }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Hud.YELLOW; strokeWidth = 1.6f * d }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f || nodes.isEmpty()) return
        // the curved wires first, under the nodes; the selected memory's wires light up
        for ((a, b, bend) in wires) {
            val na = nodes[a]; val nb = nodes[b]
            val ax = na.fx * w; val ay = na.fy * h; val bx = nb.fx * w; val by = nb.fy * h
            val mx = (ax + bx) / 2; val my = (ay + by) / 2
            val dx = bx - ax; val dy = by - ay
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            path.reset()
            path.moveTo(ax, ay)
            path.quadTo(mx - dy / len * len * bend, my + dx / len * len * bend, bx, by)
            val hot = a == selected || b == selected
            wire.alpha = if (hot) 190 else 46
            canvas.drawPath(path, if (hot) wireHot else wire)
        }
        // the nodes: memories breathe in green and cyan; conversation sparks stay small and dim
        for ((j, n) in nodes.withIndex()) {
            val x = n.fx * w; val y = n.fy * h
            if (n.fact < 0) {
                sparkPaint.alpha = 40 + (30 * (0.5f + 0.5f * sin(phase * 0.7f + n.seed))).toInt()
                canvas.drawCircle(x, y, n.r, sparkPaint)
                continue
            }
            val pulse = 0.5f + 0.5f * sin(phase + n.seed).toFloat()
            glow.color = if (n.fact % 2 == 0) Hud.GREEN else Hud.CYAN
            glow.alpha = (80 + 110 * pulse).toInt()
            canvas.drawCircle(x, y, n.r + (1.6f + 1.8f * pulse) * d, glow)
            canvas.drawCircle(x, y, n.r * 0.45f, core)
            if (j == selected) canvas.drawCircle(x, y, n.r + 5f * d + 1.5f * pulse * d, ring)
        }
    }

    companion object {
        private const val MAX_FACTS = 40   // the most memories drawn (her list is capped at 60)
        private const val MAX_SPARKS = 22  // conversation sparks completing the drawing, at most
        private val FILLER = setOf(
            "para", "como", "pero", "esto", "esta", "este", "cuando", "donde", "sobre", "tiene", "mucho",
            "with", "that", "this", "from", "they", "them", "then", "than", "what", "when", "your", "yours",
            "para", "entre", "también", "tambien", "porque", "aunque",
        )
    }
}
