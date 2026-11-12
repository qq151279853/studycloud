package com.studycloud.gate

/** 设备状态机。语义见《开发文档》6.1。 */
enum class GateState {
    /** 本 App 前台或桌面，不计时 */
    IDLE,

    /** 受控应用前台且亮屏，扣减余额 */
    WATCHING,

    /** 余额耗尽，提示即将答题 */
    PROMPT,

    /** 答题中 */
    QUIZ,

    /** 主观题待批，阻塞发奖 */
    PENDING_REVIEW,

    /** 不在可用时段，或今日额度用尽 */
    LOCKED,
}

/**
 * 当日账本。余额的权威在设备，不在服务器——断网时孩子既不能被永久锁死，
 * 也不能永久解锁。
 */
data class DayLedger(
    val day: String,
    val balanceSeconds: Int = 0,
    val earnedSeconds: Int = 0,
    val usedSeconds: Int = 0,
    val roundsPassed: Int = 0,
)

/** 加练池。跨天、跨重启保留。 */
data class ExtraPool(
    val wrongStreak: Int = 0,
    val dueCount: Int = 0,
)

/** 前台环境快照，由 Android 层采集后传入。 */
data class ForegroundContext(
    val screenOn: Boolean,
    val foregroundPackage: String?,
    val isOwnApp: Boolean,
    val isLauncher: Boolean,
    val isSystemUi: Boolean,
) {
    /** 只有「亮屏 + 受控应用在前台」才消耗余额。 */
    val isConsuming: Boolean
        get() = screenOn && foregroundPackage != null && !isOwnApp && !isLauncher && !isSystemUi

    companion object {
        fun watching(packageName: String = "com.example.player") = ForegroundContext(
            screenOn = true,
            foregroundPackage = packageName,
            isOwnApp = false,
            isLauncher = false,
            isSystemUi = false,
        )

        fun ownApp() = ForegroundContext(
            screenOn = true,
            foregroundPackage = "com.studycloud.tv",
            isOwnApp = true,
            isLauncher = false,
            isSystemUi = false,
        )

        fun screenOff() = ForegroundContext(
            screenOn = false,
            foregroundPackage = null,
            isOwnApp = false,
            isLauncher = false,
            isSystemUi = false,
        )

        fun launcher() = ForegroundContext(
            screenOn = true,
            foregroundPackage = "com.android.launcher",
            isOwnApp = false,
            isLauncher = true,
            isSystemUi = false,
        )
    }
}

/** 一道已作答的题目。 */
data class AnsweredQuestion(
    val questionId: String,
    val correct: Boolean,
    val difficulty: Int,
    val isExtra: Boolean = false,
    val needsReview: Boolean = false,
)

/** 一组任务的进行状态。 */
data class RoundState(
    val roundId: String,
    val answered: List<AnsweredQuestion> = emptyList(),
) {
    val correctCount: Int get() = answered.count { it.correct && !it.isExtra }

    val normalCount: Int get() = answered.count { !it.isExtra }

    /** 当前连续答对的题目，答错即清零。 */
    val streakQuestions: List<AnsweredQuestion>
        get() {
            val streak = mutableListOf<AnsweredQuestion>()

            for (question in answered.filter { !it.isExtra }) {
                if (question.correct) streak += question else streak.clear()
            }

            return streak
        }

    val currentStreak: Int get() = streakQuestions.size

    val hasPendingReview: Boolean get() = answered.any { it.needsReview }

    fun with(answer: AnsweredQuestion) = copy(answered = answered + answer)
}

sealed interface RoundOutcome {
    data object InProgress : RoundOutcome

    data class Passed(val correctQuestions: List<AnsweredQuestion>) : RoundOutcome

    data object Failed : RoundOutcome
}

sealed interface SettleResult {
    /** 已结算过，重复提交不再加时。 */
    data object AlreadySettled : SettleResult

    /** 加练池未清空，不发奖励。 */
    data class BlockedByExtraPool(val dueCount: Int) : SettleResult

    /** 含待批主观题，批改通过后再结算。 */
    data object PendingReview : SettleResult

    /** 发放成功；[rewardSeconds] 可能因当日额度用尽而为 0。 */
    data class Granted(val rewardSeconds: Int, val cappedByDailyLimit: Boolean) : SettleResult
}

/** 账本持久化。Android 层用 SQLite 实现，测试用内存实现。 */
interface GateStore {
    fun loadLedger(): DayLedger

    fun saveLedger(ledger: DayLedger)

    fun loadExtraPool(): ExtraPool

    fun saveExtraPool(pool: ExtraPool)

    fun isSettled(roundId: String): Boolean

    fun markSettled(roundId: String, rewardSeconds: Int)
}

class InMemoryGateStore(
    initialDay: String,
    initialLedger: DayLedger? = null,
    initialPool: ExtraPool = ExtraPool(),
) : GateStore {
    private var ledger: DayLedger = initialLedger ?: DayLedger(day = initialDay)
    private var pool: ExtraPool = initialPool
    private val settlements = mutableMapOf<String, Int>()

    override fun loadLedger(): DayLedger = ledger

    override fun saveLedger(ledger: DayLedger) {
        this.ledger = ledger
    }

    override fun loadExtraPool(): ExtraPool = pool

    override fun saveExtraPool(pool: ExtraPool) {
        this.pool = pool
    }

    override fun isSettled(roundId: String): Boolean = settlements.containsKey(roundId)

    override fun markSettled(roundId: String, rewardSeconds: Int) {
        settlements[roundId] = rewardSeconds
    }
}
