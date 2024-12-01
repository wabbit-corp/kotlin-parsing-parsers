package one.wabbit.parsing.regex

import org.junit.Ignore
import one.wabbit.parsing.charset.CharSet
//import std.parsing.TestMatcher
import one.wabbit.parsing.charset.Topology
import org.junit.Test
import one.wabbit.data.Cord
import one.wabbit.data.shuffled
import one.wabbit.parsing.Matcher
import one.wabbit.random.gen.Gen
import one.wabbit.random.gen.foreach
import one.wabbit.random.gen.foreachMin
import java.util.SplittableRandom
import kotlin.test.assertFalse
import kotlin.test.assertTrue

import java.lang.Math.sqrt
import kotlin.system.measureNanoTime
import kotlin.test.assertNotNull


class RegexSpec {
    val p: (String) -> Regex<CharSet> = { RegexParser.parse(it) }

    fun genRegexSized(depth: Int, alphabet: String, extended: Boolean = false): Gen<Regex<CharSet>> {
        val genSmallRegex = Gen.int(0 ..< 3).flatMap { n ->
            Gen.repeat(1, Gen.oneOf(alphabet.toList())).map {
                Regex.string(it.joinToString(""))
            }
        }

        with (Topology.charRanges) {
            if (depth <= 0) return genSmallRegex
            else {
                val options = mutableListOf<Gen<Regex<CharSet>>>()

                options.add(genSmallRegex)

                options.add(Gen.repeat(depth / 2, genRegexSized(depth - 1, alphabet, extended)).map { Regex.seq(it) })
                options.add(Gen.repeat(depth / 2, genRegexSized(depth - 1, alphabet, extended)).map { Regex.alt(it) })
                if (extended) {
                    options.add(Gen.repeat(depth / 2, genRegexSized(depth - 1, alphabet, extended)).map { Regex.and(it) })
                    options.add(genRegexSized(depth / 2, alphabet, extended).map { Regex.not(it) })
                }
                options.add(genRegexSized(depth / 2, alphabet, extended).map { Regex.star(it) })

                return Gen.oneOf(options).flatMap { it }
            }
        }
    }

    fun genPositive(size: Int, r: Regex<CharSet>): Gen<Cord> {
        return when (r) {
            Regex.Eps -> Gen.const(Cord.empty)
            is Regex.OneOf -> {
                val cs = r.symbols
                Gen.int(0 ..< cs.size).map { cs[it] }.map { Cord.of(it) }
            }
            is Regex.Alt -> Gen.oneOfGen(r.args.toList().map { genPositive(size - 1, it) })
            is Regex.Seq ->
                Gen.sequence(r.args.toList().map { genPositive(maxOf(size / r.args.size, 1), it) })
                    .map { it.fold(Cord.empty) { a, b -> a + b } }
            is Regex.Star -> {
                val r0 = genPositive(size / 2, r.arg)
                if (size > 0) Gen.int(0 ..< size)
                    .flatMap { Gen.repeat(it, r0) }
                    .map { it.fold(Cord.empty) { a, b -> a + b } }
                else Gen.const(Cord.empty)
            }
            Regex.Empty -> Gen.Fail
            Regex.All -> Gen.int(0 ..< size).flatMap {
                Gen.int(0 ..< 0x10FFFF).map { Cord.of(it.toChar()) }
            }

            // These can not be generated.
            is Regex.Not -> genPositive(size, with(Topology.charRanges) { r.rebuild() })
            is Regex.And -> genPositive(size, with(Topology.charRanges) { r.rebuild() })
        }
    }

    @Test fun `test genPositive against some examples`() {
        with(Topology.charRanges) {
            // Generate ints and test that they are accepted by toInt
            run {
                val r = p("-?([0-9]{1,3})")
                val g = genPositive(10, r)
                val sr = SplittableRandom()
                g.foreachMin(sr, 100) { s ->
                    assertNotNull(s.toString().toIntOrNull())
                }
            }

            // Generate some real numbers and test that they are accepted by toDouble
            run {
                val r = p("-?([0-9]{1,3})(\\.[0-9]*)?")
                val g = genPositive(10, r)
                val sr = SplittableRandom()
                g.foreachMin(sr, 100) { s ->
                    assertNotNull(s.toString().toDoubleOrNull())
                }
            }

            run {
                val r = Regex.and(p("([0-9])"), p("([0-4])").not())
                val g = genPositive(10, r)
                val sr = SplittableRandom()
                g.foreachMin(sr, 100) { s ->
                    val i = s.toString().toIntOrNull()
                    assertNotNull(s.toString().toIntOrNull())
                    i!!
                    assertTrue(i in 5..9)
                }
            }
        }
    }

    @Test fun `test genPositive against brzozowski matcher`() {
        with(Topology.charRanges) {
            val g = genRegexSized(5, "abcd", true).flatMap { r -> genPositive(10, r).map { r to it } }
            val sr = SplittableRandom()
            g.foreachMin(sr, 100) { (r, s) ->
                assertTrue(r.brzozowskiL(s.toString().toList()).nullable)
            }
        }
    }

//    fun genNegative(size: Int, r: Regex<CharSet>, ch: Char): Gen<List<Char>> {
//        assert(ch !in r.alphabet())
//
//        return genPositive(size, r, { setOf(ch) }).flatMap { pos ->
//            if (pos.size > 0) {
//                Gen.int(0, pos.size).map { i ->
//                    pos.take(i) + listOf(ch) + pos.drop(i + 1)
//                }
//            } else {
//                Gen.pure(listOf(ch))
//            }
//        }
//    }

