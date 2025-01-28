package one.wabbit.parsing.grammars

import one.wabbit.data.Need
import one.wabbit.parsing.charset.CharSet
import java.math.BigInteger
import java.util.*
import kotlin.reflect.KProperty

// linear, real-time
sealed class Parser<Set, Sym, out A> {
    data class Error<Set, Sym>(val message: String) : Parser<Set, Sym, Nothing>()
    data class Named<Set, Sym, A>(val name: String, val parser: Parser<Set, Sym, A>, val debug: Boolean, val opaque: Boolean) : Parser<Set, Sym, A>()

    class Epsilon<Set, Sym> : Parser<Set, Sym, Unit>()
    data class Terminal<Set, Sym>(val symbol: Set) : Parser<Set, Sym, Sym>()

    data class Choice<Set, Sym, A>(val list: List<Parser<Set, Sym, A>>) : Parser<Set, Sym, A>() {
        init { require(list.isNotEmpty()) }
    }
    data class Sequence<Set, Sym, A, B>(val left: Parser<Set, Sym, A>, val right: Parser<Set, Sym, B>) : Parser<Set, Sym, Pair<A, B>>()
    data class ZipLeft<Set, Sym, A, B>(val left: Parser<Set, Sym, A>, val right: Parser<Set, Sym, B>) : Parser<Set, Sym, A>()
    data class ZipRight<Set, Sym, A, B>(val left: Parser<Set, Sym, A>, val right: Parser<Set, Sym, B>) : Parser<Set, Sym, B>()
    data class Star<Set, Sym, A>(val parser: Parser<Set, Sym, A>) : Parser<Set, Sym, List<A>>()
    data class Plus<Set, Sym, A>(val parser: Parser<Set, Sym, A>) : Parser<Set, Sym, List<A>>()
    data class Optional<Set, Sym, A : Any>(val parser: Parser<Set, Sym, A>) : Parser<Set, Sym, A?>()
    data class Test<Set, Sym, A>(val parser: Parser<Set, Sym, A>) : Parser<Set, Sym, Boolean>()
    data class Ignore<Set, Sym>(val parser: Parser<Set, Sym, Any?>) : Parser<Set, Sym, Unit>()
    data class Capture<Set, Sym, out X>(val parser: Parser<Set, Sym, X>) : Parser<Set, Sym, List<Sym>>()
    data class CaptureString<X>(val parser: Parser<CharSet, Char, X>) : Parser<CharSet, Char, String>()
    data class Map<Set, Sym, A, B>(val parser: Parser<Set, Sym, A>, val transform: (A) -> B) : Parser<Set, Sym, B>()
    data class Filter<Set, Sym, A>(val parser: Parser<Set, Sym, A>, val error: (A) -> String?) : Parser<Set, Sym, A>()
    data class Delay<Set, Sym, A>(val thunk: Need<Parser<Set, Sym, A>>) : Parser<Set, Sym, A>()
    data class FlatMap<Set, Sym, A, B>(val parser: Parser<Set, Sym, A>, val f: (A) -> Parser<Set, Sym, B>) : Parser<Set, Sym, B>()

    fun <B> map(f: (A) -> B): Parser<Set, Sym, B> = Map(this, f)
    fun filter(predicate: (A) -> String?): Parser<Set, Sym, A> = Filter(this, predicate)
    fun <B> flatMap(f: (A) -> Parser<Set, Sym, B>): Parser<Set, Sym, B> = FlatMap(this, f)
    infix fun <B> zipLeft(other: Parser<Set, Sym, B>): Parser<Set, Sym, A> = ZipLeft(this, other)
    infix fun <B> zipRight(other: Parser<Set, Sym, B>): Parser<Set, Sym, B> = ZipRight(this, other)
    operator fun unaryMinus(): Parser<Set, Sym, Unit> = Ignore(this)
    val many: Parser<Set, Sym, List<A>> get() = Star(this)
    val many1: Parser<Set, Sym, List<A>> get() = Plus(this)
    val ignore: Parser<Set, Sym, Unit> get() = Ignore(this)
    fun <B> ignore(value: B): Parser<Set, Sym, B> = ignore.map { value }
    fun <B> ignore(value: () -> B): Parser<Set, Sym, B> = ignore.map { value() }
    val capture: Parser<Set, Sym, List<Sym>> get() = Capture(this)

