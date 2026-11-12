package com.studycloud.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 《开发文档》6.11 的 T1–T12。这些是 CI 阻断项：
 * 账本与状态机不依赖 Android 框架，可以纯 JVM 测。
 */
class GateEngineTest {

    private class FakeClock(var millis: Long = 0) : MonotonicClock {
        override fun elapsedRealtimeMillis(): Long = millis

        fun advanceSeconds(seconds: Long) {
            millis += seconds * 1000
        }
    }

    private fun moment(
        day: String = "2026-11-12",
        dayOfWeekIndex: Int = 3,
        halfHourSlot: Int = 38,
        wallClockMillis: Long = 1_793_000_000_000,
    ) = LocalMoment(day, dayOfWeekIndex, halfHourSlot, wallClockMillis)

    private fun engine(
        rules: RuleSnapshot = RuleSnapshot(),
        ledger: DayLedger? = null,
        pool: ExtraPool = ExtraPool(),
        clock: FakeClock = FakeClock(),
    ): Pair<GateEngine, InMemoryGateStore> {
        val store = InMemoryGateStore("2026-11-12", ledger, pool)
        return GateEngine(store, clock, rules) to store
    }

    private fun passedRound(
        roundId: String = "round-1",
        count: Int = 5,
        difficulty: Int = 2,
    ): Pair<RoundState, RoundOutcome.Passed> {
        val answers = (0 until count).map {
            AnsweredQuestion(questionId = "q$it", correct = true, difficulty = difficulty)
        }
        val round = RoundState(roundId, answers)

        return round to RoundOutcome.Passed(answers)
    }

    // T1 余额 60s，看 60s → 进入 PROMPT
    @Test
    fun `T1 balance drains to prompt`() {
        val clock = FakeClock()
        val (gate, _) = engine(ledger = DayLedger("2026-11-12", balanceSeconds = 60), clock = clock)

        repeat(60) {
            clock.advanceSeconds(1)
            gate.onTick(ForegroundContext.watching())
        }

        assertEquals(0, gate.ledger().balanceSeconds)
        assertEquals(60, gate.ledger().usedSeconds)
        assertEquals(GateState.PROMPT, gate.evaluate(moment(), ForegroundContext.watching()))
    }

    // T2 答题中途强杀再打开 → 余额不变，未结算的组不发奖励
    @Test
    fun `T2 killing the app mid round grants nothing`() {
        val clock = FakeClock()
        val store = InMemoryGateStore("2026-11-12", DayLedger("2026-11-12", balanceSeconds = 0))
        val first = GateEngine(store, clock, RuleSnapshot())

        val round = RoundState("round-1", listOf(AnsweredQuestion("q0", correct = true, difficulty = 2)))
        assertTrue(first.judge(round) is RoundOutcome.InProgress)

        // 重启：新引擎复用同一份持久化账本
        val second = GateEngine(store, clock, RuleSnapshot())

        assertEquals(0, second.ledger().balanceSeconds)
        assertEquals(0, second.ledger().earnedSeconds)
    }

    // T3 同一 roundId 重复结算 → 只加一次时长
    @Test
    fun `T3 settling the same round twice grants once`() {
        val (gate, _) = engine()
        val (round, passed) = passedRound()

        val first = gate.settle("round-1", passed, round)
        val second = gate.settle("round-1", passed, round)

        assertTrue(first is SettleResult.Granted)
        assertEquals(SettleResult.AlreadySettled, second)
        assertEquals(20 * 60, gate.ledger().balanceSeconds)
        assertEquals(1, gate.ledger().roundsPassed)
    }

    // T4 今日已获得 = 日上限，再通过一组 → 奖励为 0，提示明确
    @Test
    fun `T4 daily cap truncates the reward to zero`() {
        val rules = RuleSnapshot(dailyMinutes = 20, sessionMinutes = 20.0)
        val (gate, _) = engine(
            rules = rules,
            ledger = DayLedger("2026-11-12", earnedSeconds = 20 * 60),
        )
        val (round, passed) = passedRound()

        val result = gate.settle("round-1", passed, round) as SettleResult.Granted

        assertEquals(0, result.rewardSeconds)
        assertTrue(result.cappedByDailyLimit)
        assertEquals(GateState.LOCKED, gate.evaluate(moment(), ForegroundContext.watching()))
    }

    // T5 加练池有 3 题，通过主任务 → 不发奖励，继续出加练题
    @Test
    fun `T5 pending extra questions block the reward`() {
        val (gate, _) = engine(pool = ExtraPool(dueCount = 3))
        val (round, passed) = passedRound()

        val result = gate.settle("round-1", passed, round)

        assertEquals(SettleResult.BlockedByExtraPool(3), result)
        assertEquals(0, gate.ledger().balanceSeconds)
    }

    // T6 加练题全部答对 → 立即结算并发奖励
    @Test
    fun `T6 clearing the extra pool unlocks the reward`() {
        val (gate, _) = engine(pool = ExtraPool(dueCount = 2))
        val (round, passed) = passedRound()

        assertTrue(gate.settle("round-1", passed, round) is SettleResult.BlockedByExtraPool)

        repeat(2) { index ->
            gate.onAnswered(AnsweredQuestion("extra$index", correct = true, difficulty = 2, isExtra = true))
        }

        assertEquals(0, gate.extraPool().dueCount)

        val result = gate.settle("round-1", passed, round) as SettleResult.Granted

        assertEquals(20 * 60, result.rewardSeconds)
    }

