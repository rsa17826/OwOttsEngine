package com.k2fsa.sherpa.onnx.tts.engine

import kotlin.random.Random

/**
 * Owo accent applied to IPA phonemes (as produced by piper-phonemize / espeak-ng),
 * not to the spelling, so the engine's whole-word pronunciation logic is kept.
 *
 * Each rule takes the IPA string of one sentence and returns a new one.
 * Add, remove or reorder entries in [rules] to change the accent.
 */
object OwoPhonemes {
    private const val STRESS = "ˈˌ"
    private const val VOWELS = "aeiouæɑɒɔəɛɜɪʊʌɐɚɝɨʉøœyɯɤɘɵɞ"

    private val rules: List<(String) -> String> = listOf(
        ::rToW,
        ::nVowelToNy,
    )

    fun apply(sentence: IntArray): IntArray {
        var s = String(sentence, 0, sentence.size)
        for (rule in rules) s = rule(s)
        return s.codePoints().toArray()
    }

    private fun isVowelAt(s: String, start: Int): Boolean {
        var i = start
        while (i < s.length && STRESS.indexOf(s[i]) >= 0) i++
        return i < s.length && VOWELS.indexOf(s[i]) >= 0
    }

    // r / l -> w ("really" -> "weawwy"); r-coloured vowels first
    private fun rToW(s: String): String =
        s.replace("ɚ", "əw").replace("ɝ", "ɜw")
            .replace(Regex("[ɹɾrlɫ]"), "w")

    // n + vowel -> ny ("no" -> "nyo")
    private fun nVowelToNy(s: String): String {
        val sb = StringBuilder()
        for (i in s.indices) {
            sb.append(s[i])
            if (s[i] == 'n' && isVowelAt(s, i + 1)) sb.append('j')
        }
        return sb.toString()
    }
}
