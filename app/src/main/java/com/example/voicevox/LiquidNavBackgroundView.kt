package com.example.voicevox

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator

/**
 * ボトムナビゲーションのバーと選択中インジケーター（バルジ）を
 * 単一の連続したベクターパスとして一体描画するカスタムビュー。
 *
 * 円（バルジ）とバーが分離せず、外周全体がひとつの美しいストローク（縁取り）で包まれます。
 */
class LiquidNavBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val path = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#38263E")
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.parseColor("#D6B66E")
    }

    private var bulgeCenterX: Float = -1f
    private var animator: ValueAnimator? = null

    val density: Float get() = resources.displayMetrics.density
    var bulgeRadiusDp: Float = 35f
    var barHeightDp: Float = 80f
    var cornerRadiusDp: Float = 24f

    init {
        setWillNotDraw(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            if (!path.isEmpty && path.isConvex) {
                                outline.setConvexPath(path)
                                return
                            }
                        } catch (e: Exception) {
                            // Fallback to rect
                        }
                    }
                    val barTop = (height - barHeightDp * density).toInt().coerceAtLeast(0)
                    outline.setRoundRect(0, barTop, width, height, cornerRadiusDp * density)
                }
            }
        }
    }

    fun setThemeColors(bgColor: Int, strokeColor: Int) {
        fillPaint.color = bgColor
        strokePaint.color = strokeColor
        strokePaint.strokeWidth = 1.5f * density

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineSpotShadowColor = strokeColor
            outlineAmbientShadowColor = strokeColor
        }
        invalidate()
    }

    fun setBulgeCenterX(targetCenterX: Float, animated: Boolean = true) {
        if (targetCenterX <= 0f) return

        if (!animated || bulgeCenterX < 0f) {
            animator?.cancel()
            bulgeCenterX = targetCenterX
            updatePath()
            invalidate()
            return
        }

        if (kotlin.math.abs(bulgeCenterX - targetCenterX) < 1f) {
            return
        }

        animator?.cancel()
        animator = ValueAnimator.ofFloat(bulgeCenterX, targetCenterX).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                bulgeCenterX = va.animatedValue as Float
                updatePath()
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bulgeCenterX < 0f && w > 0) {
            // 初期位置: 5分割の1番目の中心（HOMEアイコン付近）
            bulgeCenterX = w / 10f
        }
        updatePath()
    }

    private fun updatePath() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val r = bulgeRadiusDp * density
        val barH = barHeightDp * density
        val topCorner = cornerRadiusDp * density
        val bottomCorner = 16f * density

        val barTop = h - barH
        val barBottom = h

        path.reset()

        // 左辺下部からスタート
        path.moveTo(0f, barTop + topCorner)

        // 1. 左上角丸
        val leftTopRect = RectF(0f, barTop, topCorner * 2, barTop + topCorner * 2)
        path.arcTo(leftTopRect, 180f, 90f, false)

        val cX = if (bulgeCenterX > 0f) bulgeCenterX else (w / 2f)
        val circleLeft = cX - r
        val circleRight = cX + r

        // 2. 左上角丸から円の左端まで直線
        if (circleLeft > topCorner) {
            path.lineTo(circleLeft, barTop)
        }

        // 3. 一体化突起（円弧）：バー上面から上へ飛び出す半円（内部の境界線なし）
        val oval = RectF(circleLeft, barTop - r, circleRight, barTop + r)
        path.arcTo(oval, 180f, 180f, false)

        // 4. 円の右端から右上角丸まで直線
        if (circleRight < w - topCorner) {
            path.lineTo(w - topCorner, barTop)
        } else {
            path.lineTo(w - topCorner, barTop)
        }

        // 5. 右上角丸
        val rightTopRect = RectF(w - topCorner * 2, barTop, w, barTop + topCorner * 2)
        path.arcTo(rightTopRect, 270f, 90f, false)

        // 6. 右下角丸
        val rightBottomRect = RectF(w - bottomCorner * 2, barBottom - bottomCorner * 2, w, barBottom)
        path.arcTo(rightBottomRect, 0f, 90f, false)

        // 7. 底面直線
        path.lineTo(bottomCorner, barBottom)

        // 8. 左下角丸
        val leftBottomRect = RectF(0f, barBottom - bottomCorner * 2, bottomCorner * 2, barBottom)
        path.arcTo(leftBottomRect, 90f, 90f, false)

        // 9. パスを閉じる
        path.close()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            invalidateOutline()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (path.isEmpty) {
            updatePath()
        }
        // 一体化した塗りつぶし（バーと円の間に線は一切出ない）
        canvas.drawPath(path, fillPaint)
        // 外周全体を同一カラーで縁取り（単一の一筆書きストローク）
        canvas.drawPath(path, strokePaint)
    }
}
