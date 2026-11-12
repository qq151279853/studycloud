package com.studycloud.gate

import kotlin.random.Random

enum class QuestionType { SINGLE, MULTIPLE, BOOLEAN, TEXT, SUBJECTIVE }

/** 设备本地缓存的一道题。 */
data class Question(
    val id: String,
    val bankId: String,
    val type: QuestionType,
    val stem: String,
    val options: List<String> = emptyList(),
    val answerIndex: Int? = null,
    val answerIndices: List<Int> = emptyList(),
    val answers: List<String> = emptyList(),
    val modelAnswer: String? = null,
    val rubric: String? = null,
    val passScore: Int? = null,
    val difficulty: Int = 2,
    val tip: String? = null,
) {
    val needsReview: Boolean get() = type == QuestionType.SUBJECTIVE
}

/** 孩子提交的作答。 */
sealed interface Answer {
    data class Choice(val selected: List<Int>) : Answer

    data class Text(val value: String) : Answer

    data class Subjective(val text: String?, val drawingUrl: String?) : Answer
}

data class JudgeResult(val correct: Boolean, val needsReview: Boolean, val correctAnswerLabel: String)

object QuestionJudge {
    /**
     * 判题。填空题忽略大小写、首尾空白与全角半角差异——
     * 孩子用电视输入法很容易打出全角字符，不该因此判错。
     */
    fun judge(question: Question, answer: Answer): JudgeResult = when (question.type) {
        QuestionType.SINGLE -> {
            val selected = (answer as? Answer.Choice)?.selected?.singleOrNull()
            JudgeResult(
                correct = selected != null && selected == question.answerIndex,
                needsReview = false,
                correctAnswerLabel = question.options.getOrNull(question.answerIndex ?: -1).orEmpty(),
            )
        }

        QuestionType.MULTIPLE -> {
            val selected = (answer as? Answer.Choice)?.selected?.toSortedSet() ?: sortedSetOf()
            JudgeResult(
                correct = selected.isNotEmpty() && selected == question.answerIndices.toSortedSet(),
                needsReview = false,
                correctAnswerLabel = question.answerIndices
                    .sorted()
                    .mapNotNull { question.options.getOrNull(it) }
                    .joinToString("、"),
            )
        }

        QuestionType.BOOLEAN -> {
            val selected = (answer as? Answer.Choice)?.selected?.singleOrNull()
            JudgeResult(
                correct = selected != null && selected == question.answerIndex,
                needsReview = false,
                correctAnswerLabel = if (question.answerIndex == 0) "正确" else "错误",
            )
        }

        QuestionType.TEXT -> {
            val typed = (answer as? Answer.Text)?.value.orEmpty()
            JudgeResult(
                correct = question.answers.any { normalize(it) == normalize(typed) && normalize(typed).isNotEmpty() },
                needsReview = false,
                correctAnswerLabel = question.answers.joinToString(" / "),
            )
        }

        // 主观题由家长、Agent 或 AI 判分，设备端不自作主张
        QuestionType.SUBJECTIVE -> JudgeResult(
            correct = false,
            needsReview = true,
            correctAnswerLabel = question.modelAnswer.orEmpty(),
        )
    }

    internal fun normalize(value: String): String {
        val builder = StringBuilder(value.length)

        for (char in value.trim()) {
            val code = char.code

            val folded = when {
                // 全角字符（ＡＢＣ１２３）折叠成半角
                code in 0xFF01..0xFF5E -> (code - 0xFEE0).toChar()
                code == 0x3000 -> ' '
                else -> char
            }

            if (!folded.isWhitespace()) {
                builder.append(folded.lowercaseChar())
            }
        }

        return builder.toString()
    }
}

/**
 * 抽题。优先满足加练池，其次按规则抽普通题。
 * 刻意不在这里做间隔重复的 SM-2 调度——那是 M5 的事，先留好接口位置。
 */
class QuestionPicker(private val random: Random = Random.Default) {

    fun pick(
        pool: List<Question>,
        askedIds: Set<String>,
        extraDue: Int,
        subjects: Set<String> = emptySet(),
    ): Question? {
        if (pool.isEmpty()) return null

        val candidates = pool.filterNot { it.id in askedIds }

        // 题库较少时允许重复抽取，否则一组任务永远做不完
        val source = candidates.ifEmpty { pool }

        return source.randomOrNull(random)
    }

    fun isExtra(extraDue: Int, roundComplete: Boolean): Boolean = roundComplete && extraDue > 0
}