    @Test
    fun `regression 1`() {
        with (Topology.charRanges) {
            val a: Regex<CharSet> = Regex.eps
            val b = Regex.char('a')

            assert(a.nullable)
            assert(!b.nullable)

            // a & b
            assert(Regex.and(a, b).fastIsEmptyOrThrow())
            assert(!Regex.and(a, b).nullable)
            assert(Regex.and(a, b).fastFirstSetOrThrow().isEmpty())

            // a | b
            assert(!Regex.alt(a, b).fastIsEmptyOrThrow())
            assert(Regex.alt(a, b).nullable)
            assert(Regex.alt(a, b).fastFirstSetOrThrow() == CharSet.of('a'))

            // a . b
            assert(!Regex.seq(a, b).fastIsEmptyOrThrow())
            assert(!Regex.seq(a, b).nullable)
            assert(Regex.seq(a, b).fastFirstSetOrThrow() == CharSet.of('a'))
            assert(!Regex.seq(b, a).fastIsEmptyOrThrow())
            assert(!Regex.seq(b, a).nullable)
            assert(Regex.seq(b, a).fastFirstSetOrThrow() == CharSet.of('a'))

            // Nullability checks
            assert(Regex.and(a, b).nullable == (a.nullable && b.nullable))
            assert(Regex.alt(a, b).nullable == (a.nullable || b.nullable))
            assert(Regex.seq(a, b).nullable == (a.nullable && b.nullable))
            assert(Regex.star(a).nullable == true)

            // Emptyness checks
            if (a.fastIsEmptyOrThrow() || b.fastIsEmptyOrThrow()) assert(Regex.and(a, b).fastIsEmptyOrThrow())
            assert(Regex.alt(a, b).fastIsEmptyOrThrow() == (a.fastIsEmptyOrThrow() && b.fastIsEmptyOrThrow()))
            assert(Regex.seq(a, b).fastIsEmptyOrThrow() == (a.fastIsEmptyOrThrow() || b.fastIsEmptyOrThrow()))
            assert(Regex.star(a).fastIsEmptyOrThrow() == false)

            // First set checks
            assert(Regex.and(a, b).fastFirstSetOrThrow() == (a.fastFirstSetOrThrow() intersect b.fastFirstSetOrThrow()))
            assert(Regex.alt(a, b).fastFirstSetOrThrow() == (a.fastFirstSetOrThrow() union b.fastFirstSetOrThrow()))
            println("seq(a, b).first() = ${Regex.seq(a, b).fastFirstSetOrThrow()}")
            assert(Regex.seq(a, b).fastFirstSetOrThrow() == (if (a.nullable) a.fastFirstSetOrThrow() union b.fastFirstSetOrThrow() else a.fastFirstSetOrThrow()))
            assert(Regex.star(a).fastFirstSetOrThrow() == a.fastFirstSetOrThrow())
        }
    }

    @Test
    fun `regression 2`() {
        // a = ([c] [b]*)*
        // b = [b]*

        with (Topology.charRanges) {
            val a = Regex.star(Regex.seq(Regex.char('c'), Regex.star(Regex.char('b'))))
            val b = Regex.star(Regex.char('b'))

            assert(a.nullable)
            assert(b.nullable)
            assert(a.fastFirstSetOrThrow() == CharSet.of('c'))
            assert(b.fastFirstSetOrThrow() == CharSet.of('b'))
            assert(!a.fastIsEmptyOrThrow())
            assert(!b.fastIsEmptyOrThrow())

            // Nullability checks
            assert(Regex.and(a, b).nullable == (a.nullable && b.nullable))
            assert(Regex.alt(a, b).nullable == (a.nullable || b.nullable))
            assert(Regex.seq(a, b).nullable == (a.nullable && b.nullable))
            assert(Regex.star(a).nullable == true)

            // Emptyness checks
            if (a.fastIsEmptyOrThrow() || b.fastIsEmptyOrThrow()) assert(Regex.and(a, b).fastIsEmptyOrThrow())
            assert(Regex.alt(a, b).fastIsEmptyOrThrow() == (a.fastIsEmptyOrThrow() && b.fastIsEmptyOrThrow()))
            assert(Regex.seq(a, b).fastIsEmptyOrThrow() == (a.fastIsEmptyOrThrow() || b.fastIsEmptyOrThrow()))
            assert(Regex.star(a).fastIsEmptyOrThrow() == false)

            // First set checks
            assert(Regex.and(a, b).fastFirstSetOrThrow() == (a.fastFirstSetOrThrow() intersect b.fastFirstSetOrThrow()))
            assert(Regex.alt(a, b).fastFirstSetOrThrow() == (a.fastFirstSetOrThrow() union b.fastFirstSetOrThrow()))
            assert(Regex.seq(a, b).fastFirstSetOrThrow() == (if (a.nullable) a.fastFirstSetOrThrow() union b.fastFirstSetOrThrow() else a.fastFirstSetOrThrow()))
            assert(Regex.star(a).fastFirstSetOrThrow() == a.fastFirstSetOrThrow())
        }
    }

