package dev.grainlegit.editor

import androidx.compose.runtime.saveable.Saver
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal enum class Tool(
    val title: String,
    val min: Float = -1f,
    val max: Float = 1f,
    val initial: Float = 0f,
    val steps: Int = 40,
    private val unit: String = "",
) {
    Exposure("Exposure", -3f, 3f, steps = 60, unit = " EV"),
    Brightness("Brightness"), Highlights("Highlights"), Shadows("Shadows"),
    Contrast("Contrast"), Whites("Whites"), Blacks("Blacks"), Curves("Curves"),
    Temperature("Temperature"), Tint("Tint"), Saturation("Saturation"), Vibrance("Vibrance"),
    WhiteBalance("WB picker"), Sharpen("Sharpen", 0f, 2f),
    Stock("Stock"), Format("Format"), CameraExposure("Camera EV", -2f, 2f, unit = " EV"),
    Grain("Grain", 0f, 3f, 1f, 60), Medium("Medium"),
    PrintExposure("Print EV", -4f, 4f, steps = 80, unit = " EV"),
    Enlargement("Enlargement", 1f, 30f, 6f, 58, "×"),
    EnlargerBlur("Enlarger blur", 0f, 150f, steps = 150, unit = " µm"),
    PrintGrain("Print grain", 0f, 4f, 1f, 80),
    Cyan("Red light", -4f, 4f, steps = 80, unit = " EV"),
    Magenta("Green light", -4f, 4f, steps = 80, unit = " EV"),
    Yellow("Blue light", -4f, 4f, steps = 80, unit = " EV"),
    ScanBlur("Scan blur", 0f, 8f, unit = " px"), Aperture("Scan aperture", 1f, 8f, 1f, 35, " px"),
    Straighten("Straighten", -45f, 45f, steps = 180, unit = "°"),
    Horizontal("Horizontal", -30f, 30f, steps = 120, unit = "°"),
    Vertical("Vertical", -30f, 30f, steps = 120, unit = "°");

    fun display(value: Float): String = when {
        unit.isNotEmpty() -> "${"%.1f".format(value)}$unit"
        else -> "${(value * 100).roundToInt()}%"
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
            .put("grain_colour", 0.25)
            .put("print_medium", medium)
            .put("scan_blur", get(Tool.ScanBlur).toDouble())
            .put("scan_aperture", get(Tool.Aperture).toDouble())
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
            .put("geometry", geometry.encode()).toString()
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
            )
        }
    }
}
