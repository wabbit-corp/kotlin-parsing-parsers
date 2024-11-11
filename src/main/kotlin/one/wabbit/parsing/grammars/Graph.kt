package one.wabbit.parsing.grammars

import one.wabbit.data.Need
import one.wabbit.data.Ref
import one.wabbit.parsing.charset.CharSet
import one.wabbit.parsing.charset.SetLike1

sealed class ParserF<out SS, out S, out P> {
    abstract val children: List<P>

    data object Epsilon : ParserF<Nothing, Nothing, Nothing>() {
        override val children: List<Nothing> = listOf()
    }
    data class Terminal<S>(val symbol: S) : ParserF<S, Nothing, Nothing>() {
        override val children: List<Nothing> = listOf()
    }
    data class Choice<SS, S, P>(val list: List<P>) : ParserF<SS, S, P>() {
        override val children: List<P> = list
    }

    sealed class Seq<SS, S, P> : ParserF<SS, S, P>() {
        abstract val left: P
        abstract val right: P
        override val children: List<P>
            get() = listOf(left, right)
    }

    data class Sequence<SS, S, P>(override val left: P, override val right: P) : Seq<SS, S, P>()
    data class ZipLeft<SS, S, P>(override val left: P, override val right: P) : Seq<SS, S, P>()
    data class ZipRight<SS, S, P>(override val left: P, override val right: P) : Seq<SS, S, P>()

    data class Star<SS, S, P>(val parser: P) : ParserF<SS, S, P>() {
        override val children: List<P> = listOf(parser)
    }
    data class Plus<SS, S, P>(val parser: P) : ParserF<SS, S, P>() {
        override val children: List<P> = listOf(parser)
    }
    data class Optional<SS, S, P>(val parser: P) : ParserF<SS, S, P>() {
        override val children: List<P> = listOf(parser)
    }
    data class Test<SS, S, P>(val parser: P) : ParserF<SS, S, P>() {
        override val children: List<P> = listOf(parser)
    }

    sealed class Redirect<SS, S, P>(val child: P) : ParserF<SS, S, P>() {
        override val children: List<P> = listOf(child)
    }
    data class Delay<SS, S, P>(val thunk: Need<P>) : Redirect<SS, S, P>(thunk.value)
    data class Void<SS, S, P>(val parser: P) : Redirect<SS, S, P>(parser)
    data class Named<SS, S, P>(val parser: P) : Redirect<SS, S, P>(parser)
    data class Capture<SS, S, P>(val parser: P) : Redirect<SS, S, P>(parser)
    data class CaptureString<P>(val parser: P) : Redirect<CharSet, Char, P>(parser)
    data class Map<SS, S, P>(val parser: P, val f: (Any?) -> Any?) : Redirect<SS, S, P>(parser)
}

data class Graph<SS, S, Id>(val root: Id, val nodes: Map<Id, ParserF<SS, S, Id>>)

