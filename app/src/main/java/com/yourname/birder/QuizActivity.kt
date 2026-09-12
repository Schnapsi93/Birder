package com.yourname.birder

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback

class QuizActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var dingPlayer: MediaPlayer? = null
    private var dingNegativePlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val durationHandler = Handler(Looper.getMainLooper())
    private val countdownHandler = Handler(Looper.getMainLooper())

    private lateinit var progressBarTime: ProgressBar
    private lateinit var tvScore: TextView
    private lateinit var tvProgress: TextView
    private lateinit var layoutBirdInfo: LinearLayout
    private lateinit var tvQuizCommonName: TextView
    private lateinit var tvQuizScientificName: TextView
    private lateinit var tvQuizMeta: TextView
    private lateinit var tvQuizSlovenianName: TextView
    private lateinit var btnQuizPause: Button
    private lateinit var layoutPauseOverlay: LinearLayout
    private val answerButtons = mutableListOf<Button>()

    private var songs: List<BirdSong> = emptyList()
    private var allDisplayNames: List<String> = emptyList()
    private var currentIndex = 0
    private var score = 0
    private var totalQuestions = 0
    private var listeningDurationSec = 15
    private var answered = false

    private var isPaused = false
    private var remainingCountdownTicks = 0
    private var inAnswerPhase = false
    private var currentCorrectDisplay: String = ""

    private val ANSWER_WINDOW_SEC = 5
    private val TOTAL_QUESTIONS = 10
    private val OPTIONS_COUNT = 6

    private val colorCorrect  by lazy { android.graphics.Color.parseColor("#2e7d32") }
    private val colorWrong    by lazy { android.graphics.Color.parseColor("#b71c1c") }
    private val colorNeutral  by lazy { android.graphics.Color.parseColor("#2D6A4F") }
    private val colorUnpicked by lazy { android.graphics.Color.parseColor("#4a4a4a") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz)

        progressBarTime      = findViewById(R.id.progressBarTime)
        tvScore              = findViewById(R.id.tvScore)
        tvProgress           = findViewById(R.id.tvProgress)
        layoutBirdInfo       = findViewById(R.id.layoutBirdInfo)
        tvQuizCommonName     = findViewById(R.id.tvQuizCommonName)
        tvQuizScientificName = findViewById(R.id.tvQuizScientificName)
        tvQuizMeta           = findViewById(R.id.tvQuizMeta)
        tvQuizSlovenianName  = findViewById(R.id.tvQuizSlovenianName)
        btnQuizPause         = findViewById(R.id.btnQuizPause)
        layoutPauseOverlay   = findViewById(R.id.layoutPauseOverlay)

        answerButtons.add(findViewById(R.id.btnAnswer1))
        answerButtons.add(findViewById(R.id.btnAnswer2))
        answerButtons.add(findViewById(R.id.btnAnswer3))
        answerButtons.add(findViewById(R.id.btnAnswer4))
        answerButtons.add(findViewById(R.id.btnAnswer5))
        answerButtons.add(findViewById(R.id.btnAnswer6))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Stop everything regardless of answered state
                mediaPlayer?.pause()
                durationHandler.removeCallbacksAndMessages(null)
                countdownHandler.removeCallbacksAndMessages(null)
                handler.removeCallbacksAndMessages(null)
                showEndGameConfirm()
            }
        })

        val prefs = getSharedPreferences("birder_prefs", MODE_PRIVATE)
        listeningDurationSec = prefs.getInt("listening_duration_sec", 15)

        val allSongs = BirdRepository.loadShuffled(this)
        if (allSongs.isEmpty()) {
            Toast.makeText(this, "No songs available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        allDisplayNames = allSongs.map { it.displayName() }.distinct()

        songs = allSongs
            .groupBy { it.scientificName }
            .values
            .mapNotNull { it.randomOrNull() }
            .shuffled()
            .take(TOTAL_QUESTIONS)

        totalQuestions = songs.size

        dingPlayer         = MediaPlayer.create(this, R.raw.ding)
        dingNegativePlayer = MediaPlayer.create(this, R.raw.ding_negative)

        btnQuizPause.setOnClickListener { togglePause() }

        // Pause overlay buttons
        findViewById<Button>(R.id.btnPauseResume).setOnClickListener {
            resumeQuiz()
        }

        findViewById<Button>(R.id.btnPauseEndGame).setOnClickListener {
            showEndGameConfirm()
        }

        updateScoreDisplay()
        loadQuestion(currentIndex)
    }

    private fun BirdSong.displayName(): String =
        slovenianName.ifEmpty { scientificName }

    // ── Pause / Resume ────────────────────────────────────────────────────────

    private fun togglePause() {
        if (answered) return
        if (isPaused) resumeQuiz() else pauseQuiz()
    }

    private fun pauseQuiz() {
        if (isPaused) return
        isPaused = true
        btnQuizPause.text = "▶  Resume"
        mediaPlayer?.pause()
        durationHandler.removeCallbacksAndMessages(null)
        if (inAnswerPhase) {
            remainingCountdownTicks = progressBarTime.progress
            countdownHandler.removeCallbacksAndMessages(null)
        }
        layoutPauseOverlay.visibility = View.VISIBLE
    }

    private fun resumeQuiz() {
        isPaused = false
        btnQuizPause.text = "⏸  Pause"
        layoutPauseOverlay.visibility = View.GONE

        val song = songs.getOrNull(currentIndex) ?: return

        if (inAnswerPhase) {
            resumeCountdown(song, currentCorrectDisplay, remainingCountdownTicks)
        } else {
            mediaPlayer?.start()
            val elapsed = mediaPlayer?.currentPosition ?: 0
            val remainingMs = ((listeningDurationSec * 1000) - elapsed).coerceAtLeast(0)
            if (listeningDurationSec > 0 && remainingMs > 0) {
                durationHandler.postDelayed({
                    mediaPlayer?.pause()
                    inAnswerPhase = true
                    startAnswerCountdown(song, currentCorrectDisplay)
                }, remainingMs.toLong())
            }
        }
    }

    private fun showEndGameConfirm() {
        AlertDialog.Builder(this)
            .setTitle("End Game?")
            .setMessage("Are you sure you want to end the quiz?")
            .setPositiveButton("Yes, end") { _, _ -> finish() }
            .setNegativeButton("Go back") { _, _ ->
                // overlay stays visible — user is still paused
            }
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_bg)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(getColor(R.color.cream))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(getColor(R.color.cream))
            }
    }

    // ── Question loading ──────────────────────────────────────────────────────

    private fun loadQuestion(index: Int) {
        val song = songs.getOrNull(index) ?: return
        answered = false
        isPaused = false
        inAnswerPhase = false
        btnQuizPause.text = "⏸  Pause"
        btnQuizPause.isEnabled = true
        layoutPauseOverlay.visibility = View.GONE
        layoutBirdInfo.visibility = View.GONE

        answerButtons.forEach { btn ->
            btn.isEnabled = true
            btn.setBackgroundColor(colorNeutral)
            btn.setTextColor(getColor(R.color.cream))
        }

        val correctDisplay = song.displayName()
        currentCorrectDisplay = correctDisplay

        val wrongOptions = allDisplayNames
            .filter { it != correctDisplay }
            .shuffled()
            .take(OPTIONS_COUNT - 1)

        val options = (wrongOptions + correctDisplay).shuffled()

        options.forEachIndexed { i, displayName ->
            answerButtons[i].text = displayName
            answerButtons[i].setOnClickListener {
                if (!answered && !isPaused) handleAnswer(
                    selectedDisplay = displayName,
                    correctDisplay = correctDisplay,
                    song = song
                )
            }
        }

        stopPlayback()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(song.file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play audio", Toast.LENGTH_SHORT).show()
        }

        if (listeningDurationSec > 0) {
            durationHandler.postDelayed({
                if (!isPaused) {
                    mediaPlayer?.pause()
                    inAnswerPhase = true
                    startAnswerCountdown(song, correctDisplay)
                }
            }, listeningDurationSec * 1000L)
        }

        mediaPlayer?.setOnCompletionListener {
            if (!isPaused) {
                durationHandler.removeCallbacksAndMessages(null)
                inAnswerPhase = true
                startAnswerCountdown(song, correctDisplay)
            }
        }
    }

    // ── Countdown ─────────────────────────────────────────────────────────────

    private fun startAnswerCountdown(song: BirdSong, correctDisplay: String) {
        val totalTicks = ANSWER_WINDOW_SEC * 10
        progressBarTime.max = totalTicks
        progressBarTime.progress = totalTicks
        resumeCountdown(song, correctDisplay, totalTicks)
    }

    private fun resumeCountdown(song: BirdSong, correctDisplay: String, fromTicks: Int) {
        var remaining = fromTicks
        countdownHandler.removeCallbacksAndMessages(null)

        val tick = object : Runnable {
            override fun run() {
                if (isPaused) return
                remaining -= 1
                progressBarTime.progress = remaining
                if (remaining <= 0) {
                    if (!answered) handleAnswer(
                        selectedDisplay = null,
                        correctDisplay = correctDisplay,
                        song = song
                    )
                } else {
                    countdownHandler.postDelayed(this, 100L)
                }
            }
        }
        countdownHandler.post(tick)
    }


    // ── Answer handling ───────────────────────────────────────────────────────

    private fun handleAnswer(selectedDisplay: String?, correctDisplay: String, song: BirdSong) {
        answered = true
        countdownHandler.removeCallbacksAndMessages(null)
        progressBarTime.progress = 0
        btnQuizPause.isEnabled = false

        val isCorrect = selectedDisplay == correctDisplay
        val timedOut  = selectedDisplay == null

        if (isCorrect) { score += 10; playDing() } else playDingNegative()

        answerButtons.forEach { btn ->
            btn.isEnabled = false
            when {
                timedOut && btn.text == correctDisplay -> {
                    btn.setBackgroundColor(colorUnpicked)
                    btn.setTextColor(android.graphics.Color.LTGRAY)
                }
                !timedOut && btn.text == correctDisplay -> {
                    btn.setBackgroundColor(colorCorrect)
                    btn.setTextColor(android.graphics.Color.WHITE)
                }
                btn.text == selectedDisplay && !isCorrect -> {
                    btn.setBackgroundColor(colorWrong)
                    btn.setTextColor(android.graphics.Color.WHITE)
                }
                else -> {
                    btn.setBackgroundColor(colorUnpicked)
                    btn.setTextColor(android.graphics.Color.LTGRAY)
                }
            }
        }

        showBirdInfo(song)
        updateScoreDisplay()

        handler.postDelayed({
            if (currentIndex < totalQuestions - 1) {
                currentIndex++
                loadQuestion(currentIndex)
            } else {
                showFinalScore()
            }
        }, 2500L)
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showBirdInfo(song: BirdSong) {
        tvQuizSlovenianName.text  = song.slovenianName.ifEmpty { song.scientificName }
        tvQuizCommonName.text     = song.commonName
        tvQuizScientificName.text = song.scientificName
        tvQuizMeta.text           = "Sound type: ${song.soundType}"
        layoutBirdInfo.visibility = View.VISIBLE
    }

    private fun updateScoreDisplay() {
        tvProgress.text = "${currentIndex + 1}/$totalQuestions"
        tvScore.text    = "$score"
    }

    private fun showFinalScore() {
        stopPlayback()
        val percentage = if (totalQuestions > 0)
            (score / (totalQuestions * 10f) * 100).toInt() else 0

        val message = when {
            percentage == 100 -> "Perfect score! 🎉"
            percentage >= 70  -> "Great job! 🐦"
            percentage >= 40  -> "Keep practicing!"
            else              -> "Better luck next time!"
        }

        AlertDialog.Builder(this)
            .setTitle("Quiz Complete!")
            .setMessage("$message\n\nScore: $score / ${totalQuestions * 10}\n($percentage%)")
            .setPositiveButton("Play Again") { _, _ -> recreate() }
            .setNegativeButton("Main Menu")  { _, _ -> finish() }
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_bg)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.cream))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.cream))
            }
    }

    private fun playDing() {
        try { dingPlayer?.seekTo(0); dingPlayer?.start() } catch (e: Exception) { }
    }

    private fun playDingNegative() {
        try { dingNegativePlayer?.seekTo(0); dingNegativePlayer?.start() } catch (e: Exception) { }
    }

    private fun stopPlayback() {
        durationHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        handler.removeCallbacksAndMessages(null)
        countdownHandler.removeCallbacksAndMessages(null)
        dingPlayer?.release()
        dingPlayer = null
        dingNegativePlayer?.release()
        dingNegativePlayer = null
    }
}