package com.yourname.birder

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class PlayerActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val durationHandler = Handler(Looper.getMainLooper())

    private lateinit var seekBar: SeekBar
    private lateinit var tvElapsed: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnNext: Button
    private lateinit var btnPrev: Button

    private var songs: List<BirdSong> = emptyList()
    private var currentIndex = 0
    private var listeningDurationSec = 0
    private var handsFreeMode = false
    private var ttsVolume = 1.0f  // 0.0 to 1.0

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        seekBar = findViewById(R.id.seekBar)
        tvElapsed = findViewById(R.id.tvElapsed)
        tvDuration = findViewById(R.id.tvDuration)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        btnPrev = findViewById(R.id.btnPrev)

        val prefs = getSharedPreferences("birder_prefs", MODE_PRIVATE)
        listeningDurationSec = prefs.getInt("listening_duration_sec", 15)
        handsFreeMode = prefs.getBoolean("hands_free_mode", false)
        val ttsVolumeInt = prefs.getInt("tts_volume", 10)      // 0–10
        ttsVolume = ttsVolumeInt / 10f                          // convert to 0.0–1.0

        currentIndex = intent.getIntExtra("START_INDEX", 0)
        songs = BirdRepository.loadShuffled(this)

        if (songs.isEmpty()) {
            Toast.makeText(this, "No songs loaded", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tts = TextToSpeech(this, this)

        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnNext.setOnClickListener { skipToNext() }
        btnPrev.setOnClickListener { prevSong() }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            val dialog = AlertDialog.Builder(this)
                .setTitle("Stop playback?")
                .setMessage("Are you sure you want to stop and exit?")
                .setPositiveButton("Yes") { _, _ ->
                    mediaPlayer?.stop()
                    tts?.stop()
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_bg)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.cream))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.cream))
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer?.seekTo(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Always start playing immediately — TTS speaks AFTER the song now
        loadSong(currentIndex, autoPlay = true)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.ENGLISH
            ttsReady = true
        } else {
            Toast.makeText(this, "Text-to-speech unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSong(index: Int, autoPlay: Boolean) {
        val song = songs.getOrNull(index) ?: return
        updateLabels(song)

        handler.removeCallbacks(updateSeekBar)
        durationHandler.removeCallbacksAndMessages(null)
        seekBar.progress = 0
        tvElapsed.text = "0:00"
        tvDuration.text = "0:00"
        btnPlayPause.text = "▶  Play"

        mediaPlayer?.release()
        mediaPlayer = try {
            MediaPlayer().apply {
                setDataSource(song.file.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play: ${song.file.name}", Toast.LENGTH_SHORT).show()
            null
        }

        mediaPlayer?.let { mp ->
            seekBar.max = mp.duration
            tvDuration.text = formatTime(mp.duration)

            // When song ends naturally — speak name then advance
            mp.setOnCompletionListener { finishSong() }

            if (autoPlay) {
                mp.start()
                btnPlayPause.text = "Pause"
                handler.post(updateSeekBar)
                scheduleDurationAdvance()
            }
        }
    }

    /**
     * Called when a song finishes (either by duration limit or natural end).
     * If hands free ON: speak the current bird name, then load next song.
     * If hands free OFF: load next song immediately.
     */
    private fun finishSong() {
        durationHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(updateSeekBar)
        mediaPlayer?.release()
        mediaPlayer = null

        if (handsFreeMode && ttsReady && currentIndex < songs.size - 1) {
            val song = songs[currentIndex]
            val utteranceId = "done_$currentIndex"

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsVolume)
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uid: String?) {}
                override fun onError(uid: String?) {
                    handler.post { advanceToNext() }
                }
                override fun onDone(uid: String?) {
                    handler.post { advanceToNext() }
                }
            })

            tts?.speak(song.commonName, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } else {
            advanceToNext()
        }
    }

    private fun advanceToNext() {
        if (currentIndex < songs.size - 1) {
            currentIndex++
            loadSong(currentIndex, autoPlay = true)
        } else {
            Toast.makeText(this, "End of playlist", Toast.LENGTH_SHORT).show()
            btnPlayPause.text = "▶  Play"
        }
    }

    /**
     * User tapped Next manually — skip TTS, go straight to next song.
     */
    private fun skipToNext() {
        tts?.stop()
        durationHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(updateSeekBar)
        if (currentIndex < songs.size - 1) {
            currentIndex++
            loadSong(currentIndex, autoPlay = true)
        } else {
            Toast.makeText(this, "End of playlist", Toast.LENGTH_SHORT).show()
        }
    }

    private fun prevSong() {
        tts?.stop()
        durationHandler.removeCallbacksAndMessages(null)
        val position = mediaPlayer?.currentPosition ?: 0
        if (position > 3000) {
            mediaPlayer?.seekTo(0)
        } else if (currentIndex > 0) {
            currentIndex--
            loadSong(currentIndex, autoPlay = true)
        }
    }

    private fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            btnPlayPause.text = "▶  Play"
            handler.removeCallbacks(updateSeekBar)
            durationHandler.removeCallbacksAndMessages(null)
        } else {
            mp.start()
            btnPlayPause.text = "Pause"
            handler.post(updateSeekBar)
            scheduleDurationAdvance()
        }
    }

    private fun scheduleDurationAdvance() {
        durationHandler.removeCallbacksAndMessages(null)
        if (listeningDurationSec > 0) {
            durationHandler.postDelayed({
                finishSong()   // speak name then advance — not nextSong()
            }, listeningDurationSec * 1000L)
        }
    }

    private fun updateLabels(song: BirdSong) {
        findViewById<TextView>(R.id.tvCommonName).text = song.commonName
        findViewById<TextView>(R.id.tvScientificName).text = song.scientificName
        findViewById<TextView>(R.id.tvSoundType).text = song.soundType.uppercase()
        findViewById<TextView>(R.id.tvMeta).text =
            "📍 ${song.country}  •  🎙 ${song.recordist}  •  📅 ${song.date}  •  ⭐ ${song.quality}"
    }

    private val updateSeekBar = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
                seekBar.progress = it.currentPosition
                tvElapsed.text = formatTime(it.currentPosition)
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun formatTime(ms: Int): String {
        val s = (ms / 1000) % 60
        val m = ms / 1000 / 60
        return "%d:%02d".format(m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateSeekBar)
        durationHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}