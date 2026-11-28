package dev.grainlegit.editor

import android.content.pm.ApplicationInfo
import android.graphics.Color as SystemColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

internal val background = Color(0xFF101010)
internal val panelColor = Color(0xFF181818)
internal val controlColor = Color(0xFF252525)
internal val selectedColor = Color(0xFF424242)
internal val dividerColor = Color(0xFF353535)
internal val ink = Color(0xFFF2F2F2)
internal val muted = Color(0xFFB1B1B1)
internal val accent = Color.White
internal val onAccent = Color(0xFF171717)
private val typography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontSize = 24.sp, lineHeight = 30.sp),
        headlineSmall = headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = titleMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        titleSmall = titleSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodyLarge = bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
    )
}
private val colors = darkColorScheme(
    background = background,
    onBackground = ink,
    surface = background,
    onSurface = ink,
    surfaceVariant = controlColor,
    onSurfaceVariant = muted,
    surfaceContainer = panelColor,
    surfaceContainerHigh = controlColor,
    primary = accent,
    onPrimary = onAccent,
    secondaryContainer = selectedColor,
    onSecondaryContainer = accent,
    outline = dividerColor,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra("benchmark", false)) {
            Thread({ runModelBenchmark(applicationContext) }, "Grainlegit benchmark").start()
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(SystemColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(SystemColor.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = colors, typography = typography) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Editor()
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // OSOR holds about 4.3 GiB; it reloads on the next removal if Erase is still open.
        if (level >= TRIM_MEMORY_UI_HIDDEN) EraseEngine.get(this).releaseOsor()
    }
}
