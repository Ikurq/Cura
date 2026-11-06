package com.example.voicevox

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * ボトムナビゲーションのバーと選択中インジケーター（バルジ）を
 * 単一の連続したベクターパスとして一体描画するカスタムビュー。
 *
 * 円（バルジ）とバーが分離せず、外周全体が極細のストローク（0.8dp）で美しく包まれます。
 * 淡いカラーシャドウ・グロー光彩は排除し、ソリッドで高品位なUIを実現します。
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
        strokeWidth = 0.8f * resources.displayMetrics.density
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
        elevation = 0f
    }

    fun setThemeColors(bgColor: Int, strokeColor: Int, strokeWidthDp: Float = 0.8f) {
        fillPaint.color = bgColor
        strokePaint.color = strokeColor
        strokePaint.strokeWidth = strokeWidthDp * density
        invalidate()
    }

    fun setBulgePosition(targetCenterX: Float, animated: Boolean = true) {
        setBulgeCenterX(targetCenterX, animated)
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

        // 1. バー本体の角丸長方形パス（4隅の角丸を厳密に定義）
        val barPath = Path().apply {
            val radii = floatArrayOf(
                topCorner, topCorner,       // 左上
                topCorner, topCorner,       // 右上
                bottomCorner, bottomCorner, // 右下
                bottomCorner, bottomCorner  // 左下
            )
            addRoundRect(RectF(0f, barTop, w, barBottom), radii, Path.Direction.CW)
        }

        path.reset()
        if (bulgeCenterX > 0f) {
            // 2. 突起サークルのパス（中心: bulgeCenterX, barTop）
            val circlePath = Path().apply {
                addCircle(bulgeCenterX, barTop, r, Path.Direction.CW)
            }

            // 3. ブーリアン結合（UNION）
            // 端（HOMEやATTEND）で円が角丸と重なっても、Skia幾何学エンジンが正確な外周のみを自動抽出。
            // 内側の境界線は完全消滅し、外周全体が1本のシームレスなパスになります。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                path.op(barPath, circlePath, Path.Op.UNION)
            } else {
                path.addPath(barPath)
                path.addPath(circlePath)
            }
        } else {
            path.addPath(barPath)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (path.isEmpty) {
            updatePath()
        }
        // 一体化した塗りつぶし（バーと円の間に線は一切出ない）
        canvas.drawPath(path, fillPaint)
        // 外周全体を同一カラーで縁取り（極細0.8dpのシャープなストローク）
        canvas.drawPath(path, strokePaint)
    }
}
