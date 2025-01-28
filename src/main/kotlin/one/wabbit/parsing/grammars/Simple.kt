package one.wabbit.parsing.grammars

import one.wabbit.data.CharBuf
import one.wabbit.formatting.escapeJavaString
import one.wabbit.parsing.charset.CharSet
import one.wabbit.parsing.charset.SetLike1
import one.wabbit.parsing.charset.Topology

interface SimpleInput<out S : Any> {
    val position: Int
    val current: S?
    fun advance(): Pair<SimpleInput<S>, S>?
    fun take(n: Int): Pair<SimpleInput<S>, List<S>>

    data class StringInput(val string: String, val index: Int) : SimpleInput<Char> {
        override val position: Int get() = index
        override val current: Char? get() = if (index < string.length) string[index] else null
        override fun advance(): Pair<StringInput, Char>? {
            if (index < string.length) {
                return StringInput(string, index + 1) to string[index]
            } else return null
        }
        override fun take(n: Int): Pair<StringInput, List<Char>> {
            val result = string.substring(index, minOf(string.length, index + n))
            return StringInput(string, index + result.length) to result.toList()
        }

        override fun toString(): String {
            // Cut off the string at 20 characters
            string.substring(index, minOf(string.length, index + 20)).let { cutOff ->
                return "StringInput($cutOff)"
            }
        }
    }
}

sealed interface Path {
    data class Named(val name: String, val path: Path) : Path {
        override fun toString(): String = "$name> $path"
    }
    data class Error(val message: String): Path

//    data class SequenceLeft(val path: Path) : Path {
//        override fun toString(): String = "$path"
//    }
//    data class SequenceRight(val path: Path) : Path {
//        override fun toString(): String = "$path"
//    }

    data class Choice(val paths: List<Path>) : Path {
        override fun toString(): String = paths.joinToString(" | ", "(", ")") { "${it}" }
    }

    data class Terminal(val symbol: String) : Path {
        override fun toString(): String = "$symbol"
    }

    data class Star(val path: Path) : Path {
        override fun toString(): String = "($path)*"
    }
    data class Plus(val path: Path) : Path {
        override fun toString(): String = "($path)+"
    }
    data class Optional(val path: Path) : Path {
        override fun toString(): String = "?> $path"
    }

//    data class Map(val path: Path) : Path {
//        override fun toString(): String = "$path"
//    }
    //FIXME: left and right
    data class FlatMap(val path: Path) : Path {
        override fun toString(): String = "$path"
    }

    data class Capture(val path: Path) : Path {
        override fun toString(): String = "C> $path"
    }
    data class CaptureString(val path: Path) : Path {
        override fun toString(): String = "C> $path"
    }
}

sealed interface SimpleResult<Set: Any, S : Any, out A> {
    data class Success<Set : Any, S : Any, out A>(
        val value: A,
        val newInput: SimpleInput<S>
        ) : SimpleResult<Set, S, A>

    data class Failure<Set : Any, S : Any>(
        val failurePath: Path,
        val failedAt: SimpleInput<*>,
        val expectedChars: Set?
        ) : SimpleResult<Set, S, Nothing>
    {
        fun mapPath(f: (Path) -> Path): Failure<Set, S> =
            Failure(f(failurePath), failedAt, expectedChars)
    }
}