    @Test
    fun `regression 3`() {
        // a = Eps
        // b = ([d]+) | Eps | [a-c]

        with (Topology.charRanges) {
            val a: Regex<CharSet> = Regex.eps
            val b = Regex.alt(Regex.seq(Regex.plus(Regex.char('d'))), Regex.eps, Regex.oneOf(CharSet.of('a'..'c')))

            assert(a.nullable)
            assert(b.nullable)
            assert(a.fastFirstSetOrThrow().isEmpty())
            assert(b.fastFirstSetOrThrow() == CharSet.of('a', 'b', 'c', 'd'))
            assert(!a.fastIsEmptyOrThrow())
            assert(!b.fastIsEmptyOrThrow())

            // Nullability checks
            assert(Regex.and(a, b).nullable == (a.nullable && b.nullable))
            assert(Regex.alt(a, b).nullable == (a.nullable || b.nullable))
            assert(Regex.seq(a, b).nullable == (a.nullable && b.nullable))
            assert(Regex.star(a).nullable == true)

            // Emptyness checks
            if (a.fastIsEmptyOrThrow() || b.fastIsEmptyOrThrow()) assert(Regex.and(a, b).fastIsEmptyOrThrow())
            assert(Regex.alt(a, b).fastIsEmptyOrThrow() == (a.fastIsEmptyOrThrow() && b.fastIsEmptyOrThrow()))
            assert(Regex.seq(a, b).fastIsEmptyOrThrow() == (a.fastIsEmptyOrThrow() || b.fastIsEmptyOrThrow()))
            assert(Regex.star(a).fastIsEmptyOrThrow() == false)

            // First set checks
            assert(Regex.and(a, b).fastFirstSetOrThrow() == (a.fastFirstSetOrThrow() intersect b.fastFirstSetOrThrow()))
            assert(Regex.alt(a, b).fastFirstSetOrThrow() == (a.fastFirstSetOrThrow() union b.fastFirstSetOrThrow()))
            assert(Regex.seq(a, b).fastFirstSetOrThrow() == (if (a.nullable) a.fastFirstSetOrThrow() union b.fastFirstSetOrThrow() else a.fastFirstSetOrThrow()))
            assert(Regex.star(a).fastFirstSetOrThrow() == a.fastFirstSetOrThrow())
        }
    }

    @Test
    fun `regression 4`() {
        // a = Eps | [a]
        // b = !

        with (Topology.charRanges) {
            val a: Regex<CharSet> = Regex.alt(Regex.eps, Regex.char('a'))
            val b: Regex<CharSet> = Regex.empty

            assert(a.nullable)
            assert(!b.nullable)
            assert(a.fastFirstSetOrThrow() == CharSet.of('a'))
            assert(b.fastFirstSetOrThrow() == CharSet.none)
            assert(!a.fastIsEmptyOrThrow())
            assert(b.fastIsEmptyOrThrow())

            // Nullability checks
            assert(Regex.and(a, b).nullable == (a.nullable && b.nullable))
            assert(Regex.alt(a, b).nullable == (a.nullable || b.nullable))
            assert(Regex.seq(a, b).nullable == (a.nullable && b.nullable))
            assert(Regex.star(a).nullable == true)

            // Emptyness checks
            if (a.fastIsEmptyOrThrow() || b.fastIsEmptyOrThrow()) assert(Regex.and(a, b).fastIsEmptyOrThrow())
            assert(Regex.alt(a, b).fastIsEmptyOrThrow() == (a.fastIsEmptyOrThrow() && b.fastIsEmptyOrThrow()))
            assert(Regex.seq(a, b).fastIsEmptyOrThrow() == (a.fastIsEmptyOrThrow() || b.fastIsEmptyOrThrow()))
            assert(Regex.star(a).fastIsEmptyOrThrow() == false)

            // First set checks
            assert(Regex.and(a, b).fastFirstSetOrThrow() == (a.fastFirstSetOrThrow() intersect b.fastFirstSetOrThrow()))
            assert(Regex.alt(a, b).fastFirstSetOrThrow() == (a.fastFirstSetOrThrow() union b.fastFirstSetOrThrow()))
            if (!a.fastIsEmptyOrThrow() && !b.fastIsEmptyOrThrow())
                assert(Regex.seq(a, b).fastFirstSetOrThrow() == (if (a.nullable) a.fastFirstSetOrThrow() union b.fastFirstSetOrThrow() else a.fastFirstSetOrThrow()))
            assert(Regex.star(a).fastFirstSetOrThrow() == a.fastFirstSetOrThrow())
        }
    }

    @Test
    fun `regression 5`() {
        // a = [a]*
        // b = [a]

        with(Topology.charRanges) {
            val a: Regex<CharSet> = Regex.star(Regex.char('a'))
            val b: Regex<CharSet> = Regex.char('a')
            assert(!a.equiv(b))
            assert(a.includes(b))
            assert(!b.includes(a))
        }
    }

    @Test
    fun `regression 6`() {
        // a = [a]
        // b = [a]+

        with(Topology.charRanges) {
            val a: Regex<CharSet> = Regex.char('a')
            val b: Regex<CharSet> = Regex.plus(Regex.char('a'))
            assert(!a.equiv(b))
            assert(b.includes(a))
            assert(!a.includes(b))
        }
    }

    @Test
    fun `regression 7`() {
        // a = [c]*
        // b = [c]*?

        with(Topology.charRanges) {
            val a: Regex<CharSet> = Regex.star(Regex.char('c'))
            val b: Regex<CharSet> = Regex.star(Regex.char('c')).opt
            assert(a.equiv(b))
            assert(a.includes(b))
            assert(b.includes(a))
        }
    }

