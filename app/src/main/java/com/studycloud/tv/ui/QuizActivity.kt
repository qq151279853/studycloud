package com.studycloud.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.studycloud.gate.Answer
import com.studycloud.gate.AnsweredQuestion
import com.studycloud.gate.GateEvent
import com.studycloud.gate.Question
import com.studycloud.gate.QuestionJudge
import com.studycloud.gate.QuestionPicker
import com.studycloud.gate.QuestionType
import com.studycloud.gate.RoundOutcome
import com.studycloud.gate.RoundState
import com.studycloud.gate.SettleResult
import com.studycloud.tv.R
import com.studycloud.tv.StudyCloudApp
import com.studycloud.tv.databinding.ActivityQuizBinding
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 答题页。电视端全程可用遥控器完成：
 * 方向键在选项间移动，OK 选中，底部主操作按钮提交 / 继续。
 */
class QuizActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuizBinding
    private val app: StudyCloudApp get() = application as StudyCloudApp

    private val picker = QuestionPicker()
    private var pool: List<Question> = emptyList()
    private var round = RoundState(UUID.randomUUID().toString())
    private var current: Question? = null
    private var selected = mutableListOf<Int>()
    private var askedIds = mutableSetOf<String>()
    private var showingFeedback = false
    private var answeringExtra = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityQuizBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setTitle(R.string.quiz_title)

        binding.primaryAction.setOnClickListener { onPrimaryAction() }

        lifecycleScope.launch {
            pool = withContext(Dispatchers.IO) { app.store.loadQuestions() }
            nextQuestion()
        }
    }

    /** 管控期间不允许返回键逃离答题页。 */
    override fun onBackPressed() {
        if (app.gate.ledger().balanceSeconds > 0) {
            super.onBackPressed()
        }
    }

    private fun nextQuestion() {
        if (pool.isEmpty()) {
            binding.stem.setText(R.string.quiz_no_questions)
            binding.primaryAction.visibility = View.GONE
            return
        }

        val rules = app.gate.rules
        val extraDue = app.gate.extraPool().dueCount
        val roundComplete = round.normalCount >= rules.questionsPerRound

        answeringExtra = picker.isExtra(extraDue, roundComplete)

        val question = picker.pick(pool, askedIds, extraDue)

        if (question == null) {
            finish()
            return
        }

        current = question
        selected = mutableListOf()
        showingFeedback = false

        renderQuestion(question)
    }

    private fun renderQuestion(question: Question) {
        val rules = app.gate.rules
        val balanceMinutes = app.gate.ledger().balanceSeconds / 60

        binding.progress.text = if (answeringExtra) {
            getString(R.string.quiz_extra, app.gate.extraPool().dueCount)
        } else {
            getString(R.string.quiz_progress, round.normalCount + 1, rules.questionsPerRound)
        }

        binding.balance.text = getString(R.string.quiz_balance, balanceMinutes)
        binding.stem.text = question.stem
        binding.feedback.visibility = View.GONE
        binding.primaryAction.setText(R.string.quiz_submit)
        binding.primaryAction.visibility = View.VISIBLE

        binding.options.removeAllViews()
        binding.textAnswer.visibility = View.GONE
        binding.textAnswer.setText("")

        when (question.type) {
            QuestionType.TEXT, QuestionType.SUBJECTIVE -> {
                binding.textAnswer.visibility = View.VISIBLE
                binding.textAnswer.requestFocus()
            }

            else -> {
                val labels = if (question.type == QuestionType.BOOLEAN) {
                    listOf(getString(R.string.quiz_correct_option), getString(R.string.quiz_wrong_option))
                } else {
                    question.options
                }

                labels.forEachIndexed { index, label -> addOption(index, label, question) }
                binding.options.getChildAt(0)?.requestFocus()
            }
        }
    }

    private fun addOption(index: Int, label: String, question: Question) {
        val multiple = question.type == QuestionType.MULTIPLE

        val view = Button(this).apply {
            text = if (multiple) "☐  $label" else label
            textSize = 24f
            minHeight = resources.getDimensionPixelSize(R.dimen.tv_option_height)
            isAllCaps = false
            setBackgroundResource(R.drawable.option_background)
            contentDescription = if (multiple) "$label，多选项，按 OK 切换选中" else label
            isFocusable = true

            setOnClickListener {
                if (multiple) {
                    if (selected.remove(index)) {
                        text = "☐  $label"
                        isSelected = false
                    } else {
                        selected.add(index)
                        text = "☑  $label"
                        isSelected = true
                    }
                } else {
                    selected = mutableListOf(index)
                    // 单选：刷新同级按钮的选中态，状态不只靠颜色，前缀也会变
                    for (position in 0 until binding.options.childCount) {
                        binding.options.getChildAt(position).isSelected = position == index
                    }
                }
            }
        }

        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.tv_option_gap) }

        binding.options.addView(view, params)
    }

    private fun onPrimaryAction() {
        if (showingFeedback) {
            nextQuestion()
            return
        }

        val question = current ?: return
        val answer = buildAnswer(question)

        if (answer == null) {
            return
        }

        val result = QuestionJudge.judge(question, answer)
        askedIds += question.id

        val answered = AnsweredQuestion(
            questionId = question.id,
            correct = result.correct,
            difficulty = question.difficulty,
            isExtra = answeringExtra,
            needsReview = result.needsReview,
        )

        app.gate.onAnswered(answered)
        round = round.with(answered)

        if (result.needsReview) {
            submitForReview(question, answer)
        }

        showFeedback(question, result.correct, result.needsReview, result.correctAnswerLabel)

        if (!answeringExtra) {
            maybeSettle()
        }
    }

    private fun buildAnswer(question: Question): Answer? = when (question.type) {
        QuestionType.TEXT -> Answer.Text(binding.textAnswer.text?.toString().orEmpty())
        QuestionType.SUBJECTIVE -> Answer.Subjective(binding.textAnswer.text?.toString(), null)
        else -> if (selected.isEmpty()) null else Answer.Choice(selected.toList())
    }

    private fun showFeedback(question: Question, correct: Boolean, needsReview: Boolean, correctLabel: String) {
        showingFeedback = true

        binding.feedback.visibility = View.VISIBLE
        binding.primaryAction.setText(R.string.quiz_continue)
        binding.primaryAction.requestFocus()

        binding.verdict.text = when {
            needsReview -> getString(R.string.quiz_pending_review)
            correct -> getString(R.string.quiz_correct)
            else -> getString(R.string.quiz_wrong)
        }

        binding.correctAnswer.text = if (correct || needsReview) "" else "正确答案：$correctLabel"
        binding.correctAnswer.visibility = if (binding.correctAnswer.text.isEmpty()) View.GONE else View.VISIBLE

        binding.tip.text = question.tip.orEmpty()
        binding.tip.visibility = if (question.tip.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun submitForReview(question: Question, answer: Answer) {
        val text = (answer as? Answer.Subjective)?.text

        lifecycleScope.launch(Dispatchers.IO) {
            app.store.enqueueReview(
                clientAnswerId = "${round.roundId}:${question.id}",
                questionId = question.id,
                answerText = text,
            )
        }
    }

    private fun maybeSettle() {
        when (val outcome = app.gate.judge(round)) {
            is RoundOutcome.Passed -> {
                when (val result = app.gate.settle(round.roundId, outcome, round)) {
                    is SettleResult.Granted -> {
                        val minutes = result.rewardSeconds / 60
                        val message = if (result.cappedByDailyLimit && result.rewardSeconds == 0) {
                            "今天的时长已经用完了，明天再来吧"
                        } else {
                            "真棒！解锁了 $minutes 分钟"
                        }

                        binding.verdict.text = message
                        binding.primaryAction.setText(R.string.quiz_finish)
                        binding.primaryAction.setOnClickListener { finish() }
                    }

                    is SettleResult.BlockedByExtraPool ->
                        binding.verdict.text = "还要完成 ${result.dueCount} 道加练题"

                    SettleResult.PendingReview ->
                        binding.verdict.text = getString(R.string.quiz_pending_review)

                    SettleResult.AlreadySettled -> Unit
                }

                flushEvents()
            }

            RoundOutcome.Failed -> {
                // 没通过就重新开一组，加练池不清零
                round = RoundState(UUID.randomUUID().toString())
                askedIds.clear()
            }

            RoundOutcome.InProgress -> Unit
        }
    }

    private fun flushEvents() {
        val events = app.gate.drainEvents()

        if (events.isEmpty()) {
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            for (event in events) {
                app.store.enqueueGateEvent(event)
            }
        }
    }
}

/** 把引擎事件翻译成上报用的事件类型。 */
internal fun GateEvent.typeName(): String = when (this) {
    is GateEvent.Usage -> "usage"
    is GateEvent.RoundPassed -> "roundPassed"
    is GateEvent.ExtraAdded -> "extraAdded"
    is GateEvent.DayRollover -> "dayRollover"
    is GateEvent.ClockDrift -> "clockDrift"
}
