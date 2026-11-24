package dev.grainlegit.editor

import androidx.compose.runtime.saveable.Saver
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class Tool(
    val title: String,
    val min: Float = -1f,
    val max: Float = 1f,
    val initial: Float = 0f,
    val steps: Int = 40,
    private val unit: String = "",
    private val logarithmic: Boolean = false,
) {
    Exposure("Exposure", -3f, 3f, steps = 60, unit = " EV"),
    Brightness("Brightness"), Highlights("Highlights"), Shadows("Shadows"),
    Contrast("Contrast"), Whites("Whites"), Blacks("Blacks"), Curves("Curves"),
    Temperature("Temperature"), Tint("Tint"), Saturation("Saturation"), Vibrance("Vibrance"),
    WhiteBalance("WB picker"), Sharpen("Sharpen", 0f, 2f),
    CameraExposure("Negative exposure", -2f, 2f, unit = " EV"),
    Grain("Grain amount", 0f, 3f, 1f, 60, "×"),
    // 0.1–15.85× in tenth-decade stops keeps 1× on a stop and leaves room for Auto on small photos.
    GrainSize("Grain size", 0.1f, 15.85f, 1f, 22, "×", logarithmic = true),
    GrainColour("Grain colour", 0f, 1f, 0.25f, 100),
    GrainLoad("Dye per cloud", 0.1f, 8f, 1f, 158, "×"),
    CoarseFraction("Coarse fraction", 0f, 1f, 0.24f, 100),
    RedCloudSize("Red cloud size", 0.25f, 4f, 1f, 14, "×", logarithmic = true),
    GreenCloudSize("Green cloud size", 0.25f, 4f, 1f, 14, "×", logarithmic = true),
    BlueCloudSize("Blue cloud size", 0.25f, 4f, 1f, 14, "×", logarithmic = true),
    Development("Development", -2f, 2f, steps = 80),
    PrintExposure("Print exposure", -4f, 4f, steps = 80, unit = " EV"),
    Enlargement("Enlargement", 1f, 30f, 6f, 58, "×"),
    EnlargerBlur("Enlarger blur", 0f, 150f, steps = 150, unit = " µm"),
    PrintGrain("Print grain", 0f, 4f, 1f, 80, "×"),
    Cyan("Red light", -4f, 4f, steps = 80, unit = " EV"),
    Magenta("Green light", -4f, 4f, steps = 80, unit = " EV"),
    Yellow("Blue light", -4f, 4f, steps = 80, unit = " EV"),
    ScanBlur("Scan blur", 0f, 8f, unit = " px"), Aperture("Scan aperture", 1f, 8f, 1f, 35, " px"),
    Straighten("Straighten", -45f, 45f, steps = 180, unit = "°"),
    Horizontal("Horizontal", -30f, 30f, steps = 120, unit = "°"),
    Vertical("Vertical", -30f, 30f, steps = 120, unit = "°"),
    BrushSize("Brush size", 6f, 80f, 26f, 74, " dp"),
    // Keep names used in saved activity state before these moved to dedicated controls.
    Stock("Stock"), Format("Format"), Medium("Medium");

    fun display(value: Float): String = when {
        logarithmic -> "${"%.2f".format(value)}$unit"
        this == Development -> "%.1f".format(value)
        unit.isNotEmpty() -> "${"%.1f".format(value)}$unit"
        else -> "${(value * 100).roundToInt()}%"
    }

    fun rulerPosition(value: Float): Float {
        val bounded = value.coerceIn(min, max)
        return if (logarithmic) ln(bounded / min) / ln(max / min) else (bounded - min) / (max - min)
    }

    fun rulerValue(position: Float): Float {
        val stop = (position.coerceIn(0f, 1f) * steps).roundToInt()
        val fraction = stop.toFloat() / steps
        return if (logarithmic) min * (max / min).pow(fraction) else min + (max - min) * fraction
    }
}

internal val stockIds = listOf("generic", "portra-400", "gold-200", "ektar-100", "vision3-500t")
internal val stockNames = listOf("Generic", "Portra 400", "Gold 200", "Ektar 100", "Vision3 500T")
internal val formatNames = listOf("Super 16", "110", "35 mm", "120 · 6×6", "4×5", "8×10")
internal val formatWidths = listOf(12.5f, 17f, 36f, 56f, 120f, 245f)
internal val printMedia = listOf("Direct scan", "Paper", "Print film")
internal val neutralCurve = List(5) { it / 4f }

