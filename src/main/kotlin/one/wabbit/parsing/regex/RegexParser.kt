package one.wabbit.parsing.regex

import one.wabbit.parsing.CharInput
import one.wabbit.parsing.charset.CharSet
import one.wabbit.parsing.charset.Topology

private fun <S> extractContext(input: CharInput<S>, index: Int) {
    when (input) {
        is CharInput.FromString<S> -> {
            val input = input.input
            input.substring(maxOf(0, index - 5), minOf(input.length, index + 5)) + "\n" + " ".repeat(5) + "^"
        }
    }

}

class RegexParseException(message: String, val input: CharInput<*>, val index: Int) : Exception(message + " at index $index\n${extractContext(input, index)}")

//class CharInput(val input: String) {
//    var index: Int = 0
//
//    val current: Char
//        get() = if (index < input.length) input[index] else EOB
//
//    fun advance(): Char {
//        if (index < input.length) {
//            index++
//            return current
//        } else throw IndexOutOfBoundsException("Unexpected end of input")
//    }
//
//    companion object {
//        val EOB = Char.MAX_VALUE
//    }
//}

object RegexParser {
    private fun <S> error(input: CharInput<S>, message: String): Nothing {
        throw RegexParseException(message, input, input.index)
    }

    private fun <S> CharInput<S>.advance1(): Char {
        if (this.current == CharInput.EOB) {
            error("Unexpected end of input")
        }
        this.advance()
        return this.current
    }

    fun <S> matchCurrent(input: CharInput<S>, c: Char): Char {
        if (input.current != c)
            error("Expected '$c' but got '${input.current}'")
        input.advance1()
        return c
    }
    fun <S> matchCurrent(input: CharInput<S>, c: CharSet): Char {
        assert(CharInput.EOB !in c)

        val current = input.current
        if (current !in c)
            error("Expected '$c' but got '$current'")
        input.advance1()
        return current
    }

    fun <S> matchCurrentSeq(input: CharInput<S>, c: CharSet): String {
        assert(CharInput.EOB !in c)

        val current = input.current
        if (current !in c)
            error("Expected '$c' but got '$current'")

        var result = ""
        while (input.current in c) {
            result += input.current
            input.advance1()
        }
        return result
    }

    fun <S> matchNext(input: CharInput<S>, c: Char): Char {
        input.advance1()
        return matchCurrent(input, c)
    }
    fun <S> matchNext(input: CharInput<S>, c: CharSet): Char {
        assert(CharInput.EOB !in c)

        input.advance1()
        return matchCurrent(input, c)
    }

    fun parse(input: String): Regex<CharSet> =
        parse(CharInput.withPosOnlySpans(input))

    fun <S> parse(input: CharInput<S>): Regex<CharSet> {
        val r = parseAlt(input)
        if (input.current != CharInput.EOB)
            error(input, "Expected end of input but got '${input.current}'")
        return r
    }

    fun <S> parseAlt(input: CharInput<S>): Regex<CharSet> {
        with(Topology.charRanges) {
            val list = mutableListOf<Regex<CharSet>>()

            while (true) {
                list.add(parseSeq(input))

                if (input.current == CharInput.EOB) break
                if (input.current == ')') break

                if (input.current == '|') {
                    input.advance1()
                } else {
                    error(input, "Expected '|' but got '${input.current}'")
                }
            }

            return Regex.alt(list)
        }
    }

    fun <S> parseSeq(input: CharInput<S>): Regex<CharSet> {
        with(Topology.charRanges) {
            val list = mutableListOf<Regex<CharSet>>()

            while (true) {
                val c = input.current
                when (c) {
                    '*' -> error("Unexpected '*'")
                    CharInput.EOB, ')', '|' -> break
                    '[' -> list.add(parseCharSet(input))
                    '(' -> list.add(parseGroup(input))
                    else -> list.add(parseChar(input))
                }
            }

            return Regex.seq(list)
        }
    }

    fun <S> parseGroup(input: CharInput<S>): Regex<CharSet> {
        with (Topology.charRanges) {
            matchCurrent(input, '(')
            if (input.current == '?') {
                input.advance1()
                matchCurrent(input, ':')
            }
            var r = parseAlt(input)
            matchCurrent(input, ')')
            r = applyModifiers(input, r)
            return r
        }
    }