    // T7 主观题待批时通过 → PENDING_REVIEW，批改合格后才加时
    @Test
    fun `T7 subjective answers wait for grading`() {
        val (gate, _) = engine()
        val answers = listOf(
            AnsweredQuestion("q0", correct = true, difficulty = 2),
            AnsweredQuestion("q1", correct = true, difficulty = 2, needsReview = true),
        )
        val round = RoundState("round-1", answers)

        assertEquals(SettleResult.PendingReview, gate.settle("round-1", RoundOutcome.Passed(answers), round))
        assertEquals(0, gate.ledger().balanceSeconds)

        val afterReview = gate.settleAfterReview("round-1", RoundOutcome.Passed(answers))

        assertTrue(afterReview is SettleResult.Granted)
        assertEquals(20 * 60, gate.ledger().balanceSeconds)
    }

    // T8 设备休眠 2 小时后唤醒 → 余额不被一次性扣光
    @Test
    fun `T8 waking from sleep does not drain the balance`() {
        val clock = FakeClock()
        val (gate, _) = engine(ledger = DayLedger("2026-11-12", balanceSeconds = 3600), clock = clock)

        clock.advanceSeconds(2 * 60 * 60)
        gate.onTick(ForegroundContext.watching())

        assertEquals(3600, gate.ledger().balanceSeconds)
        assertEquals(0, gate.ledger().usedSeconds)
    }

    // T9 把系统时间往前调 3 小时 → 余额与已用时长不变，上报时钟偏差
    @Test
    fun `T9 moving the wall clock does not change the ledger`() {
        val clock = FakeClock()
        val (gate, _) = engine(ledger = DayLedger("2026-11-12", balanceSeconds = 600), clock = clock)

        clock.advanceSeconds(10)
        gate.onTick(ForegroundContext.watching())
        val afterNormalUse = gate.ledger()

        // 墙上时钟被改了 3 小时，但单调时钟只走了 1 秒
        val shifted = moment(wallClockMillis = 1_793_000_000_000 - 3 * 60 * 60 * 1000)
        clock.advanceSeconds(1)
        gate.onTick(ForegroundContext.watching())
        gate.checkClockDrift(serverTimeMillis = 1_793_000_000_000, moment = shifted)

        assertEquals(afterNormalUse.balanceSeconds - 1, gate.ledger().balanceSeconds)
        assertTrue(gate.drainEvents().filterIsInstance<GateEvent.ClockDrift>().isNotEmpty())
    }

    // T10 断网 24 小时 → 可正常答题解锁，事件入队
    @Test
    fun `T10 offline play still settles and queues events`() {
        val (gate, _) = engine()
        val (round, passed) = passedRound()

        gate.settle("round-1", passed, round)

        val events = gate.drainEvents()

        assertEquals(1, events.filterIsInstance<GateEvent.RoundPassed>().size)
        assertEquals(20 * 60, gate.ledger().balanceSeconds)
        // 排空后不应重复产出，避免联网时重复上报
        assertTrue(gate.drainEvents().isEmpty())
    }

    // T11 quizOnly 模式时段外 → 不扣余额、不弹题
    @Test
    fun `T11 free window neither consumes nor prompts`() {
        val clock = FakeClock()
        val rules = RuleSnapshot(
            scheduleMode = ScheduleMode.QUIZ_ONLY,
            allDay = false,
            weekSlots = RuleSnapshot.emptyWeek(),
        )
        val (gate, _) = engine(rules = rules, ledger = DayLedger("2026-11-12", balanceSeconds = 0), clock = clock)

        assertEquals(GateState.IDLE, gate.evaluate(moment(), ForegroundContext.watching()))

        clock.advanceSeconds(30)
        gate.onTick(ForegroundContext.watching())

        assertEquals(0, gate.ledger().usedSeconds)
    }

    // T12 跨零点 → 当日额度重置，加练池保留
    @Test
    fun `T12 day rollover resets the ledger but keeps the extra pool`() {
        val (gate, _) = engine(
            ledger = DayLedger("2026-11-12", balanceSeconds = 300, earnedSeconds = 1200, roundsPassed = 2),
            pool = ExtraPool(wrongStreak = 2, dueCount = 4),
        )

        gate.ensureToday(moment(day = "2026-11-13"))

        val ledger = gate.ledger()

        assertEquals("2026-11-13", ledger.day)
        assertEquals(0, ledger.balanceSeconds)
        assertEquals(0, ledger.earnedSeconds)
        assertEquals(0, ledger.roundsPassed)
        assertEquals(ExtraPool(wrongStreak = 2, dueCount = 4), gate.extraPool())

        // 幂等：重复调用不再产生事件
        gate.drainEvents()
        gate.ensureToday(moment(day = "2026-11-13"))
        assertTrue(gate.drainEvents().isEmpty())
    }
}
