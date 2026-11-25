package io.github.emmanuelquintana.capri

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.sin

/**
 * (1.12.5) Her neurons, on the «Su mente» tab: a net of nodes in her own colours (Hud), drawn the
 * plain Canvas way. How many are lit is real, never made up: her memories (memory.json's facts)
 * plus your conversations with her (ChatLog), set by MainActivity each time it refreshes.
 * A soft pulse runs over the lit ones; the view only redraws while it's on screen.
 */
class MindView(context: Context) : View(context) {

    /** How many neurons are lit (her memories + your conversations, capped at the net's size). */
    var lit: Int = 0
        set(v) {
            field = v.coerceIn(0, TOTAL)
            invalidate()
        }

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

    private val d = resources.displayMetrics.density
    private val wire = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hud.LINE_STRONG; strokeWidth = 1.2f * d }
    private val wireLit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hud.CYAN; alpha = 70; strokeWidth = 1.2f * d }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hud.PAPER_2; alpha = 55 }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hud.INK }

    // her net: four layers of nodes; a node's place comes only from its number, so the net is the
    // same every time you look (no jumping around between draws)
    private val layers = intArrayOf(5, 9, 9, 5)
    private val total = layers.sum()
    private fun pos(layer: Int, i: Int, w: Float, h: Float): Pair<Float, Float> {
        val padX = 26f * d
        val padY = 20f * d
        val x = if (layers.size == 1) w / 2 else padX + layer * (w - 2 * padX) / (layers.size - 1)
        val n = layers[layer]
        val y = padY + (i + 0.5f) * (h - 2 * padY) / n + sin(i * 12.9898 + layer * 3.7).toFloat() * 5f * d
        return x to y
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        // each node's place and whether it's lit, in layer order
        val pts = ArrayList<Triple<Float, Float, Boolean>>(total)
        var idx = 0
        for (l in layers.indices) for (i in 0 until layers[l]) {
            val (x, y) = pos(l, i, w, h)
            pts.add(Triple(x, y, idx < lit))
            idx++
        }
        // the wires between one layer and the next (each node reaches two of the next layer)
        idx = 0
        for (l in 0 until layers.size - 1) {
            val next = layers[l + 1]
            for (i in 0 until layers[l]) {
                val a = pts[idx + i]
                for (k in 0..1) {
                    val b = pts[idx + layers[l] + (i * 2 + k) % next]
                    canvas.drawLine(a.first, a.second, b.first, b.second, if (a.third && b.third) wireLit else wire)
                }
            }
            idx += layers[l]
        }
        // the nodes: lit ones in her green and cyan, breathing a little; the rest quiet
        for ((j, p) in pts.withIndex()) {
            if (!p.third) {
                canvas.drawCircle(p.first, p.second, 3.2f * d, dim)
                continue
            }
            val pulse = 0.5f + 0.5f * sin(phase + j * 0.9).toFloat()
            glow.color = if (j % 2 == 0) Hud.GREEN else Hud.CYAN
            glow.alpha = (90 + 120 * pulse).toInt()
            canvas.drawCircle(p.first, p.second, (5.5f + 2.5f * pulse) * d, glow)
            canvas.drawCircle(p.first, p.second, 2f * d, core)
        }
    }

    companion object {
        const val TOTAL = 28 // 5 + 9 + 9 + 5
    }
}
