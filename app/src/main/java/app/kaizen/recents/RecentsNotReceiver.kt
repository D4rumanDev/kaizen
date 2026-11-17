package app.kaizen.recents

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kaizen.database.CacheDB
import app.kaizen.jobscheduler.RecentsWork
import app.kaizen.pojos.NotificationObj
import app.kaizen.commons.background

class RecentsNotReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notificationDAO = CacheDB.INSTANCE.notificationDAO()
        if (intent.getIntExtra("mode", 0) == 1)
            removeAll(context)
        else {
            background {
                notificationDAO.delete(NotificationObj.fromIntent(intent))
                if (notificationDAO.getByType(NotificationObj.RECENT).isEmpty())
                    context.getSystemService(NotificationManager::class.java).cancel(RecentsWork.KEY_SUMMARY)
            }
        }
    }

    companion object {
        fun removeAll(context: Context) {
            background {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notificationDAO = CacheDB.INSTANCE.notificationDAO()
                for (obj in notificationDAO.all)
                    manager.cancel(obj.key)
                notificationDAO.clear()
                manager.cancel(RecentsWork.KEY_SUMMARY)
            }
        }
    }
}
