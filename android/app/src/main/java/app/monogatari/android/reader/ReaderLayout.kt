package app.monogatari.android.reader

/** A landscape page is a spread of its own. Pairing restarts after each landscape page. */
object ReaderLayout {
    fun spreads(pairable: List<Boolean>, double: Boolean, offset: Boolean,isolatedHeads:Set<Int> = emptySet()): List<List<Int>> {
        if (!double) return pairable.indices.map(::listOf)
        val result = mutableListOf<List<Int>>()
        val firstPairable = pairable.indexOfFirst { it }
        var index = 0
        while (index < pairable.size) {
            if (!pairable[index] || index in isolatedHeads || offset && index == firstPairable || index == pairable.lastIndex || !pairable[index + 1] || index+1 in isolatedHeads) {
                result += listOf(index); index++
            } else { result += listOf(index, index + 1); index += 2 }
        }
        return result
    }
    fun segmentHead(pairable:List<Boolean>,index:Int):Int? {
        if(index !in pairable.indices)return null
        var head=if(pairable[index])index else (index+1 until pairable.size).firstOrNull{pairable[it]} ?: return null
        while(head>0 && pairable[head-1])head--
        return head
    }
}
