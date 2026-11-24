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
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

internal val background = Color(0xFF121511)
internal val panelColor = Color(0xFF1B201A)
internal val controlColor = Color(0xFF252B23)
internal val selectedColor = Color(0xFF3D4A35)
internal val dividerColor = Color(0xFF384036)
internal val ink = Color(0xFFF2F3E9)
internal val muted = Color(0xFFB4BDB0)
internal val accent = Color(0xFFD8EDAB)
internal val onAccent = Color(0xFF202A14)
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
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Editor()
                }
            }
        }
    }
}
