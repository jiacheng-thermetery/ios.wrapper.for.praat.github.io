// PraatPicture.kt — Android renderer for Praat's recorded Graphics opcode stream.
// Part of the Spraak derivative. GPL-3.0-or-later.
//
// [Android port] iOS draws through Praat's own Quartz Graphics backend; Android has no
// Quartz/Cairo, so android/engine/PraatBridgeAndroid.cpp records the object's draw into a
// backend-less GraphicsScreen and hands the raw double stream to Kotlin
// (PraatEngine.drawSelectedRecord). This file is a faithful re-implementation of the decoder
// loop in sys/Graphics_record.cpp (Graphics_play) plus the world->NDC->device coordinate
// machinery of sys/Graphics.cpp (computeTrafo / setViewport / setWindow / setInner /
// setWsWindow), replaying everything onto an android.graphics.Canvas.
//
// Stream layout (see sys/GraphicsP.h and Graphics_play in sys/Graphics_record.cpp):
//     [opcode, numberOfArguments, arg...]*
// Text bytes are UTF-8 packed 8-per-double (sput/sget); cell arrays and images are packed
// row-major blocks of doubles (mput/mget). Unknown opcodes are skipped by jumping exactly
// numberOfArguments doubles — the format makes that safe; this renderer never throws.