    @Test
    fun `regression 8`() {
        // a = [b]*?
        // b = ([b]* [b])?

        with(Topology.charRanges) {
            val a: Regex<CharSet> = Regex.star(Regex.char('b')).opt
            val b: Regex<CharSet> = Regex.seq(Regex.star(Regex.char('b')), Regex.char('b')).opt
            assert(a.equiv(b))
            assert(a.includes(b))
            assert(b.includes(a))
        }
    }

    @Test
    fun `regression 9`() {
        with(Topology.charRanges) {
            assert(!p("a").intersects(p("b")))
        }
    }

    @Test
    fun `regression 10`() {
        // r1 = [b]* & ([b]* [d])
        // r2 = !
        with(Topology.charRanges) {
            val r1: Regex<CharSet> = Regex.and(
                Regex.star(Regex.char('b')),
                Regex.seq(Regex.star(Regex.char('b')), Regex.char('d')))
            val r2: Regex<CharSet> = Regex.empty

            assert(r1.equiv(r2))
        }
    }

    @Test
    fun `regression 11`() {
        // r1 = [d] (![b] & [b])
        // r2 = !

        with(Topology.charRanges) {
            val r1: Regex<CharSet> = Regex.seq(
                Regex.char('d'),
                Regex.and(
                    Regex.not(Regex.char('b')),
                    Regex.char('b')))
            val r2: Regex<CharSet> = Regex.empty

            assert(r1.equiv(r2))
        }
    }

    @Test
    fun `test dfa`() {
        with(Topology.charRanges) {
            val r1: Regex<CharSet> = RegexParser.parse("(a|b)*a(a|b)(a|b)(a|b)(a|b)(a|b)(a|b)(a|b)(a|b)(a|b)(a|b)")

            val dfa = Compiler.buildDFA(r1)
            println("dfa = ${dfa.states.size} states")
        }
    }

    @Test
    fun `test dfa 1`() {
        with(Topology.charRanges) {
            val r1: Regex<CharSet> = RegexParser.parse("for|[a-z]+|[xb]?[0-9]+")

            val dfa = Compiler.buildDFA(r1)
            // Convert dfa to graphviz
            val sb = StringBuilder()
            sb.append("digraph G {\n")
            for ((id, state) in dfa.states.withIndex()) {
                val name = state.regex.show()
                sb.append("  A$id [label=\"$name\"]\n")
                for ((c, target) in state.transitions) {
                    sb.append("  A$id -> A$c [label=\"$target\"]\n")
                }
                if (state.nullable) {
                    sb.append("  A$id [shape=doublecircle]\n")
                }
            }
            sb.append("}\n")

            println(sb.toString())
        }
    }

    @Test fun `regression 12`() {
        // and(l = ConsList(OneOf(symbols=[d]), And(args=[Not(arg=OneOf(symbols=[b])), OneOf(symbols=[c])])))
        //[Not(arg=OneOf(symbols=[b])), Empty]

        with(Topology.charRanges) {
            Regex.and(listOf(
                Regex.char('d'),
                Regex.And(listOf(
                    Regex.Not(Regex.char('b')),
                    Regex.char('c')))
            ))
        }
    }

