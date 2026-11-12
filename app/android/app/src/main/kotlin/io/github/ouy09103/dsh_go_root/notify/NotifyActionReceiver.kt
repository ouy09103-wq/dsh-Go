package io.github.ouy09103.dsh_go_root.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The approval buttons on chat notifications (not exported: only our own PendingIntents reach it). */
class NotifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ChatNotifier(context.applicationContext).receiveAction(intent)
    }
}
