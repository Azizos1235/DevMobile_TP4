package tn.isetbizerte.tp4_alertes

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*

class ServiceSurveillance : Service() {

    // Portée liée à la vie du service (concurrence structurée, cours Ch.2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null      // service « démarré », pas « lié »

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null si Android a redémarré le service après l'avoir tué (START_STICKY)
        val seuil = intent?.getDoubleExtra(EXTRA_SEUIL, 40.0) ?: 40.0

        creerCanalService()
        Alertes.creerCanal(this)
        ServiceCompat.startForeground(              // obligatoire dans les 10 s
            this, ID_NOTIF, notificationEnCours("Démarrage…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        job?.cancel()                               // nouveau seuil : on relance la boucle
        job = scope.launch {
            var enAlerte = false
            while (isActive) {
                val t = temperatureBatterie()
                Log.d("TP4", "Surveillance : $t °C (seuil $seuil °C)")
                mettreAJour("Batterie : %.1f °C (seuil %.1f °C)".format(t, seuil))
                if (t >= seuil && !enAlerte) {      // une alerte au franchissement, pas toutes les 10 s
                    if (Alertes.autorisee(this@ServiceSurveillance)) {
                        Alertes.afficher(this@ServiceSurveillance, "Température élevée",
                            "Batterie à %.1f °C (seuil %.1f °C)".format(t, seuil))
                    }
                    enAlerte = true
                } else if (t < seuil) {
                    enAlerte = false                // repasse sous le seuil : réarmé
                }
                delay(10_000)                       // suspend, ne bloque pas
            }
        }
        return START_STICKY                         // tué faute de mémoire ? Android le relance
    }

    override fun onDestroy() {
        scope.cancel()                              // stopService → la boucle s'arrête
        super.onDestroy()
    }

    private fun temperatureBatterie(): Double {
        // ACTION_BATTERY_CHANGED est « collant » : on lit la dernière valeur sans s'abonner
        val etat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (etat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0  // en dixièmes de °C
    }

    private fun creerCanalService() {
        val canal = NotificationChannel(CANAL_SERVICE, "Surveillance", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun notificationEnCours(texte: String): Notification =
        NotificationCompat.Builder(this, CANAL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Surveillance active")
            .setContentText(texte)
            .setOngoing(true)                       // ne se balaie pas
            .setOnlyAlertOnce(true)                 // pas de son à chaque mise à jour
            .build()

    @SuppressLint("MissingPermission")
    private fun mettreAJour(texte: String) {
        if (Alertes.autorisee(this)) {
            NotificationManagerCompat.from(this).notify(ID_NOTIF, notificationEnCours(texte))  // même id = remplace
        }
    }

    companion object {
        const val EXTRA_SEUIL = "seuil"
        private const val CANAL_SERVICE = "surveillance"
        private const val ID_NOTIF = 1
    }
}

// Le canal "service/surveillance" : Flutter démarre ou arrête le service
class ControleService(private val context: Context) : MethodChannel.MethodCallHandler {
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val intent = Intent(context, ServiceSurveillance::class.java)
        when (call.method) {
            "demarrer" -> {
                intent.putExtra(ServiceSurveillance.EXTRA_SEUIL, call.argument<Double>("seuil") ?: 40.0)
                ContextCompat.startForegroundService(context, intent)
                result.success(null)
            }
            "arreter" -> {
                context.stopService(intent)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }
}
