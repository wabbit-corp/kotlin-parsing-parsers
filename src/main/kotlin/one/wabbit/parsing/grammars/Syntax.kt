package one.wabbit.parsing.grammars

import one.wabbit.parsing.charset.CharSet
import java.math.BigInteger
import java.util.*

operator fun <Set, Sym, A, B> Parser<Set, Sym, A>.plus(other: Parser<Set, Sym, B>): Parser<Set, Sym, Pair<A, B>> =
    Parser.Sequence(this, other)
@JvmName("timesUnitRight")
operator fun <Set, Sym, A> Parser<Set, Sym, A>.plus(other: Parser<Set, Sym, Unit>): Parser<Set, Sym, A> =
    Parser.ZipLeft(this, other)
@JvmName("timesUnitLeft")
operator fun <Set, Sym, A> Parser<Set, Sym, Unit>.plus(other: Parser<Set, Sym, A>): Parser<Set, Sym, A> =
    Parser.ZipRight(this, other)
@JvmName("timesUnitBoth")
operator fun <Set, Sym> Parser<Set, Sym, Unit>.plus(other: Parser<Set, Sym, Unit>): Parser<Set, Sym, Unit> =
    Parser.Ignore(Parser.Sequence(this, other))

fun <Set, Sym, A, B> Parser<Set, Sym, A>.seq(other: Parser<Set, Sym, B>): Parser<Set, Sym, Pair<A, B>> =
    Parser.Sequence(this, other)


//fun <A> Parser<CharSet, Char, A>.withCapturedString(): Parser<CharSet, Char, Pair<String, A>> =
//    Parser.WithCapturedString(this)

//val decimal: Parser<CharSet, Char, String> =
//    (signum * digit.many1().map { it.joinToString("").toBigInteger() })
//        .map { it.second.multiply(it.first.toBigInteger()) }
//        .named("integer")

@JvmName("map12p")
fun <Set, Sym, X, A1, A2> Parser<Set, Sym, Pair<A1, A2>>.map2(f: (A1, A2) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first, it.second) }

@JvmName("map123pp")
fun <Set, Sym, X, A1, A2, A3> Parser<Set, Sym, Pair<A1, Pair<A2, A3>>>.map3(f: (A1, A2, A3) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first, it.second.first, it.second.second) }

@JvmName("map12p3p")
fun <Set, Sym, X, A1, A2, A3> Parser<Set, Sym, Pair<Pair<A1, A2>, A3>>.map3(f: (A1, A2, A3) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first.first, it.first.second, it.second) }

@JvmName("map1234ppp") // (1(2(34)))
fun <Set, Sym, X, A1, A2, A3, A4> Parser<Set, Sym, Pair<A1, Pair<A2, Pair<A3, A4>>>>.map4(f: (A1, A2, A3, A4) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first, it.second.first, it.second.second.first, it.second.second.second) }

@JvmName("map12p34pp") // ((12)(34))
fun <Set, Sym, X, A1, A2, A3, A4> Parser<Set, Sym, Pair<Pair<A1, A2>, Pair<A3, A4>>>.map4(f: (A1, A2, A3, A4) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first.first, it.first.second, it.second.first, it.second.second) }

@JvmName("map123p4pp") // (1((23)4))
fun <Set, Sym, X, A1, A2, A3, A4> Parser<Set, Sym, Pair<A1, Pair<Pair<A2, A3>, A4>>>.map4(f: (A1, A2, A3, A4) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first, it.second.first.first, it.second.first.second, it.second.second) }

@JvmName("map123pp4p") // ((1(23))4)
fun <Set, Sym, X, A1, A2, A3, A4> Parser<Set, Sym, Pair<Pair<A1, Pair<A2, A3>>, A4>>.map4(f: (A1, A2, A3, A4) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first.first, it.first.second.first, it.first.second.second, it.second) }

@JvmName("map12p3p4p") // (((12)3)4)
fun <Set, Sym, X, A1, A2, A3, A4> Parser<Set, Sym, Pair<Pair<Pair<A1, A2>, A3>, A4>>.map4(f: (A1, A2, A3, A4) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first.first.first, it.first.first.second, it.first.second, it.second) }

@JvmName("map12345pppp") // (1(2(3(4(5)))))
fun <Set, Sym, X, A1, A2, A3, A4, A5> Parser<Set, Sym, Pair<A1, Pair<A2, Pair<A3, Pair<A4, A5>>>>>.map5(f: (A1, A2, A3, A4, A5) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first, it.second.first, it.second.second.first, it.second.second.second.first, it.second.second.second.second) }

@JvmName("map12p345ppp") // ((12)(3(4(5))))
fun <Set, Sym, X, A1, A2, A3, A4, A5> Parser<Set, Sym, Pair<Pair<A1, A2>, Pair<A3, Pair<A4, A5>>>>.map5(f: (A1, A2, A3, A4, A5) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first.first, it.first.second, it.second.first, it.second.second.first, it.second.second.second) }

@JvmName("map123p45pp") // ((1(23))(45))
fun <Set, Sym, X, A1, A2, A3, A4, A5> Parser<Set, Sym, Pair<Pair<A1, Pair<A2, A3>>, Pair<A4, A5>>>.map5(f: (A1, A2, A3, A4, A5) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first.first, it.first.second.first, it.first.second.second, it.second.first, it.second.second) }

@JvmName("map12p34p5p") // ((12)((34)5))
fun <Set, Sym, X, A1, A2, A3, A4, A5> Parser<Set, Sym, Pair<Pair<A1, A2>, Pair<Pair<A3, A4>, A5>>>.map5(f: (A1, A2, A3, A4, A5) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first.first, it.first.second, it.second.first.first, it.second.first.second, it.second.second) }

@JvmName("map1p23p45pp") // (1((23)(45)))
fun <Set, Sym, X, A1, A2, A3, A4, A5> Parser<Set, Sym, Pair<A1, Pair<Pair<A2, A3>, Pair<A4, A5>>>>.map5(f: (A1, A2, A3, A4, A5) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first, it.second.first.first, it.second.first.second, it.second.second.first, it.second.second.second) }

@JvmName("map1234ppp5p") // ((((12)3)4)5)
fun <Set, Sym, X, A1, A2, A3, A4, A5> Parser<Set, Sym, Pair<Pair<Pair<Pair<A1, A2>, A3>, A4>, A5>>.map5(f: (A1, A2, A3, A4, A5) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first.first.first.first, it.first.first.first.second, it.first.first.second, it.first.second, it.second) }

@JvmName("map12p34pp5p") // (((12)(34))5)
fun <Set, Sym, X, A1, A2, A3, A4, A5> Parser<Set, Sym, Pair<Pair<Pair<A1, A2>, Pair<A3, A4>>, A5>>.map5(f: (A1, A2, A3, A4, A5) -> X): Parser<Set, Sym, X> =
    Parser.Map(this) { f(it.first.first.first, it.first.first.second, it.first.second.first, it.first.second.second, it.second) }
