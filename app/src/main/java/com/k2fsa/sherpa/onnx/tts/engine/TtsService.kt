package com.k2fsa.sherpa.onnx.tts.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import com.k2fsa.sherpa.onnx.GenerationConfig
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import android.os.SystemClock

class TtsService : TextToSpeechService() {

    // Member variables to hold state for the callback
    private var currentPitch = 100f
    private var currentSynthesisCallback: SynthesisCallback? = null
    // latency instrumentation (look for "LATENCY" in logcat)
    private var reqStart = 0L
    private var firstAudioLogged = false
    private fun lat(what: String) = Log.i(TAG, "LATENCY ${SystemClock.elapsedRealtime() - reqStart} ms since request: $what")

    override fun onCreate() {
        Log.i(TAG, "onCreate tts service")
        super.onCreate()
        val preferenceHelper = PreferenceHelper(this)
        val language = preferenceHelper.getCurrentLanguage()
        // see https://github.com/Miserlou/Android-SDK-Samples/blob/master/TtsEngine/src/com/example/android/ttsengine/RobotSpeakTtsService.java#L68
        val t = SystemClock.elapsedRealtime()
         onLoadLanguage(language, "", "")
        Log.i(TAG, "LATENCY service onCreate model load took ${SystemClock.elapsedRealtime() - t} ms")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy tts service")
        super.onDestroy()
    }

    // https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeechService#onislanguageavailable
    override fun onIsLanguageAvailable(_lang: String?, _country: String?, _variant: String?): Int {
        val lang = _lang ?: ""
        return if (TtsEngine.getAvailableLanguages(this).contains(lang)) {
            TextToSpeech.LANG_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> {  //returns language currently being used
        return arrayOf(TtsEngine.lang!!, "", "")
    }

    // https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeechService#onLoadLanguage(kotlin.String,%20kotlin.String,%20kotlin.String)
    override fun onLoadLanguage(_lang: String?, _country: String?, _variant: String?): Int {
        Log.i(TAG, "onLoadLanguage: $_lang, $_country")
        val lang = _lang ?: ""
        Migrate.renameModelFolder(this)   //Rename model folder if "old" structure
        val preferenceHelper = PreferenceHelper(this)
        return if (preferenceHelper.getCurrentLanguage().equals("")) {
            TextToSpeech.LANG_MISSING_DATA
        } else {
            if (TtsEngine.getAvailableLanguages(this).contains(lang)) {
                Log.i(TAG, "creating tts, lang :$lang")
                TtsEngine.createTts(application, lang)
                TextToSpeech.LANG_AVAILABLE
            } else {
                Log.i(TAG, "lang $lang not supported, tts engine lang: ${TtsEngine.lang}")
                TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    override fun onStop() {}

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        Log.i(TAG, "onSynthesizeText")
        reqStart = SystemClock.elapsedRealtime()
        firstAudioLogged = false
        if (TtsEngine.tts == null || request == null || callback == null) {
            return
        }
        val language = request.language
        val country = request.country
        val variant = request.variant
        var pitch = 100f

        val ret = onIsLanguageAvailable(language, country, variant)
        if (ret == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.error()
            return
        } else {
            TtsEngine.createTts(application, language)
            lat("createTts done")
        }

        val preferenceHelper = PreferenceHelper(this)

        if (preferenceHelper.applySystemSpeed()) {
            pitch = request.pitch * 1.0f
            TtsEngine.speed.value = request.speechRate / pitch  //divide by pitch to compensate for pitch adjustment performed in ttsCallback
        }         // request.speechRate: System does not memorize different speeds for different languages

        var text = request.charSequenceText.toString()

        if (preferenceHelper.getStripSSML()) text = TtsEngine.stripSsmlTags(text)



        val tts = TtsEngine.tts!!

        callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1)

        if (text.isBlank() || text.isEmpty()) {
            callback.done()
            return
        }

        // Store state in member variables so the function reference can access them
        currentPitch = pitch
        currentSynthesisCallback = callback

        // FIX: Use a function reference (::ttsCallback) instead of an inline lambda.
        // This forces the Kotlin compiler to generate the correct JNI signature: ([F)Ljava/lang/Integer;
        val (genText, genConfig) = TtsEngine.prepareGeneration(text)
        Log.i(TAG, "owo shift=${TtsEngine.owoShift()} modelType=${TtsEngine.modelType} genSpeed=${genConfig.speed}")
        lat("phonemized (${genConfig.phonemeCodepoints?.size ?: 0} chunks)")
        genConfig.speed /= TtsEngine.owoShift() // cancel the duration change from the resample in ttsCallback
        tts.generateWithConfigAndCallback(
            text = genText,
            config = genConfig,
            callback = ::ttsCallback,
        )

        callback.done()

        // Clear state after synthesis is complete
        currentSynthesisCallback = null
    }

    // This MUST be a member function so we can use the ::ttsCallback reference
    private fun ttsCallback(floatSamples: FloatArray): Int {
        val cb = currentSynthesisCallback ?: return 0
        // log every chunk: audio length vs time taken (real-time factor > 1 means generation is slower than playback)
        val audioSec = floatSamples.size.toFloat() / TtsEngine.tts!!.sampleRate()
        lat("model chunk: %.2f s of audio, %d samples%s".format(audioSec, floatSamples.size, if (!firstAudioLogged) " (FIRST)" else ""))
        firstAudioLogged = true
        val pitch = currentPitch

        val samples: ByteArray

        val speedFactor = pitch / 100f * TtsEngine.owoShift()
        if (speedFactor != 1f) {   //if not default pitch, play samples faster or slower. Speed has already been compensated before generation, see above
            val newSampleCount = (floatSamples.size / speedFactor).toInt()
            val newSamples = FloatArray(newSampleCount)

            for (i in 0 until newSampleCount) {
                // linear interpolation between neighbouring samples (avoids the grain of nearest-sample picking)
                val pos = i * speedFactor
                val i0 = pos.toInt()
                val i1 = minOf(i0 + 1, floatSamples.size - 1)
                val frac = pos - i0
                newSamples[i] = (floatSamples[i0] * (1f - frac) + floatSamples[i1] * frac) * TtsEngine.volume.value
            }
            // Convert the modified FloatArray to ByteArray
            samples = floatArrayToByteArray(newSamples)
        } else {
            // The floatSamples array is a fresh instance created by JNI for this callback,
            // so modifying it in place is safe and avoids an extra allocation.
            // Convert FloatArray to ByteArray
            for (i in floatSamples.indices) {
                floatSamples[i] *= TtsEngine.volume.value
            }
            samples = floatArrayToByteArray(floatSamples)
        }

        val maxBufferSize: Int = cb.maxBufferSize
        var offset = 0
        while (offset < samples.size) {
            val bytesToWrite = Math.min(maxBufferSize, samples.size - offset)
            cb.audioAvailable(samples, offset, bytesToWrite)
            offset += bytesToWrite
        }

        // 1 means to continue
        // 0 means to stop
        return 1
    }

    private fun floatArrayToByteArray(audio: FloatArray): ByteArray {
        // byteArray is actually a ShortArray
        val byteArray = ByteArray(audio.size * 2)
        for (i in audio.indices) {
            val sample = (audio[i] * 32767).toInt()
            byteArray[2 * i] = sample.toByte()
            byteArray[2 * i + 1] = (sample shr 8).toByte()
        }
        return byteArray
    }
}