    @Test
    fun testRegex() {
        val random = SplittableRandom()
        with (Topology.charRanges) {
            genRegexSized(5, "abcd").foreachMin(random, 1000) { a ->
                // assert(a.firstSetOrNull() == a.firstSetOrThrow())

                // Nullability and emptiness consistency
                assert(a.fastIsEmptyOrThrow() == (a is Regex.Empty))
                assert(a.fastFirstSetOrThrow().isEmpty() == (a is Regex.Empty || a is Regex.Eps))
                if (a.nullable) assert(!a.fastIsEmptyOrThrow())

                // First set and Brzozowski derivative consistency
                val goodFirst = a.fastFirstSetOrThrow().toSet().shuffled(random).take(100)
                val badFirst = (a.fastFirstSetOrThrow().invert()).toSet().shuffled(random).take(100)
                for (c in goodFirst) {
                    val aD = a.brzozowskiL(CharSet.one(c))
                    assertFalse(aD.fastIsEmptyOrThrow())
                }
                for (c in badFirst) {
                    val aD = a.brzozowskiL(CharSet.one(c))
                    assertTrue(aD.fastIsEmptyOrThrow())
                }

                // Brzozowski derivative consistency.
                fun accepts(r: Regex<CharSet>, s: String, exactly: Boolean = true): Boolean {
                    var r = a
                    for (c in s) {
                        r = r.brzozowskiL(CharSet.one(c))
                        if (r.fastIsEmptyOrThrow()) return false
                    }
                    if (exactly) return r is Regex.Eps
                    else return !r.fastIsEmptyOrThrow()
                }
                if (accepts(a, "ab", false)) assertTrue(accepts(a, "a", false))
                if (accepts(a, "ab", true)) assertTrue(accepts(a, "a", false))

//                    val dfa = Compiler.buildDFA(a)
//
////                if (dfa.states.size > 1000) {
////                    println("a = ${a.show()}")
////                    println("dfa = ${dfa.states.size} states")
////                }
//
//                    Compiler.compileCharMatcher(a)
            }

            genRegexSized(5, "abcd").zip(genRegexSized(5, "abcd")).foreachMin(random, 1000) { pair ->
                val (a, b) = pair

                // System.err.println("a = ${a.show()}")
                // System.err.println("b = ${b.show()}")
                // println("a nullable = ${a.nullable}")
                // println("b nullable = ${b.nullable}")
                // println("a first    = ${a.first()}")
                // println("b first    = ${b.first()}")
                // println("a isEmpty  = ${a.isEmpty()}")
                // println("b isEmpty  = ${b.isEmpty()}")

                // Nullability checks
                assert(Regex.and(a, b).nullable == (a.nullable && b.nullable))
                assert(Regex.alt(a, b).nullable == (a.nullable || b.nullable))
                assert(Regex.seq(a, b).nullable == (a.nullable && b.nullable))
                assert(Regex.star(a).nullable == true)

                // Emptyness checks
                if (a.fastIsEmptyOrThrow() || b.fastIsEmptyOrThrow()) assert(Regex.and(a, b).fastIsEmptyOrThrow())
                assert(Regex.alt(a, b).fastIsEmptyOrThrow() == (a.fastIsEmptyOrThrow() && b.fastIsEmptyOrThrow()))
                assert(Regex.seq(a, b).fastIsEmptyOrThrow() == (a.fastIsEmptyOrThrow() || b.fastIsEmptyOrThrow()))
                assert(Regex.star(a).fastIsEmptyOrThrow() == false)

                // First set checks
//                println("a = ${a.show()}")
//                println("b = ${b.show()}")
//                println("a.firstSet = ${a.computeFirstSet()}")
//                println("b.firstSet = ${b.computeFirstSet()}")
//                println("(a and b).firstSet = ${(a and b).computeFirstSet()}")
                // assert(Regex.and(a, b).computeFirstSet() == (a.computeFirstSet() intersect b.computeFirstSet()))
                assert(Regex.alt(a, b).firstSet() == (a.firstSet() union b.firstSet()))
                // println("seq(a, b).first() = ${Regex.seq(a, b).first()}")
                if (!a.fastIsEmptyOrThrow() && !b.fastIsEmptyOrThrow())
                    assert(Regex.seq(a, b).firstSet() == (if (a.nullable) a.firstSet() union b.firstSet() else a.fastFirstSetOrThrow()))
                assert(Regex.star(a).firstSet() == a.firstSet())

                // Brzozowski derivative checks
                val s = "ab"
                val aD = a.brzozowskiL(CharSet.one(s[0]))
                val bD = b.brzozowskiL(CharSet.one(s[0]))
                // assert(Regex.and(a, b).brzozowskiL(CharSet.one(s[0])) == Regex.and(aD, bD))

                if (a.equiv(b)) {
                    // System.err.println("a.equiv(b) == true")
                    assert(a.includes(b))
                    assert(b.includes(a))
                    assert(a.nullable == b.nullable)
                    assert(a.fastFirstSetOrThrow() == b.fastFirstSetOrThrow())
                    assert(a.fastIsEmptyOrThrow() == b.fastIsEmptyOrThrow())

                    for (c in 'a'..'e') {
                        val aD = a.brzozowskiL(CharSet.one(c))
                        val bD = b.brzozowskiL(CharSet.one(c))
                        assert(aD.includes(bD))
                        assert(bD.includes(aD))
                        assert(aD.nullable == bD.nullable)
                        assert(aD.fastFirstSetOrThrow() == bD.fastFirstSetOrThrow())
                        assert(aD.fastIsEmptyOrThrow() == bD.fastIsEmptyOrThrow())
                        assert(aD.equiv(bD))
                    }
                }
            }
        }
    }

    @Test
    fun `test inclusion`() {
        val random = SplittableRandom()

        with(Topology.charRanges) {
            genRegexSized(5, "abcd").zip(genRegexSized(5, "abcd")).foreachMin(random, 1000) { pair ->
                var (a, b) = pair
                if (b.includes(a)) {
                    val tmp = a
                    a = b
                    b = tmp
                }

                if (a.includes(b) && !b.includes(a)) {
                    if (b.nullable) assert(a.nullable)
                    assert(a.fastFirstSetOrThrow().containsAll(b.fastFirstSetOrThrow()))
                }
            }
        }
    }

    @Test
    fun `test intersection`() {
        val random = SplittableRandom()

        with(Topology.charRanges) {
            genRegexSized(5, "abcd").zip(genRegexSized(5, "abcd")).foreachMin(random, 1000) { pair ->
                var (a, b) = pair

                assert(a.includes(a and b))
                assert(b.includes(a and b))
                assert((a + b).includes(a))
                assert((a + b).includes(b))
                assert((a + b).includes(a and b))
                assert((a.opt + b).includes(a))
                assert((a.opt + b).includes(b))

                if (!a.isEmpty() && !b.isEmpty() && !(a and b).isEmpty()) {
                    assert(a.intersects(a and b))
                    assert(b.intersects(a and b))
                    assert((a + b).intersects(a))
                    assert((a + b).intersects(b))
                }

                if (a.intersects(b)) {
                    assert(!(a and b).isEmpty())
                }
            }
        }
    }

    @Test
    fun `test isEmpty`() {
        val random = SplittableRandom()

        with(Topology.charRanges) {
            genRegexSized(6, "abcd").foreachMin(random, 1000) { a ->
                if (a.fastIsEmptyOrNull() == YesNoUnknown.YES) {
                    assert(!a.nullable)
                    assert(a.fastFirstSetOrThrow().isEmpty())
                    assert(a.isEmpty())
                }
                if (a.isEmpty()) {
                    assert(a.fastIsEmptyOrNull() != YesNoUnknown.NO)
                }
                if (a.isEmpty() && a != Regex.Empty) {
                    println("a = ${a.show()}")
                }
            }

            assert(!p("a*").isEmpty())
            assert(Regex.and(p("a"), p("b")).isEmpty())
            //assert(!Regex.and(p("a*"), !p("a")).checkEmpty())

            assert(!p("a").intersects(p("b")))
            assert(p("a*").intersects(p("b*")))
            assert(!p("a+").intersects(p("b*")))
        }
    }