    operator fun <This> getValue(thisRef: This, property: KProperty<*>): Parser<Set, Sym, A> =
        Named(property.name, this, false, false)

    abstract class Module<Set, Sym> {
        val epsilon: Parser<Set, Sym, Unit> = Epsilon()
        fun <A> ok(v: A): Parser<Set, Sym, A> = Epsilon<Set, Sym>().map { v }

        fun terminal(symbol: Set): Parser<Set, Sym, Sym> = Terminal(symbol)
        fun <A> delay(thunk: () -> Parser<Set, Sym, A>): Parser<Set, Sym, A> =
            Delay(Need.apply(thunk))
        fun <A> parser(thunk: () -> Parser<Set, Sym, A>): Parser<Set, Sym, A> =
            Delay(Need.apply(thunk))

        val <A : Any> Parser<Set, Sym, A>.opt: Parser<Set, Sym, A?> get() = Optional(this)
        val <A> Parser<Set, Sym, A>.test: Parser<Set, Sym, Boolean> get() = Test(this)

        infix fun <Set, Sym, A> Parser<Set, Sym, A>.or(that: Parser<Set, Sym, A>): Parser<Set, Sym, A> =
            when (this) {
                is Choice -> when (that) {
                    is Choice -> Choice(this.list + that.list)
                    else -> Choice(this.list + that)
                }
                else -> when (that) {
                    is Choice -> Choice(listOf(this) + that.list)
                    else -> Choice(listOf(this, that))
                }
            }

        operator fun <Set, Sym, A, B> Parser<Set, Sym, A>.plus(other: Parser<Set, Sym, B>): Parser<Set, Sym, Pair<A, B>> =
            Sequence(this, other)
        @JvmName("timesUnitRight")
        operator fun <Set, Sym, A> Parser<Set, Sym, A>.plus(other: Parser<Set, Sym, Unit>): Parser<Set, Sym, A> =
            ZipLeft(this, other)
        @JvmName("timesUnitLeft")
        operator fun <Set, Sym, A> Parser<Set, Sym, Unit>.plus(other: Parser<Set, Sym, A>): Parser<Set, Sym, A> =
            ZipRight(this, other)
        @JvmName("timesUnitBoth")
        operator fun <Set, Sym> Parser<Set, Sym, Unit>.plus(other: Parser<Set, Sym, Unit>): Parser<Set, Sym, Unit> =
            Ignore(Sequence(this, other))

        fun <A> Parser<Set, Sym, A>.sepBy1(sep: Parser<Set, Sym, Any>): Parser<Set, Sym, List<A>> =
            (this + (sep + this).map2 { _, b -> b }.many).map2 { a, b -> listOf(a) + b }

        fun <A, Op> Parser<Set, Sym, A>.sepBy1(sep: Parser<Set, Sym, Op>, apply: (A, Op, A) -> A): Parser<Set, Sym, A> =
            (this + (sep + this).many).map2 { a, bs ->
                bs.fold(a) { acc, (op, b) -> apply(acc, op, b) }
            }

        fun <A> Parser<Set, Sym, A>.sepBy(sep: Parser<Set, Sym, Any>): Parser<Set, Sym, List<A>> =
            Optional(sepBy1(sep)).map { it ?: emptyList() }

        fun <A> Parser<Set, Sym, A>.named(name: String, debug: Boolean = false): Parser<Set, Sym, A> =
            Parser.Named(name, this, debug, false)
        fun <A> Parser<Set, Sym, A>.namedOpaque(name: String, debug: Boolean = false): Parser<Set, Sym, A> =
            Parser.Named(name, this, debug, true)