fun <SS, S, A> Parser<SS, S, A>.toGraph(): Graph<SS, S, Ref<Parser<SS, S, *>>> {
    val nodes = mutableMapOf<Ref<Parser<SS, S, *>>, ParserF<SS, S, Ref<Parser<SS, S, *>>>>()
    val queue = ArrayDeque<Ref<Parser<SS, S, *>>>()
    val root = Ref<Parser<SS, S, *>>(this)
    queue.add(root)

    while (true) {
        val next = queue.firstOrNull() ?: break
        queue.removeFirst()
        if (next in nodes) continue

        when (val value = next.value) {
            is Parser.Epsilon ->
                nodes[next] = ParserF.Epsilon
            is Parser.Terminal<*, *> ->
                nodes[next] = ParserF.Terminal(value.symbol) as ParserF<SS, S, Ref<Parser<SS, S, *>>>
            is Parser.Choice -> {
                val children = value.list.map { Ref(it) }
                nodes[next] = ParserF.Choice(children)
                for (child in children) {
                    if (child !in nodes) queue.add(child)
                }
            }
            is Parser.Sequence<SS, S, *, *> -> {
                val left = Ref(value.left)
                val right = Ref(value.right)
                nodes[next] = ParserF.Sequence(left, right)
                if (left !in nodes) queue.add(left)
                if (right !in nodes) queue.add(right)
            }
            is Parser.ZipLeft<SS, S, *, *> -> {
                val left = Ref(value.left)
                val right = Ref(value.right)
                nodes[next] = ParserF.ZipLeft(left, right)
                if (left !in nodes) queue.add(left)
                if (right !in nodes) queue.add(right)
            }
            is Parser.ZipRight<SS, S, *, *> -> {
                val left = Ref(value.left)
                val right = Ref(value.right)
                nodes[next] = ParserF.ZipRight(left, right)
                if (left !in nodes) queue.add(left)
                if (right !in nodes) queue.add(right)
            }

            is Parser.Optional<SS, S, *> -> {
                val child = Ref(value.parser)
                nodes[next] = ParserF.Optional(child)
                if (child !in nodes) queue.add(child)
            }
            is Parser.Test<SS, S, *> -> {
                val child = Ref(value.parser)
                nodes[next] = ParserF.Test(child)
                if (child !in nodes) queue.add(child)
            }

            is Parser.Star<SS, S, *> -> {
                val child = Ref(value.parser)
                nodes[next] = ParserF.Star(child)
                if (child !in nodes) queue.add(child)
            }
            is Parser.Plus<SS, S, *> -> {
                val child = Ref(value.parser)
                nodes[next] = ParserF.Plus(child)
                if (child !in nodes) queue.add(child)
            }

            is Parser.Ignore<SS, S> -> {
                val child = Ref(value.parser)
                nodes[next] = ParserF.Void(child)
                if (child !in nodes) queue.add(child)
            }
            is Parser.Capture<SS, S, *> -> {
                val child = Ref(value.parser)
                nodes[next] = ParserF.Capture(child)
                if (child !in nodes) queue.add(child)
            }
            is Parser.CaptureString<*> -> {
                val child = Ref(value.parser) as Ref<Parser<SS, S, *>>
                nodes[next] = ParserF.CaptureString(child) as ParserF<SS, S, Ref<Parser<SS, S, *>>>
                if (child !in nodes) queue.add(child)
            }
            is Parser.Named -> {
                val child = Ref(value.parser)
                nodes[next] = ParserF.Named(child)
                if (child !in nodes) queue.add(child)
            }

            is Parser.Map<SS, S, *, *> -> {
                val child = Ref(value.parser)
                nodes[next] = ParserF.Map(child, value.transform as (Any?) -> Any?)
                if (child !in nodes) queue.add(child)
            }
            is Parser.Delay -> {
                val child = Ref(value.thunk.value)
                nodes[next] = ParserF.Delay(Need.now(child))
                if (child !in nodes) queue.add(child)
            }

            is Parser.FlatMap<SS, S, *, *> -> {
                throw IllegalStateException("FlatMap should be eliminated")
            }

            is Parser.Error -> TODO()
            is Parser.Filter -> TODO()
            is Parser.AndNot -> TODO()
            is Parser.AndNotFollowedBy -> TODO()
        }

        require(nodes[next]!!.children.all { it != null }) {
            "Parser ${next.value} has null children"
        }
    }

    return Graph(root, nodes)
}

sealed class MaxSize : Comparable<MaxSize> {
    object Infinite : MaxSize()
    data class Fixed(val value: Int) : MaxSize()

    override fun compareTo(other: MaxSize): Int = when (this) {
        is Infinite -> when (other) {
            is Infinite -> 0
            is Fixed -> 1
        }
        is Fixed -> when (other) {
            is Infinite -> -1
            is Fixed -> value.compareTo(other.value)
        }
    }
}

data class NodeInfo<SS, Id>(
    val cyclic: Boolean,
    val minLength: Int,
    val productive: Boolean,
    val initialSet: SS,
    val fullSet: SS,
    val directDescendents: Set<Id>)

