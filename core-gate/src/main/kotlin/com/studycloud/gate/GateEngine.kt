package com.studycloud.gate

import kotlin.math.abs
import kotlin.math.ceil

/**
 * 单调时钟。**所有时长累计都必须走这里**——孩子改系统时间不应该影响计时。
 * Android 实现返回 `SystemClock.elapsedRealtime()`。
 */
fun interface MonotonicClock {
    fun elapsedRealtimeMillis(): Long
}

/** 需要上报到云端的事件；引擎只负责产出，由上层入队。 */
sealed interface GateEvent {
    data class Usage(val seconds: Int) : GateEvent

    data class RoundPassed(val roundId: String, val rewardSeconds: Int) : GateEvent

    data class ExtraAdded(val count: Int) : GateEvent

    data class DayRollover(val day: String) : GateEvent

    data class ClockDrift(val driftSeconds: Long) : GateEvent
}

/**
 * 时长账本与弹题状态机，纯 Kotlin，不依赖 Android 框架。
 * 对应《开发文档》第 6 章，测试用例见 6.11（T1–T12）。
 */
class GateEngine(
    private val store: GateStore,
    private val clock: MonotonicClock,
    rules: RuleSnapshot,
) {
    var rules: RuleSnapshot = rules
        private set

    private var lastTickElapsed: Long = clock.elapsedRealtimeMillis()

    val pendingEvents: MutableList<GateEvent> = mutableListOf()

    fun updateRules(next: RuleSnapshot) {
        rules = next
    }

    fun ledger(): DayLedger = store.loadLedger()

    fun extraPool(): ExtraPool = store.loadExtraPool()

    /** 零点重置：只清当日账本，**加练池跨天保留**。幂等。 */
    fun ensureToday(moment: LocalMoment) {
        val ledger = store.loadLedger()

        if (ledger.day == moment.day) return

        store.saveLedger(DayLedger(day = moment.day))
        pendingEvents += GateEvent.DayRollover(moment.day)
    }

    /**
     * 每秒驱动一次。超过 [MAX_TICK_SECONDS] 的跨度直接丢弃——
     * 设备休眠后唤醒会产生巨大 delta，不能一次扣光余额。
     */
    fun onTick(context: ForegroundContext) {
        val now = clock.elapsedRealtimeMillis()
        val deltaSeconds = ((now - lastTickElapsed) / 1000L).toInt()
        lastTickElapsed = now

        if (deltaSeconds <= 0 || deltaSeconds > MAX_TICK_SECONDS) return
        if (!context.isConsuming) return

        val ledger = store.loadLedger()
        val consumed = minOf(deltaSeconds, ledger.balanceSeconds)

        if (consumed <= 0) return

        store.saveLedger(
            ledger.copy(
                balanceSeconds = ledger.balanceSeconds - consumed,
                usedSeconds = ledger.usedSeconds + consumed,
            ),
        )

        pendingEvents += GateEvent.Usage(consumed)
    }

    /** 当前应处于哪个状态。 */
    fun evaluate(moment: LocalMoment, context: ForegroundContext): GateState {
        ensureToday(moment)

        val controlled = rules.isControlledAt(moment)

        if (!controlled) {
            return when (rules.scheduleMode) {
                // 自由时段：不扣余额、不弹题
                ScheduleMode.QUIZ_ONLY -> GateState.IDLE
                // 时段外不可用
                ScheduleMode.AVAILABLE_ONLY -> GateState.LOCKED
            }
        }

        val ledger = store.loadLedger()

        if (ledger.balanceSeconds > 0) {
            return if (context.isConsuming) GateState.WATCHING else GateState.IDLE
        }

        if (ledger.earnedSeconds >= rules.dailyCapSeconds) return GateState.LOCKED
        if (ledger.roundsPassed >= rules.roundsPerDay) return GateState.LOCKED

        return GateState.PROMPT
    }

    /** 判定一组任务是否完成。 */
    fun judge(round: RoundState): RoundOutcome = when (rules.playMode) {
        PlayMode.ROUND -> when {
            round.normalCount < rules.questionsPerRound -> RoundOutcome.InProgress
            round.correctCount >= requiredCorrect() -> RoundOutcome.Passed(round.answered.filter { it.correct })
            else -> RoundOutcome.Failed
        }

        PlayMode.STREAK ->
            if (round.currentStreak >= rules.questionsPerRound) {
                RoundOutcome.Passed(round.streakQuestions)
            } else {
                RoundOutcome.InProgress
            }

        PlayMode.FREE -> RoundOutcome.InProgress
    }

    /** 记录一次作答，维护加练池。 */
    fun onAnswered(answer: AnsweredQuestion) {
        val pool = store.loadExtraPool()

        if (answer.correct) {
            if (answer.isExtra && pool.dueCount > 0) {
                store.saveExtraPool(pool.copy(dueCount = pool.dueCount - 1))
            }
            return
        }

        var next = pool.copy(wrongStreak = pool.wrongStreak + 1)

        if (rules.extraEnabled && next.wrongStreak >= rules.wrongThreshold) {
            next = next.copy(wrongStreak = 0, dueCount = next.dueCount + rules.extraQuestions)
            pendingEvents += GateEvent.ExtraAdded(rules.extraQuestions)
        }

        store.saveExtraPool(next)
    }

    /**
     * 结算奖励。四条硬约束缺一不可：幂等、加练池清空、无待批主观题、受当日额度截断。
     */
    fun settle(roundId: String, passed: RoundOutcome.Passed, round: RoundState): SettleResult {
        if (store.isSettled(roundId)) return SettleResult.AlreadySettled

        val pool = store.loadExtraPool()
        if (pool.dueCount > 0) return SettleResult.BlockedByExtraPool(pool.dueCount)

        if (round.hasPendingReview) return SettleResult.PendingReview

        val rawReward = if (rules.difficultyRewardEnabled) {
            passed.correctQuestions.filterNot { it.isExtra }.sumOf { rules.rewardSecondsFor(it.difficulty) }
        } else {
            rules.sessionRewardSeconds
        }

        val ledger = store.loadLedger()
        val remainingQuota = (rules.dailyCapSeconds - ledger.earnedSeconds).coerceAtLeast(0)
        val reward = minOf(rawReward, remainingQuota)

        store.saveLedger(
            ledger.copy(
                balanceSeconds = ledger.balanceSeconds + reward,
                earnedSeconds = ledger.earnedSeconds + reward,
                roundsPassed = ledger.roundsPassed + 1,
            ),
        )
        store.markSettled(roundId, reward)
        pendingEvents += GateEvent.RoundPassed(roundId, reward)

        return SettleResult.Granted(reward, cappedByDailyLimit = reward < rawReward)
    }

    /** 批改通过后补结算；与 [settle] 共用幂等键。 */
    fun settleAfterReview(roundId: String, passed: RoundOutcome.Passed): SettleResult =
        settle(roundId, passed, RoundState(roundId))

    /** 与服务端时间比对，偏差过大只上报不惩罚——误报比作弊更常见。 */
    fun checkClockDrift(serverTimeMillis: Long, moment: LocalMoment) {
        val driftSeconds = abs(serverTimeMillis - moment.wallClockMillis) / 1000L

        if (driftSeconds > CLOCK_DRIFT_THRESHOLD_SECONDS) {
            pendingEvents += GateEvent.ClockDrift(driftSeconds)
        }
    }

    fun drainEvents(): List<GateEvent> {
        val drained = pendingEvents.toList()
        pendingEvents.clear()
        return drained
    }

    private fun requiredCorrect(): Int = ceil(rules.questionsPerRound * rules.passRatio).toInt()

    companion object {
        /** 单次 tick 的跨度上限，超过视为休眠唤醒。 */
        const val MAX_TICK_SECONDS: Int = 120

        const val CLOCK_DRIFT_THRESHOLD_SECONDS: Long = 600
    }
}
