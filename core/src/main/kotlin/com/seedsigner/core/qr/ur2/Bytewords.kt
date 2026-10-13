package com.seedsigner.core.qr.ur2

/**
 * Port of seedsigner_orig/src/seedsigner/helpers/ur2/bytewords.py
 * (Copyright © 2020 Foundation Devices, Inc., BSD-2-Clause Plus Patent License).
 */
object Bytewords {
    const val StyleStandard = 1
    const val StyleUri = 2
    const val StyleMinimal = 3

    private const val BYTEWORDS = "ableacidalsoapexaquaarchatomauntawayaxisbackbaldbarnbeltbetabiasbluebodybragbrewbulbbuzzcalmcashcatschefcityclawcodecolacookcostcruxcurlcuspcyandarkdatadaysdelidicedietdoordowndrawdropdrumdulldutyeacheasyechoedgeepicevenexamexiteyesfactfairfernfigsfilmfishfizzflapflewfluxfoxyfreefrogfuelfundgalagamegeargemsgiftgirlglowgoodgraygrimgurugushgyrohalfhanghardhawkheathelphighhillholyhopehornhutsicedideaidleinchinkyintoirisironitemjadejazzjoinjoltjowljudojugsjumpjunkjurykeepkenokeptkeyskickkilnkingkitekiwiknoblamblavalazyleaflegsliarlimplionlistlogoloudloveluaulucklungmainmanymathmazememomenumeowmildmintmissmonknailnavyneednewsnextnoonnotenumbobeyoboeomitonyxopenovalowlspaidpartpeckplaypluspoempoolposepuffpumapurrquadquizraceramprealredorichroadrockroofrubyruinrunsrustsafesagascarsetssilkskewslotsoapsolosongstubsurfswantacotasktaxitenttiedtimetinytoiltombtoystriptunatwinuglyundouniturgeuservastveryvetovialvibeviewvisavoidvowswallwandwarmwaspwavewaxywebswhatwhenwhizwolfworkyankyawnyellyogayurtzapszerozestzinczonezoom"

    private const val DIM = 26


    // Lazily generated two-dimensional lookup table (first letter, last letter) -> byte.
    // Flattened 26x26 table (Python source: 26 lists of 26 ints).
    private val wordArray: IntArray = IntArray(DIM * DIM) { -1 }.also { arr ->
        for (i in 0 until 256) {
            val o = i * 4
            val x = BYTEWORDS[o] - 'a'
            val y = BYTEWORDS[o + 3] - 'a'
            arr[y * DIM + x] = i
        }
    }

    private fun decodeWord(word: String, wordLen: Int): Int {
        require(word.length == wordLen) { "Invalid Bytewords." }
        val x = word[0].lowercaseChar() - 'a'
        val y = word[if (word.length == 4) 3 else 1].lowercaseChar() - 'a'
        require(x in 0 until DIM && y in 0 until DIM) { "Invalid Bytewords." }
        val offset = y * DIM + x
        val value = wordArray[offset]
        require(value != -1) { "Invalid Bytewords." }
        if (word.length == 4) {
            val o = value * 4
            val c1 = word[1].lowercaseChar()
            val c2 = word[2].lowercaseChar()
            require(c1 == BYTEWORDS[o + 1] && c2 == BYTEWORDS[o + 2]) { "Invalid Bytewords." }
        }
        return value
    }

    private fun getWord(index: Int) = BYTEWORDS.substring(index * 4, index * 4 + 4)

    private fun getMinimalWord(index: Int): String {
        val o = index * 4
        return BYTEWORDS[o].toString() + BYTEWORDS[o + 3].toString()
    }

    private fun addCrc(buf: ByteArray) = buf + Crc32.crc32n(buf)

    fun encode(style: Int, bytes: ByteArray): String = when (style) {
        StyleStandard -> (addCrc(bytes).map { getWord(it.toInt() and 0xFF) }).joinToString(" ")
        StyleUri -> (addCrc(bytes).map { getWord(it.toInt() and 0xFF) }).joinToString("-")
        StyleMinimal -> buildString {
            for (b in addCrc(bytes)) append(getMinimalWord(b.toInt() and 0xFF))
        }
        else -> throw IllegalArgumentException("Invalid Bytewords style")
    }

    fun decode(style: Int, s: String): ByteArray {
        val wordLen = if (style == StyleMinimal) 2 else 4
        val words = if (wordLen == 4) {
            val separator = if (style == StyleUri) "-" else " "
            s.split(separator)
        } else {
            s.chunked(2)
        }
        val buf = ByteArray(words.size) { i -> decodeWord(words[i], wordLen).toByte() }
        require(buf.size >= 5) { "Invalid Bytewords." }
        val body = buf.copyOfRange(0, buf.size - 4)
        require(Crc32.crc32n(body).contentEquals(buf.copyOfRange(buf.size - 4, buf.size))) { "Invalid Bytewords." }
        return body
    }
}
