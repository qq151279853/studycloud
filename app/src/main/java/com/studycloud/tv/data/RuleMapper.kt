package com.studycloud.tv.data

import com.studycloud.gate.PlayMode
import com.studycloud.gate.RuleSnapshot
import com.studycloud.gate.ScheduleMode
import org.json.JSONArray
import org.json.JSONObject

/** 把服务端下发的规则 JSON 翻译成设备端执行用的 [RuleSnapshot]。 */
object RuleMapper {

    fun parse(payload: String?): RuleSnapshot? {
        if (payload.isNullOrBlank()) return null

        return runCatching {
            val json = JSONObject(payload)
            val defaults = RuleSnapshot()

            RuleSnapshot(
                dailyMinutes = json.optInt("dailyMinutes", defaults.dailyMinutes),
                dailyFreeMinutes = json.optInt("dailyFreeMinutes", defaults.dailyFreeMinutes),
                sessionMinutes = json.optDouble("sessionMinutes", defaults.sessionMinutes),
                questionsPerRound = json.optInt("questionsPerRound", defaults.questionsPerRound),
                roundsPerDay = json.optInt("roundsPerDay", defaults.roundsPerDay),
                passRatio = json.optDouble("passRatio", defaults.passRatio),
                playMode = parsePlayMode(json.optString("playMode")),
                scheduleMode = parseScheduleMode(json.optString("scheduleMode")),
                allDay = json.optBoolean("allDay", defaults.allDay),
                weekSlots = parseWeek(json.optJSONArray("weekSlots")) ?: defaults.weekSlots,
                spacedRepetitionEnabled = json.optBoolean("spacedRepetitionEnabled", false),
                extraEnabled = json.optBoolean("extraEnabled", defaults.extraEnabled),
                wrongThreshold = json.optInt("wrongThreshold", defaults.wrongThreshold),
                extraQuestions = json.optInt("extraQuestions", defaults.extraQuestions),
                difficultyRewardEnabled = json.optBoolean("difficultyRewardEnabled", false),
                difficultyRewards = parseRewards(json.optJSONArray("difficultyRewards"))
                    ?: defaults.difficultyRewards,
                blockSettingsDuringControl = json.optBoolean("blockSettingsDuringControl", true),
                autoNextSeconds = json.optInt("autoNextSeconds", defaults.autoNextSeconds),
            )
        }.getOrNull()
    }

    private fun parsePlayMode(value: String?) = when (value) {
        "streak" -> PlayMode.STREAK
        "free" -> PlayMode.FREE
        else -> PlayMode.ROUND
    }

    private fun parseScheduleMode(value: String?) = when (value) {
        "availableOnly" -> ScheduleMode.AVAILABLE_ONLY
        else -> ScheduleMode.QUIZ_ONLY
    }

    private fun parseWeek(array: JSONArray?): List<List<Int>>? {
        if (array == null || array.length() != 7) return null

        return (0 until 7).map { day ->
            val row = array.optJSONArray(day) ?: return null
            (0 until 48).map { slot -> row.optInt(slot, 0) }
        }
    }

    private fun parseRewards(array: JSONArray?): List<Double>? {
        if (array == null || array.length() != 5) return null

        return (0 until 5).map { array.optDouble(it, 0.0) }
    }
}
