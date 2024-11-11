package one.wabbit.parsing.regex

import one.wabbit.parsing.*
import one.wabbit.parsing.charset.*
import one.wabbit.parsing.regex.Regex.Companion

enum class YesNoUnknown {
    YES, NO, UNKNOWN
}

sealed class Regex<out S : Any> {
    abstract val nullable: Boolean

    // Eps matches the empty string
    data object Eps : Regex<Nothing>() {
        override val nullable: Boolean = true
    }
    // Empty matches nothing
    data object Empty : Regex<Nothing>() {
        override val nullable: Boolean = false
    }
    // All matches any string
    data object All : Regex<Nothing>() {
        override val nullable: Boolean = true
    }

    data class OneOf<out S : Any>(val symbols: S) : Regex<S>() {
        override val nullable: Boolean = false
    }

    data class Alt<out S : Any> internal constructor(val args: List<Regex<S>>) : Regex<S>() {
        init {
            require(args.size > 1)
            require(args.all { it !is Alt })
            require(args.all { it !is Empty })
//            require(args.all { it !is All })
            require(args.count { it is Eps } <= 1)
        }

        override val nullable: Boolean = args.any { it.nullable }
    }
    data class Seq<out S : Any> internal constructor(val args: List<Regex<S>>) : Regex<S>() {
        init {
            require(args.size > 1)
            require(args.all { it !is Seq })
            require(args.all { it !is Eps })
            require(args.all { it !is Empty })
        }

        override val nullable: Boolean = args.all { it.nullable }
    }

    data class And<out S : Any>(val args: List<Regex<S>>) : Regex<S>() {
        init {
            require(args.size > 1)
            require(args.all { it !is And })
            require(args.all { it !is All })
            require(args.all { it !is Empty })
        }

        override val nullable: Boolean = args.all { it.nullable }
    }
    data class Not<out S : Any>(val arg: Regex<S>) : Regex<S>() {
        init {
            require(arg !is Not)
            require(arg !is All)
            require(arg !is Empty)
        }

        override val nullable: Boolean = !arg.nullable
    }
    data class Star<out S : Any> internal constructor(val arg: Regex<S>) : Regex<S>() {
        init {
            require(arg !is Star)
            require(arg !is Empty)
            require(arg !is All)
            require(arg !is Eps)
        }

        override val nullable: Boolean = true
    }

    // ∀ = Σ*
    // ∧{} = ∀ = ¬∅
    // ∨{} = ∅ = ¬∀
    // ∘{} = ε

    context(SetLike<S>)
    val opt: Regex<S>
        get() = alt(listOf(eps, this))

    val many: Regex<S>
        get() = when (this) {
            Eps -> Eps
            is Star<*> -> this
            else -> Star(this)
        }

    context(SetLike<S>)
    operator fun times(that: Regex<@UnsafeVariance S>): Regex<S> = seq(this, that)

    context(SetLike<S>)
    operator fun plus(that: Regex<@UnsafeVariance S>): Regex<S> = alt(this, that)

    context(SetLike<S>)
    infix fun and(that: Regex<@UnsafeVariance S>): Regex<S> = and(this, that)

    context(SetLike<S>)
    operator fun not(): Regex<S> = not(this)

