package com.example.roombalocal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Recibe la alarma del respaldo y los avisos del sistema (reinicio, cambio de hora, actualización de la app)
 * para volver a armar la próxima alarma.
 */
class ScheduleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RUN = "com.example.roombalocal.SCHEDULED_CLEAN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RUN) {
            try {
                context.startForegroundService(Intent(context, ScheduleService::class.java))
            } catch (e: Exception) {
                ScheduleStore.setLastRun(context, ScheduleService.stamp() + " — no se pudo arrancar: ${e.message}")
                Scheduler.reschedule(context)
            }
        } else {
            // BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, MY_PACKAGE_REPLACED, permiso de alarmas exactas…
            Scheduler.reschedule(context)
        }
    }
}
