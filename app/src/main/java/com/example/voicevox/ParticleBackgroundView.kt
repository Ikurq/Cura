package com.example.voicevox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Random

class ParticleBackgroundView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val particles = mutableListOf<Particle>()
    private val paint = Paint().apply {
        isAntiAlias = true
    }
    private val random = Random()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        particles.clear()
        repeat(30) {
            particles.add(createParticle(w, h))
        }
    }

    private fun createParticle(w: Int, h: Int): Particle {
        return Particle(
            x = random.nextFloat() * w,
            y = random.nextFloat() * h,
            vx = (random.nextFloat() - 0.5f) * 1.5f,
            vy = (random.nextFloat() - 0.5f) * 1.5f,
            radius = random.nextFloat() * 6f + 2f,
            alpha = random.nextInt(100) + 50
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        particles.forEach { p ->
            paint.color = Color.parseColor("#06B6D4") // Cyber Cyan
            paint.alpha = p.alpha
            canvas.drawCircle(p.x, p.y, p.radius, paint)

            // Update position
            p.x += p.vx
            p.y += p.vy

            // Boundary check
            if (p.x < 0 || p.x > width) p.vx *= -1
            if (p.y < 0 || p.y > height) p.vy *= -1
        }

        invalidate() // Keep animating
    }

    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val radius: Float,
        val alpha: Int
    )
}
