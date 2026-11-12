package com.studycloud.tv.gate

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent

/**
 * 学习页出现时先发送媒体暂停并请求音频焦点；播放器继续输出时静音兜底。
 * 退出管控后恢复**原来的**静音状态——原先未静音的才恢复音量，
 * 否则会把家长自己调的静音给解除掉。
 */
class MediaController(private val context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var mutedByUs = false
    private var volumeBeforeMute: Int? = null

    fun pauseAndMute() {
        sendPause()
        requestFocus()

        if (mutedByUs) {
            return
        }

        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

        // 原本就静音就不动它，退出时也不该擅自恢复音量
        if (current > 0) {
            volumeBeforeMute = current
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            mutedByUs = true
        }
    }

    fun restore() {
        abandonFocus()

        if (!mutedByUs) {
            return
        }

        volumeBeforeMute?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        volumeBeforeMute = null
        mutedByUs = false
    }

    private fun sendPause() {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)

        runCatching {
            audio.dispatchMediaKeyEvent(down)
            audio.dispatchMediaKeyEvent(up)
        }
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()

            focusRequest = request
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audio.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(null)
        }
    }
}
