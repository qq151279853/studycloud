package com.studycloud.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 答题反馈语的取词规则。界面在拿到 null 时用内置文案兜底。 */
class FeedbackPhraseTest {

    private val rules = RuleSnapshot(
        praisePhrases = listOf("你真棒！", "答对了，继续加油！"),
        encouragePhrases = listOf("再仔细想想～", "别灰心，下一题继续！"),
    )

    @Test
    fun `答对取表扬语，答错取鼓励语`() {
        assertTrue(rules.feedbackPhrase(correct = true, seed = 0) in rules.praisePhrases)
        assertTrue(rules.feedbackPhrase(correct = false, seed = 0) in rules.encouragePhrases)
    }

    @Test
    fun `同一题反复渲染不会换词`() {
        val seed = "q-42".hashCode()

        assertEquals(
            rules.feedbackPhrase(correct = true, seed = seed),
            rules.feedbackPhrase(correct = true, seed = seed),
        )
    }

    @Test
    fun `不同的题会轮换到不同的词`() {
        val picked = (0 until 20).map { rules.feedbackPhrase(correct = false, seed = it) }.toSet()

        assertEquals(rules.encouragePhrases.toSet(), picked)
    }

    @Test
    fun `负数种子不会越界`() {
        assertTrue(rules.feedbackPhrase(correct = true, seed = -7) in rules.praisePhrases)
        assertTrue(rules.feedbackPhrase(correct = false, seed = Int.MIN_VALUE) in rules.encouragePhrases)
    }

    @Test
    fun `没有配置时返回 null 交给界面兜底`() {
        val empty = RuleSnapshot()

        assertNull(empty.feedbackPhrase(correct = true, seed = 0))
        assertNull(empty.feedbackPhrase(correct = false, seed = 0))
    }

    @Test
    fun `全是空白的配置等同于没配置`() {
        val blank = RuleSnapshot(praisePhrases = listOf("", "   "))

        assertNull(blank.feedbackPhrase(correct = true, seed = 0))
    }

    @Test
    fun `默认答错显示答案`() {
        assertTrue(RuleSnapshot().showAnswerOnWrong)
    }
}
