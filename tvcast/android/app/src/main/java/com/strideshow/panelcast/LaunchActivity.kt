package com.strideshow.panelcast

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Entry point. Routes to the whiteboard (default) or the classic
 * single-window receiver, per the on-device setting. Kept as a separate,
 * theme-less activity so the choice can change without reinstalling and
 * without either real activity needing to know about the other.
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (Prefs(this).classicMode) MainActivity::class.java else BoardActivity::class.java
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
