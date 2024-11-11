package one.wabbit.parsing.regex
//
//interface CharMatcher {
//    fun state(): Int
//    fun reset(): Unit
//
//    fun feed(c: Char): Boolean
//    fun feed(chunk: CharArray, from: Int, to: Int): Int
//
//    fun failed(): Boolean
//    fun nullable(): Boolean
//}
//
//fun CharMatcher.matches(chunk: String, from: Int = 0, to: Int = chunk.length): Boolean {
//    reset()
//    feed(chunk.toCharArray(), from, to)
//    return nullable()
//}
//
//fun CharMatcher.matches(chunk: CharArray, from: Int = 0, to: Int = chunk.size): Boolean {
//    reset()
//    feed(chunk, from, to)
//    return nullable()
//}
