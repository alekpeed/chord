package com.alekpeed.hearsay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.alekpeed.hearsay.ui.HearsayApp
import com.alekpeed.hearsay.ui.theme.HearsayTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The only activity.
 *
 * Everything above this is Compose; this class exists to host it and to keep the window
 * configuration in one place.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HearsayTheme {
                HearsayApp()
            }
        }
    }
}
