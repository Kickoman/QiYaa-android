package io.github.kickoman.qiyaa.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import io.github.kickoman.qiyaa.AppLocale
import io.github.kickoman.qiyaa.data.Settings

class MainActivity : ComponentActivity() {
    private val app: AppViewModel by viewModels()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // An override configuration rather than a wrapped context: the activity keeps following
        // the screen and the night mode, and only the language is the app's own.
        applyOverrideConfiguration(AppLocale.configuration(Settings.storedLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot(app) }
        askNotificationPermission()
        if (savedInstanceState == null) openJamLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openJamLink(intent)
    }

    private fun openJamLink(intent: Intent) {
        val text =
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.dataString
                Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                else -> null
            } ?: return
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        app.openJamLink(text, launchedFromHistory = fromHistory)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        setIntent(launcher.setClass(this, MainActivity::class.java))
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifications.launch(permission)
    }
}
