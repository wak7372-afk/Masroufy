package om.masroofi.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import om.masroofi.core.*
import java.time.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val inputExecutor = Executors.newSingleThreadExecutor()
fun inputPreferences(context: Context) = context.getSharedPreferences("input", Context.MODE_PRIVATE)

class BankSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !inputPreferences(context).getBoolean("sms", false)) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) return
        val text = parts.joinToString("") { it.messageBody.orEmpty() }
        val received = parts.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val id = "${fingerprint(parts.first().originatingAddress?.trim()?.uppercase() ?: "unknown")}:$received:${fingerprint(text)}"
        val pending = goAsync()
        inputExecutor.execute {
            try {
                LedgerRepository.get(context).update { it.importMessage(text, received, "sms", id) }
                NoticeCenter.flush(context)
            } catch (_: Exception) { NoticeCenter.storageFailure(context) }
            finally { pending.finish() }
        }
    }
}

class BankNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.packageName !in inputPreferences(this).getStringSet("apps", emptySet()).orEmpty()) return
        val extras = sbn.notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        // Stable content group, distinct post events. Ambiguous re-posts remain
        // visible for review rather than being erased or silently double-counted.
        val id = "${fingerprint("${sbn.key}|$text")}:${sbn.postTime}"
        inputExecutor.execute {
            try {
                LedgerRepository.get(this).update { it.importMessage(text, sbn.postTime, "notification", id) }
                NoticeCenter.flush(this)
            } catch (_: Exception) { NoticeCenter.storageFailure(this) }
        }
    }
}

class CategoryActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("transaction") ?: return
        val category = intent.getStringExtra("category") ?: return
        val pending = goAsync()
        inputExecutor.execute {
            try {
                LedgerRepository.get(context).update { it.assignCategory(id, category, false) }
                context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
                NoticeCenter.flush(context)
            } finally { pending.finish() }
        }
    }
}

@SuppressLint("MissingPermission")
object NoticeCenter {
    private fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("transactions", "تصنيف العمليات", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("budgets", "حدود الإنفاق", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel("reminders", "تذكير المراجعة", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun open(context: Context, id: String? = null): PendingIntent = PendingIntent.getActivity(context, id?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java).putExtra("review", id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notice(context: Context, channel: String, title: String, body: String): NotificationCompat.Builder {
        val public = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notice).setContentTitle("مصروفي").setContentText("افتح التطبيق لمراجعة عملياتك").build()
        return NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notice).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open(context)).setAutoCancel(true)
            .setVisibility(if (LedgerRepository.get(context).states.value.preferences.showAmountsOnLockScreen) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
    }
    @Synchronized fun flush(context: Context) {
        channels(context)
        if (!allowed(context) || !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return
        val repo = LedgerRepository.get(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        repo.states.value.pendingTransactionNotices.toList().forEach { id ->
            val txn = repo.states.value.transactions.find { it.id == id } ?: return@forEach
            val amount = txn.accountingBaisa?.let { "${Money.format(it)} ر.ع" } ?: "مبلغ يحتاج مراجعة"
            val title = txn.counterparty ?: if (txn.direction == "in") "عملية واردة" else "عملية جديدة"
            val body = "$amount • ${if (txn.duplicateOf != null) "تكرار محتمل؛ راجع قبل الاحتساب" else if (txn.effect == "unresolved" || !txn.financialVerified) "حدد نوع العملية قبل احتسابها" else "أكد الفئة أو غيّرها"}"
            val builder = notice(context, "transactions", title, body).setContentIntent(open(context, id))
            val suggested = if (txn.financialVerified && txn.effect != "unresolved" && txn.duplicateOf == null)
                (listOfNotNull(txn.categoryId) + listOf("fuel", "telecom", "groceries")).distinct().take(2) else emptyList()
            suggested.forEach { cat ->
                val category = repo.states.value.categories.find { it.id == cat } ?: return@forEach
                val action = PendingIntent.getBroadcast(context, (id + cat).hashCode(), Intent(context, CategoryActionReceiver::class.java).putExtra("transaction", id).putExtra("category", cat), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(0, category.name, action)
            }
            builder.addAction(0, "أخرى", open(context, id))
            manager.notify(id.hashCode(), builder.build())
            repo.update { it.acknowledgeTransactionNotice(id) }
        }
        repo.update { it.takeBudgetAlerts(System.currentTimeMillis()) }
        repo.states.value.pendingAlerts.toList().forEach { alert ->
            manager.notify(alert.key.hashCode(), notice(context, "budgets", alert.title, alert.body).build())
            repo.update { it.acknowledgeBudgetAlert(alert.key) }
        }
    }
    fun reminder(context: Context) {
        if (!allowed(context)) return
        val count = LedgerRepository.get(context).states.value.transactions.count { !it.reviewed }
        if (count > 0) context.getSystemService(NotificationManager::class.java).notify(6001,
            notice(context, "reminders", "مراجعة عملياتك", "لديك $count عمليات تحتاج مراجعة").build())
    }
    fun storageFailure(context: Context) {
        channels(context)
        if (allowed(context)) context.getSystemService(NotificationManager::class.java).notify(6002,
            notice(context, "reminders", "تعذر حفظ عملية", "افتح مصروفي للتحقق من التخزين المحلي").build())
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val repo = LedgerRepository.get(applicationContext)
        if (repo.states.value.preferences.remindersEnabled && repo.loadError == null) {
            NoticeCenter.flush(applicationContext); NoticeCenter.reminder(applicationContext)
        }
        return Result.success()
    }
}
fun scheduleReminder(context: Context, preferences: Preferences) {
    val manager = WorkManager.getInstance(context)
    if (!preferences.remindersEnabled) { manager.cancelUniqueWork("daily-review"); return }
    val zone = ZoneId.of(preferences.timeZone)
    val now = ZonedDateTime.now(zone)
    var next = now.toLocalDate().atTime(preferences.reminderHour, 0).atZone(zone)
    if (!next.isAfter(now)) next = next.plusDays(1)
    val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build()
    manager.enqueueUniquePeriodicWork("daily-review", ExistingPeriodicWorkPolicy.UPDATE, request)
}
