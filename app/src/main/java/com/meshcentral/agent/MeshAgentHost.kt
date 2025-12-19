package com.meshcentral.agent

import android.content.Context
import android.content.Intent
import android.content.IntentSender

interface MeshAgentHost {
    val context: Context

    fun agentStateChanged()

    fun refreshInfo()

    fun returnToMainScreen()

    fun openUrl(url: String): Boolean

    fun showAlertMessage(title: String, msg: String)

    fun showToastMessage(msg: String)

    fun startProjection()

    fun stopProjection()

    fun runOnUiThread(action: () -> Unit)

    fun launchActivity(intent: Intent)

    fun launchIntentSenderForResult(intentSender: IntentSender, pad: PendingActivityData)
}