    fun <S> parseCharSet(input: CharInput<S>, skipFirst: Boolean = false): Regex<CharSet> {
        with(Topology.charRanges) {
            if (!skipFirst) matchCurrent(input, '[')
            val c = input.current
            when (c) {
                '^' -> {
                    input.advance1()
                    val r = Regex.oneOf(parseCharSet1(input).invert())
                    matchCurrent(input, ']')
                    return applyModifiers(input, r)
                }

                else -> {
                    val r = Regex.oneOf(parseCharSet1(input))
                    matchCurrent(input, ']')
                    return applyModifiers(input, r)
                }
            }
        }
    }

    fun <S> parseCharSet1(input: CharInput<S>): CharSet {
        with(Topology.charRanges) {
            var set = CharSet.none
            while (input.current != ']') {
                val start = input.current
                input.advance1()
                val end = input.current
                if (end == '-') {
                    input.advance1()
                    val end2 = input.current

                    if (end2 == ']') {
                        set = set.union(CharSet.one(start))
                        set = set.union(CharSet.one('-'))
                        break
                    } else {
                        if (start > end2)
                            set = set.union(CharSet.range(end2, start))
                        else
                            set = set.union(CharSet.range(start, end2))
                        input.advance1()
                    }
                } else {
                    set = set.union(CharSet.one(start))
                }
            }
            return set
        }
    }

    fun <S> parseChar(input: CharInput<S>): Regex<CharSet> {
        with(Topology.charRanges) {
            var r = run {
                val c = input.current
                when (c) {
                    CharInput.EOB, ')', '|', '[', ']' ->
                        throw IllegalArgumentException("Unexpected $c")
                    '.' -> Regex.oneOf(CharSet.all)
                    '\\' -> {
                        input.advance1()
                        when (val c = matchCurrent(input, CharSet.of("nrtdxu", "\\/[]().|*?+"))) {
                            'n' -> return@run Regex.char('\n')
                            'r' -> return@run Regex.char('\r')
                            't' -> return@run Regex.char('\t')
                            'd' -> return@run Regex.oneOf(CharSet.unicodeDigit)
                            'x' -> {
                                val c1 = matchCurrent(input, CharSet.hexDigit).toString()
                                val c2 = matchCurrent(input, CharSet.hexDigit).toString()
                                return@run Regex.char((c1 + c2).toInt(16).toChar())
                            }
                            'u' -> {
                                val c1 = matchCurrent(input, CharSet.hexDigit).toString()
                                val c2 = matchCurrent(input, CharSet.hexDigit).toString()
                                val c3 = matchCurrent(input, CharSet.hexDigit).toString()
                                val c4 = matchCurrent(input, CharSet.hexDigit).toString()
                                return@run Regex.char((c1 + c2 + c3 + c4).toInt(16).toChar())
                            }
                            else -> return@run Regex.char(c)
                        }
                    }
                    else -> {
                        input.advance1()
                        return@run Regex.char(c)
                    }
                }
            }

            return applyModifiers(input, r)
        }
    }

    fun <S> applyModifiers(input: CharInput<S>, r: Regex<CharSet>): Regex<CharSet> {
        with(Topology.charRanges) {
            var r = r
            while (input.current == '*' || input.current == '?' || input.current == '+' || input.current == '{') {
                if (input.current == '*') {
                    input.advance1()
                    r = Regex.star(r)
                } else if (input.current == '+') {
                    input.advance1()
                    r = Regex.plus(r)
                } else if (input.current == '?') {
                    input.advance1()
                    r = Regex.opt(r)
                } else if (input.current == '{') {
                    input.advance1()
                    val min = matchCurrentSeq(input, CharSet.unicodeDigit).toInt()
                    val max = if (input.current == ',') {
                        input.advance1()
                        if (input.current == '}') {
                            input.advance1()
                            null
                        } else {
                            val v = matchCurrentSeq(input, CharSet.unicodeDigit).toInt()
                            matchCurrent(input, '}')
                            v
                        }
                    } else {
                        matchCurrent(input, '}')
                        min
                    }
                    r = Regex.rep(r, min, max)
                }
            }
            return r
        }
    }
}
