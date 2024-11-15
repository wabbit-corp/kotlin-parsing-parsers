package one.wabbit.parsing

interface Matcher<S> {
    fun state(): Int
    fun reset()
    fun nullable(): Boolean
    fun failed(): Boolean

    fun feed(chunk: Array<S>, from: Int, until: Int): Int
    fun feed(chunk: List<S>, from: Int, until: Int): Int
}