    context(SetLike<S>)
    fun show(): String {
        fun go(r: Regex<S>): Pair<Boolean, String> {
            when (r) {
                Empty -> return false to "!" // "∅"
                Eps   -> return false to "Eps" // "ε"
                All   -> return false to "All"
                is OneOf -> return false to r.symbols.toString()

                is Alt -> {
                    if (r.args.size == 2) {
                        if (r.args.first() is Eps) {
                            val (p, x) = go(r.args.last())
                            if (p) return false to "($x)?"
                            return false to "$x?"
                        }
                        if (r.args.last() is Eps) {
                            val (p, x) = go(r.args.first())
                            if (p) return false to "($x)?"
                            return false to "$x?"
                        }
                    }

                    val xs = r.args.map { go(it) }
                    return true to xs.joinToString(" | ") { (p, x) -> if (p) "($x)" else x }
                }

                is Regex.And<S> -> {
                    val xs = r.args.map { go(it) }
                    return true to xs.joinToString(" & ") { (p, x) -> if (p) "($x)" else x }
                }
                is Regex.Not<S> -> {
                    val (p, x) = go(r.arg)
                    return false to "!$x"
                }

                is Seq<S> -> {
                    val chunks = mutableListOf<String>()

                    var i = 0
                    while (i < r.args.size) {
                        val arg = r.args[i]
                        val next = if (i + 1 < r.args.size) r.args[i + 1] else null

                        if (next == star(arg)) {
                            val (p, x) = go(arg)
                            if (p) chunks.add("($x)+")
                            else chunks.add("$x+")
                            i += 2
                        } else {
                            val (p, x) = go(arg)
                            if (p) chunks.add("($x)")
                            else chunks.add(x)
                            i += 1
                        }
                    }

                    return true to chunks.joinToString(" ")
                }
                is Star<S> -> {
                    val (p, x) = go(r.arg)
                    if (p) return false to "($x)*"
                    return false to "$x*"
                }
            }
        }

        return go(this).second
    }

    context(SetLike<S>)
    fun fastFirstSetOrNull(): S? {
        val setLike = this@SetLike
        when (this) {
            Eps -> return setLike.empty()
            Empty -> return setLike.empty()
            All -> return setLike.all()
            is OneOf<S> -> return symbols
            is Alt<S> -> {
                var result = setLike.empty()
                for (arg in args) {
                    val s = arg.fastFirstSetOrNull() ?: return null
                    result = setLike.union(result, s)
                }
                return result
            }
            is Seq<S> -> {
                var result = setLike.empty()
                var done = false
                for (arg in args) {
                    val s = arg.fastFirstSetOrNull() ?: return null
                    if (!done) result = setLike.union(result, s)
                    if (!arg.nullable) done = true
                }
                return result
            }
            is Star<S> -> return arg.fastFirstSetOrNull()
            is And<S> -> return null
//            {
//                var result = setLike.all()
//                for (arg in args) {
//                    val s = arg.firstSetOrNull() ?: return null
//                    result = setLike.intersect(result, s)
//                }
//                return result
//            }
            is Not<S> -> return null
        }
    }

    context(SetLike<S>)
    @Throws(UnsupportedOperationException::class)
    fun fastFirstSetOrThrow(): S =
        fastFirstSetOrNull() ?: throw UnsupportedOperationException("firstSetOrThrow() is not supported for $this")

    context(SetLike<S>)
    @Throws(UnsupportedOperationException::class)
    fun fastIsEmptyOrThrow(): Boolean =
        !nullable && (this === Empty || this@SetLike.isEmpty(fastFirstSetOrThrow()))

    context(SetLike<S>)
    fun fastIsEmptyOrNull(): YesNoUnknown {
        if (this === Empty) return YesNoUnknown.YES
        if (nullable) return YesNoUnknown.NO
        val firstSet = fastFirstSetOrNull()
        if (firstSet === null) return YesNoUnknown.UNKNOWN
        if (this@SetLike.isEmpty(firstSet)) return YesNoUnknown.YES
        return YesNoUnknown.NO
    }

    class UnrefinedTopologyException(val set: Any) : Exception()