internal data class Edit(
    val values: Map<Tool, Float> = emptyMap(),
    val stockId: String = "generic",
    val format: Int = 2,
    val medium: Int = 0,
    val balance: List<Float> = listOf(1f, 1f, 1f),
    val curves: List<List<Float>> = List(4) { neutralCurve },
    val geometry: Geometry = Geometry(),
    val useCoarseProfile: Boolean = true,
    val retouches: List<Retouch> = emptyList(),
) {
    operator fun get(tool: Tool) = values[tool] ?: tool.initial

    fun with(tool: Tool, value: Float): Edit =
        copy(values = if (value == tool.initial) values - tool else values + (tool to value))

    fun nativeSettings(): String {
        fun vector(vararg tools: Tool) = JSONArray(tools.map { get(it).toDouble() })
        val film = JSONObject()
            .put("negative_stock", stockIds.indexOf(stockId).coerceAtLeast(0))
            .put("negative_width", formatWidths[format].toDouble())
            .put("exposure", get(Tool.CameraExposure).toDouble())
            .put("grain", get(Tool.Grain).toDouble())
            .put("grain_size", get(Tool.GrainSize).toDouble())
            .put("grain_colour", get(Tool.GrainColour).toDouble())
            .put("grain_load", get(Tool.GrainLoad).toDouble())
            .put("layer_size", vector(Tool.RedCloudSize, Tool.GreenCloudSize, Tool.BlueCloudSize))
            .put("development", get(Tool.Development).toDouble())
            .put("print_medium", medium)
            .put("scan_blur", get(Tool.ScanBlur).toDouble())
            .put("scan_aperture", get(Tool.Aperture).toDouble())
        if (!useCoarseProfile) film.put("coarse_fraction", get(Tool.CoarseFraction).toDouble())
        if (medium != 0) {
            film.put("print_exposure", get(Tool.PrintExposure).toDouble())
                .put("enlargement", get(Tool.Enlargement).toDouble())
                .put("enlarger_blur", get(Tool.EnlargerBlur).toDouble())
                .put("print_grain", get(Tool.PrintGrain).toDouble())
                .put("print_lights", vector(Tool.Cyan, Tool.Magenta, Tool.Yellow))
        }
        val adjust = JSONObject()
            .put("light", vector(Tool.Exposure, Tool.Brightness, Tool.Highlights, Tool.Shadows))
            .put("tones", vector(Tool.Contrast, Tool.Whites, Tool.Blacks, Tool.Sharpen))
            .put("colour", vector(Tool.Temperature, Tool.Tint, Tool.Saturation, Tool.Vibrance))
            .put("balance", JSONArray((balance + 1f).map(Float::toDouble)))
            .put("curves", JSONArray(List(5) { point -> JSONArray(curves.map { it[point].toDouble() }) }))
        return JSONObject().put("film", film).put("adjust", adjust).toString()
    }

    fun savedSettings(): String {
        val adjustments = JSONObject()
        values.forEach { (tool, value) -> adjustments.put(tool.name, value.toDouble()) }
        return JSONObject().put("values", adjustments).put("stock", stockId)
            .put("format", format).put("medium", medium)
            .put("balance", JSONArray(balance.map(Float::toDouble)))
            .put("curves", JSONArray(curves.map { curve -> JSONArray(curve.map(Float::toDouble)) }))
            .put("geometry", geometry.encode()).put("useCoarseProfile", useCoarseProfile)
            .put("retouches", retouches.encode()).toString()
    }

    companion object {
        val saver = Saver<Edit, String>(save = { it.savedSettings() }, restore = ::restore)

        fun restore(saved: String): Edit {
            val json = JSONObject(saved)
            val values = json.getJSONObject("values")
            val curves = json.getJSONArray("curves")
            return Edit(
                values = Tool.entries.filter { values.has(it.name) }.associateWith { values.getDouble(it.name).toFloat() },
                stockId = json.getString("stock"),
                format = json.getInt("format"),
                medium = json.getInt("medium"),
                balance = List(3) { json.getJSONArray("balance").getDouble(it).toFloat() },
                curves = List(4) { channel -> List(5) { curves.getJSONArray(channel).getDouble(it).toFloat() } },
                geometry = Geometry.decode(json.getJSONObject("geometry")),
                useCoarseProfile = json.optBoolean("useCoarseProfile", true),
                retouches = Retouch.decodeAll(json.optJSONArray("retouches")),
            )
        }
    }
}
