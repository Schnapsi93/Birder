package com.yourname.birder

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity


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
    private val answerButtons = mutableListOf<Button>()

    private var songs: List<BirdSong> = emptyList()
    // Display name used on buttons: slovenian if available, else scientific
    private var allDisplayNames: List<String> = emptyList()
    private var currentIndex = 0
    private var score = 0
    private var totalQuestions = 0
    private var listeningDurationSec = 15
    private var answered = false

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

        answerButtons.add(findViewById(R.id.btnAnswer1))
        answerButtons.add(findViewById(R.id.btnAnswer2))
        answerButtons.add(findViewById(R.id.btnAnswer3))
        answerButtons.add(findViewById(R.id.btnAnswer4))
        answerButtons.add(findViewById(R.id.btnAnswer5))
        answerButtons.add(findViewById(R.id.btnAnswer6))

        val prefs = getSharedPreferences("birder_prefs", MODE_PRIVATE)
        listeningDurationSec = prefs.getInt("listening_duration_sec", 15)

        val allSongs = BirdRepository.loadShuffled(this)
        if (allSongs.isEmpty()) {
            Toast.makeText(this, "No songs available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Build display name pool: slovenian if available, else scientific
        allDisplayNames = allSongs
            .map { it.displayName() }
            .distinct()

        // Pick one song per species, up to TOTAL_QUESTIONS
        songs = allSongs
            .groupBy { it.scientificName }
            .values
            .mapNotNull { it.randomOrNull() }
            .shuffled()
            .take(TOTAL_QUESTIONS)

        totalQuestions = songs.size

        dingPlayer = MediaPlayer.create(this, R.raw.ding)
        dingNegativePlayer = MediaPlayer.create(this, R.raw.ding_negative)

        updateScoreDisplay()
        loadQuestion(currentIndex)
    }

    /**
     * Returns the display name for a song:
     * Slovenian name if available, otherwise scientific name.
     */
    private fun BirdSong.displayName(): String =
        slovenianName.ifEmpty { scientificName }

    private fun loadQuestion(index: Int) {
        val song = songs.getOrNull(index) ?: return
        answered = false

        layoutBirdInfo.visibility = View.GONE

        answerButtons.forEach { btn ->
            btn.isEnabled = true
            btn.setBackgroundColor(colorNeutral)
            btn.setTextColor(getColor(R.color.cream))
        }

        val correctDisplay = song.displayName()

        // Wrong options from the full display name pool
        val wrongOptions = allDisplayNames
            .filter { it != correctDisplay }
            .shuffled()
            .take(OPTIONS_COUNT - 1)

        val options = (wrongOptions + correctDisplay).shuffled()

        options.forEachIndexed { i, displayName ->
            answerButtons[i].text = displayName
            answerButtons[i].setOnClickListener {
                if (!answered) handleAnswer(
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

        durationHandler.removeCallbacksAndMessages(null)
        if (listeningDurationSec > 0) {
            durationHandler.postDelayed({
                mediaPlayer?.pause()
                startAnswerCountdown(song, correctDisplay)
            }, listeningDurationSec * 1000L)
        }

        mediaPlayer?.setOnCompletionListener {
            durationHandler.removeCallbacksAndMessages(null)
            startAnswerCountdown(song, correctDisplay)
        }
    }

    private fun startAnswerCountdown(song: BirdSong, correctDisplay: String) {
        progressBarTime.max = ANSWER_WINDOW_SEC * 10
        progressBarTime.progress = ANSWER_WINDOW_SEC * 10

        var remaining = ANSWER_WINDOW_SEC * 10
        countdownHandler.removeCallbacksAndMessages(null)

        val tick = object : Runnable {
            override fun run() {
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

//    private fun handleAnswer(selectedDisplay: String?, correctDisplay: String, song: BirdSong) {
//        answered = true
//        countdownHandler.removeCallbacksAndMessages(null)
//        progressBarTime.progress = 0
//
//        val isCorrect = selectedDisplay == correctDisplay
//
//        if (isCorrect) {
//            score += 10
//            playDing()
//        } else{
//            playDingNegative()
//        }
//
//        // Colour buttons
//        answerButtons.forEach { btn ->
//            btn.isEnabled = false
//            when {
//                btn.text == correctDisplay -> {
//                    btn.setBackgroundColor(colorCorrect)
//                    btn.setTextColor(android.graphics.Color.WHITE)
//                }
//                btn.text == selectedDisplay && !isCorrect -> {
//                    btn.setBackgroundColor(colorWrong)
//                    btn.setTextColor(android.graphics.Color.WHITE)
//                }
//                else -> {
//                    btn.setBackgroundColor(colorUnpicked)
//                    btn.setTextColor(android.graphics.Color.LTGRAY)
//                }
//            }
//        }
//
//        showBirdInfo(song)
//        updateScoreDisplay()
//
//        handler.postDelayed({
//            if (currentIndex < totalQuestions - 1) {
//                currentIndex++
//                loadQuestion(currentIndex)
//            } else {
//                showFinalScore()
//            }
//        }, 2500L)
//    }

    private fun handleAnswer(selectedDisplay: String?, correctDisplay: String, song: BirdSong) {
        answered = true
        countdownHandler.removeCallbacksAndMessages(null)
        progressBarTime.progress = 0

        val isCorrect = selectedDisplay == correctDisplay
        val timedOut = selectedDisplay == null

        when {
            isCorrect -> {
                score += 10
                playDing()
            }
            else -> {
                playDingNegative()
            }
        }

        // Colour buttons
        answerButtons.forEach { btn ->
            btn.isEnabled = false
            when {
                timedOut && btn.text == correctDisplay -> {
                    // Timed out — correct answer shown in neutral grey, not green
                    btn.setBackgroundColor(colorUnpicked)
                    btn.setTextColor(android.graphics.Color.LTGRAY)
                }
                !timedOut && btn.text == correctDisplay -> {
                    // User answered — correct answer shown in green
                    btn.setBackgroundColor(colorCorrect)
                    btn.setTextColor(android.graphics.Color.WHITE)
                }
                btn.text == selectedDisplay && !isCorrect -> {
                    // Wrong button tapped — red
                    btn.setBackgroundColor(colorWrong)
                    btn.setTextColor(android.graphics.Color.WHITE)
                }
                else -> {
                    // All other buttons — grey
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

    private fun showBirdInfo(song: BirdSong) {
        // Slovenian name — biggest, top
        tvQuizSlovenianName.text = song.slovenianName.ifEmpty { song.scientificName }
        // English common name
        tvQuizCommonName.text = song.commonName
        // Scientific name — smallest, italic
        tvQuizScientificName.text = song.scientificName
        // Sound type
        tvQuizMeta.text = "Sound type: ${song.soundType}"

        layoutBirdInfo.visibility = View.VISIBLE
    }

    private fun playDing() {
        try {
            dingPlayer?.seekTo(0)
            dingPlayer?.start()
        } catch (e: Exception) { }
    }

    private fun playDingNegative() {
        try {
            dingNegativePlayer?.seekTo(0)
            dingNegativePlayer?.start()
        } catch (e: Exception) { }
    }

    private fun updateScoreDisplay() {
        tvProgress.text = "${currentIndex + 1}/$totalQuestions"
        tvScore.text = "$score"
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
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(getColor(R.color.cream))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(getColor(R.color.cream))
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
        dingPlayer?.release()
        dingPlayer = null
        dingNegativePlayer?.release()
        dingNegativePlayer = null
    }
}