fun <SS, S, Id> Graph<SS, S, Id>.voidAndSimplify(): Graph<SS, S, Id> {
    val representative = mutableMapOf<Id, Id>()

    for (id in nodes.keys) {
        if (id in representative) continue
        val node = nodes[id]!!

        if (node is ParserF.Redirect) {
            if (node.child in representative)
                representative[id] = representative[node.child]!!
            else
                representative[id] = node.child
        }
    }

    println(representative)

    val newNodes = nodes.toMutableMap()

    while (true) {
        var modified = false

        for (id in nodes.keys) {
            val node = newNodes[id]!!

            val newNode: ParserF<SS, S, Id> = when (node) {
                is ParserF.Epsilon -> node
                is ParserF.Terminal -> node
                is ParserF.Sequence -> node
                is ParserF.ZipLeft -> node
                is ParserF.ZipRight -> node
                is ParserF.Choice -> node
                is ParserF.Star -> node
                is ParserF.Plus -> node
                is ParserF.Optional -> node
                is ParserF.Test -> node
                is ParserF.Redirect -> newNodes[representative[id]]!!
            }

            if (newNode != node) {
                modified = true
                newNodes[id] = newNode
            }
        }

        if (!modified) break
    }

    println(newNodes)

    val newRoot = representative[root] ?: root
    val reachable = mutableSetOf<Id>()
    val queue = ArrayDeque<Id>()
    queue.add(newRoot)
    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()!!

        if (next in reachable) continue
        reachable.add(next)

        val node = newNodes[next] ?: run {
            println("Missing node $next")
            throw IllegalStateException("Missing node $next")
        }
        node.children.forEach { queue.add(it!!) }
    }
    for (k in newNodes.keys.toList()) {
        if (k !in reachable) newNodes.remove(k)
    }

    return Graph(newRoot, newNodes)
}

