package com.studycloud.gate

/**
 * 与服务端同步协议对应的数据结构，放在纯 Kotlin 模块里，
 * 这样协议解析逻辑也能脱离 Android 框架做单元测试。
 */
data class SyncManifest(
    val serverTimeMillis: Long,
    val rules: RuleManifestEntry?,
    val banks: List<BankManifestEntry>,
    val nextSyncAfterSeconds: Int,
    val fingerprint: String,
)

data class RuleManifestEntry(val profileId: String, val version: Int)

data class BankManifestEntry(
    val bankId: String,
    val name: String,
    val version: Int,
    val questionCount: Int,
    val checksum: String,
)

/** 设备本地已缓存的版本。 */
data class LocalSyncState(
    val ruleVersion: Int?,
    val bankVersions: Map<String, Int>,
)

/** 对比清单与本地状态，得出要做什么。 */
data class SyncPlan(
    val refreshRules: Boolean,
    val banksToFetch: List<BankFetch>,
    val bankIdsToDrop: List<String>,
) {
    val isUpToDate: Boolean get() = !refreshRules && banksToFetch.isEmpty() && bankIdsToDrop.isEmpty()
}

data class BankFetch(val bankId: String, val sinceVersion: Int, val targetVersion: Int) {
    val isFullSync: Boolean get() = sinceVersion == 0
}

object SyncPlanner {
    /**
     * 本地版本高于服务端（回滚、改库）时退回全量，避免本地停留在不存在的版本上。
     */
    fun plan(manifest: SyncManifest, local: LocalSyncState): SyncPlan {
        val refreshRules = manifest.rules != null && manifest.rules.version != local.ruleVersion

        val fetches = manifest.banks.mapNotNull { bank ->
            val localVersion = local.bankVersions[bank.bankId]

            when {
                localVersion == null -> BankFetch(bank.bankId, 0, bank.version)
                localVersion > bank.version -> BankFetch(bank.bankId, 0, bank.version)
                localVersion < bank.version -> BankFetch(bank.bankId, localVersion, bank.version)
                else -> null
            }
        }

        val serverBankIds = manifest.banks.map { it.bankId }.toSet()
        val drops = local.bankVersions.keys.filterNot { it in serverBankIds }

        return SyncPlan(refreshRules, fetches, drops)
    }

    /**
     * 整批落库后校验内容校验和；不一致说明增量拉取丢了东西，退回全量。
     * 计算方式必须与服务端 `Checksum.ForQuestions` 一致。
     */
    fun needsFullResync(expectedChecksum: String, actualChecksum: String): Boolean =
        expectedChecksum.isNotEmpty() && !expectedChecksum.equals(actualChecksum, ignoreCase = true)
}
