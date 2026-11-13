package tn.isetbizerte.tp4_alertes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private var permissionResult: MethodChannel.Result? = null
    private lateinit var canalAlerte: MethodChannel
    private var actionEnAttente: Map<String, Any?>? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Alertes.creerCanal(this)

        val messager = flutterEngine.dartExecutor.binaryMessenger
        canalAlerte = MethodChannel(messager, "alerte/notifier")
        actionEnAttente = lireAction(intent)          // lancée par une notification ?
        canalAlerte.setMethodCallHandler { call, result ->
            when (call.method) {
                "demanderPermission" -> {
                    if (Alertes.autorisee(this)) {
                        result.success(true)
                    } else if (Build.VERSION.SDK_INT >= 33) {
                        permissionResult = result
                        requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                        )
                    } else {
                        result.success(false)
                    }
                }
                "notifier" -> {
                    if (!Alertes.autorisee(this)) {
                        result.error("PERMISSION_REFUSEE", "Notifications non autorisées", null)
                    } else {
                        val titre = call.argument<String>("titre") ?: "Alerte"
                        val texte = call.argument<String>("texte") ?: ""
                        result.success(Alertes.afficher(this, titre, texte))
                    }
                }
                "actionInitiale" -> {
                    result.success(actionEnAttente)            // null si lancement normal
                    actionEnAttente = null                     // on ne la donne qu'une fois
                }
                else -> result.notImplemented()
            }
        }

        // Étape 4 : canal "service/surveillance"
        MethodChannel(messager, "service/surveillance").setMethodCallHandler(ControleService(this))

        // Étape 5 : canal "sync/planifier" (WorkManager : batterie)
        MethodChannel(messager, "sync/planifier").setMethodCallHandler(Planificateur(this))
    }

    // Application déjà ouverte : Android lui remet le nouvel intent
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lireAction(intent)?.let { canalAlerte.invokeMethod("surAction", it) }   // Kotlin → Dart
    }

    // Extrait l'action d'un intent de notification, et ferme cette notification
    private fun lireAction(intent: Intent?): Map<String, Any?>? {
        val action = intent?.getStringExtra(Alertes.EXTRA_ACTION) ?: return null
        val id = intent.getIntExtra(Alertes.EXTRA_ID, 0)
        Alertes.fermer(this, id)        // un bouton d'action ne ferme pas la notification seul
        return mapOf("action" to action, "titre" to intent.getStringExtra(Alertes.EXTRA_TITRE), "id" to id)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            permissionResult?.success(
                grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            )
            permissionResult = null
        }
    }
}