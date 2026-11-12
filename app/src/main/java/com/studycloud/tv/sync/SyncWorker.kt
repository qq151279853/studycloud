package com.studycloud.tv.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.studycloud.gate.BankManifestEntry
import com.studycloud.gate.LocalSyncState
import com.studycloud.gate.RuleManifestEntry
import com.studycloud.gate.SyncManifest
import com.studycloud.gate.SyncPlanner
import com.studycloud.tv.data.ApiClient
import com.studycloud.tv.data.ApiException
import com.studycloud.tv.data.LocalStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 周期同步：清单对比 → 增量拉题 → 上报事件 → 拉取批改结果。
 *
 * 题库写入必须是**事务性替换**：整批拉完、校验和通过后，在一个事务里替换该题库的题目。
 * 中途失败保留旧数据，孩子不会突然无题可答。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val api: ApiClient,
    private val store: LocalStore,
    private val onRulesChanged: () -> Unit = {},
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.IO) { runSync() }
        Result.success()
    } catch (_: IOException) {
        Result.retry()
    } catch (error: ApiException) {
        // 4xx 重试没有意义；登录态问题由 ApiClient 的回调处理
        if (error.status in 400..499) Result.failure() else Result.retry()
    }

    private fun runSync() {
        val manifest = parseManifest(api.getManifest())
        val local = LocalSyncState(store.ruleVersion(), store.bankVersions())
        val plan = SyncPlanner.plan(manifest, local)

        if (plan.refreshRules) {
            val rules = api.getRules()
            store.saveRules(rules.getInt("version"), rules.getJSONObject("payload").toString())
            // 已开始的一组任务用旧规则，下一组生效
            onRulesChanged()
        }

        for (fetch in plan.banksToFetch) {
            syncBank(fetch.bankId, fetch.sinceVersion, fetch.targetVersion)
        }

        store.removeBanks(plan.bankIdsToDrop)

        uploadPendingEvents()

        api.heartbeat(
            JSONObject()
                .put("ruleVersion", store.ruleVersion())
                .put("appVersion", store.appVersion())
                .put("bankVersions", JSONObject(store.bankVersions() as Map<*, *>)),
        )
    }

    private fun syncBank(bankId: String, sinceVersion: Int, targetVersion: Int) {
        val collected = mutableListOf<JSONObject>()
        val removed = mutableListOf<String>()
        var offset = 0
        var checksum = ""

        while (true) {
            val page = api.getQuestions(bankId, sinceVersion, offset, PAGE_SIZE)
            checksum = page.optString("checksum")

            val questions = page.getJSONArray("questions")
            for (index in 0 until questions.length()) {
                collected += questions.getJSONObject(index)
            }

            val tombstones = page.getJSONArray("removedQuestionIds")
            for (index in 0 until tombstones.length()) {
                removed += tombstones.getString(index)
            }

            if (page.isNull("nextOffset")) break
            offset = page.getInt("nextOffset")
        }

        store.applyBankDelta(
            bankId = bankId,
            version = targetVersion,
            fullSync = sinceVersion == 0,
            upserts = collected,
            removedIds = removed,
        )

        if (SyncPlanner.needsFullResync(checksum, store.checksumOf(bankId))) {
            // 增量拉取丢了东西，退回全量；只在这里重试一次，避免死循环。
            if (sinceVersion != 0) {
                syncBank(bankId, 0, targetVersion)
            } else {
                store.markBankCorrupt(bankId)
            }
        }
    }

    private fun uploadPendingEvents() {
        while (true) {
            val batch = store.takePendingEvents(EVENT_BATCH_SIZE)
            if (batch.isEmpty()) return

            val payload = JSONObject().put("events", org.json.JSONArray(batch.map { it.payload }))
            api.uploadEvents(payload)

            store.markEventsUploaded(batch.map { it.clientEventId })
        }
    }

    private fun parseManifest(json: JSONObject): SyncManifest {
        val rules = json.optJSONObject("rules")?.let {
            RuleManifestEntry(it.getString("profileId"), it.getInt("version"))
        }

        val banks = json.getJSONArray("banks").let { array ->
            (0 until array.length()).map { index ->
                val bank = array.getJSONObject(index)
                BankManifestEntry(
                    bankId = bank.getString("bankId"),
                    name = bank.getString("name"),
                    version = bank.getInt("version"),
                    questionCount = bank.getInt("questionCount"),
                    checksum = bank.getString("checksum"),
                )
            }
        }

        return SyncManifest(
            serverTimeMillis = System.currentTimeMillis(),
            rules = rules,
            banks = banks,
            nextSyncAfterSeconds = json.optInt("nextSyncAfterSeconds", 900),
            fingerprint = json.optString("fingerprint"),
        )
    }

    companion object {
        private const val PAGE_SIZE = 200
        private const val EVENT_BATCH_SIZE = 200
        private const val UNIQUE_NAME = "studycloud-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
