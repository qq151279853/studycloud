package com.studycloud.tv.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.studycloud.tv.StudyCloudApp
import com.studycloud.tv.data.ApiException
import com.studycloud.tv.databinding.ActivityLoginBinding
import com.studycloud.tv.gate.GateService
import com.studycloud.tv.sync.SyncWorker
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设备登录页。电视端要点见《开发文档》7.5。
 *
 * 密码框永远为空——本地只回填用户名，绝不保存密码。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var lockoutTimer: CountDownTimer? = null

    private val app: StudyCloudApp get() = application as StudyCloudApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.username.setText(app.credentials.lastUsername.orEmpty())
        binding.deviceName.setText(android.os.Build.MODEL ?: "电视")
        binding.submit.setOnClickListener { submit() }

        // 默认焦点落在用户名输入框
        binding.username.requestFocus()

        if (app.credentials.hasSession) {
            restoreSession()
        }
    }

    override fun onDestroy() {
        lockoutTimer?.cancel()
        super.onDestroy()
    }

    /** 冷启动且已记住登录：静默刷新，不打扰用户。 */
    private fun restoreSession() {
        setBusy(true, getString(com.studycloud.tv.R.string.login_restoring))

        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) { app.api.restoreSession() }

            if (restored) {
                onSignedIn()
            } else {
                // 登录态失效：保留用户名回填，本地题库与规则不清除
                setBusy(false)
                showError("登录状态已过期，请重新登录")
            }
        }
    }

    private fun submit() {
        val username = binding.username.text?.toString()?.trim().orEmpty()
        val password = binding.password.text?.toString().orEmpty()
        val deviceName = binding.deviceName.text?.toString()?.trim().orEmpty().ifEmpty { "电视" }

        if (username.isEmpty()) {
            showError("请输入用户名")
            binding.username.requestFocus()
            return
        }

        if (password.isEmpty()) {
            showError("请输入密码")
            binding.password.requestFocus()
            return
        }

        hideError()
        setBusy(true, getString(com.studycloud.tv.R.string.login_in_progress))

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.api.login(username, password, deviceName, app.appVersion)
                }
                onSignedIn()
            } catch (error: ApiException) {
                setBusy(false)
                handleApiError(error)
            } catch (_: IOException) {
                setBusy(false)
                showError("连接不上服务器，请检查网络后重试")
            }
        }
    }

    private fun handleApiError(error: ApiException) {
        when (error.code) {
            // 错误提示落到具体字段，不要只说「登录失败」
            "INVALID_CREDENTIALS" -> {
                showError("用户名或密码错误")
                binding.password.setText("")
                binding.password.requestFocus()
            }

            "ACCOUNT_LOCKED" -> startLockoutCountdown(LOCKOUT_SECONDS)

            "QUOTA_DEVICE_EXCEEDED" -> showError("${error.message}。请先在家长后台解绑一台设备。")

            else -> showError(error.message)
        }
    }

    /** 账号锁定时显示剩余秒数，而不是只说「请稍后再试」。 */
    private fun startLockoutCountdown(seconds: Long) {
        lockoutTimer?.cancel()
        binding.submit.isEnabled = false

        lockoutTimer = object : CountDownTimer(seconds * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                showError("登录失败次数过多，请等待 ${millisUntilFinished / 1000} 秒后重试")
            }

            override fun onFinish() {
                hideError()
                binding.submit.isEnabled = true
                binding.password.requestFocus()
            }
        }.start()
    }

    private fun onSignedIn() {
        SyncWorker.schedule(applicationContext)
        GateService.start(applicationContext)
        finish()
    }

    private fun setBusy(busy: Boolean, label: String? = null) {
        binding.submit.isEnabled = !busy
        binding.username.isEnabled = !busy
        binding.password.isEnabled = !busy
        binding.deviceName.isEnabled = !busy
        binding.submit.text = label ?: getString(com.studycloud.tv.R.string.login_submit)
    }

    private fun showError(message: String) {
        binding.error.text = message
        binding.error.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.error.visibility = View.GONE
    }

    private companion object {
        const val LOCKOUT_SECONDS = 60L
    }
}
