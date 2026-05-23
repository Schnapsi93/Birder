package com.yourname.birder

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class QuizActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val durationHandler = Handler(Looper.getMainLooper())
    private val countdownHandler = Handler(Looper.getMainLooper())

    private lateinit var progressBarTime: ProgressBar
    private lateinit var tvScore: TextView
    private lateinit var tvProgress: TextView
    private val answerButtons = mutableListOf<Button>()

    private var songs: List<BirdSong> = emptyList()       // quiz playlist (shuffled)
    private var allSpecies: List<String> = emptyList()    // all enabled species common names
    private var currentIndex = 0
    private var score = 0
    private var totalQuestions = 0
    private var listeningDurationSec = 15
    private var answered = false                          // prevent double-tap

    private val ANSWER_WINDOW_SEC = 5                     // seconds to answer
    private val TOTAL_QUESTIONS = 10
    private val OPTIONS_COUNT = 6

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz)

        progressBarTime = findViewById(R.id.progressBarTime)
        tvScore = findViewById(R.id.tvScore)
        tvProgress = findViewById(R.id.tvProgress)

        // Collect answer buttons
        answerButtons.add(findViewById(R.id.btnAnswer1))
        answerButtons.add(findViewById(R.id.btnAnswer2))
        answerButtons.add(findViewById(R.id.btnAnswer3))
        answerButtons.add(findViewById(R.id.btnAnswer4))
        answerButtons.add(findViewById(R.id.btnAnswer5))
        answerButtons.add(findViewById(R.id.btnAnswer6))

        val prefs = getSharedPreferences("birder_prefs", MODE_PRIVATE)
        listeningDurationSec = prefs.getInt("listening_duration_sec", 15)

        // Build quiz from cached songs
        val allSongs = BirdRepository.loadShuffled(this)
        if (allSongs.isEmpty()) {
            Toast.makeText(this, "No songs available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Unique species pool for wrong answers
        allSpecies = allSongs.map { it.commonName }.distinct()

        // Pick TOTAL_QUESTIONS songs — one per species where possible
        songs = allSongs
            .groupBy { it.scientificName }
            .values
            .mapNotNull { it.randomOrNull() }
            .shuffled()
            .take(TOTAL_QUESTIONS)

        totalQuestions = songs.size

        updateScoreDisplay()
        loadQuestion(currentIndex)
    }

    private fun loadQuestion(index: Int) {
        val song = songs.getOrNull(index) ?: return
        answered = false

        // Reset button states
        answerButtons.forEach { btn ->
            btn.isEnabled = true
            btn.backgroundTintList = null
            btn.setBackgroundColor(getColor(R.color.green_mid))
            btn.setTextColor(getColor(R.color.cream))
        }

        // Build answer options: 1 correct + 5 random wrong
        val correctName = song.commonName
        val wrongOptions = allSpecies
            .filter { it != correctName }
            .shuffled()
            .take(OPTIONS_COUNT - 1)

        val options = (wrongOptions + correctName).shuffled()

        options.forEachIndexed { i, name ->
            answerButtons[i].text = name
            answerButtons[i].setOnClickListener {
                if (!answered) handleAnswer(name == correctName, correctName)
            }
        }

        // Stop any previous playback
        stopPlayback()

        // Play the sound
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(song.file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play audio", Toast.LENGTH_SHORT).show()
        }

        // Advance after listening duration
        durationHandler.removeCallbacksAndMessages(null)
        durationHandler.postDelayed({
            mediaPlayer?.pause()
            startAnswerCountdown()
        }, listeningDurationSec * 1000L)

        // Also advance if song ends before duration
        mediaPlayer?.setOnCompletionListener {
            durationHandler.removeCallbacksAndMessages(null)
            startAnswerCountdown()
        }
    }

    /**
     * After sound stops, user has ANSWER_WINDOW_SEC seconds to tap an answer.
     */
    private fun startAnswerCountdown() {
        progressBarTime.max = ANSWER_WINDOW_SEC * 10
        progressBarTime.progress = ANSWER_WINDOW_SEC * 10

        val interval = 100L
        var remaining = ANSWER_WINDOW_SEC * 10

        countdownHandler.removeCallbacksAndMessages(null)
        val tick = object : Runnable {
            override fun run() {
                remaining -= 1
                progressBarTime.progress = remaining
                if (remaining <= 0) {
                    if (!answered) handleAnswer(correct = false, correctName = songs[currentIndex].commonName)
                } else {
                    countdownHandler.postDelayed(this, interval)
                }
            }
        }
        countdownHandler.post(tick)
    }

    private fun handleAnswer(correct: Boolean, correctName: String) {
        answered = true
        countdownHandler.removeCallbacksAndMessages(null)
        progressBarTime.progress = 0


        if (correct) {
            score += 10
            // Highlight correct button green
            answerButtons.first { it.text == correctName }
                .setBackgroundColor(getColor(android.R.color.holo_green_dark))
        } else {
            // Highlight correct answer, wrong ones gray
            answerButtons.forEach { btn ->
                if (btn.text == correctName) {
                    btn.setBackgroundColor(getColor(android.R.color.holo_green_dark))
                } else {
                    btn.setBackgroundColor(getColor(android.R.color.darker_gray))
                }
            }

        }

        // Disable all buttons
        answerButtons.forEach { it.isEnabled = false }

        updateScoreDisplay()

        // Wait 1.5 seconds so user sees the result, then advance
        handler.postDelayed({
            if (currentIndex < totalQuestions - 1) {
                currentIndex++
                loadQuestion(currentIndex)
            } else {
                showFinalScore()
            }
        }, 1500L)
    }

    private fun updateScoreDisplay() {
        tvProgress.text = "${currentIndex + 1}/$totalQuestions"
        tvScore.text = "$score"
    }

    private fun showFinalScore() {
        stopPlayback()
        val percentage = if (totalQuestions > 0) (score / (totalQuestions * 10f) * 100).toInt() else 0
        val message = when {
            percentage == 100 -> "Perfect score!"
            percentage >= 70  -> "Great job! 🐦"
            percentage >= 40  -> "Keep practicing!"
            else              -> "Better luck next time!"
        }

        AlertDialog.Builder(this)
            .setTitle("Quiz Complete!")
            .setMessage("$message\n\nScore: $score / ${totalQuestions * 10}\n($percentage%)")
            .setPositiveButton("Play Again") { _, _ ->
                recreate()
            }
            .setNegativeButton("Main Menu") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_bg)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.cream))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.cream))
            }
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
    }
}