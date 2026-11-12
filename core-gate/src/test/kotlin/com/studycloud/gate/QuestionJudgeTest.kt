package com.studycloud.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuestionJudgeTest {

    private fun single() = Question(
        id = "q1",
        bankId = "b1",
        type = QuestionType.SINGLE,
        stem = "1 + 1 = ?",
        options = listOf("1", "2", "3"),
        answerIndex = 1,
    )

    private fun multiple() = Question(
        id = "q2",
        bankId = "b1",
        type = QuestionType.MULTIPLE,
        stem = "选出偶数",
        options = listOf("2", "3", "4"),
        answerIndices = listOf(0, 2),
    )

    private fun text() = Question(
        id = "q3",
        bankId = "b1",
        type = QuestionType.TEXT,
        stem = "水的化学式？",
        answers = listOf("H2O", "H₂O"),
    )

    @Test
    fun `single choice is correct only for the right option`() {
        assertTrue(QuestionJudge.judge(single(), Answer.Choice(listOf(1))).correct)
        assertFalse(QuestionJudge.judge(single(), Answer.Choice(listOf(0))).correct)
        assertEquals("2", QuestionJudge.judge(single(), Answer.Choice(listOf(1))).correctAnswerLabel)
    }

    @Test
    fun `multiple choice needs every correct option and nothing else`() {
        assertTrue(QuestionJudge.judge(multiple(), Answer.Choice(listOf(0, 2))).correct)
        assertTrue(QuestionJudge.judge(multiple(), Answer.Choice(listOf(2, 0))).correct)
        assertFalse(QuestionJudge.judge(multiple(), Answer.Choice(listOf(0))).correct)
        assertFalse(QuestionJudge.judge(multiple(), Answer.Choice(listOf(0, 1, 2))).correct)
    }

    @Test
    fun `empty selection is never correct`() {
        assertFalse(QuestionJudge.judge(multiple(), Answer.Choice(emptyList())).correct)
        assertFalse(QuestionJudge.judge(single(), Answer.Choice(emptyList())).correct)
    }

    @Test
    fun `boolean question maps zero to true`() {
        val question = Question("q", "b", QuestionType.BOOLEAN, "一年有四季。", answerIndex = 0)

        assertTrue(QuestionJudge.judge(question, Answer.Choice(listOf(0))).correct)
        assertFalse(QuestionJudge.judge(question, Answer.Choice(listOf(1))).correct)
        assertEquals("正确", QuestionJudge.judge(question, Answer.Choice(listOf(0))).correctAnswerLabel)
    }

    @Test
    fun `text answers ignore case whitespace and full width characters`() {
        assertTrue(QuestionJudge.judge(text(), Answer.Text("H2O")).correct)
        assertTrue(QuestionJudge.judge(text(), Answer.Text(" h2o ")).correct)
        // 电视输入法很容易打出全角
        assertTrue(QuestionJudge.judge(text(), Answer.Text("Ｈ２Ｏ")).correct)
        assertTrue(QuestionJudge.judge(text(), Answer.Text("H₂O")).correct)
        assertFalse(QuestionJudge.judge(text(), Answer.Text("CO2")).correct)
    }

    @Test
    fun `blank text answer is not correct`() {
        assertFalse(QuestionJudge.judge(text(), Answer.Text("   ")).correct)
        assertFalse(QuestionJudge.judge(text(), Answer.Text("")).correct)
    }

    @Test
    fun `subjective answers are never auto judged`() {
        val question = Question(
            id = "q4",
            bankId = "b1",
            type = QuestionType.SUBJECTIVE,
            stem = "解方程",
            modelAnswer = "x=2",
        )

        val result = QuestionJudge.judge(question, Answer.Subjective("过程…", null))

        assertFalse(result.correct)
        assertTrue(result.needsReview)
    }

    @Test
    fun `picker avoids repeating until the pool runs out`() {
        val picker = QuestionPicker(Random(42))
        val pool = listOf(single(), multiple(), text())

        val first = picker.pick(pool, emptySet(), extraDue = 0)
        assertNotNull(first)

        val asked = pool.map { it.id }.toSet()
        // 题库抽完后允许重复，否则任务永远做不完
        assertNotNull(picker.pick(pool, asked, extraDue = 0))
    }

    @Test
    fun `picker returns null for an empty pool`() {
        assertEquals(null, QuestionPicker().pick(emptyList(), emptySet(), extraDue = 0))
    }

    @Test
    fun `extra questions only start after the round is complete`() {
        val picker = QuestionPicker()

        assertFalse(picker.isExtra(extraDue = 2, roundComplete = false))
        assertTrue(picker.isExtra(extraDue = 2, roundComplete = true))
        assertFalse(picker.isExtra(extraDue = 0, roundComplete = true))
    }
}
