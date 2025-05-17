package com.programminghut.realtime_object

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import java.util.*

class HomeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var textToSpeech: TextToSpeech
    private lateinit var cardTextRecognition: CardView
    private lateinit var cardObjectRecognition: CardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)

        // Initialize UI components
        cardTextRecognition = findViewById(R.id.cardTextRecognition)
        cardObjectRecognition = findViewById(R.id.cardObjectRecognition)

        // Set click listeners for the feature buttons
        cardTextRecognition.setOnClickListener {
            speakOut("Opening Text Recognition")
            val intent = Intent(this, TextRecognitionActivity::class.java)
            startActivity(intent)
        }

        cardObjectRecognition.setOnClickListener {
            speakOut("Opening Object Recognition")
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Welcome message when app starts or returns to this screen
        speakOut("Welcome to Drishti Cone. Please select a feature. Text Recognition or Object Recognition.")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set language for TTS
            val result = textToSpeech.setLanguage(Locale.US)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Language not supported for Text to Speech", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Text to Speech initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun speakOut(text: String) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    override fun onDestroy() {
        // Release TTS resources
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        super.onDestroy()
    }
}
