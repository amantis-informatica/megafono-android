package com.amantis.megafono

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Mantiene el audio vivo cuando la app no esta en pantalla.
 *
 * Sin esto Android corta el microfono a los pocos segundos de minimizar:
 * desde Android 9 una app en segundo plano no puede grabar. El servicio en
 * primer plano con la notificacion es la unica via legitima.
 */
class MegafonoService : Service() {

    companion object {
        const val CANAL = "megafono"
        const val ID_AVISO = 1

        const val ACCION_PARAR = "com.amantis.megafono.PARAR"

        fun arrancar(contexto: Context) {
            val i = Intent(contexto, MegafonoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                contexto.startForegroundService(i)
            } else {
                contexto.startService(i)
            }
        }

        fun detener(contexto: Context) {
            contexto.stopService(Intent(contexto, MegafonoService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_PARAR) {
            // El usuario pulso "Parar" en la notificacion.
            Megafono.motor?.parar()
            stopSelf()
            return START_NOT_STICKY
        }

        crearCanal()
        startForeground(ID_AVISO, construirAviso())
        // No queremos que el sistema lo reviva solo: si se cayo, que el
        // usuario decida. Revivir sin querer seria abrir el micro a solas.
        return START_NOT_STICKY
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val gestor = getSystemService(NotificationManager::class.java)
        if (gestor.getNotificationChannel(CANAL) != null) return

        val canal = NotificationChannel(
            CANAL,
            "Megafono en marcha",
            NotificationManager.IMPORTANCE_LOW
        )
        canal.description = "Avisa mientras el microfono esta abierto."
        canal.setShowBadge(false)
        canal.enableVibration(false)
        canal.setSound(null, null)
        gestor.createNotificationChannel(canal)
    }

    private fun construirAviso(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val parar = PendingIntent.getService(
            this, 1,
            Intent(this, MegafonoService::class.java).setAction(ACCION_PARAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val constructor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CANAL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return constructor
            .setContentTitle("Megafono Amantis")
            .setContentText("Microfono abierto y sonando")
            .setSmallIcon(R.drawable.ic_aviso)
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(
                // Con null aqui Kotlin no sabe si es el constructor de int o el
                // de Icon y no compila. Le damos el icono explicito.
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_aviso),
                    "Parar",
                    parar
                ).build()
            )
            .build()
    }
}
