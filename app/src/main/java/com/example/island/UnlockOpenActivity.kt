package com.example.island

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * An invisible screen used to open an app from the lock screen: it asks Android to unlock the phone (the PIN, pattern
 * or fingerprint prompt), then opens the music app and closes itself.
 */
class UnlockOpenActivity : Activity() {
    private var asked = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        IslandBus.tapDebug = "unlock: screen opened"
    }

    // Asking before the screen is really up (in onCreate) is refused, so it waits until this one is showing.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !asked) {
            asked = true
            handler.postDelayed({ ask() }, 150)
        }
    }

    private fun ask() {
        if (isFinishing) return
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            IslandBus.tapDebug = "unlock: already unlocked, opening"
            IslandBus.openMusicAppNow()
            finish()
            return
        }
        km.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    IslandBus.tapDebug = "unlock: succeeded, opening"
                    handler.postDelayed({
                        IslandBus.openMusicAppNow()
                        finish()
                    }, 100)
                }

                override fun onDismissCancelled() {
                    IslandBus.tapDebug = "unlock: cancelled"
                    finish()
                }

                override fun onDismissError() {
                    IslandBus.tapDebug = "unlock: error (Android refused to show the unlock screen)"
                    finish()
                }
            },
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        fun start(context: android.content.Context) {
            context.startActivity(
                Intent(context, UnlockOpenActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            )
        }
    }
}