        fun <A> choice(vararg ps: Pair<Parser<Set, Sym, Unit>, A>): Parser<Set, Sym, A> =
            Choice(ps.map { (name, value) -> name.ignore(value) })
        fun <A> choice(vararg ps: Parser<Set, Sym, A>): Parser<Set, Sym, A> =
            Choice(ps.toList())
    }

    abstract class CharModule : Module<CharSet, Char>() {
        fun string(str: String): Parser<CharSet, Char, String> =
            str.map { terminal(CharSet.of(it)) }
                .reduce<Parser<CharSet, Char, Any>, Parser<CharSet, Char, Any>> { a, b -> a + b }
                .ignore(str)

        fun char(c: Char): Parser<CharSet, Char, Char> = terminal(CharSet.of(c))
        fun char(vararg cs: Char): Parser<CharSet, Char, Char> = terminal(CharSet.of(*cs))
        fun char(c: CharRange): Parser<CharSet, Char, Char> = terminal(CharSet.of(c))
        fun char(c: CharSet): Parser<CharSet, Char, Char> = terminal(c)

        infix fun <A> Parser<CharSet, Char, A>.or(that: Char): Parser<CharSet, Char, Unit> =
            this.ignore or terminal(CharSet.of(that)).ignore
        infix fun Char.or(that: Parser<CharSet, Char, Any>): Parser<CharSet, Char, Unit> =
            terminal(CharSet.of(this)).ignore or that.ignore
        infix fun <A> Parser<CharSet, Char, A>.or(that: CharSet): Parser<CharSet, Char, Unit> =
            this.ignore or terminal(that).ignore
        infix fun CharSet.or(that: Parser<CharSet, Char, Any>): Parser<CharSet, Char, Unit> =
            terminal(this).ignore or that.ignore

        operator fun Char.not(): Parser<CharSet, Char, Char> = terminal(CharSet.of(this).invert())
        val Char.ignore: Parser<CharSet, Char, Unit> get() = terminal(CharSet.of(this)).ignore
        fun <A> Char.ignore(value: A): Parser<CharSet, Char, A> = terminal(CharSet.of(this)).ignore(value)
        val Char.many: Parser<CharSet, Char, List<Char>> get() = terminal(CharSet.of(this)).many
        val Char.many1: Parser<CharSet, Char, List<Char>> get() = terminal(CharSet.of(this)).many1
        val Char.opt: Parser<CharSet, Char, Char?> get() = terminal(CharSet.of(this)).opt
        val Char.test: Parser<CharSet, Char, Boolean> get() = terminal(CharSet.of(this)).test

        val CharSet.ignore: Parser<CharSet, Char, Unit> get() = terminal(this).ignore
        fun <A> CharSet.ignore(value: () -> A): Parser<CharSet, Char, A> = terminal(this).ignore(value)
        val CharSet.many: Parser<CharSet, Char, List<Char>> get() = terminal(this).many
        val CharSet.many1: Parser<CharSet, Char, List<Char>> get() = terminal(this).many1
        val CharSet.opt: Parser<CharSet, Char, Char?> get() = terminal(this).opt
        val CharSet.test: Parser<CharSet, Char, Boolean> get() = terminal(this).test
        operator fun <A> Parser<CharSet, Char, A>.plus(that: CharSet): Parser<CharSet, Char, A> = this + terminal(that).ignore
        operator fun <A> CharSet.plus(that: Parser<CharSet, Char, A>): Parser<CharSet, Char, A> = terminal(this).ignore + that
        operator fun <A> Parser<CharSet, Char, A>.plus(that: Char): Parser<CharSet, Char, A> = this + terminal(CharSet.of(that)).ignore
        operator fun <A> Char.plus(that: Parser<CharSet, Char, A>): Parser<CharSet, Char, A> = terminal(CharSet.of(this)).ignore + that
        operator fun Char.plus(that: Char): Parser<CharSet, Char, Unit> = terminal(CharSet.of(this)).ignore + terminal(CharSet.of(that)).ignore