    @Test
    fun `test encodeAsTree`() {
        val tree = Compiler.encodeAsTree(
            listOf(
                CharSet.of('a', 'b', 'c'),
                CharSet.of('d', 'e', 'f'),
                CharSet.of('g', 'h', 'i'),
                CharSet.of('j', 'k', 'l'),
            ),
            { it }
        )

        println(Compiler.Tree.print(tree))
    }

    // NameStartChar ::= ":" | [A-Z] | "_" | [a-z] | [#xC0-#xD6] | [#xD8-#xF6] | [#xF8-#x2FF] | [#x370-#x37D]
    //                 | [#x37F-#x1FFF] | [#x200C-#x200D] | [#x2070-#x218F] | [#x2C00-#x2FEF] | [#x3001-#xD7FF]
    //                 | [#xF900-#xFDCF] | [#xFDF0-#xFFFD] | [#x10000-#xEFFFF]
    // NameChar ::= NameStartChar | "-" | "." | [0-9] | #xB7 | [#x0300-#x036F] | [#x203F-#x2040]

    @Ignore @Test
    fun `test`() {
        // Print JVM version
        println("JVM version: ${System.getProperty("java.version")}")
        val random = SplittableRandom()

        with(Topology.charRanges) {
            val nameStartCharRanges = listOf(
                Pair(0xC0, 0xD6), Pair(0xD8, 0xF6), Pair(0xF8, 0x2FF), Pair(0x370, 0x37D), Pair(0x37F, 0x1FFF),
                Pair(0x200C, 0x200D), Pair(0x2070, 0x218F), Pair(0x2C00, 0x2FEF), Pair(0x3001, 0xD7FF),
                Pair(0xF900, 0xFDCF), Pair(0xFDF0, 0xFFFD), Pair(0x10000, 0xEFFFF),
            )

            val nameCharRanges = listOf(
                Pair(0x0300, 0x036F), Pair(0x203F, 0x2040),
            )

            val nameStartCharAlt = mutableListOf<Regex<CharSet>>()
            nameStartCharAlt.add(Regex.char(':'))
            nameStartCharAlt.add(Regex.oneOf(CharSet.of('A'..'Z')))
            nameStartCharAlt.add(Regex.char('_'))
            nameStartCharAlt.add(Regex.oneOf(CharSet.of('a'..'z')))
            for (it in nameStartCharRanges) {
                var startSingleChar: Int = -1
                var lastSingleChar: Int = -1
                for (ch in it.first..it.second) {
                    val chars = Character.toChars(ch)
                    if (chars.size == 1) {
                        if (startSingleChar == -1) {
                            startSingleChar = chars[0].code
                        }
                        lastSingleChar = chars[0].code
                    } else {
                        if (startSingleChar != -1) {
                            check(lastSingleChar != -1)
                            nameStartCharAlt.add(Regex.oneOf(CharSet.range(startSingleChar.toChar(), lastSingleChar.toChar())))
                            startSingleChar = -1
                            lastSingleChar = -1
                        }

                        nameStartCharAlt.add(Regex.seq(*chars.map { Regex.char(it) }.toTypedArray()))
                    }
                }

                if (startSingleChar != -1) {
                    check(lastSingleChar != -1)
                    nameStartCharAlt.add(Regex.oneOf(CharSet.range(startSingleChar.toChar(), lastSingleChar.toChar())))
                    startSingleChar = -1
                    lastSingleChar = -1
                }
            }

            println(nameStartCharAlt.size)

            val nameStartChar = Regex.Alt(
                nameStartCharAlt
            )

            // val dfa = Compiler.buildDFA(nameStartChar)
            println(Compiler.compileToJava(nameStartChar, "Regex2"))
        }
    }