fun interface Simple<Set : Any, S : Any, out A> {
    class Result(
        var value: Any?,
        var chars: CharBuf
    )

    fun parse(input: SimpleInput<S>): SimpleResult<Set, S, A>

    fun parseAll(input: SimpleInput<S>): SimpleResult<Set, S, A> {
        val result = parse(input)
        return when (result) {
            is SimpleResult.Success -> {
                val r = result.newInput.advance()
                if (r == null) result
                else SimpleResult.Failure(
                    Path.Error("Expected end of input"),
                    result.newInput,
                    null)
            }
            is SimpleResult.Failure -> result
        }
    }

    companion object {
        context(SetLike1<Sym, Set>)
        fun <Set : Any, Sym : Any, Result> compile(p: Parser<Set, Sym, Result>): Simple<Set, Sym, Result> {
            return when (p) {
                is Parser.Capture<Set, Sym, *> -> {
                    // We know that Result >: List<Sym>
                    fun upcast(list: List<Sym>): Result = list as Result

                    val p0 = compile(p.parser)
                    Simple { s ->
                        when (val result = p0.parse(s)) {
                            is SimpleResult.Success -> {
                                val len = result.newInput.position - s.position
                                val (newInput, symbols) = s.take(len)
                                SimpleResult.Success(upcast(symbols), newInput)
                            }
                            is SimpleResult.Failure -> result.mapPath(Path::Capture)
                        }
                    }
                }

                is Parser.CaptureString<*> -> {
                    // We know that Char = Sym and CharSet = Set
                    // Since we are consuming Syms


                    with (Topology.charRanges) {
                        val p0 = compile(p.parser)

                        Simple { s ->
                            val result = p0.parse(s as SimpleInput<Char>) as SimpleResult<Set, Sym, Any?>

                            when (result) {
                                is SimpleResult.Success -> {
                                    val length = result.newInput.position - s.position
                                    val (newInput, chars) = s.take(length)
                                    SimpleResult.Success(
                                        chars.joinToString("") as Result,
                                        newInput as SimpleInput<Sym>)
                                }
                                is SimpleResult.Failure ->
                                    result.mapPath(Path::CaptureString)
                            }
                        }
                    }
                }
                is Parser.Choice -> {
                    Simple { s ->
                        val ps = p.list.map { compile(it) }
                        val errors = mutableListOf<SimpleResult.Failure<Set, Sym>>()
                        for (p in ps) {
                            val result = p.parse(s)
                            when (result) {
                                is SimpleResult.Success ->
                                    return@Simple SimpleResult.Success(result.value, result.newInput)
                                is SimpleResult.Failure -> {
                                    // println("${result.failurePath} ${result.failedAt}")
                                    errors.add(result)
                                    continue
                                }
                            }
                        }

                        val furthestPosition = errors.maxOf { it.failedAt.position }
                        val furthest = errors.filter { it.failedAt.position == furthestPosition }
                        val expectedChars = furthest.map { it.expectedChars }.reduce { a, b ->
                            if (a == null) b
                            else if (b == null) a
                            else this@SetLike1.union(a, b)
                        }

                        SimpleResult.Failure(
                            Path.Choice(errors.map { it.failurePath }),
                            furthest.first().failedAt,
                            expectedChars)
                    }
                }
                is Parser.Delay -> {
                    Simple { s ->
                        compile(p.thunk.value).parse(s)
                    }
                }
                is Parser.Epsilon -> {
                    Simple { s -> SimpleResult.Success(Unit as Result, s) }
                }
                is Parser.Error -> {
                    Simple { s -> SimpleResult.Failure(Path.Error(p.message), s, null) }
                }
                is Parser.FlatMap<Set, Sym, *, Result> -> {
                    val p0 = compile(p.parser)
                    Simple { s ->
                        val r1 = p0.parse(s)
                        when (r1) {
                            is SimpleResult.Success -> {
                                val p1 = compile((p.f as (Any?) -> Parser<Set, Sym, Result>)(r1.value))
                                p1.parse(r1.newInput)
                            }
                            is SimpleResult.Failure ->
                                SimpleResult.Failure(
                                    Path.FlatMap(r1.failurePath),
                                    r1.failedAt,
                                    r1.expectedChars)
                        }
                    }
                }
                is Parser.Map<Set, Sym, *, Result> -> {
                    val p0 = compile(p.parser)
                    Simple { s ->
                        val result = p0.parse(s)
                        when (result) {
                            is SimpleResult.Success ->
                                SimpleResult.Success(
                                    (p.transform as (Any?) -> Result)(result.value),
                                    result.newInput)
                            is SimpleResult.Failure ->
                                SimpleResult.Failure(
                                    // Path.Map(result.failurePath),
                                    result.failurePath,
                                    result.failedAt,
                                    result.expectedChars)
                        }
                    }
                }
                is Parser.Terminal<*, *> -> {
                    Simple { s ->
                        val set = p.symbol as Set

                        val c = s.current ?: return@Simple SimpleResult.Failure(Path.Terminal(p.symbol.toString()), s, set)

                        if (this@SetLike1.contains(set, c)) {
                            val (s1, c1) = s.advance() ?: error("impossible")
                            SimpleResult.Success(c1 as Result, s1)
                        } else {
                            SimpleResult.Failure(Path.Terminal(p.symbol.toString()), s, set)
                        }
                    }
                }
                is Parser.Ignore<Set, Sym> -> {
                    val p0 = compile(p.parser)
                    Simple { s ->
                        val result = p0.parse(s)
                        when (result) {
                            is SimpleResult.Success ->
                                SimpleResult.Success(Unit as Result, result.newInput)
                            is SimpleResult.Failure ->
                                SimpleResult.Failure(
                                    result.failurePath, // FIXME: Path.Void(result.failurePath),
                                    result.failedAt,
                                    result.expectedChars)
                        }
                    }
                }

                is Parser.Filter<Set, Sym, Result> -> {
                    val p0 = compile(p.parser)
                    Simple { s ->
                        val result = p0.parse(s)
                        when (result) {
                            is SimpleResult.Success -> {
                                val e = p.error(result.value)
                                if (e == null) {
                                    SimpleResult.Success(result.value, result.newInput)
                                } else {
                                    SimpleResult.Failure(Path.Error(e), result.newInput, null)
                                }
                            }
                            is SimpleResult.Failure ->
                                SimpleResult.Failure(
                                    result.failurePath,
                                    result.failedAt,
                                    null)
                        }
                    }
                }

                is Parser.Sequence<Set, Sym, *, *> -> {
                    val p1 = compile(p.left)
                    val p2 = compile(p.right)

                    Simple { s ->
                        // p1.parse(s)?.let { (s1, x) ->
                        //                            p2.parse(s1)?.let { (s2, y) ->
                        //                                s2 to ((x to y) as Result)
                        //                            }
                        //                        }

                        val r1 = p1.parse(s)
                        when (r1) {
                            is SimpleResult.Success -> {
                                val r2 = p2.parse(r1.newInput)
                                when (r2) {
                                    is SimpleResult.Success ->
                                        SimpleResult.Success(
                                            (r1.value to r2.value) as Result,
                                            r2.newInput)
                                    is SimpleResult.Failure ->
                                        SimpleResult.Failure(
                                            // Path.SequenceRight(r2.failurePath),
                                            r2.failurePath,
                                            r2.failedAt,
                                            r2.expectedChars)
                                }
                            }
                            is SimpleResult.Failure ->
                                SimpleResult.Failure(
                                    // Path.SequenceLeft(r1.failurePath),
                                    r1.failurePath,
                                    r1.failedAt,
                                    r1.expectedChars)
                        }
                    }
                }
                is Parser.ZipLeft<Set, Sym, *, *> -> {
                    Simple { s ->
                        val p1 = compile(p.left)
                        val p2 = compile(p.right)
                        val r1 = p1.parse(s)
                        when (r1) {
                            is SimpleResult.Success -> {
                                val r2 = p2.parse(r1.newInput)
                                when (r2) {
                                    is SimpleResult.Success ->
                                        SimpleResult.Success(
                                            r1.value as Result,
                                            r2.newInput)
                                    is SimpleResult.Failure ->
                                        SimpleResult.Failure(
                                            // Path.SequenceRight(r2.failurePath),
                                            r2.failurePath,
                                            r2.failedAt,
                                            r2.expectedChars)
                                }
                            }
                            is SimpleResult.Failure ->
                                SimpleResult.Failure(
                                    // Path.SequenceLeft(r1.failurePath),
                                    r1.failurePath,
                                    r1.failedAt,
                                    r1.expectedChars)
                        }
                    }
                }
                is Parser.ZipRight<Set, Sym, *, *> -> {
                    Simple { s ->
                        val p1 = compile(p.left)
                        val p2 = compile(p.right)

                        val r1 = p1.parse(s)
                        when (r1) {
                            is SimpleResult.Success -> {
                                val r2 = p2.parse(r1.newInput)
                                when (r2) {
                                    is SimpleResult.Success ->
                                        SimpleResult.Success(
                                            r2.value as Result,
                                            r2.newInput)
                                    is SimpleResult.Failure ->
                                        SimpleResult.Failure(
                                            // Path.SequenceRight(r2.failurePath),
                                            r2.failurePath,
                                            r2.failedAt,
                                            r2.expectedChars)
                                }
                            }
                            is SimpleResult.Failure ->
                                SimpleResult.Failure(
                                    // Path.SequenceLeft(r1.failurePath),
                                    r1.failurePath,
                                    r1.failedAt,
                                    r1.expectedChars)
                        }
                    }
                }

                is Parser.Optional<Set, Sym, *> -> {
                    val p0 = compile(p.parser)
                    Simple { s ->
                        val r = p0.parse(s)
                        when (r) {
                            is SimpleResult.Success ->
                                SimpleResult.Success(
                                    r.value as Result,
                                    r.newInput)
                            is SimpleResult.Failure ->
                                SimpleResult.Success(
                                    null as Result,
                                    s)
                        }
                    }
                }

                is Parser.Test<Set, Sym, *> -> {
                    val p0 = compile(p.parser)
                    Simple { s ->
                        val r = p0.parse(s)
                        when (r) {
                            is SimpleResult.Success ->
                                SimpleResult.Success(
                                    true as Result,
                                    r.newInput)
                            is SimpleResult.Failure ->
                                SimpleResult.Success(
                                    false as Result,
                                    s)
                        }
                    }
                }

                is Parser.Star<Set, Sym, *> -> {
                    val p0 = compile(p.parser)
                    Simple { s ->
                        val list = mutableListOf<Any?>()
                        var s1 = s
                        while (true) {
                            // println("* s1 = at ``$s1''")
                            val r = p0.parse(s1)
                            when (r) {
                                is SimpleResult.Success -> {
                                    if (r.newInput == s1) break

                                    list.add(r.value)
                                    s1 = r.newInput
                                }
                                is SimpleResult.Failure ->
                                    break
                            }
                        }

                        SimpleResult.Success(list as Result, s1)
                    }
                }
                is Parser.Plus<Set, Sym, *> -> {
                    Simple { s ->
                        val p0 = compile(p.parser)
                        val list = mutableListOf<Any?>()
                        var s1 = s
                        while (true) {
                            val r = p0.parse(s1)
                            when (r) {
                                is SimpleResult.Success -> {
                                    if (r.newInput == s1) {
                                        if (list.isEmpty()) {
                                            return@Simple SimpleResult.Failure(
                                                Path.Plus(Path.Plus(Path.Error("consumed epsilon"))),
                                                s1, null)
                                        }
                                        break
                                    }

                                    list.add(r.value)
                                    s1 = r.newInput
                                }
                                is SimpleResult.Failure -> {
                                    if (list.isEmpty()) {
                                        return@Simple SimpleResult.Failure(
                                            Path.Plus(r.failurePath),
                                            r.failedAt,
                                            r.expectedChars)
                                    }
                                    break
                                }
                            }
                        }
                        SimpleResult.Success(list as Result, s1)
                    }
                }

                is Parser.Named -> {
                    val p0 = compile(p.parser)
                    Simple { s ->
                        val r = p0.parse(s)
                        val next = "\"" + escapeJavaString(s.toString().take(30), doubleQuoted = true) + "\""

                        when (r) {
                            is SimpleResult.Success -> {
                                if (p.debug) println("+ ${p.name} at <$next> resulting in ${r.value}")
                                SimpleResult.Success(
                                    r.value as Result,
                                    r.newInput
                                )
                            }
                            is SimpleResult.Failure -> {
                                if (p.debug) {
                                    val readPos = "\"" + escapeJavaString(r.failedAt.toString().take(30), doubleQuoted = true) + "\""
                                    println("- ${p.name} at <$next> via ${r.failurePath} at <$readPos>")
                                }
                                SimpleResult.Failure(
                                    // if (p.opaque) Path.Terminal(p.name)
                                    //else
                                        Path.Named(p.name, r.failurePath),
                                    r.failedAt,
                                    r.expectedChars
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun <A> Simple<CharSet, Char, A>.parse(s: String): SimpleResult<CharSet, Char, A> =
    parse(SimpleInput.StringInput(s, 0))
