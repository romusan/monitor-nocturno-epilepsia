package co.edu.ecci.monitornocturno

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class AccelerationChartView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val x = ArrayDeque<Float>()
    private val y = ArrayDeque<Float>()
    private val z = ArrayDeque<Float>()
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(215, 222, 228); strokeWidth = 1f }
    private val px = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 55, 65); strokeWidth = 3f; style = Paint.Style.STROKE }
    private val py = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 150, 80); strokeWidth = 3f; style = Paint.Style.STROKE }
    private val pz = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 100, 210); strokeWidth = 3f; style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 28f }

    fun addSample(ax: Float, ay: Float, az: Float) {
        add(x, ax); add(y, ay); add(z, az); invalidate()
    }

    private fun add(queue: ArrayDeque<Float>, value: Float) {
        queue.addLast(value); while (queue.size > 120) queue.removeFirst()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(248, 250, 252))
        for (i in 0..4) {
            val yy = height * i / 4f
            canvas.drawLine(0f, yy, width.toFloat(), yy, grid)
        }
        if (x.size < 2) {
            canvas.drawText("Inicie la sesion para ver la senal", 24f, height / 2f, label)
            return
        }
        val all = x + y + z
        val limit = max(12f, all.maxOf { kotlin.math.abs(it) } * 1.1f)
        drawLine(canvas, x, limit, px); drawLine(canvas, y, limit, py); drawLine(canvas, z, limit, pz)
    }

    private fun drawLine(canvas: Canvas, values: ArrayDeque<Float>, limit: Float, paint: Paint) {
        val path = Path(); val list = values.toList()
        list.forEachIndexed { index, value ->
            val xx = index.toFloat() / 119f * width
            val yy = height / 2f - value / limit * height / 2.2f
            if (index == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
        }
        canvas.drawPath(path, paint)
    }
}
