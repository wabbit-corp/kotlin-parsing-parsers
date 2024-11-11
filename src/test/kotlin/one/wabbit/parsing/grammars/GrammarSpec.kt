package one.wabbit.parsing.grammars

import one.wabbit.parsing.charset.CharSet
import one.wabbit.parsing.charset.Topology
import kotlin.test.Test

class ParserTest : Parser.CharModule() {
    val num: Parser<CharSet, Char, Int> = Parser.Choice(
        listOf(
            char('0').ignore(0),
            char('1').ignore(1),
            char('2').ignore(2),
        )
    )

    val test1: Parser<CharSet, Char, Int> = delay { test2 }
    val test2: Parser<CharSet, Char, Int> = delay { test1 }

    val factor: Parser<CharSet, Char, Int> = delay {
        num or (char('(') + expr + char(')')).map3 { _, v, _ -> v } or
                test1
    }

    val expr: Parser<CharSet, Char, Int> = delay {
        factor or (factor + char('+') + factor).map3 { v1, _, v2 -> v1 + v2 } or
                (factor + char('-') + factor).map3 { v1, _, v2 -> v1 - v2 }
    }

    @Test
    fun test() {
        with (Topology.charRanges) {
            val graph = expr.toGraph()
//        println(graph)
//        println(Ref(test1))
            // println(graph.nodeInfo()[Ref(test1)])
            println(graph.show())
            println("==========================")
            println(graph.voidAndSimplify().show())
        }
    }
}
