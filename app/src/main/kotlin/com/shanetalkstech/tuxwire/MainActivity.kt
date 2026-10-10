package com.shanetalkstech.tuxwire

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shanetalkstech.tuxwire.ui.HomeScreen

/**
 * The app's one and only Android "Activity": the screen the launcher opens.
 *
 * In Android, an Activity is the entry point for a screen. Everything you see
 * is drawn by Jetpack Compose, a toolkit where the UI is described by ordinary
 * Kotlin functions marked `@Composable`, so this class has almost nothing to
 * do: it just hands the screen over to Compose.
 */
class MainActivity : ComponentActivity() {

    /**
     * Called by Android once, when the screen is created.
     *
     * [savedInstanceState] holds state Android saved if the screen is being
     * recreated (for example after the phone is rotated). We do not need it
     * directly, but we must pass it up to the parent class.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the status bar and the navigation bar so the theme's
        // background colour fills the whole screen, like a terminal would.
        // (HomeScreen adds padding so text never sits under the bars.)
        enableEdgeToEdge()

        // `setContent` gives Compose the root of the screen. Everything from
        // here down is `@Composable` functions.
        setContent {
            HomeScreen()
        }
    }
}
