package com.videofetch.pro
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.videofetch.pro.ui.VideoFetchApp
import com.videofetch.pro.ui.theme.VideoFetchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        setContent { VideoFetchTheme { VideoFetchApp(sharedText = sharedText) } }
    }
}
