package one.wabbit.parsing

import java.nio.CharBuffer

interface CharMatcher : Matcher<Char> {
    override fun state(): Int
    override fun reset()
    override fun nullable(): Boolean
    override fun failed(): Boolean

    fun feed(chunk: CharArray, from: Int, until: Int): Int
    fun feed(chunk: String, from: Int, until: Int): Int
    fun feed(chunk: CharSequence, from: Int, until: Int): Int
    fun feed(chunk: CharBuffer, from: Int, until: Int): Int

    fun matches(s: CharSequence): Boolean {
        reset()
        // System.err.println("Matching: " + s + " with " + this + " (" + this.getClass() + ")");
        // System.err.println("State: " + state() + " (nullable: " + nullable() + ")" + " (failed: " + failed() + ")");
        val n = feed(s, 0, s.length)
        // System.err.println("Consumed: " + n);
        // System.err.println("State: " + state() + " (nullable: " + nullable() + ")" + " (failed: " + failed() + ")");
        return nullable()
    }
}
