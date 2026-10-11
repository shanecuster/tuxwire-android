package com.shanetalkstech.tuxwire

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shanetalkstech.tuxwire.ui.AppRoot

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
     * Text another app shared to tuxwire (see the Share intent filter in the
     * manifest), waiting for the screen to act on it; `null` when there is
     * none. It is Compose state (`mutableStateOf`), so the screen notices when
     * it changes, including when a share arrives while the app is already open.
     */
    private var sharedText by mutableStateOf<String?>(null)

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
        // (AppRoot adds padding so text never sits under the bars.)
        enableEdgeToEdge()

        // If the app was started by the Share menu, pick up what was shared.
        // `savedInstanceState == null` means this is a fresh start, not the
        // screen being rebuilt after a rotation: without that check, rotating
        // the phone would open the add-feed form all over again.
        if (savedInstanceState == null) {
            takeSharedText(intent)
        }

        // `setContent` gives Compose the root of the screen. Everything from
        // here down is `@Composable` functions.
        setContent {
            AppRoot(
                sharedText = sharedText,
                onSharedTextHandled = { sharedText = null },
            )
        }
    }

    /**
     * Called by Android when something is shared to tuxwire while it is
     * already running (the manifest makes this the only tuxwire screen, so a
     * second share reuses it instead of opening another).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Remember the newest intent, then pick up what it carries.
        setIntent(intent)
        takeSharedText(intent)
    }

    /**
     * Reads shared text out of [intent], if it is a Share ("send") of plain
     * text, and stores it in [sharedText]. Any other kind of intent (such as
     * the ordinary launch from the app drawer) is ignored.
     */
    private fun takeSharedText(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            // The text may be any kind of character sequence; `toString` makes
            // it an ordinary string.
            sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        }
    }
}