    context(SetLike<S>)//  = mutableMapOf()
    fun brzozowskiL(base: @UnsafeVariance S): Regex<S> {
        val topology = this@SetLike
        // println("$base /: ${this@Regex} = ?")
        var r = when (this) {
            is Eps   -> Empty
            is Empty -> Empty
            is All   -> All

            is OneOf<S> -> {
//                println("OneOf: $base :/ $s")
//                println("  overlap: ${topology.testOverlap(base, s)}")
                when (topology.testOverlap(base, symbols)) {
                    Overlap.EMPTY -> Empty
                    Overlap.FIRST_CONTAINS_SECOND -> throw UnrefinedTopologyException(symbols)
                    Overlap.SECOND_CONTAINS_FIRST -> Eps
                    Overlap.EQUAL -> Eps
                    Overlap.PARTIAL -> throw UnrefinedTopologyException(symbols)
                }
            }

            is Alt<S> -> alt(args.map { it.brzozowskiL(base) })
            is And<S>  -> and(args.map { it.brzozowskiL(base) })
            is Not<S>  -> not(arg.brzozowskiL(base))
            is Star<S> -> arg.brzozowskiL(base) * this@Regex

            is Seq<S> -> {
                // e /: (x ~ y ~ z) = (e /: z) | (e /: y) ~ z | (e /: x) ~ y ~ z
                // provided that x and y are nullable

                if (args.size == 2) {
                    // If x is not nullable, then e /: (x ~ y) = (e /: x) ~ z
                    // otherwise                  e /: (x ~ y) = (e /: y) | (e /: x) ~ y
                    val x = args[0]
                    val y = args[1]

                    if (!x.nullable) {
                        val xD = x.brzozowskiL(base)
                        if (xD.fastIsEmptyOrNull() == YesNoUnknown.YES) return Empty
                        return xD * y
                    } else {
                        val xD = x.brzozowskiL(base)
                        val yD = y.brzozowskiL(base)
                        val xD_empty = xD.fastIsEmptyOrNull()
                        val yD_empty = yD.fastIsEmptyOrNull()
                        if (xD_empty == YesNoUnknown.YES && yD_empty == YesNoUnknown.YES) return Empty
                        if (xD_empty == YesNoUnknown.YES) return yD
                        if (yD_empty == YesNoUnknown.YES) return xD * y
                        return alt(listOf(yD, xD * y))
                    }
                } else {
                    val result = mutableListOf<Regex<S>>()
                    for (i in args.indices) {
                        val x = args[i]
                        val list = mutableListOf<Regex<S>>()
                        list.add(x.brzozowskiL(base))
                        list.addAll(args.subList(i + 1, args.size))
                        result.add(seq(list))
                        if (!x.nullable) break
                    }

                    alt(result)
                }
            }
        }

        //println("$base /: ${this@Regex} = $r")

        val isEmpty = r.fastIsEmptyOrNull()
        // For extended regexes, this might be null, which means that we don't know
        if (isEmpty == YesNoUnknown.YES) r = Empty

        //println("$base /: ${this@Regex} = $r")
        return r
    }

    context(SetLike1<Element, S>)
    fun <Element> brzozowskiL(base: List<@UnsafeVariance Element>): Regex<S> {
        val setLike = this@SetLike1
        var r: Regex<S> = this
        for (a in base) {
            val aD = r.brzozowskiL(setLike.lift(a))
            val fD = aD.fastIsEmptyOrNull()
            if (fD == YesNoUnknown.YES) return Empty
            r = aD
        }
        return r
    }

    context(Topology<Element, S, Top>)
    fun <Element, Top> isEmpty(): Boolean {
        val topology = this@Topology
        var top = topology.trivial()

        // All of these must be empty.
        val queue = ArrayDeque<Regex<S>>()
        queue.add(this)
        val visited = mutableSetOf<Regex<S>>()

        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            val f = a.fastIsEmptyOrNull()
            if (f == YesNoUnknown.YES) continue
            if (f == YesNoUnknown.NO) return false

            if (a in visited) continue
            visited.add(a)

            while (true) {
                var refined = false
                for (baseSet in topology.basis(top)) {
                    val aD = try {
                        a.brzozowskiL(baseSet)
                    } catch (e: Regex.UnrefinedTopologyException) {
                        val newTop = topology.refineViaSet(top, e.set as S)
                        top = newTop
                        refined = true
                        break
                    }

                    if (aD in visited) continue
                    val fD = aD.fastIsEmptyOrNull()
                    if (fD == YesNoUnknown.YES) continue
                    if (fD == YesNoUnknown.NO) return false
                    queue.add(aD)
                }
                if (!refined) break
            }
        }

