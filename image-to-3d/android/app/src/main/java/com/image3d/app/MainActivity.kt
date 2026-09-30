package com.image3d.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.IntentCompat
import com.image3d.app.data.Library
import com.image3d.app.ui.CreateScreen
import com.image3d.app.ui.HomeScreen
import com.image3d.app.ui.InfoScreen
import com.image3d.app.ui.ModelsScreen
import com.image3d.app.ui.ViewerScreen
import com.image3d.app.work.Jobs

sealed interface Screen {
    data object Home : Screen
    data object Models : Screen
    data object Info : Screen
    data class Create(val sharedImage: Uri? = null) : Screen
    data class Viewer(val id: String) : Screen
}

private val Colors = darkColorScheme(
    primary = Color(0xFF8C7CFF),
    onPrimary = Color(0xFF12102A),
    secondary = Color(0xFF3DD6C4),
    background = Color(0xFF12131A),
    surface = Color(0xFF12131A),
    surfaceVariant = Color(0xFF1E2030),
    surfaceContainer = Color(0xFF1A1C27),
    surfaceContainerHigh = Color(0xFF222433),
    surfaceContainerHighest = Color(0xFF2A2C3D),
)

class MainActivity : ComponentActivity() {
    private val stack = mutableStateListOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!Jobs.busy) Library.cleanup(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleShare(intent)
        setContent {
            MaterialTheme(colorScheme = Colors) {
                val screen = stack.last()
                BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }
                val go: (Screen) -> Unit = { stack.add(it) }
                val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
                val replace: (Screen) -> Unit = { stack[stack.lastIndex] = it }
                when (screen) {
                    Screen.Home -> HomeScreen(go)
                    Screen.Models -> ModelsScreen(back)
                    Screen.Info -> InfoScreen(back)
                    is Screen.Create -> CreateScreen(screen.sharedImage, back, go, replace)
                    is Screen.Viewer -> ViewerScreen(screen.id, back)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        stack.clear()
        stack.add(Screen.Home)
        stack.add(Screen.Create(uri))
    }
}