package com.thermetery.spraak

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PraatPicture {

    /** Resolution the C side records at: Graphics_create_pngfile(file, 100 dpi, 0, w, 0, h). */
    private const val RESOLUTION = 100.0

    /**
     * Replay a Praat Graphics recording onto a fresh bitmap.
     *
     * [widthInches]/[heightInches] must match what was passed to
     * PraatEngine.drawSelectedRecord — they define the recording's device extents
     * (width*100 x height*100 device dots), which are then scaled to [widthPx] x [heightPx].
     * Keep widthPx/heightPx in the same aspect ratio to avoid distortion.
     */
    fun render(
        record: DoubleArray,
        widthPx: Int,
        heightPx: Int,
        widthInches: Double = 6.0,
        heightInches: Double = 4.5,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(max(widthPx, 1), max(heightPx, 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val deviceWidth = widthInches * RESOLUTION
        val deviceHeight = heightInches * RESOLUTION
        // One global scale from the recording's device dots to actual bitmap pixels.
        canvas.scale((bitmap.width / deviceWidth).toFloat(), (bitmap.height / deviceHeight).toFloat())
        val start = streamStart(record)
        if (start >= 0)
            Interpreter(record, start, canvas, widthInches, heightInches).run()
        return bitmap
    }

    // ---- locating the stream inside the array ------------------------------------------------
    //
    // [Android port] Praat's record buffer is 1-based (the first useful double is record[1];
    // record[0] is never written — see the put()/get() macros in sys/GraphicsP.h). The current
    // JNI glue copies irecord doubles starting at record[0], so the stream usually begins at
    // array index 1 and its very last double is cut off. We auto-detect the start offset by
    // walking the [opcode, nArgs] chain, and all argument reads are clamped to the array end,
    // so both the current layout and a corrected one (stream at index 0) replay fine.

    private fun streamStart(record: DoubleArray): Int {
        if (record.size < 2) return -1
        if (walksCleanly(record, 0)) return 0
        if (walksCleanly(record, 1)) return 1
        return -1
    }

    private fun walksCleanly(record: DoubleArray, start: Int): Boolean {
        var p = start
        var sawAny = false
        while (p + 1 < record.size) {
            val opcode = record[p]
            val nArgs = record[p + 1]
            if (opcode != floor(opcode) || opcode < 101.0 || opcode > 200.0) return false
            if (nArgs != floor(nArgs) || nArgs < 0.0 || nArgs > 100_000_000.0) return false
            sawAny = true
            p += 2 + nArgs.toInt()
        }
        // A genuine stream ends exactly at the array end (fixed JNI) or one double past it
        // (current JNI, last double truncated); size-1 happens when the truncated double was
        // the argument count of a final zero-argument opcode.
        return sawAny && p >= record.size - 1 && p <= record.size + 1
    }

    // ---- opcodes (sys/GraphicsP.h `enum opcode`, starting at 101) -----------------------------

    private const val SET_VIEWPORT = 101
    private const val SET_INNER = 102
    private const val UNSET_INNER = 103
    private const val SET_WINDOW = 104
    private const val TEXT = 105
    private const val POLYLINE = 106
    private const val LINE = 107
    private const val ARROW = 108
    private const val FILL_AREA = 109
    private const val FUNCTION = 110
    private const val RECTANGLE = 111
    private const val FILL_RECTANGLE = 112
    private const val CIRCLE = 113
    private const val FILL_CIRCLE = 114
    private const val ARC = 115
    private const val SET_FONT = 119
    private const val SET_FONT_SIZE = 120
    private const val SET_FONT_STYLE = 121
    private const val SET_TEXT_ALIGNMENT = 122
    private const val SET_TEXT_ROTATION = 123
    private const val SET_LINE_TYPE = 124
    private const val SET_LINE_WIDTH = 125
    private const val SET_STANDARD_COLOUR = 126
    private const val SET_GREY = 127
    private const val MARK_GROUP = 128
    private const val ELLIPSE = 129
    private const val FILL_ELLIPSE = 130
    private const val CIRCLE_MM = 131
    private const val FILL_CIRCLE_MM = 132
    private const val IMAGE8 = 133
    private const val SET_WS_WINDOW = 139
    private const val INNER_RECTANGLE = 152
    private const val CELL_ARRAY8 = 153
    private const val IMAGE = 154
    private const val DOUBLE_ARROW = 158
    private const val SET_RGB_COLOUR = 159
    private const val POLYLINE_CLOSED = 161
    private const val SET_COLOUR_SCALE = 164
    private const val SET_SPECKLE_SIZE = 165
    private const val SPECKLE = 166
    private const val CELL_ARRAY = 118

    // line types (sys/Graphics.h)
    private const val LT_DRAWN = 0
    private const val LT_DOTTED = 1
    private const val LT_DASHED = 2
    private const val LT_DASHED_DOTTED = 3

    // vertical text alignment (sys/Graphics.h)
    private const val VA_BOTTOM = 0
    private const val VA_HALF = 1
    private const val VA_TOP = 2
    private const val VA_BASELINE = 3

    // ---- the interpreter + transform state machine --------------------------------------------

    private class Interpreter(
        private val rec: DoubleArray,
        private val start: Int,
        private val canvas: Canvas,
        widthInches: Double,
        heightInches: Double,
    ) {
        // Device extents of the recording Graphics (Graphics_create_pngfile, yIsZeroAtTheTop).
        private val x1DC = 0.0
        private val x2DC = widthInches * RESOLUTION
        private val y1DC = 0.0
        private val y2DC = heightInches * RESOLUTION

        // Workstation window in NDC: Graphics_create_pngfile calls
        // Graphics_setWsWindow(0, widthInches, 0, heightInches) before recording starts,
        // so NDC units are inches here.
        private var x1wNDC = 0.0; private var x2wNDC = widthInches
        private var y1wNDC = 0.0; private var y2wNDC = heightInches

        // Viewport in NDC and window in world coordinates (Graphics_init defaults, 0..1).
        private var x1NDC = 0.0; private var x2NDC = 1.0
        private var y1NDC = 0.0; private var y2NDC = 1.0
        private var x1WC = 0.0; private var x2WC = 1.0
        private var y1WC = 0.0; private var y2WC = 1.0

        // outerViewport saved by SET_INNER, restored by UNSET_INNER
        private var outerX1 = 0.0; private var outerX2 = 1.0
        private var outerY1 = 0.0; private var outerY2 = 1.0

        private var deltaX = 0.0; private var deltaY = 0.0
        private var scaleX = 1.0; private var scaleY = 1.0

        // graphics state (Graphics_init defaults; fontSize 10 is also re-recorded by the bridge)
        private var fontSize = 10.0
        private var font = 0                  // kGraphics_font: 0 Helvetica 1 Times 2 Courier 3 Palatino
        private var fontStyle = 0             // bit 0 bold, bit 1 italic
        private var horAlign = 0              // 0 left, 1 centre, 2 right
        private var vertAlign = VA_BOTTOM
        private var textRotation = 0.0
        private var lineType = LT_DRAWN
        private var lineWidth = 1.0
        private var speckleSize = 1.0         // mm
        private var colour = Color.BLACK

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val imagePaint = Paint()

        init { computeTrafo() }

        // exact port of computeTrafo() in sys/Graphics.cpp for yIsZeroAtTheTop == true
        private fun computeTrafo() {
            val worldScaleX = (x2NDC - x1NDC) / (x2WC - x1WC)
            val worldScaleY = (y2NDC - y1NDC) / (y2WC - y1WC)
            var dX = x1NDC - x1WC * worldScaleX
            var dY = y1NDC - y1WC * worldScaleY
            val workScaleX = (x2DC - x1DC) / (x2wNDC - x1wNDC)
            dX = x1DC - (x1wNDC - dX) * workScaleX
            val workScaleY = (y1DC - y2DC) / (y2wNDC - y1wNDC)   // yIsZeroAtTheTop
            dY = y2DC - (y1wNDC - dY) * workScaleY
            deltaX = dX
            deltaY = dY
            scaleX = worldScaleX * workScaleX
            scaleY = worldScaleY * workScaleY
        }

        private fun wdx(x: Double) = (x * scaleX + deltaX).toFloat()
        private fun wdy(y: Double) = (y * scaleY + deltaY).toFloat()

        /** Clamped argument read: the current JNI glue drops the stream's final double. */
        private fun arg(i: Int): Double = if (i in rec.indices) rec[i] else 0.0

        fun run() {
            var p = start
            while (p + 1 < rec.size) {
                val opcode = rec[p].toInt()
                val nArgs = rec[p + 1].toInt()
                if (nArgs < 0) return
                val a = p + 2   // index of the first argument
                try {
                    dispatch(opcode, a, nArgs)
                } catch (_: Throwable) {
                    // never crash on a malformed element; the chain skip below stays valid
                }
                p += 2 + nArgs
            }
        }

        private fun dispatch(opcode: Int, a: Int, nArgs: Int) {
            when (opcode) {
                SET_VIEWPORT -> {
                    x1NDC = arg(a); x2NDC = arg(a + 1); y1NDC = arg(a + 2); y2NDC = arg(a + 3)
                    computeTrafo()
                }
                SET_INNER -> setInner()
                UNSET_INNER -> {
                    x1NDC = outerX1; x2NDC = outerX2; y1NDC = outerY1; y2NDC = outerY2
                    computeTrafo()
                }
                SET_WINDOW -> {
                    val wx1 = arg(a); val wx2 = arg(a + 1); val wy1 = arg(a + 2); val wy2 = arg(a + 3)
                    if (wx1 != wx2 && wy1 != wy2) {
                        x1WC = wx1; x2WC = wx2; y1WC = wy1; y2WC = wy2
                        computeTrafo()
                    }
                }
                SET_WS_WINDOW -> {
                    val wx1 = arg(a); val wx2 = arg(a + 1); val wy1 = arg(a + 2); val wy2 = arg(a + 3)
                    if (wx1 != wx2 && wy1 != wy2) {
                        x1wNDC = wx1; x2wNDC = wx2; y1wNDC = wy1; y2wNDC = wy2
                        computeTrafo()
                    }
                }
                TEXT -> {
                    val x = arg(a); val y = arg(a + 1)
                    val length = arg(a + 2).toInt()
                    drawText(x, y, decodeUtf8(a + 3, length))
                }
                POLYLINE, POLYLINE_CLOSED -> {
                    val n = arg(a).toInt()
                    if (n >= 2) {
                        val path = Path()
                        path.moveTo(wdx(arg(a + 1)), wdy(arg(a + 1 + n)))
                        for (i in 1 until n)
                            path.lineTo(wdx(arg(a + 1 + i)), wdy(arg(a + 1 + n + i)))
                        if (opcode == POLYLINE_CLOSED) path.close()
                        canvas.drawPath(path, linePaint())
                    }
                }
                LINE -> canvas.drawLine(
                    wdx(arg(a)), wdy(arg(a + 1)), wdx(arg(a + 2)), wdy(arg(a + 3)), linePaint()
                )
                ARROW, DOUBLE_ARROW ->   // [Android port] arrowheads dropped: none of the six draws emit them
                    canvas.drawLine(wdx(arg(a)), wdy(arg(a + 1)), wdx(arg(a + 2)), wdy(arg(a + 3)), linePaint())
                FILL_AREA -> {
                    val n = arg(a).toInt()
                    if (n >= 3) {
                        val path = Path()
                        path.moveTo(wdx(arg(a + 1)), wdy(arg(a + 1 + n)))
                        for (i in 1 until n)
                            path.lineTo(wdx(arg(a + 1 + i)), wdy(arg(a + 1 + n + i)))
                        path.close()
                        canvas.drawPath(path, areaPaint())
                    }
                }
                FUNCTION -> {
                    val n = arg(a).toInt()
                    if (n >= 2) drawFunction(n, arg(a + 1), arg(a + 2), a + 3)
                }
                RECTANGLE -> canvas.drawRect(
                    rectWC(arg(a), arg(a + 1), arg(a + 2), arg(a + 3)), linePaint()
                )
                FILL_RECTANGLE -> canvas.drawRect(
                    rectWC(arg(a), arg(a + 1), arg(a + 2), arg(a + 3)), areaPaint()
                )
                INNER_RECTANGLE -> {   // Graphics_innerRectangle: inset by one device dot
                    val r = rectWC(arg(a), arg(a + 1), arg(a + 2), arg(a + 3))
                    r.inset(1f, 1f)
                    canvas.drawRect(r, linePaint())
                }
                CIRCLE -> canvas.drawCircle(
                    wdx(arg(a)), wdy(arg(a + 1)), (arg(a + 2) * scaleX).toFloat(), linePaint()
                )
                FILL_CIRCLE -> canvas.drawCircle(
                    wdx(arg(a)), wdy(arg(a + 1)), (arg(a + 2) * scaleX).toFloat(), areaPaint()
                )
                CIRCLE_MM -> canvas.drawCircle(
                    wdx(arg(a)), wdy(arg(a + 1)), mmRadius(arg(a + 2)), linePaint()
                )
                FILL_CIRCLE_MM -> canvas.drawCircle(
                    wdx(arg(a)), wdy(arg(a + 1)), mmRadius(arg(a + 2)), areaPaint()
                )
                SPECKLE -> canvas.drawCircle(   // Graphics_speckle: filled circle, speckleSize mm diameter
                    wdx(arg(a)), wdy(arg(a + 1)), mmRadius(speckleSize), areaPaint()
                )
                ELLIPSE, FILL_ELLIPSE -> canvas.drawOval(
                    rectWC(arg(a), arg(a + 1), arg(a + 2), arg(a + 3)),
                    if (opcode == FILL_ELLIPSE) areaPaint() else linePaint()
                )
                ARC -> {
                    val x = wdx(arg(a)); val y = wdy(arg(a + 1))
                    val r = (arg(a + 2) * scaleX).toFloat()
                    val from = arg(a + 3); val to = arg(a + 4)
                    // world angles are counterclockwise; the canvas y-axis is flipped
                    canvas.drawArc(RectF(x - r, y - r, x + r, y + r),
                        (-from).toFloat(), (-(to - from)).toFloat(), false, linePaint())
                }
                CELL_ARRAY, IMAGE -> drawImage(a, byteValued = false, interpolate = opcode == IMAGE)
                CELL_ARRAY8, IMAGE8 -> drawImage(a, byteValued = true, interpolate = opcode == IMAGE8)
                SET_FONT -> font = arg(a).toInt()
                SET_FONT_SIZE -> fontSize = arg(a)
                SET_FONT_STYLE -> fontStyle = arg(a).toInt()
                SET_TEXT_ALIGNMENT -> { horAlign = arg(a).toInt(); vertAlign = arg(a + 1).toInt() }
                SET_TEXT_ROTATION -> textRotation = arg(a)
                SET_LINE_TYPE -> lineType = arg(a).toInt()
                SET_LINE_WIDTH -> lineWidth = arg(a)
                SET_STANDARD_COLOUR -> colour = standardColour(arg(a).toInt())
                SET_GREY -> {
                    val g = (arg(a).coerceIn(0.0, 1.0) * 255.0).roundToInt()
                    colour = Color.rgb(g, g, g)
                }
                SET_RGB_COLOUR -> colour = Color.rgb(
                    (arg(a).coerceIn(0.0, 1.0) * 255.0).roundToInt(),
                    (arg(a + 1).coerceIn(0.0, 1.0) * 255.0).roundToInt(),
                    (arg(a + 2).coerceIn(0.0, 1.0) * 255.0).roundToInt(),
                )
                SET_COLOUR_SCALE -> { /* grey only; none of the supported draws use BLUE_TO_RED */ }
                SET_SPECKLE_SIZE -> speckleSize = arg(a)
                MARK_GROUP -> { /* no-op marker */ }
                else -> { /* unknown/unsupported opcode: the chain skip in run() handles it */ }
            }
        }

        // exact port of Graphics_setInner() in sys/Graphics.cpp (horTick/vertTick are only
        // needed at record time, so they are not tracked here)
        private fun setInner() {
            val margin = 2.8 * fontSize * RESOLUTION / 72.0
            val wDC = (x2DC - x1DC) / (x2wNDC - x1wNDC) * (x2NDC - x1NDC)
            val hDC = abs(y2DC - y1DC) / (y2wNDC - y1wNDC) * (y2NDC - y1NDC)
            var dx = 1.5 * margin / wDC
            var dy = margin / hDC
            if (dx > 0.4) dx = 0.4
            if (dy > 0.4) dy = 0.4
            outerX1 = x1NDC; outerX2 = x2NDC; outerY1 = y1NDC; outerY2 = y2NDC
            x1NDC = (1.0 - dx) * outerX1 + dx * outerX2
            x2NDC = (1.0 - dx) * outerX2 + dx * outerX1
            y1NDC = (1.0 - dy) * outerY1 + dy * outerY2
            y2NDC = (1.0 - dy) * outerY2 + dy * outerY1
            computeTrafo()
        }

        // ---- painters --------------------------------------------------------------------

        private fun linePaint(): Paint {
            strokePaint.color = colour
            // LINE_WIDTH_IN_PIXELS: at 100 dpi (<= 192) the width in device dots is lineWidth itself
            strokePaint.strokeWidth = max(lineWidth, 0.25).toFloat()
            strokePaint.pathEffect = when (lineType) {
                LT_DOTTED -> DashPathEffect(floatArrayOf(2f, 2f), 0f)          // quartz/gdi pattern at <=192 dpi
                LT_DASHED -> DashPathEffect(floatArrayOf(6f, 2f), 0f)
                LT_DASHED_DOTTED -> DashPathEffect(floatArrayOf(6f, 2f, 2f, 2f), 0f)
                else -> null
            }
            return strokePaint
        }

        private fun areaPaint(): Paint {
            fillPaint.color = colour
            return fillPaint
        }

        private fun mmRadius(diameterMm: Double): Float =
            (0.5 * diameterMm * RESOLUTION / 25.4).toFloat()

        private fun rectWC(wx1: Double, wx2: Double, wy1: Double, wy2: Double): RectF {
            val l = wdx(wx1); val r = wdx(wx2)
            val t = wdy(wy2); val b = wdy(wy1)   // y flipped on screen
            return RectF(min(l, r), min(t, b), max(l, r), max(t, b))
        }

        // ---- text ------------------------------------------------------------------------

        /** sget: UTF-8 bytes packed 8 per double (little-endian, NUL-terminated). */
        private fun decodeUtf8(firstIndex: Int, lengthInDoubles: Int): String {
            val len = lengthInDoubles.coerceIn(0, 1 shl 20)   // defensive: length comes from the stream
            val bytes = ByteArray(len * 8)
            var n = 0
            outer@ for (i in 0 until len) {
                var bits = java.lang.Double.doubleToRawLongBits(arg(firstIndex + i))
                for (k in 0 until 8) {
                    val b = (bits and 0xFFL).toByte()
                    if (b == 0.toByte()) break@outer
                    bytes[n++] = b
                    bits = bits ushr 8
                }
            }
            return String(bytes, 0, n, Charsets.UTF_8)
        }

        private fun drawText(xWC: Double, yWC: Double, text: String) {
            if (text.isEmpty()) return
            textPaint.color = colour
            textPaint.textSize = (fontSize * RESOLUTION / 72.0).toFloat()
            textPaint.typeface = Typeface.create(
                when (font) {
                    1, 3 -> Typeface.SERIF       // Times, Palatino
                    2 -> Typeface.MONOSPACE      // Courier
                    else -> Typeface.SANS_SERIF  // Helvetica
                },
                when (fontStyle and 3) {
                    1 -> Typeface.BOLD
                    2 -> Typeface.ITALIC
                    3 -> Typeface.BOLD_ITALIC
                    else -> Typeface.NORMAL
                },
            )
            val width = textPaint.measureText(text).toDouble()
            // exact port of the dx/dy offsets in drawOneCell() (sys/Graphics_text.cpp)
            val dx = when (horAlign) {
                1 -> -width / 2.0                                                     // centre
                2 -> if (width != 0.0) -width - 0.1 / 72.0 * fontSize * RESOLUTION else 0.0   // right
                else -> 1.0 + 0.1 / 72.0 * fontSize * RESOLUTION                      // left
            }
            val dy = when (vertAlign) {     // y-up offset of the baseline relative to yWC
                VA_BOTTOM -> 0.4 / 72.0 * fontSize * RESOLUTION
                VA_HALF -> -0.3 / 72.0 * fontSize * RESOLUTION
                VA_TOP -> -1.0 / 72.0 * fontSize * RESOLUTION
                else -> 0.0                 // baseline
            }
            canvas.save()
            canvas.translate(wdx(xWC), wdy(yWC))
            if (textRotation != 0.0)
                canvas.rotate((-textRotation).toFloat())   // world angles are CCW; canvas y is flipped
            canvas.drawText(text, dx.toFloat(), (-dy).toFloat(), textPaint)
            canvas.restore()
        }

        // ---- Graphics_function (the workhorse of Sound/Intensity/Spectrum draws) ----------

        private fun drawFunction(n: Int, x1: Double, x2: Double, yBase: Int) {
            // y values are clipped to the world window (FUNCTIONS_ARE_CLIPPED in
            // sys/Graphics_linesAndAreas.cpp); with yIsZeroAtTheTop, y1WC maps to the LARGER device y
            val clipBottom = wdy(y1WC)
            val clipTop = wdy(y2WC)
            val lo = min(clipBottom, clipTop)
            val hi = max(clipBottom, clipTop)
            val dxWC = (x2 - x1) / (n - 1)
            val xDC1 = wdx(x1)
            val xDC2 = wdx(x2)
            val columns = (abs(xDC2 - xDC1) + 1.0).toInt()
            val paint = linePaint()
            if (n > 2 * columns && columns >= 2) {
                // min/max per device column, like Praat's optimized branch: keeps peaks visible
                val path = Path()
                var started = false
                for (c in 0 until columns) {
                    val jMin = ((c.toDouble() / columns) * (n - 1)).toInt().coerceIn(0, n - 1)
                    val jMax = (((c + 1).toDouble() / columns) * (n - 1)).toInt().coerceIn(0, n - 1)
                    var minV = arg(yBase + jMin); var maxV = minV
                    for (j in jMin + 1..jMax) {
                        val v = arg(yBase + j)
                        if (v < minV) minV = v
                        if (v > maxV) maxV = v
                    }
                    val x = xDC1 + (xDC2 - xDC1) * c / (columns - 1).coerceAtLeast(1)
                    val yA = wdy(minV).coerceIn(lo, hi)
                    val yB = wdy(maxV).coerceIn(lo, hi)
                    if (!started) { path.moveTo(x, yA); started = true } else path.lineTo(x, yA)
                    path.lineTo(x, yB)
                }
                canvas.drawPath(path, paint)
            } else {
                val path = Path()
                for (i in 0 until n) {
                    val x = wdx(x1 + i * dxWC)
                    val y = wdy(arg(yBase + i)).coerceIn(lo, hi)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }
        }

        // ---- cell arrays / images (the spectrogram greyscale) -----------------------------

        private fun drawImage(a: Int, byteValued: Boolean, interpolate: Boolean) {
            val wx1 = arg(a); val wx2 = arg(a + 1); val wy1 = arg(a + 2); val wy2 = arg(a + 3)
            val minimum = arg(a + 4); val maximum = arg(a + 5)
            val nrow = arg(a + 6).toInt(); val ncol = arg(a + 7).toInt()
            if (nrow < 1 || ncol < 1 || maximum == minimum) return
            if (nrow.toLong() * ncol.toLong() > 64L * 1024 * 1024) return   // sanity cap
            // grey mapping from _GraphicsScreen_cellArrayOrImage: value = offset - scale * z,
            // i.e. minimum -> 255 (white), maximum -> 0 (black)
            val scale = 255.0 / (maximum - minimum)
            val offset = 255.0 + minimum * scale
            // byteValued (CELL_ARRAY8/IMAGE8) stores plain 0..255 values, one per double,
            // so the decode below covers both layouts identically.
            val pixels = IntArray(nrow * ncol)
            val data = a + 8
            for (row in 0 until nrow) {
                // z row 1 is drawn at the BOTTOM edge (y1); bitmap row 0 is the top -> flip
                val src = data + (nrow - 1 - row) * ncol
                val dst = row * ncol
                for (col in 0 until ncol) {
                    val v = (offset - scale * arg(src + col)).roundToInt().coerceIn(0, 255)
                    pixels[dst + col] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
            val cells = Bitmap.createBitmap(pixels, ncol, nrow, Bitmap.Config.ARGB_8888)
            val dest = rectWC(wx1, wx2, wy1, wy2)
            imagePaint.isFilterBitmap = interpolate
            canvas.save()
            // Praat clips the cells to the world window
            canvas.clipRect(rectWC(x1WC, x2WC, y1WC, y2WC))
            canvas.drawBitmap(cells, null, dest, imagePaint)
            canvas.restore()
        }

        // SET_STANDARD_COLOUR table from Graphics_play (only in old picture files, kept for safety)
        private fun standardColour(index: Int): Int = when (index) {
            0 -> Color.BLACK
            1 -> Color.WHITE
            2 -> Color.RED
            3 -> Color.GREEN
            4 -> Color.BLUE
            5 -> Color.CYAN
            6 -> Color.MAGENTA
            7 -> Color.YELLOW
            8 -> Color.rgb(128, 0, 0)      // maroon
            9 -> Color.rgb(0, 255, 0)      // lime
            10 -> Color.rgb(0, 0, 128)     // navy
            11 -> Color.rgb(0, 128, 128)   // teal
            12 -> Color.rgb(128, 0, 128)   // purple
            13 -> Color.rgb(128, 128, 0)   // olive
            14 -> Color.rgb(255, 192, 203) // pink
            15 -> Color.rgb(192, 192, 192) // silver
            else -> Color.GRAY
        }
    }
}
