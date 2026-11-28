package dev.grainlegit.editor

import androidx.compose.runtime.saveable.Saver
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

private val lensStops = listOf(1.4f, 1.6f, 1.8f, 2f, 2.2f, 2.5f, 2.8f, 3.2f, 3.5f, 4f, 4.5f, 5f, 5.6f)

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
    // 1× is the real stock. The default 2× sits mid-slider; 4× matches the film stock previews.
    StockStrength("Stock strength", 0f, 4f, 2f, 40, "×"),
    LensAperture("Aperture", lensStops.first(), lensStops.last(), lensStops.last(), lensStops.lastIndex),
    LensVignette("Vignette", -4f, 0f, -2.3f, 40, " EV"),
    LensGlow("Glow", 0f, 1f, 0.30f, 100),
    LensHaze("Haze", 0f, 0.12f, 0.03f, 12),
    LensSoftness("Softness", 0f, 1f, 0.35f, 100),
    LensWarmth("Edge warmth", -1f, 1f, 0.12f, 200),
    LensSaturation("Edge saturation", -1f, 1f, -0.15f, 200),
    LensGhosts("Ghosts", 0f, 1f, 0f, 100),
    Grain("Grain amount", 0f, 3f, 1f, 60, "×"),
    // 0.1–15.85× in tenth-decade stops keeps 1× on a stop and leaves room for Auto on small photos.
    GrainSize("Grain size", 0.1f, 15.85f, 1f, 22, "×", logarithmic = true),
    GrainColour("Grain colour", 0f, 1f, 0.25f, 100),
    GrainStrength("Grain strength", 0f, 2f, 1f, 40, "×"),
    // Negative favours shadows, positive highlights; ±1 doubles the grain at that end.
    GrainTone("Grain tone", -1f, 1f, steps = 40),
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
        this == LensAperture -> "f/${"%.1f".format(value)}"
        logarithmic -> "${"%.2f".format(value)}$unit"
        this == Development -> "%.1f".format(value)
        this == GrainTone -> when {
            value < -0.005f -> "Shadows ${(-value * 100).roundToInt()}%"
            value > 0.005f -> "Highlights ${(value * 100).roundToInt()}%"
            else -> "Even"
        }
        unit.isNotEmpty() -> "${"%.1f".format(value)}$unit"
        else -> "${(value * 100).roundToInt()}%"
    }

    fun rulerPosition(value: Float): Float {
        val bounded = value.coerceIn(min, max)
        if (this == LensAperture) {
            val index = lensStops.indices.minBy { abs(lensStops[it] - bounded) }
            return index.toFloat() / steps
        }
        return if (logarithmic) ln(bounded / min) / ln(max / min) else (bounded - min) / (max - min)
    }

    fun rulerValue(position: Float): Float {
        val stop = (position.coerceIn(0f, 1f) * steps).roundToInt()
        if (this == LensAperture) return lensStops[stop]
        val fraction = stop.toFloat() / steps
        return if (logarithmic) min * (max / min).pow(fraction) else min + (max - min) * fraction
    }
}

internal data class FilmStock(val id: String, val name: String)

internal val filmStocks: List<FilmStock> by lazy {
    val stocks = JSONArray(NativeRenderer.filmStocks())
    listOf(FilmStock("generic", "Generic")) + List(stocks.length()) { index ->
        val stock = stocks.getJSONObject(index)
        FilmStock(stock.getString("id"), stock.getString("name"))
    }
}
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
    private val stockIndex get() = filmStocks.indexOfFirst { it.id == stockId }.coerceAtLeast(0)
    val stock get() = filmStocks[stockIndex]

    fun defaultValue(tool: Tool): Float {
        if (tool != Tool.LensGlow) return tool.initial
        val opening = (1f - log2(get(Tool.LensAperture) / Tool.LensAperture.min)).coerceIn(0f, 1f)
        return tool.initial * opening * opening
    }

    operator fun get(tool: Tool): Float = values[tool] ?: defaultValue(tool)

    fun with(tool: Tool, value: Float): Edit =
        copy(values = if (value == defaultValue(tool)) values - tool else values + (tool to value))

    fun withStock(id: String): Edit =
        if (id == stock.id) this else copy(stockId = id, values = values - Tool.StockStrength)

    fun nativeSettings(): String {
        fun vector(vararg tools: Tool) = JSONArray(tools.map { get(it).toDouble() })
        val film = JSONObject()
            .put("negative_stock", stockIndex)
            .put("stock_strength", get(Tool.StockStrength).toDouble())
            .put("negative_width", formatWidths[format].toDouble())
            .put("exposure", get(Tool.CameraExposure).toDouble())
            .put("grain", get(Tool.Grain).toDouble())
            .put("grain_size", get(Tool.GrainSize).toDouble())
            .put("grain_colour", get(Tool.GrainColour).toDouble())
            .put("grain_strength", get(Tool.GrainStrength).toDouble())
            .put("grain_tone", get(Tool.GrainTone).toDouble())
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
        val edit = JSONObject().put("film", film).put("adjust", adjust)
        if (get(Tool.LensAperture) < Tool.LensAperture.max || get(Tool.LensGlow) > 0f) {
            edit.put("lens", JSONObject()
                .put("aperture", get(Tool.LensAperture).toDouble())
                .put("vignette", get(Tool.LensVignette).toDouble())
                .put("glow", get(Tool.LensGlow).toDouble())
                .put("haze", get(Tool.LensHaze).toDouble())
                .put("softness", get(Tool.LensSoftness).toDouble())
                .put("warmth", get(Tool.LensWarmth).toDouble())
                .put("saturation", get(Tool.LensSaturation).toDouble())
                .put("ghosts", get(Tool.LensGhosts).toDouble()))
        }
        return edit.toString()
    }

    fun savedSettings(): String {
        val adjustments = JSONObject()
        values.forEach { (tool, value) -> adjustments.put(tool.name, value.toDouble()) }
        return JSONObject().put("values", adjustments).put("stock", stockId)
            .put("format", format).put("medium", medium)
            .put("balance", JSONArray(balance.map(Float::toDouble)))
            .put("curves", JSONArray(curves.map { curve -> JSONArray(curve.map(Float::toDouble)) }))
            .put("geometry", geometry.encode()).put("useCoarseProfile", useCoarseProfile)
            .put("retouches", retouches.encode()).put("stockStrengthDefault", Tool.StockStrength.initial.toDouble())
            .toString()
    }

    companion object {
        val saver = Saver<Edit, String>(save = { it.savedSettings() }, restore = ::restore)

        fun restore(saved: String): Edit {
            val json = JSONObject(saved)
            val values = json.getJSONObject("values")
            val curves = json.getJSONArray("curves")
            return Edit(
                values = Tool.entries.filter { values.has(it.name) }.associateWith { values.getDouble(it.name).toFloat() }
                    .let { restored ->
                        // Edits saved while the default strength was 1× omit it; keep their look.
                        val savedDefault = json.optDouble("stockStrengthDefault", 1.0).toFloat()
                        if (Tool.StockStrength in restored || savedDefault == Tool.StockStrength.initial) restored
                        else restored + (Tool.StockStrength to savedDefault)
                    },
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
