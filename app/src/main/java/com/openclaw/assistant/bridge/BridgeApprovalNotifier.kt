package com.openclaw.assistant.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Posts a high-priority notification that opens [BridgeApprovalActivity] so the
 * user can approve or deny a pending bridge action. The notification body
 * intentionally omits arguments to avoid leaking sensitive payload contents to
 * the lock screen — the full request is shown only inside the Activity.
 */
internal object BridgeApprovalNotifier {
    private const val CHANNEL_ID = "agent_voice_bridge_approval"

    fun notify(context: Context, requestId: String, capability: String) {
        ensureChannel(context)
        val intent = Intent(context, BridgeApprovalActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(BridgeApprovalActivity.EXTRA_REQUEST_ID, requestId)
        val pi = PendingIntent.getActivity(
            context, requestId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Mobile Bridge action requested")
            .setContentText("Tap to approve or deny: $capability")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(requestId.hashCode(), n)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bridge approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Approve or deny Mobile Bridge actions"
                }
            )
        }
    }
}
