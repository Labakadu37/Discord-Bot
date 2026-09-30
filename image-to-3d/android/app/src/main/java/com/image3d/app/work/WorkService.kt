package com.image3d.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.image3d.app.MainActivity
import com.image3d.app.ai.GenerationSettings
import com.image3d.app.ai.Generator
import com.image3d.app.ai.ImagePrep
import com.image3d.app.ai.ModelStore
import com.image3d.app.ai.Quality
import com.image3d.app.mesh.Mesh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** État de la tâche en cours, observé par l'interface. */
sealed interface JobState {
    data object Idle : JobState
    data class Downloading(val progress: ModelStore.Progress) : JobState
    data class Generating(val stage: Generator.Stage, val fraction: Float?, val startedAt: Long) : JobState
    data class Done(val creationId: String) : JobState
    data class DownloadDone(val quality: Quality) : JobState
    data class Failed(val message: String) : JobState
}

object Jobs {
    internal val mutable = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = mutable

    /** Modèle 3D grossier affiché pendant que la version détaillée se calcule. */
    internal val mutablePreview = MutableStateFlow<Mesh?>(null)
    val preview: StateFlow<Mesh?> = mutablePreview
    val busy get() = mutable.value is JobState.Downloading || mutable.value is JobState.Generating

    fun acknowledge() {
        if (!busy) mutable.value = JobState.Idle
    }

    fun download(ctx: Context, q: Quality) = start(ctx, Intent(ctx, WorkService::class.java).setAction(ACTION_DOWNLOAD).putExtra("quality", q.name))

    /** [prepared] : image 512x512 déjà détourée et cadrée (celle de l'aperçu). */
    fun generate(ctx: Context, prepared: File, s: GenerationSettings) = start(
        ctx,
        Intent(ctx, WorkService::class.java).setAction(ACTION_GENERATE)
            .putExtra("image", prepared.absolutePath)
            .putExtra("resolution", s.resolution)
            .putExtra("smooth", s.smooth)
            .putExtra("quality", s.quality.name),
    )

    fun cancel(ctx: Context) = start(ctx, Intent(ctx, WorkService::class.java).setAction(ACTION_CANCEL))

    private fun start(ctx: Context, i: Intent) = ContextCompat.startForegroundService(ctx, i)

    internal const val ACTION_DOWNLOAD = "download"
    internal const val ACTION_GENERATE = "generate"
    internal const val ACTION_CANCEL = "cancel"
}

/**
 * Service de premier plan : le téléchargement et la génération continuent même si l'écran
 * s'éteint ou si l'on change d'application (notification avec la progression).
 */
class WorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Préparation…", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        when (intent?.action) {
            Jobs.ACTION_CANCEL -> {
                job?.cancel()
                if (job == null) stop()
            }
            Jobs.ACTION_DOWNLOAD -> if (job?.isActive != true) {
                val q = Quality.valueOf(intent.getStringExtra("quality")!!)
                launchJob {
                    ModelStore.download(this, q) { p ->
                        Jobs.mutable.value = JobState.Downloading(p)
                        val label = if (p.verifying) "Vérification de ${p.file}" else "Téléchargement de l'IA (${p.index + 1}/${p.count})"
                        notifyThrottled(label, p.done.toFloat() / p.total)
                    }
                    ModelStore.setQuality(this, q)
                    JobState.DownloadDone(q)
                }
            }
            Jobs.ACTION_GENERATE -> if (job?.isActive != true) {
                val settings = GenerationSettings(
                    resolution = intent.getIntExtra("resolution", 192),
                    smooth = intent.getBooleanExtra("smooth", true),
                    quality = Quality.valueOf(intent.getStringExtra("quality")!!),
                )
                val image = File(intent.getStringExtra("image")!!)
                val started = System.currentTimeMillis()
                Jobs.mutablePreview.value = null
                Jobs.mutable.value = JobState.Generating(Generator.Stage.ENCODE, null, started)
                launchJob(wakeLock = true) {
                    val bmp = ImagePrep.loadFile(image)
                    val id = Generator(this).generate(bmp, settings, onPreview = { Jobs.mutablePreview.value = it }) { p ->
                        Jobs.mutable.value = JobState.Generating(p.stage, p.fraction, started)
                        notifyThrottled(p.stage.label, p.fraction)
                    }
                    JobState.Done(id)
                }
            }
            else -> if (job?.isActive != true) stop()
        }
        return START_NOT_STICKY
    }

    private fun launchJob(wakeLock: Boolean = false, block: suspend () -> JobState) {
        val lock = if (wakeLock) {
            (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "image3d:generation").apply { acquire(60 * 60 * 1000L) }
        } else null
        job = scope.launch {
            val result = try {
                block()
            } catch (e: CancellationException) {
                JobState.Failed("Annulé")
            } catch (e: OutOfMemoryError) {
                JobState.Failed("Mémoire insuffisante : choisis un niveau de détail plus bas ou la qualité Standard")
            } catch (e: Throwable) {
                // Une annulation pendant un calcul ONNX remonte comme une erreur d'ONNX Runtime
                if (!isActive) JobState.Failed("Annulé") else JobState.Failed(e.message ?: e.javaClass.simpleName)
            }
            Jobs.mutable.value = result
            Jobs.mutablePreview.value = null
            lock?.let { if (it.isHeld) it.release() }
            finalNotification(result)
            stop()
        }
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun notifyThrottled(text: String, fraction: Float?) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        manager().notify(NOTIF_ID, notification(text, fraction))
    }

    private fun finalNotification(s: JobState) {
        val text = when (s) {
            is JobState.Done -> "Ton modèle 3D est prêt !"
            is JobState.DownloadDone -> "L'IA est installée, tout fonctionne hors ligne"
            is JobState.Failed -> "Échec : ${s.message}"
            else -> return
        }
        manager().notify(NOTIF_ID, builder().setContentText(text).setOngoing(false).setAutoCancel(true).build())
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun builder(): NotificationCompat.Builder {
        val nm = manager()
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Génération 3D", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Image 3D")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
    }

    private fun notification(text: String, fraction: Float?) = builder()
        .setContentText(text)
        .setOngoing(true)
        .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
        .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "work"
        private const val NOTIF_ID = 1
    }
}