    @Ignore
    @Test
    fun `test organizing`() {
        val cls = Matcher::class.java

        val random = SplittableRandom()

        val regexes = mutableListOf<Pair<String, String>>()
        // Reals
        regexes.add("Real" to "(?:-|\\+)?[0-9]+(?:\\.[0-9]+)?(?:[eE](?:-|\\+)?[0-9]+)?")
        // Integers
        regexes.add("Integer" to "(?:-|\\+)?[0-9]+")
        // Base16 encoded bytes
        regexes.add("Base16" to "(?:[0-9a-fA-F]{2})+")
        // RGBA colors
        regexes.add("RGBA" to "#(?:[0-9a-fA-F]{2}){3,4}")
        // IPv4 addresses (carefully handle numbers above 255)
        regexes.add("IPv4" to "(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)")

        with(Topology.charRanges) {
            for (i in regexes.indices) {
                val raw = regexes[i].second
                val regex = RegexParser.parse(raw)
                println("Testing ${regexes[i].first} (${regex.show()})")
                println("  nullable = ${regex.nullable}")
                println("  firstSets = ${regex.firstSets(4)}")

                val dfa = Compiler.buildDFA(regex)
                val charMatcherBuilder = Compiler.compileCharMatcher_v2(regex)

                val charMatcher = charMatcherBuilder.build()
                val r_slow = Regex(raw)

                // FIXME: handling of - is incorrect so it has to go last
                val r_garbage = RegexParser.parse("[0-9a-kA-K.\\+#-]{5,20}")

//                genPositive(10, regex).foreach(random, 10) { s ->
//                    println(charMatcher.matches(s.toString()))
//                }

                val N = 100000
                val stats_fast = mutableListOf<Long>()
                val stats_slow = mutableListOf<Long>()
                for (iter in 1..20) {
                    val list = mutableListOf<String>()
                    genPositive(10, regex).foreach(random, N) { s ->
                        list.add(s.toString())
                    }
                    genPositive(10, r_garbage).foreach(random, N) { s ->
                        list.add(s.toString())
                    }

                    println("  ${list.take(4)} ... ${list.takeLast(4)}")

                    var total_fast = 0
                    val t_fast = measureNanoTime {
                        for (s in list) {
                            if (charMatcher.matches(s)) {
                                total_fast++
                            }
                        }
                    }

                    var total_slow = 0
                    val t_slow = measureNanoTime {
                        for (s in list) {
                            if (r_slow.matches(s)) {
                                total_slow++
                            }
                        }
                    }

                    assert(total_fast == total_slow)

                    stats_fast.add(t_fast)
                    stats_slow.add(t_slow)
                }

                for (iter in 1..5) {
                    stats_fast.removeAt(0)
                    stats_slow.removeAt(0)
                }

                fun std(list: List<Long>): Double {
                    val avg = list.average()
                    var sum = 0.0
                    for (v in list) {
                        sum += (v - avg) * (v - avg)
                    }
                    return sqrt(sum / list.size)
                }

                println("  fast = ${stats_fast.average() / 1000000.0}ms +- ${std(stats_fast) / 1000000.0}ms")
                println("  slow = ${stats_slow.average() / 1000000.0}ms +- ${std(stats_slow) / 1000000.0}ms")

                for (j in regexes.indices) {
                    if (i == j) continue

                    val (name1, r1_raw) = regexes[i]
                    val (name2, r2_raw) = regexes[j]

                    val r1 = RegexParser.parse(r1_raw)
                    val r2 = RegexParser.parse(r2_raw)

                    if (r1.includes(r2)) {
                        println("  $name1 includes $name2")
                    } else if (r1.intersects(r2)) {
                        println("  $name1 intersects $name2 = ${(r1 and r2).rebuild().show()}")

                        genPositive(5, (r1 and r2).rebuild()).foreach(random, 10) { s ->
                            println("    $s")
                        }
                    }
                }
            }
        }
    }

    // #x10000-#xEFFFF

    @Ignore @Test
    fun `test rebuild`() {
        with(Topology.charRanges) {
            genRegexSized(5, "abcd", extended = true).foreachMin(SplittableRandom(), 1000) { r1 ->
                val dfa = Compiler.buildDFA(r1)
                val r2 = Compiler.dfaToRegex(dfa)
                println("r1 = ${r1.show()}")
                println("r2 = ${r2.show()}")
                assert(r1.equiv(r2))
            }
        }
    }

    val dateRegex = with (Topology.charRanges) {
        fun p(s: String) = RegexParser.parse(s)

        // We ignore years before 1800 and after 2100 for the long form
        val year = p("1[89][0-9][0-9]|20[0-9][0-9]")
        val shortYear = p("[0-9]{2}")

        val month = p("(0[1-9]|1[0-2])")
        val monthAsStr = p("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)")

        val day = p("(0[1-9]|[12][0-9]|3[01])")

        val formats = mutableListOf<Regex<CharSet>>()

        // samples=[2016, 2011, 2015, 2014, 2012, 2013]                           => Year
        // {year}
        formats.add(year)

        // samples=[Jan 1971, Jan 2001, Jan 1984, Jan 1976]                       => Date (month + year)
        // {month-literal} {year}
        formats.add(monthAsStr * Regex.string(" ") * year)

        // samples=[08/19, 02/10, 10/29, 09/17, 08/20, 02/02, 02/06, 08/26]       => Date (month + day)
        // {month}/{day}
        formats.add(month * Regex.string("/") * day)

        // samples=[1971-01-01, 2001-01-01, 1984-01-01, 1976-01-01]               => Date
        // {year}-{month}-{day}
        // samples=[19710101, 20010101, 19840101, 19760101]                       => Int | Date
        // {year}{month}{day}
        // samples=[5/24/13, 4/14/14, 4/16/14, 6/18/14, 7/12/2013]                => Date
        // {month}/{day}/{year} | {month}/{day}/{short-year}
        // samples=[9-Feb-01, 12-Dec-00, 28-Feb-06, 5-Feb-02, 1-Jan-01]           => Date
        // {day}-{month-literal}-{short-year}

        val separators = listOf("-", "/", ".", "")
        for (sep in separators) {
            // YMD
            formats.add(Regex.seq(year, Regex.string(sep), month, Regex.string(sep), day))
            if (sep != "") formats.add(Regex.seq(year, Regex.string(sep), monthAsStr, Regex.string(sep), day))

            // MDY
            formats.add(Regex.seq(month, Regex.string(sep), day, Regex.string(sep), year))
            formats.add(Regex.seq(month, Regex.string(sep), day, Regex.string(sep), shortYear))
            if (sep != "") formats.add(Regex.seq(monthAsStr, Regex.string(sep), day, Regex.string(sep), year))

            // DMY
            formats.add(Regex.seq(day, Regex.string(sep), month, Regex.string(sep), year))
            formats.add(Regex.seq(day, Regex.string(sep), month, Regex.string(sep), shortYear))
            if (sep != "") formats.add(Regex.seq(day, Regex.string(sep), monthAsStr, Regex.string(sep), year))
        }

        // samples=[1971-01-01 00:00:00, 2001-01-01 00:00:00]                     => Date + Time
        // samples=[1971-01-01 00:00:00.000, 2001-01-01 00:00:00.000]             => Date + Time
        // samples=[13:08, 10:01, 20:26, 8:13, 9:19, 13:38, 19:19]                => Time
        // samples=[, 18:00:00, 18:30:00, 07:00:00, 19:25:00, 17:30:00]           => Time
        // samples=[1 hr 44 min, 1 hr 50 min, 2 hr 55 min, 2 hr 30 min]           => Time
        // samples=[4:16 PM, 12:09 PM, 5:54 PM, 4:12 PM, 12:06 PM, 12:08 PM]      => Time

        val dateYMD = Regex.alt(formats)

        dateYMD
    }