context(SetLike1<S, SS>)
fun <SS, S, Id> Graph<SS, S, Id>.nodeInfo(): Map<Id, NodeInfo<SS, Id>> {
    val setLike = this@SetLike1
    // Step 0. Check that there are no cycles
    val directChildren = mutableMapOf<Id, Set<Id>>()
    while (true) {
        var modified = false
        for ((id, node) in nodes) {
            var newValue: Set<Id>? = null
            when (node) {
                is ParserF.Epsilon -> newValue = emptySet()
                is ParserF.Terminal -> newValue = emptySet()
                is ParserF.Redirect -> {
                    val v = directChildren[node.child]
                    if (v == null) newValue = setOf(node.child)
                    else newValue = v + node.child
                }
                is ParserF.Plus -> {
                    val v = directChildren[node.parser]
                    if (v == null) newValue = setOf(node.parser)
                    else newValue = v + node.parser
                }

                is ParserF.Star -> newValue = emptySet()
                is ParserF.Optional -> newValue = emptySet()

                is ParserF.Test -> newValue = emptySet()

                is ParserF.Choice -> {
                    newValue = this.nodes.keys.toMutableSet()
                    for (child in node.list) {
                        val v = directChildren[child]
                        if (v != null) {
                            newValue = newValue?.intersect(v)
                        }
                    }
                }
                is ParserF.Seq -> {
                    val left = directChildren[node.left]
                    val right = directChildren[node.right]
                    if (left != null && right != null) {
                        newValue = left.intersect(right)
                    }
                }
            }

            val oldValue = directChildren[id]

            if (oldValue != newValue) {
                newValue!!
                modified = true
                directChildren[id] = newValue
            }
        }

        if (!modified) break
    }

    val hasCycles = directChildren.map { it.key to (it.key in it.value) }.toMap()

    // Step 1. Check that all rules are productive, i.e. have a non-epsilon production.
    val productive = mutableMapOf<Id, Boolean>()
    while (true) {
        var modified = false
        for ((id, node) in nodes) {
            val newValue: Boolean?
            when (node) {
                is ParserF.Epsilon -> {
                    newValue = false
                }
                is ParserF.Terminal -> {
                    newValue = true
                }
                is ParserF.Choice -> {
                    var anyProductive = false
                    var allNonProductive = true
                    for (child in node.list) {
                        val v = productive[child]
                        if (v == null) {
                            allNonProductive = false
                            continue
                        }
                        if (v) {
                            anyProductive = true
                            allNonProductive = false
                        }
                    }
                    if (anyProductive) newValue = true
                    else if (allNonProductive) newValue = false
                    else newValue = null
                }
                is ParserF.Seq -> {
                    val left = productive[node.left]
                    val right = productive[node.right]
                    if (left == true) newValue = true
                    else if (right == true) newValue = true
                    else if (left == false && right == false) newValue = false
                    else newValue = null
                }
                is ParserF.Star -> newValue = productive[node.parser]
                is ParserF.Plus -> newValue = productive[node.parser]
                is ParserF.Optional -> newValue = productive[node.parser]
                is ParserF.Test -> newValue = productive[node.parser]
                is ParserF.Void -> newValue = productive[node.parser]
                is ParserF.Capture -> newValue = productive[node.parser]
                is ParserF.CaptureString -> newValue = productive[node.parser]
                is ParserF.Named -> newValue = productive[node.parser]
                is ParserF.Map -> newValue = productive[node.parser]
                is ParserF.Delay -> newValue = productive[node.thunk.value]
            }

            val oldValue = productive[id]
            if (oldValue != newValue) {
                newValue!!
                modified = true
                productive[id] = newValue
            }
        }

        if (!modified) break
    }

    // Step 2. Compute the minimum length of each rule.
    val minLengths = mutableMapOf<Id, Int>()
    while (true) {
        var modified = false
        for ((id, node) in nodes) {
            val oldLength = minLengths[id]

            val newLength = when (node) {
                is ParserF.Epsilon -> 0
                is ParserF.Terminal -> 1
                is ParserF.Choice -> {
                    val children = node.list.map { minLengths[it] }
                    var min: Int? = null
                    for (child in children) {
                        if (child == null) continue
                        if (min == null || child < min) min = child
                    }
                    min
                }
                is ParserF.Seq -> {
                    val left = node.left
                    val right = node.right
                    val leftLength = minLengths[left]
                    val rightLength = minLengths[right]
                    if (leftLength == null) rightLength
                    else if (rightLength == null) leftLength
                    else leftLength + rightLength
                }
                is ParserF.Star -> 0
                is ParserF.Plus -> {
                    val child = node.parser
                    minLengths[child]
                }
                is ParserF.Optional -> 0
                is ParserF.Test -> 0
                is ParserF.Void -> {
                    val child = node.parser
                    minLengths[child]
                }
                is ParserF.Capture -> {
                    val child = node.parser
                    minLengths[child]
                }
                is ParserF.Named -> {
                    val child = node.parser
                    minLengths[child]
                }
                is ParserF.CaptureString -> {
                    val child = node.parser
                    minLengths[child]
                }
                is ParserF.Map -> {
                    val child = node.parser
                    minLengths[child]
                }
                is ParserF.Delay -> {
                    val child = node.thunk.value
                    minLengths[child]
                }
            }

            if (newLength != null && oldLength != null) {
                assert(newLength >= oldLength)
            }

            if (newLength != oldLength) {
                newLength!!
                minLengths[id] = newLength
                modified = true
            }
        }
        if (!modified) break
    }

    // Step 3. Compute the initial set of each rule.
    val initialSets = mutableMapOf<Id, SS>()
    while (true) {
        var modified = false
        for ((id, node) in nodes) {
            val oldSet = initialSets[id]

            val newSet = when (node) {
                is ParserF.Epsilon -> setLike.empty()
                is ParserF.Terminal -> node.symbol
                is ParserF.Choice -> {
                    val children = node.list.map { initialSets[it] }
                    var result: SS = setLike.empty()
                    for (child in children) {
                        if (child == null) continue
                        result = setLike.union(result, child)
                    }
                    result
                }
                is ParserF.Seq -> {
                    val left = node.left
                    val right = node.right
                    val leftSet = initialSets[left]
                    val rightSet = initialSets[right]
                    if (hasCycles[left]!!) setLike.empty()
                    else if (!productive[left]!!) rightSet else {
                        if (minLengths[left]!! != 0) leftSet
                        else {
                            if (leftSet != null && rightSet != null) setLike.union(leftSet, rightSet)
                            else if (leftSet != null) leftSet
                            else if (rightSet != null) rightSet
                            else null
                        }
                    }
                }
                is ParserF.Star -> {
                    val child = node.parser
                    initialSets[child]
                }
                is ParserF.Plus -> {
                    val child = node.parser
                    initialSets[child]
                }
                is ParserF.Optional -> {
                    val child = node.parser
                    initialSets[child]
                }
                is ParserF.Test -> {
                    val child = node.parser
                    initialSets[child]
                }
                is ParserF.Redirect -> {
                    val child = node.child
                    initialSets[child]
                }
            }

            if (newSet != null && oldSet != null) {
                assert(setLike.containsAll(newSet, oldSet))
            }

            if (newSet != oldSet) {
                newSet!!
                initialSets[id] = newSet
                modified = true
            }
        }

        if (!modified) break
    }

    // Step 4. Compute the full set of each rule.
    val fullSets = mutableMapOf<Id, SS>()
    while (true) {
        var modified = false
        for ((id, node) in nodes) {
            val oldSet = fullSets[id]

            val newSet = when (node) {
                is ParserF.Epsilon -> setLike.empty()
                is ParserF.Terminal -> node.symbol
                is ParserF.Choice -> {
                    val children = node.list.map { fullSets[it] }
                    var result: SS = setLike.empty()
                    for (child in children) {
                        if (child == null) continue
                        result = setLike.union(result, child)
                    }
                    result
                }
                is ParserF.Seq -> {
                    val left = node.left
                    val right = node.right
                    val leftSet = initialSets[left]
                    val rightSet = initialSets[right]
                    if (leftSet == null) rightSet
                    else if (rightSet == null) leftSet
                    else setLike.union(leftSet, rightSet)
                }
                is ParserF.Star -> {
                    val child = node.parser
                    fullSets[child]
                }
                is ParserF.Plus -> {
                    val child = node.parser
                    fullSets[child]
                }
                is ParserF.Optional -> {
                    val child = node.parser
                    fullSets[child]
                }
                is ParserF.Test -> {
                    val child = node.parser
                    fullSets[child]
                }
                is ParserF.Redirect -> {
                    val child = node.child
                    fullSets[child]
                }
            }

            if (newSet != null && oldSet != null) {
                assert(setLike.containsAll(newSet, oldSet))
            }

            if (newSet != oldSet) {
                newSet!!
                fullSets[id] = newSet
                modified = true
            }
        }

        if (!modified) break
    }

//    // Step 5. Compute the max size for each rule.
//    val maxSizes = mutableMapOf<Id, MaxSize>()
//    while (true) {
//        var modified = false
//        for ((id, node) in nodes) {
//            val oldSize = maxSizes[id]
//
//            val newSize = when (node) {
//                is ParserF.Epsilon -> MaxSize.Fixed(0)
//                is ParserF.Terminal -> MaxSize.Fixed(1)
//                is ParserF.Redirect -> {
//                    val child = node.child
//                    maxSizes[child]
//                }
//                is ParserF.Optional -> {
//                    val child = node.parser
//                    maxSizes[child]
//                }
//                is ParserF.Star -> {
//                    val child = node.parser
//                    if (hasCycles[child]!!) MaxSize.Fixed(0)
//                    else if (productive[child]!!) MaxSize.Infinite
//                    else MaxSize.Fixed(0)
//                }
//                is ParserF.Plus -> {
//                    val child = node.parser
//                    if (hasCycles[child]!!) MaxSize.Fixed(0)
//                    else if (productive[child]!!) MaxSize.Infinite
//                    else MaxSize.Fixed(0)
//                }
//                is ParserF.Sequence -> {
//                    val left = node.left
//                    val right = node.right
//                    val leftSize = maxSizes[left]
//                    val rightSize = maxSizes[right]
//                    if (leftSize == null) rightSize
//                    else if (rightSize == null) leftSize
//                    else {
//                        if (leftSize == MaxSize.Infinite) MaxSize.Infinite
//                        else if (rightSize == MaxSize.Infinite) MaxSize.Infinite
//                        else {
//                            leftSize as MaxSize.Fixed
//                            rightSize as MaxSize.Fixed
//                            MaxSize.Fixed(leftSize.value + rightSize.value)
//                        }
//                    }
//                }
//                is ParserF.Choice -> {
//                    val children = node.list.map { maxSizes[it] }
//                    var result: MaxSize? = null
//                    for (child in children) {
//                        if (child == null) continue
//                        if (result == null) result = child
//                        else result = result.max(child)
//                    }
//                    result
//                }
//            }
//
//            if (newSize != null && oldSize != null) {
//                assert(newSize >= oldSize)
//            }
//
//            if (newSize != oldSize) {
//                newSize!!
//                maxSizes[id] = newSize
//                modified = true
//            }
//        }
//
//        if (!modified) break
//    }

    val result = mutableMapOf<Id, NodeInfo<SS, Id>>()
    for ((id, _) in nodes) {
        val directDescendents = directChildren[id]!!
        val cyclic = hasCycles[id]!!
        if (cyclic) {
            assert(id !in productive)
            assert(id !in minLengths)
            assert(id !in initialSets)
            assert(id !in fullSets)
            result[id] = NodeInfo(true, 0, false, setLike.empty(), setLike.empty(), directDescendents)
            continue
        }
        val productive = productive[id]!!
        val minLength = minLengths[id]!!
        val initialSet = initialSets[id]!!
        val fullSet = fullSets[id]!!

        if (productive) {
            assert(!setLike.isEmpty(initialSet))
            assert(!setLike.isEmpty(fullSet))
        } else {
            assert(minLength == 0)
            assert(setLike.isEmpty(initialSet))
            assert(setLike.isEmpty(fullSet))
        }
        assert(setLike.containsAll(fullSet, initialSet))

        result[id] = NodeInfo(false, minLength, productive, initialSet, fullSet, directDescendents)
    }
    return result
}

