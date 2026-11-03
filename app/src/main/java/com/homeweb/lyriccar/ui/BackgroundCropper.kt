package com.homeweb.lyriccar.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.homeweb.lyriccar.data.Diagnostics
import com.homeweb.lyriccar.data.carlyricDirectory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 背景图片的读取 / 保存。
 *
 * 从系统选择器拿到的是 Uri，这里先落到缓存文件（顺便按 EXIF 转正），
 * 解码时限制到 2048px 以内；用户框好区域后按屏幕大小压成 JPEG 存进
 * 应用私有目录，从此不再需要读外部文件。
 */
object BackgroundImages {

    const val FILE_NAME = com.homeweb.lyriccar.LyricCarApp.BACKGROUND_IMAGE_NAME

    /** 目标画面宽高比 = 屏幕宽高比。 */
    fun screenAspect(activity: Activity): Float {
        val metrics = activity.resources.displayMetrics
        if (metrics.heightPixels <= 0) return 16f / 9f
        return metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
    }

    fun load(context: Context, uri: Uri): Bitmap? {
        val temp = File(context.cacheDir, "background_src")
        val source = openSource(context, uri) ?: run {
            temp.delete()
            return null
        }
        try {
            source.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (error: Exception) {
            Diagnostics.log("[背景] 读取图片失败：$error")
            temp.delete()
            return null
        }
        val orientation = try {
            ExifInterface(temp.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(temp.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            com.homeweb.lyriccar.data.Diagnostics.log("[背景] 图片尺寸读不出来：${bounds.outWidth}x${bounds.outHeight}")
            temp.delete()
            return null
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val decoded = try {
            BitmapFactory.decodeFile(
                temp.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (error: Exception) {
            com.homeweb.lyriccar.data.Diagnostics.log("[背景] 解码失败：$error")
            null
        } finally {
            temp.delete()
        }
        return decoded?.let { rotate(it, orientation) }
    }

    /**
     * 打开选中的图片。
     *
     * 正常的系统选择器会把读权限一起授过来；但部分车机自带的文件管理器（实测 BYD）
     * 返回的是没授权的 content:// URI，会抛 SecurityException。这时退回按 raw 路径读文件
     * —— 只在旧系统（有存储权限）或自己应用目录下能奏效，失败就返回 null 让界面提示。
     */
    private fun openSource(context: Context, uri: Uri): InputStream? {
        try {
            context.contentResolver.openInputStream(uri)?.let { return it }
        } catch (error: Exception) {
            Diagnostics.log("[背景] 选择器 URI 打不开：$error")
        }
        val segment = try {
            uri.lastPathSegment
        } catch (_: Exception) {
            null
        } ?: return null
        val path = if (segment.startsWith("raw:")) segment.removePrefix("raw:") else segment
        if (!path.startsWith("/")) return null
        return try {
            Diagnostics.log("[背景] 改从文件路径读取：$path")
            File(path).inputStream()
        } catch (error: Exception) {
            Diagnostics.log("[背景] 文件路径也读不了：$error")
            null
        }
    }

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (_: Exception) {
            bitmap
        }
    }

    /** 已经保存好的背景图。 */
    fun loadSaved(context: Context): Bitmap? {
        val file = File(carlyricDirectory(context), FILE_NAME)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    /** 按屏幕宽度存成 JPEG；失败返回 false。 */
    fun save(context: Context, bitmap: Bitmap, targetWidth: Int): Boolean {
        val width = min(bitmap.width, max(640, targetWidth))
        val height = max(1, (width.toFloat() * bitmap.height / bitmap.width).toInt())
        val scaled = if (width != bitmap.width) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        return try {
            FileOutputStream(File(carlyricDirectory(context), FILE_NAME)).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun targetWidth(activity: Activity): Int =
        activity.resources.displayMetrics.widthPixels.coerceIn(640, 1920)
}

/**
 * 屏幕比例的遮罩：把整张图片按 contain 铺开，中间放一个屏幕宽高比的框，
 * 手指拖框选择要用哪一块；框不能拖出图片。
 */
class BackgroundCropView(context: Context, private val bitmap: Bitmap) : View(context) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xA6000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpF(2)
        color = Palette.ACCENT
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }

    private val imageRect = RectF()
    private val maskRect = RectF()
    private var aspect = 16f / 9f

    private var downX = 0f
    private var downY = 0f
    private var startLeft = 0f
    private var startTop = 0f

    init {
        isClickable = true
        contentDescription = "拖动选框，选择用作背景的区域"
    }

    fun setTargetAspect(value: Float) {
        if (value <= 0f) return
        aspect = value
        if (width > 0 && height > 0) layoutImage()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutImage()
    }

    private fun layoutImage() {
        if (width == 0 || height == 0 || bitmap.width == 0 || bitmap.height == 0) return
        val scale = min(
            width.toFloat() / bitmap.width,
            height.toFloat() / bitmap.height,
        )
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        imageRect.set(
            (width - imageWidth) / 2f,
            (height - imageHeight) / 2f,
            (width + imageWidth) / 2f,
            (height + imageHeight) / 2f,
        )
        var maskWidth = imageRect.width()
        var maskHeight = maskWidth / aspect
        if (maskHeight > imageRect.height()) {
            maskHeight = imageRect.height()
            maskWidth = maskHeight * aspect
        }
        maskRect.set(
            imageRect.centerX() - maskWidth / 2f,
            imageRect.centerY() - maskHeight / 2f,
            imageRect.centerX() + maskWidth / 2f,
            imageRect.centerY() + maskHeight / 2f,
        )
        clampMask()
    }

    private fun clampMask() {
        val minLeft = imageRect.left
        val maxLeft = max(minLeft, imageRect.right - maskRect.width())
        val minTop = imageRect.top
        val maxTop = max(minTop, imageRect.bottom - maskRect.height())
        maskRect.offsetTo(
            maskRect.left.coerceIn(minLeft, maxLeft),
            maskRect.top.coerceIn(minTop, maxTop),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint)

        // 选框外面压暗，只留中间亮着
        canvas.drawRect(0f, 0f, width.toFloat(), maskRect.top, dimPaint)
        canvas.drawRect(0f, maskRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, maskRect.top, maskRect.left, maskRect.bottom, dimPaint)
        canvas.drawRect(maskRect.right, maskRect.top, width.toFloat(), maskRect.bottom, dimPaint)

        canvas.drawRect(maskRect, borderPaint)
        val handle = dpF(9)
        for (x in listOf(maskRect.left, maskRect.right)) {
            for (y in listOf(maskRect.top, maskRect.bottom)) {
                canvas.drawCircle(x, y, handle, handlePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                startLeft = maskRect.left
                startTop = maskRect.top
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                maskRect.offsetTo(startLeft + (event.x - downX), startTop + (event.y - downY))
                clampMask()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 把选框里的区域从原图裁出来；没准备好返回 null。 */
    fun crop(): Bitmap? {
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return null
        val ratioX = (maskRect.left - imageRect.left) / imageRect.width()
        val ratioY = (maskRect.top - imageRect.top) / imageRect.height()
        val ratioW = maskRect.width() / imageRect.width()
        val ratioH = maskRect.height() / imageRect.height()
        val x = (ratioX * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (ratioY * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val w = (ratioW * bitmap.width).toInt().coerceIn(1, bitmap.width - x)
        val h = (ratioH * bitmap.height).toInt().coerceIn(1, bitmap.height - y)
        return try {
            Bitmap.createBitmap(bitmap, x, y, w, h)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 全屏的裁剪对话框：中间是 [BackgroundCropView]，底部「取消 / 使用这张」。
 * 原图由对话框负责回收，裁剪出来的位图交给 onConfirm 的调用方回收。
 */
class BackgroundCropperDialog(
    private val activity: Activity,
    private val bitmap: Bitmap,
    private val onConfirm: (Bitmap) -> Unit,
) {

    private val dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar)
    private val cropView = BackgroundCropView(activity, bitmap)

    fun show() {
        val root = FrameLayout(activity).apply {
            setBackgroundColor(0xFF000000.toInt())
        }
        root.addView(
            cropView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        cropView.setTargetAspect(BackgroundImages.screenAspect(activity))

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingDp(24, 16, 24, 12)
        }
        header.addView(
            textView(activity, "拖动选框，选择用作背景的区域", 16, 0xFFFFFFFF.toInt(), bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(textButton(activity, "✕") { dialog.dismiss() })
        root.addView(
            header,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPaddingDp(24, 12, 24, 20)
        }
        actions.addView(textButton(activity, "取消") { dialog.dismiss() })
        actions.addView(space(activity, 0).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(12), 1)
        })
        actions.addView(filledButton(activity, "使用这张") {
            val cropped = cropView.crop()
            if (cropped == null) {
                toast(activity, "裁剪失败，换一张试试")
                dialog.dismiss()
            } else {
                onConfirm(cropped)
                dialog.dismiss()
            }
        })
        root.addView(
            actions,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            actions.setPadding(
                activity.dp(24),
                activity.dp(12),
                activity.dp(24),
                activity.dp(20) + bottom,
            )
            insets
        }

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
    }
}
