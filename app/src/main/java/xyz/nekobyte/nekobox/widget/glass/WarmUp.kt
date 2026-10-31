package xyz.nekobyte.nekobox.widget.glass

import android.content.Context
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.util.TypedValue
import androidx.annotation.RequiresApi
import xyz.nekobyte.nekobox.ktx.Logs

/**
 * Loads the GPU programs a first frame needs on the render thread while the activity is still being built, so
 * that frame does not stop to load them one by one: fills, circles, rounded cards and their outlines, text, and
 * under liquid glass the frost's blur and the glass shader. Draws them once into a small offscreen buffer.
 */
object WarmUp {
    private const val SIZE = 96

    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            draw(context)
        } catch (e: RuntimeException) {
            Logs.w("GPU warm-up skipped", e)
        }
    }

    @RequiresApi(31)
    private fun draw(context: Context) {
        val reader = ImageReader.newInstance(SIZE, SIZE, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val renderer = HardwareRenderer()
        renderer.setSurface(reader.surface)
        val scene = RenderNode("warm up scene").apply { setPosition(0, 0, SIZE, SIZE) }
        val canvas = scene.beginRecording()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY }
        canvas.drawColor(Color.BLACK)
        canvas.drawRect(4f, 4f, 36f, 36f, paint)
        canvas.drawCircle(64f, 20f, 14f, paint)
        canvas.drawRoundRect(4f, 44f, 92f, 92f, 16f, 16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.resources.displayMetrics.density
        canvas.drawRoundRect(8f, 48f, 88f, 88f, 12f, 12f, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics)
        canvas.drawText("Aa 01", 10f, 72f, paint)
        scene.endRecording()
        val root = RenderNode("warm up").apply { setPosition(0, 0, SIZE, SIZE) }
        val frame = root.beginRecording()
        frame.drawRenderNode(scene)
        if (Glass.enabled && Build.VERSION.SDK_INT >= 33) {
            val (blur, glass) = liveGlassEffects(context.resources)
            val frost = RenderNode("warm up frost").apply {
                setPosition(0, 0, SIZE / 4, SIZE / 4)
                setRenderEffect(blur)
                val copy = beginRecording()
                copy.scale(0.25f, 0.25f)
                copy.drawRenderNode(scene)
                endRecording()
            }
            val pane = RenderNode("warm up glass").apply {
                setPosition(0, 0, SIZE, SIZE)
                setRenderEffect(glass)
                val upscaled = beginRecording()
                upscaled.scale(4f, 4f)
                upscaled.drawRenderNode(frost)
                endRecording()
            }
            frame.drawRenderNode(pane)
        }
        root.endRecording()
        renderer.setContentRoot(root)
        renderer.createRenderRequest().setFrameCommitCallback(context.mainExecutor) {
            renderer.destroy()
            reader.close()
        }.syncAndDraw()
    }
}