context(SetLike1<S, SS>)
fun <SS, S, Id> Graph<SS, S, Id>.show(): String {
    val ni = this.nodeInfo()

    var nextId = 1
    val ids = nodes.keys.toList().map {
        it to (nextId++)
    }.toMap()

    val sb = StringBuilder()
    sb.append("S = A${ids[root]}\n")
    for ((id, node) in nodes) {
        sb.append("$id -- A${ids[id]!!} = ")
        when (node) {
            is ParserF.Epsilon ->
                sb.append("ε")
            is ParserF.Terminal ->
                sb.append("'${node.symbol}'")
            is ParserF.Choice -> {
                var first = true
                for (child in node.list) {
                    if (!first) sb.append(" | ")
                    sb.append("A${ids[child]}")
                    first = false
                }
            }
            is ParserF.Seq -> {
                sb.append("A${ids[node.left]}")
                sb.append(" ")
                sb.append("A${ids[node.right]}")
            }
            is ParserF.Star -> {
                sb.append("A${ids[node.parser]}*")
            }
            is ParserF.Plus -> {
                sb.append("A${ids[node.parser]}+")
            }
            is ParserF.Optional -> {
                sb.append("A${ids[node.parser]}?")
            }
            is ParserF.Test -> {
                sb.append("A${ids[node.parser]}? // test")
            }
            is ParserF.Void -> {
                sb.append("A${ids[node.parser]} // void")
            }
            is ParserF.Capture -> {
                sb.append("A${ids[node.parser]} // capture")
            }

            is ParserF.CaptureString -> {
                sb.append("A${ids[node.parser]} // capture-string")
            }
            is ParserF.Named -> {
                sb.append("A${ids[node.parser]} // named")
            }

            is ParserF.Map -> {
                sb.append("A${ids[node.parser]} // map")
            }
            is ParserF.Delay -> {
                sb.append("A${ids[node.thunk.value]} // delay")
            }

        }
        sb.append(" ")
        sb.append(ni[id].toString())
        sb.append("\n")
    }
    return sb.toString()
}