    @Test
    fun testBrzozowskiDerivative() {
        val random = SplittableRandom()
        with (Topology.charRanges) {
            with (Regex) {
//                val r = alt(char('0'), char('1')).many * char('2') * char('2').many
//                assertThrows(Regex.UnrefinedTopologyException::class.java) {
//                    r.brzozowskiL(CharSet.range(Char.MIN_VALUE, '1'))
//                }

                // val r = RegexParser("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").parse()

//                // real number regex:
//                println(Compiler.compileToJava(
//                    // +42.0e-1
//                    RegexParser("(-|+)?[ ]*[0-9]+(\\.[0-9]+)?([eE](-|+)?[0-9]+)?").parse(),
//                    "RealMatcher"))
//
//                // integer regex:
//                println(Compiler.compileToJava(
//                    // - 100
//                    // + 100
//                    // -9
//                    RegexParser("(-|+)?[ ]*[0-9]+").parse(),
//                    "IntMatcher"))
//
//                // email regex:
//                println(Compiler.compileToJava(
//                    // foo@gmail.com
//                    RegexParser("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").parse(),
//                    "EmailMatcher"))
//
//                // url (with query parameters) regex:
//                println(Compiler.compileToJava(
//                    RegexParser("https?://[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/[a-zA-Z0-9._%+-]+)*\\??([a-zA-Z0-9._%+-]+=[a-zA-Z0-9._%+-]+&?)*").parse(),
//                    "UrlMatcher"))
//
//                // base16 bytes regex:
//                println(Compiler.compileToJava(
//                    RegexParser("([0-9a-fA-F]{2})+").parse(),
//                    "Base16Matcher"))

                println(Compiler.compileToJava(
                    dateRegex,
                    "DateMatcher"))

//                // Currency amount like so:
//                // $1,000,000.00 | $34,500.00 | $1,000.00 | $1.00
//                // Support most common currencies that have a unicode symbol
//                println(Compiler.compileToJava(
//                    RegexParser("(?:$|€|R\$|£)[0-9]{1,3}(,[0-9]{3})*\\.[0-9]{2}").parse(),
//                    "DollarAmountMatcher"))


//                val random = SplittableRandom()
//                val examples = mutableListOf<String>()
//                genPositive(10, r).foreach(random, 1000) {
//                    examples.add(it.toString().map {
//                        if (it in CharSet.unicodeDigit) 'd'
//                        else if (it in CharSet.unicodeLetter) 'l'
//                        else if (it in CharSet.unicodeWhitespace) ' '
//                        else it
//                    }.joinToString(""))
//                }

//                val r1 = Regex.Alt(examples.map { Regex.string(it) }.toSet())

//                val t = TestMatcher()
//                assertTrue(t.matches("   123   "))
//                assertTrue(t.matches("   -123   "))
//                assertTrue(t.matches("   +123   "))
//                assertFalse(t.matches("   123a   "))
            }

//            genRegexSized(10, "abcd").foreach(random, 10000) { a ->
//                val aD = a.brzozowskiL(CharSet.of("a"))
//                // println("a :/ ${a.show()} = ${aD.show()}")
//            }
        }
    }

    @Test
    fun testBuildDFA() {
        with (Topology.charRanges) {
            with (Regex) {
                // val r = Parser("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").parse()
                // val r = alt(char('0'), char('1')).many * char('2') * char('2').many
                val r = RegexParser.parse("[01]*22*")

                println(r.show())

                val dfa = Compiler.buildDFA(r)
                println(dfa)
            }
        }
    }

//    @Test
//    fun test() {
//        val r = SplittableRandom()
//        genRegexSized(10, "0123").foreach(r, 100) { regex ->
//            println(regex.show())
//            val p = Regex.compileCharMatcher(regex)
//
//            var seen = mutableSetOf<List<Char>>()
//            genPositive(10, regex, { setOf(it) }).foreach(r, 10) { v ->
//                if (v !in seen) {
//                    seen += v
//                    println("+ " + v.joinToString())
//                    assert(p.apply(v.toTypedArray()))
//                }
//            }
//
//            seen.clear()
//            genNegative(10, regex, '5').foreach(r, 10) { v ->
//                if (v !in seen) {
//                    seen += v
//                    println("- " + v.joinToString())
//                    assert(!p.apply(v.toTypedArray()))
//                }
//            }
//        }
//
////        val bin = char('0') | char('1')
////        val binary = bin ~ bin.many
////
//////    forAll(gen(binary)) { v =>
//////      println(v.mkString(""))
//////    }
//////
//////    println(binary.show)
////
////        println(Regex.buildDFA[Char](binary, _ == _))
////
////        val p = Regex.compileCharMatcher(binary)
////        println(p.apply("0".toCharArray))
//    }
}
