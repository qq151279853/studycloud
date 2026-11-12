package com.studycloud.gate

/** 玩法。语义见《开发文档》6.6。 */
enum class PlayMode { ROUND, STREAK, FREE }

/** 时段用途。`QUIZ_ONLY` 的时段外为自由使用，不扣余额。 */
enum class ScheduleMode { AVAILABLE_ONLY, QUIZ_ONLY }

/**
 * 设备端生效的规则快照。服务端只下发数据，语义全部在这里执行，
 * 保证离线与在线行为一致。
 */
data class RuleSnapshot(
    val version: Int = 1,
    val dailyMinutes: Int = 120,
    val dailyFreeMinutes: Int = 0,
    val sessionMinutes: Double = 20.0,
    val questionsPerRound: Int = 5,
    val roundsPerDay: Int = 6,
    val passRatio: Double = 0.8,
    val playMode: PlayMode = PlayMode.ROUND,
    val scheduleMode: ScheduleMode = ScheduleMode.QUIZ_ONLY,
    val allDay: Boolean = true,
    /** 7 行 × 48 格，周一至周日，每格 30 分钟；非 0 表示该格处于管控时段。 */
    val weekSlots: List<List<Int>> = fullWeek(),
    val spacedRepetitionEnabled: Boolean = false,
    val extraEnabled: Boolean = true,
    val wrongThreshold: Int = 3,
    val extraQuestions: Int = 2,
    val difficultyRewardEnabled: Boolean = false,
    val difficultyRewards: List<Double> = listOf(1.0, 2.0, 3.0, 4.0, 5.0),
    val blockSettingsDuringControl: Boolean = true,
    val autoNextSeconds: Int = 0,
    /** 答错后是否把正确答案一并显示出来。 */
    val showAnswerOnWrong: Boolean = true,
    /** 答对时的鼓励语，随机取一条；为空时由界面用内置文案兜底。 */
    val praisePhrases: List<String> = emptyList(),
    /** 答错时的鼓励语，随机取一条；为空时由界面用内置文案兜底。 */
    val encouragePhrases: List<String> = emptyList(),
) {
    val dailyCapSeconds: Int get() = dailyMinutes * 60

    val sessionRewardSeconds: Int get() = Math.round(sessionMinutes * 60).toInt()

    fun rewardSecondsFor(difficulty: Int): Int {
        val index = (difficulty - 1).coerceIn(0, difficultyRewards.lastIndex)
        return Math.round(difficultyRewards[index] * 60).toInt()
    }

    /**
     * 取一条反馈语。[seed] 由调用方给（通常是题目 id 的哈希），
     * 同一题重复渲染不会换词，但不同题之间会轮换。
     */
    fun feedbackPhrase(correct: Boolean, seed: Int): String? {
        val phrases = if (correct) praisePhrases else encouragePhrases
        val usable = phrases.filter { it.isNotBlank() }

        if (usable.isEmpty()) return null

        return usable[Math.floorMod(seed, usable.size)]
    }

    /** 该时刻是否落在「需要答题」的管控时段内。 */
    fun isControlledAt(moment: LocalMoment): Boolean {
        if (allDay) return true

        val row = weekSlots.getOrNull(moment.dayOfWeekIndex) ?: return true
        val slot = row.getOrNull(moment.halfHourSlot) ?: return true

        return slot != 0
    }

    companion object {
        fun fullWeek(): List<List<Int>> = List(7) { List(48) { 1 } }

        fun emptyWeek(): List<List<Int>> = List(7) { List(48) { 0 } }
    }
}

/**
 * 设备本地时间的轻量表示。刻意不用 java.time：
 * 日界判定用本地日期，而时长累计一律用单调时钟（见 [MonotonicClock]）。
 */
data class LocalMoment(
    /** yyyy-MM-dd */
    val day: String,
    /** 周一 = 0 … 周日 = 6 */
    val dayOfWeekIndex: Int,
    /** 0..47，每格 30 分钟 */
    val halfHourSlot: Int,
    /** 墙上时钟的毫秒值，仅用于与服务端比对偏差 */
    val wallClockMillis: Long,
)
