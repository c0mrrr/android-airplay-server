package io.github.jqssun.airplay.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

// exoplayer calls stay on the main thread; native only reads the onPlaybackInfo snapshot
class AirPlayVideoPlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pendingSurface: Surface? = null

    // rate is 0 while buffering; overlay wants logical playWhenReady, native the effective rate
    var onPlaybackInfo: ((position: Float, duration: Float, rate: Float, readyToPlay: Boolean, playWhenReady: Boolean) -> Unit)? = null
    var onVideoAspect: ((Float) -> Unit)? = null
    var onEnded: (() -> Unit)? = null

    private val _reportTick = object : Runnable {
        override fun run() {
            _reportPlaybackInfo()
            mainHandler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    private val _listener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.w(TAG, "playback error", error)
            onEnded?.invoke()
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) onEnded?.invoke()
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height == 0) return
            onVideoAspect?.invoke(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
        }
    }

    fun play(location: String, startPositionSeconds: Float) = mainHandler.post {
        // recycling must not report the stopped sentinel: senders poll right after /play
        _stopInternal(reportStopped = false)
        val p = ExoPlayer.Builder(context).build().also {
            it.addListener(_listener)
            pendingSurface?.let { s -> it.setVideoSurface(s) }
        }
        player = p
        p.setMediaItem(MediaItem.fromUri(location), (startPositionSeconds * 1000).toLong())
        p.playWhenReady = true
        p.prepare()
        // a stop queued before this ran may have stamped the sentinel; re-arm the snapshot
        onPlaybackInfo?.invoke(startPositionSeconds, 0f, 0f, false, true)
        mainHandler.postDelayed(_reportTick, REPORT_INTERVAL_MS)
    }

    fun scrub(positionSeconds: Float) = mainHandler.post {
        player?.seekTo((positionSeconds * 1000).toLong())
    }

    fun setRate(rate: Float) = mainHandler.post {
        val p = player ?: return@post
        if (rate <= 0f) {
            p.playWhenReady = false
        } else {
            p.playbackParameters = PlaybackParameters(rate.coerceAtLeast(0.1f))
            p.playWhenReady = true
        }
    }

    // local-only: the sender self-syncs from its next /playback-info poll
    fun setPlaying(playing: Boolean) = mainHandler.post {
        player?.playWhenReady = playing
    }

    fun seekBy(deltaMs: Long) = mainHandler.post {
        val p = player ?: return@post
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = p.duration
        if (duration != C.TIME_UNSET) {
            target = target.coerceAtMost(duration)
        }
        p.seekTo(target)
    }

    fun setSurface(surface: Surface) = mainHandler.post {
        pendingSurface = surface
        player?.setVideoSurface(surface)
    }

    // no-op if a newer surface already replaced this one
    fun clearSurface(surface: Surface) = mainHandler.post {
        if (pendingSurface !== surface) return@post
        pendingSurface = null
        player?.setVideoSurface(null)
    }

    fun stop() = mainHandler.post { _stopInternal(reportStopped = true) }

    private fun _stopInternal(reportStopped: Boolean) {
        mainHandler.removeCallbacks(_reportTick)
        player?.let {
            it.removeListener(_listener)
            it.release()
        }
        player = null
        // duration=-1 is the "video finished" sentinel for the playback-info handler
        if (reportStopped) {
            onPlaybackInfo?.invoke(0f, -1f, 0f, false, false)
        }
    }

    private fun _reportPlaybackInfo() {
        val p = player ?: return
        val durationMs = p.duration
        val position = p.currentPosition / 1000f
        // 0 = unknown/live; a real value only exists for vod
        val duration = if (durationMs == C.TIME_UNSET) 0f else durationMs / 1000f
        val rate = if (p.playWhenReady && p.playbackState == Player.STATE_READY) p.playbackParameters.speed else 0f
        val ready = p.playbackState == Player.STATE_READY
        onPlaybackInfo?.invoke(position, duration, rate, ready, p.playWhenReady)
    }

    companion object {
        private const val TAG = "AirPlayVideoPlayer"
        private const val REPORT_INTERVAL_MS = 250L
    }
}