        return true
    }

    context(Topology<Element, S, Top>)
    fun <Element, Top> firstSets(maxSize: Int): List<S> {
        val topology = this@Topology
        var top = topology.trivial()

        // All of these must be empty.
        val queue = ArrayDeque<Pair<Int, Regex<S>>>()
        queue.add(0 to this)

        val result = MutableList<S>(maxSize) { topology.empty() }
        val emptyMemo = mutableMapOf<Regex<S>, Boolean>()
        fun isEmpty(r: Regex<S>): Boolean {
            val old = emptyMemo[r]
            if (old != null) return old
            if (r == Empty) return true
            val f = r.fastIsEmptyOrNull()
            if (f == YesNoUnknown.YES) return true
            if (f == YesNoUnknown.NO) return false
            val result = r.isEmpty()
            emptyMemo[r] = result
            return result
        }

        while (queue.isNotEmpty()) {
            val (offset, a) = queue.removeFirst()
            val f = a.fastIsEmptyOrNull()
            if (f == YesNoUnknown.YES) continue
            if (offset >= maxSize) continue

            while (true) {
                var refined = false
                for (baseSet in topology.basis(top)) {
                    val aD = try {
                        a.brzozowskiL(baseSet)
                    } catch (e: Regex.UnrefinedTopologyException) {
                        val newTop = topology.refineViaSet(top, e.set as S)
                        top = newTop
                        refined = true
                        break
                    }

                    if (isEmpty(aD)) continue
                    result[offset] = topology.union(result[offset], baseSet)
                    queue.add(offset + 1 to aD)
                }
                if (!refined) break
            }
        }

        return result
    }

    context(Topology<Element, S, Top>)
    fun <Element, Top> firstSet(): S =
        firstSets(1).first()

    context(Topology<Element, S, Top>)
    fun <Element, Top> intersects(that: Regex<@UnsafeVariance S>): Boolean =
        !and(this, that).isEmpty()

    context(Topology<Element, S, Top>)
    fun <Element, Top> equiv(other: Regex<@UnsafeVariance S>): Boolean {
        val topology = this@Topology
        var top = topology.trivial()

        fun heuristic(a: Regex<S>, b: Regex<S>): Boolean? {
            if (a == b) return true
            if (a.nullable != b.nullable) return false
            val aF = a.fastFirstSetOrNull()
            val bF = b.fastFirstSetOrNull()
            if ((aF != null && bF != null) && aF != bF) return false
            return null
        }

        val queue = ArrayDeque<Pair<Regex<S>, Regex<S>>>()
        queue.add(this to other)
        val visited = mutableSetOf<Pair<Regex<S>, Regex<S>>>()

        while (queue.isNotEmpty()) {
            val (a, b) = queue.removeFirst()
            if (a to b in visited) continue
            visited.add(a to b)

            val h = heuristic(a, b)

            if (h != null) {
                if (h) continue
                return false
            }

            while (true) {
                var refined = false
                for (baseSet in topology.basis(top)) {
                    val aD = try {
                        a.brzozowskiL(baseSet)
                    } catch (e: Regex.UnrefinedTopologyException) {
                        val newTop = topology.refineViaSet(top, e.set as S)
                        top = newTop
                        refined = true
                        break
                    }

                    val bD = try {
                        b.brzozowskiL(baseSet)
                    } catch (e: Regex.UnrefinedTopologyException) {
                        val newTop = topology.refineViaSet(top, e.set as S)
                        top = newTop
                        refined = true
                        break
                    }

                    if (aD to bD in visited) continue

                    val hD = heuristic(aD, bD)
                    if (hD != null) {
                        if (hD) continue
                        return false
                    }

                    queue.add(aD to bD)
                }
                if (!refined) break
            }
        }

        return true
    }

    context(Topology<Element, S, Top>)
    fun <Element, Top> includes(other: Regex<@UnsafeVariance S>): Boolean {
        val topology = this@Topology
        var top = topology.trivial()

        val queue = ArrayDeque<Pair<Regex<S>, Regex<S>>>()
        queue.add(this to other)
        val visited = mutableSetOf<Pair<Regex<S>, Regex<S>>>()

        fun heuristic(a: Regex<S>, b: Regex<S>): Boolean? {
            if (a == b) return true // if a == b, then a includes b

            if (a == Empty) {
                // if a == Empty, then a does not include b if b != Empty
                if (b != Empty) return false
            }

            // if b == Empty, then a includes b (empty set is included in any set)
            if (b == Empty) return true

            if (b.nullable) {
                // if b is nullable, then a does not include b iff a is not nullable
                if (!a.nullable) return false
            }

            val aF = a.fastFirstSetOrNull()
            val bF = b.fastFirstSetOrNull()
            if ((aF != null && bF != null) && !topology.containsAll(a.fastFirstSetOrThrow(), b.fastFirstSetOrThrow())) return false

            return null
        }

        while (queue.isNotEmpty()) {
            val (a, b) = queue.removeFirst()
            if (a to b in visited) continue
            visited.add(a to b)

            val cmp = heuristic(a, b)
            if (cmp != null) {
                if (cmp) continue
                else return false
            }

            while (true) {
                var refined = false
                for (baseSet in topology.basis(top)) {
                    val aD = try {
                        a.brzozowskiL(baseSet)
                    } catch (e: Regex.UnrefinedTopologyException) {
                        val newTop = topology.refineViaSet(top, e.set as S)
                        top = newTop
                        refined = true
                        break
                    }

                    val bD = try {
                        b.brzozowskiL(baseSet)
                    } catch (e: Regex.UnrefinedTopologyException) {
                        val newTop = topology.refineViaSet(top, e.set as S)
                        top = newTop
                        refined = true
                        break
                    }

                    if (aD to bD in visited) continue

                    val cmpD = heuristic(aD, bD)
                    if (cmpD != null) {
                        if (cmpD) continue
                        else return false
                    }

                    queue.add(aD to bD)
                }
                if (!refined) break
            }
        }

        return true
    }

    context(Topology<Element, S, Top>)
    fun <Element, Top> rebuild(): Regex<S> {
        val dfa = Compiler.buildDFA(this)
        return Compiler.dfaToRegex(dfa)
    }

    companion object {
        // ∀ = Σ*
        // ∧{} = ∀ = ¬∅
        // ∨{} = ∅ = ¬∀
        // ∘{} = ε
        val empty: Regex<Nothing> = Empty
        val eps: Regex<Nothing> = Eps
        val all: Regex<Nothing> = All

        fun <S : Any> seq(vararg l: Regex<S>): Regex<S> = seq(l.toList())

        fun <S : Any> seq(l: List<Regex<S>>): Regex<S> {
            when (l.size) {
                0 -> return Eps
                1 -> return l[0]
                else -> {
                    val args = mutableListOf<Regex<S>>()

                    for (r in l) {
                        when (r) {
                            Eps -> { }
                            Empty -> return Empty
                            is Seq<S> -> args.addAll(r.args)
                            else -> args.add(r)
                        }
                    }

                    if (args.isEmpty()) return Eps
                    if (args.size == 1) return args[0]

                    return Seq(args)
                }
            }
        }

        context(SetLike<S>) fun <S : Any> alt(vararg l: Regex<S>): Regex<S> = alt(l.toList())
        fun alt(vararg l: Regex<CharSet>): Regex<CharSet> = with (Topology.charRanges) { alt<CharSet>(l.toList()) }

        context(SetLike<S>) fun <S : Any> oneOf(s: S): Regex<S> =
            if (s == this@SetLike.empty()) Empty
            else OneOf(s)
        fun oneOf(s: CharSet): Regex<CharSet> = if (s.isEmpty()) Empty else OneOf(s)

        fun chars(vararg s: Char): Regex<CharSet> =
            if (s.isEmpty()) Empty
            else OneOf(CharSet.of(*s))

        fun chars(s: String): Regex<CharSet> =
            if (s.isEmpty()) Empty
            else OneOf(CharSet.of(s))

        fun chars(s: CharRange): Regex<CharSet> =
            if (s.isEmpty()) Empty
            else OneOf(CharSet.range(s.first, s.last))

        fun chars(vararg s: CharRange): Regex<CharSet> {
            val s = CharSet.of(*s)
            return if (s.isEmpty()) Empty
            else OneOf(s)
        }

        context(SetLike<S>) fun <S : Any> alt(l: List<Regex<S>>): Regex<S> {
            val setLike = this@SetLike
            when (l.size) {
                0 -> return Empty
                1 -> return l[0]
                else -> {
                    var set = setLike.empty()
                    var setCount = 0
                    var hasEpsilonArg = false
                    val args = mutableSetOf<Regex<S>>()

                    for (r in l) {
                        when (r) {
//                            All -> return All
                            Empty -> { }
                            Eps -> hasEpsilonArg = true
                            is OneOf<S> -> {
                                set = setLike.union(set, r.symbols)
                                setCount++
                            }
                            is Alt<S> -> for (r1 in r.args) {
                                when (r1) {
//                                    All -> return All
                                    Empty -> { }
                                    Eps -> hasEpsilonArg = true
                                    is OneOf<S> -> {
                                        set = setLike.union(set, r1.symbols)
                                        setCount++
                                    }
                                    else -> args.add(r1)
                                }
                            }
                            else -> args.add(r)
                        }
                    }

                    if (hasEpsilonArg) args.add(Eps)
                    if (setCount > 0) {
                        val s1 = oneOf(set)
                        if (s1 != Empty) args.add(s1)
                    }

                    if (args.isEmpty()) return Empty
                    if (args.size == 1) return args.toList().first()

                    if (args.size == 2) {
                        if (Eps in args) {
                            val other = args.first { it != Eps }
                            when (other) {
                                Eps -> return Eps
                                Empty -> return Eps
                                All -> return All
                                is Star<*> -> return other
                                else -> { }
                            }
                        }
                    }

//                    println("l = $l")
//                    println("args = $args")
                    return Alt(args.toList())
                }
            }
        }
        fun alt(l: List<Regex<CharSet>>): Regex<CharSet> = with (Topology.charRanges) { alt<CharSet>(l) }

        context(SetLike<S>) fun <S : Any> and(vararg l: Regex<S>): Regex<S> = and(l.toList())
        fun and(vararg l: Regex<CharSet>): Regex<CharSet> = with (Topology.charRanges) { and<CharSet>(l.toList()) }

        private sealed class AndState<out S : Any> {
            // Accepts all strings
            object All : AndState<Nothing>()
            // Accepts no strings
            object Empty : AndState<Nothing>()
            // Accepts some strings that begin with a set of symbols
            data class Some<S : Any>(val set: S?, val acceptsEmpty: Boolean) : AndState<S>()

            context(SetLike<S>)
            fun meets(other: AndState<@UnsafeVariance S>): AndState<S> {
                val setLike = this@SetLike
                return when (this) {
                    All -> other
                    Empty -> Empty
                    is Some -> when (other) {
                        All -> this
                        Empty -> Empty
                        is Some -> {
                            val set =
                                if (this.set == null || other.set == null) null
                                else setLike.intersect(this.set, other.set)
                            val acceptsEmpty = this.acceptsEmpty && other.acceptsEmpty
                            if (set != null && setLike.isEmpty(set) && !acceptsEmpty) Empty
                            else Some(set, acceptsEmpty)
                        }
                    }
                }
            }
        }

        context(SetLike<S>)fun <S : Any> and(l: List<Regex<S>>): Regex<S> {
            val setLike = this@SetLike
            when (l.size) {
                0 -> return All
                1 -> return l[0]
                else -> {
                    var set = setLike.all()
                    var setCount = 0
                    val args = mutableListOf<Regex<S>>()

                    var state: AndState<S> = AndState.All

                    for (r in l) {
                        when (r) {
                            All -> {
                                state = state.meets(AndState.All)
                            }
                            Empty -> return Empty
                            Eps -> {
                                state = state.meets(AndState.Some(setLike.empty(), true))
                            }
                            is OneOf<S> -> {
                                state = state.meets(AndState.Some(r.symbols, false))
                                set = setLike.intersect(set, r.symbols)
                                setCount++
                            }
                            is And<S> -> for (r1 in r.args) {
                                when (r1) {
                                    All -> {
                                        state = state.meets(AndState.All)
                                    }
                                    Empty -> return Empty
                                    Eps -> {
                                        state = state.meets(AndState.Some(setLike.empty(), true))
                                    }
                                    is OneOf<S> -> {
                                        state = state.meets(AndState.Some(r1.symbols, false))
                                        set = setLike.intersect(set, r1.symbols)
                                        setCount++
                                    }
                                    else -> {
                                        state = state.meets(AndState.Some(r1.fastFirstSetOrNull(), r1.nullable))
                                        args.add(r1)
                                    }
                                }
                            }
                            else -> {
                                val s1 = AndState.Some(r.fastFirstSetOrNull(), r.nullable)
                                state = state.meets(s1)
                                args.add(r)
                            }
                        }

                        if (state == AndState.Empty) return Empty
                    }

                    if (state == AndState.All) return All

                    state as AndState.Some
                    val stateSet = state.set
                    if (stateSet != null) {
                        if (setLike.isEmpty(stateSet)) {
                            if (state.acceptsEmpty) return Eps
                            return Empty
                        }
                        if (state.acceptsEmpty) {
                            if (args.size == 0) return Eps
                        }
                    }

                    if (setCount > 0)  {
                        if (setLike.isEmpty(set)) return Empty
                        val s1 = oneOf(set)
                        args.add(s1)
                    }

                    if (args.size == 1) return args[0]

                    return And(args)
                }
            }
        }
        fun and(l: List<Regex<CharSet>>): Regex<CharSet> = with (Topology.charRanges) { and<CharSet>(l) }

        context(SetLike<S>) fun <S : Any> not(r: Regex<S>): Regex<S> {
            val setLike = this@SetLike
            return when (r) {
                Empty -> All
                All -> Empty
                Eps -> seq(oneOf(setLike.all()), All)
                is Not<S> -> r.arg
//                is OneOf<S> -> alt(
//                    Eps,
//                    seq(oneOf(setLike.invert(r.symbols)), All)
//                )

                else -> Not(r)
//                is Alt -> TODO()
//                is And -> TODO()
//                is Seq -> TODO()
//                is Star -> TODO()
            }
        }
        fun not(r: Regex<CharSet>): Regex<CharSet> = with (Topology.charRanges) { not<CharSet>(r) }

        context(SetLike<S>) fun <S : Any> star(r: Regex<S>): Regex<S> = when (r) {
            Eps -> Eps
            Empty -> Eps
            All -> All
            is OneOf ->
                if (r.symbols == this@SetLike.all()) All
                else Star(r)
            is Star -> r
            else -> Star(r)
        }
        fun star(r: Regex<CharSet>): Regex<CharSet> = with (Topology.charRanges) { star<CharSet>(r) }

        context(SetLike<S>) fun <S : Any> plus(r: Regex<S>): Regex<S> = when (r) {
            Eps -> Eps
            Empty -> Empty
            All -> All
            is Star -> r
            else -> seq(r, star(r))
        }
        fun plus(r: Regex<CharSet>): Regex<CharSet> = with (Topology.charRanges) { plus<CharSet>(r) }

        context(SetLike<S>) fun <S : Any> opt(r: Regex<S>): Regex<S> = when (r) {
            Eps -> Eps
            Empty -> Eps
            All -> All
            else -> alt(r, eps)
        }
        fun opt(r: Regex<CharSet>): Regex<CharSet> = with (Topology.charRanges) { opt<CharSet>(r) }

        context(SetLike<S>) fun <S : Any> rep(r: Regex<S>, min: Int, max: Int?): Regex<S> {
            require(min >= 0)
            require(max == null || max >= min)

            val args = mutableListOf<Regex<S>>()
            for (i in 1..min) args.add(r)
            if (max == null) args.add(star(r))
            else for (i in min + 1..max) args.add(opt(r))
            return seq(args)
        }
        fun rep(r: Regex<CharSet>, min: Int, max: Int?): Regex<CharSet> = with (Topology.charRanges) { rep<CharSet>(r, min, max) }

        fun char(c: Char): Regex<CharSet> = OneOf(CharSet.one(c))

        fun string(s: String): Regex<CharSet> = seq(s.map { char(it) })
    }
}