        val CharRange.ignore: Parser<CharSet, Char, Unit> get() = terminal(CharSet.of(this)).ignore
        fun <A> CharRange.ignore(value: () -> A): Parser<CharSet, Char, A> = terminal(CharSet.of(this)).ignore(value)
        val CharRange.many: Parser<CharSet, Char, List<Char>> get() = terminal(CharSet.of(this)).many
        val CharRange.many1: Parser<CharSet, Char, List<Char>> get() = terminal(CharSet.of(this)).many1
        val CharRange.opt: Parser<CharSet, Char, Char?> get() = terminal(CharSet.of(this)).opt
        val CharRange.test: Parser<CharSet, Char, Boolean> get() = terminal(CharSet.of(this)).test
        operator fun <A> Parser<CharSet, Char, A>.plus(that: CharRange): Parser<CharSet, Char, A> = this + terminal(CharSet.of(that)).ignore
        operator fun <A> CharRange.plus(that: Parser<CharSet, Char, A>): Parser<CharSet, Char, A> = terminal(CharSet.of(this)).ignore + that
        val CharRange.p: Parser<CharSet, Char, Unit> get() = CharSet.of(this).ignore

        val String.ignore: Parser<CharSet, Char, Unit> get() = string(this).ignore
        fun <A> String.ignore(value: () -> A): Parser<CharSet, Char, A> = string(this).ignore(value)
        val String.many: Parser<CharSet, Char, List<String>> get() = string(this).many
        val String.many1: Parser<CharSet, Char, List<String>> get() = string(this).many1
        val String.opt: Parser<CharSet, Char, String?> get() = string(this).opt
        val String.test: Parser<CharSet, Char, Boolean> get() = string(this).test
        operator fun <A> Parser<CharSet, Char, A>.plus(that: String): Parser<CharSet, Char, A> = this + string(that).ignore
        val String.p: Parser<CharSet, Char, Unit> get() = string(this).ignore

        val <A> Parser<CharSet, Char, A>.string: Parser<CharSet, Char, String> get() = CaptureString(this)

        private val digit: Parser<CharSet, Char, Char> = terminal(CharSet.digit)
        private val letter: Parser<CharSet, Char, Char> = terminal(CharSet.letter)
        private val letterOrDigit: Parser<CharSet, Char, Char> = terminal(CharSet.letterOrDigit)

        fun asciiCI(str: String): Parser<CharSet, Char, String> {
            require(str.all { it.code in 0..127 })

            val lower = str.lowercase(Locale.ENGLISH)
            val upper = str.uppercase(Locale.ENGLISH)

            return str.indices.map { terminal(CharSet.of("${lower[it]}${upper[it]}")) }
                .reduce<Parser<CharSet, Char, Any>, Parser<CharSet, Char, Any>> { a, b -> a + b }
                .ignore(str)
        }

//        val skipWhitespace: Parser<CharSet, Char, Unit> =
//            terminal(CharSet.unicodeWhitespace).many.ignore.named("whitespace")
//        fun <A> token(p: Parser<CharSet, Char, A>): Parser<CharSet, Char, A> =
//            p.zipLeft(skipWhitespace)

        val signum: Parser<CharSet, Char, Int> =
            char('+').ignore(1) or char('-').ignore(-1) or ok(1)

        val bigInt: Parser<CharSet, Char, BigInteger> =
            (signum + digit.many1.map { it.joinToString("").toBigInteger() })
                .map { it.second.multiply(it.first.toBigInteger()) }
                .named("integer")

    }

    companion object {

        // detect unused non-terminal
        // detect non-productive non-terminal
        // detect (direct) loops

    }
}
