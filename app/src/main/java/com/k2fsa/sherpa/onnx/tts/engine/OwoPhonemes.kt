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
/**
     * Splits one sentence's phonemes into short chunks so synthesis can start playing sooner:
     * sherpa-onnx emits audio once per "sentence", and a model can't start a sentence's audio
     * until it has generated all of it. Cuts after , ; : once a chunk has [minLen] phonemes,
     * and at the last space once a chunk reaches [maxLen].
     */
    fun splitForStreaming(s: IntArray, minLen: Int = 12, maxLen: Int = 70): List<IntArray> {
        val out = mutableListOf<IntArray>()
        fun add(from: Int, to: Int) {
            val chunk = s.copyOfRange(from, to).dropWhile { it == ' '.code }.toIntArray()
            if (chunk.isNotEmpty()) out.add(chunk)
        }
        var start = 0
        var lastSpace = -1
        for (i in s.indices) {
            val c = s[i]
            if (c == ' '.code) lastSpace = i
            val len = i + 1 - start
            if ((c == '\n'.code) && len >= minLen) {
                add(start, i + 1)
                start = i + 1
                lastSpace = -1
            } else if (len >= maxLen && lastSpace > start) {
                add(start, lastSpace)
                start = lastSpace + 1
                lastSpace = -1
            }
        }
        add(start, s.size)
        return out
    }
    /** Splits one chunk at the last space before [max] phonemes (used to make the very first chunk tiny). */
    fun splitFirstChunk(chunk: IntArray, max: Int): List<IntArray> {
        if (chunk.size <= max) return listOf(chunk)
        val cut = (0 until max).lastOrNull { chunk[it] == ' '.code } ?: return listOf(chunk)
        return listOf(chunk.copyOfRange(0, cut), chunk.copyOfRange(cut + 1, chunk.size))
    }

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
