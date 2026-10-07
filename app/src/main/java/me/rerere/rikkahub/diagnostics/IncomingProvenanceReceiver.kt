package me.rerere.rikkahub.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import me.rerere.ai.diagnostics.IncomingProvenanceSwitch
import me.rerere.ai.diagnostics.IncomingProvenanceTrace

/** DUMP restricts the explicit diagnostic broadcast to privileged callers such as adb shell. */
class IncomingProvenanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "me.rerere.rikkahub.TRACE_NEXT_REQUEST") return
        val conversationId = intent.getStringExtra("conversation_id") ?: return
        try {
            IncomingProvenanceSwitch.arm(conversationId)
            Log.i(IncomingProvenanceTrace.TAG, "armed_next_request ttl_seconds=60")
        } catch (_: IllegalArgumentException) {
            Log.i(IncomingProvenanceTrace.TAG, "invalid_conversation_id")
        }
    }
}
