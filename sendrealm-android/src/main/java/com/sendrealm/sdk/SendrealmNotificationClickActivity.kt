package com.sendrealm.sdk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

class SendrealmNotificationClickActivity : Activity() {
    private companion object {
        const val TAG = "Sendrealm"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        try {
            Sendrealm.handleNotificationOpen(intent)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to track notification action", error)
        }

        try {
            Sendrealm.openNotificationDestination(this, intent)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to open notification action destination", error)
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
