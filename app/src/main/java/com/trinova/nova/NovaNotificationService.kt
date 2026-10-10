package com.trinova.nova

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class Msg(val pkg: String, val title: String, val text: String, val reply: Notification.Action?)

object Inbox {
    @Volatile var connected = false
    val items = java.util.concurrent.CopyOnWriteArrayList<Msg>()

    fun reply(ctx: Context, m: Msg, text: String): Boolean {
        val a = m.reply ?: return false
        val inputs = a.remoteInputs ?: return false
        return try {
            val b = Bundle(); b.putCharSequence(inputs[0].resultKey, text)
            val i = Intent(); RemoteInput.addResultsToIntent(inputs, i, b)
            a.actionIntent.send(ctx, 0, i); items.remove(m); true
        } catch (e: PendingIntent.CanceledException) { false }
    }
}

class NovaNotificationService : NotificationListenerService() {
    override fun onListenerConnected() { Inbox.connected = true }
    override fun onListenerDisconnected() { Inbox.connected = false }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (sbn.packageName == packageName) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val reply = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        Inbox.items.removeAll { it.pkg == sbn.packageName && it.title == title }
        Inbox.items.add(0, Msg(sbn.packageName, title, text, reply))
        while (Inbox.items.size > 30) Inbox.items.removeAt(Inbox.items.size - 1)
    }
}
