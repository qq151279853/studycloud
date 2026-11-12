package com.studycloud.tv

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import android.os.SystemClock
import com.studycloud.gate.GateEngine
import com.studycloud.gate.MonotonicClock
import com.studycloud.gate.RuleSnapshot
import com.studycloud.tv.data.ApiClient
import com.studycloud.tv.data.CredentialStore
import com.studycloud.tv.data.LocalStore
import com.studycloud.tv.data.RuleMapper
import com.studycloud.tv.sync.SyncWorker

class StudyCloudApp : Application(), Configuration.Provider {

    val appVersion: String get() = BuildConfig.VERSION_NAME

    val credentials: CredentialStore by lazy { CredentialStore(this) }

    val store: LocalStore by lazy { LocalStore(this, appVersion) }

    /**
     * 账本与弹题状态机。时长累计用单调时钟——孩子改系统时间不影响计时。
     */
    val gate: GateEngine by lazy {
        GateEngine(
            store = store,
            clock = MonotonicClock { SystemClock.elapsedRealtime() },
            rules = RuleMapper.parse(store.rulePayload()) ?: RuleSnapshot(),
        )
    }

    /** 同步拉到新规则后调用，下一组任务生效。 */
    fun reloadRules() {
        RuleMapper.parse(store.rulePayload())?.let(gate::updateRules)
    }

    val api: ApiClient by lazy {
        ApiClient(
            baseUrl = BuildConfig.API_BASE_URL,
            credentials = credentials,
            onSessionLost = {
                // 登录态失效不清本地题库与规则：孩子仍可离线答题，家长重新登录即可恢复同步。
            },
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(SyncWorkerFactory(api, store, ::reloadRules))
            .build()
}

/** SyncWorker 需要注入 ApiClient 与 LocalStore，WorkManager 默认工厂做不到。 */
private class SyncWorkerFactory(
    private val api: ApiClient,
    private val store: LocalStore,
    private val onRulesChanged: () -> Unit,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters, api, store, onRulesChanged)
        else -> null
    }
}
