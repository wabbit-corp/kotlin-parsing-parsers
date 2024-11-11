package one.wabbit.parsing.regex

import one.wabbit.parsing.charset.CharSet
import one.wabbit.parsing.charset.Topology
import org.junit.Test
import kotlin.test.assertEquals

class RegexParserSpec {
    @Test
    fun testParser() {
        with(Topology.charRanges) {
            with(Regex) {
                fun parse(s: String) = RegexParser.parse(s)

                assertEquals(eps, parse(""))

                assertEquals(eps, parse("()"))
                assertEquals(eps, parse("()|()"))
                assertEquals(empty, parse("[]"))
                assertEquals(empty, parse("[]|[]"))
                assertEquals(char('a'), parse("a"))
                assertEquals(char('a'), parse("(a)"))
                assertEquals(chars("ab"), parse("a|b"))
                assertEquals(chars("ab"), parse("(a|b)"))
                assertEquals(seq(listOf(char('a'), char('b'))), parse("ab"))
                assertEquals(seq(listOf(char('a'), char('b'))), parse("(ab)"))
                assertEquals(alt(listOf(char('a'), char('b'))), parse("a|b"))
                assertEquals(alt(listOf(char('a'), char('b'))), parse("(a|b)"))
                assertEquals(star(char('a')), parse("a*"))
                assertEquals(star(char('a')), parse("(a*)"))
                assertEquals(plus(char('a')), parse("a+"))
                assertEquals(plus(char('a')), parse("(a+)"))
                assertEquals(opt(char('a')), parse("a?"))
                assertEquals(opt(char('a')), parse("(a?)"))
                assertEquals(rep(char('a'), 3, 5), parse("a{3,5}"))
                assertEquals(rep(char('a'), 3, 5), parse("(a{3,5})"))
                assertEquals(rep(char('a'), 3, null), parse("a{3,}"))
                assertEquals(rep(char('a'), 3, null), parse("(a{3,})"))
                assertEquals(rep(char('a'), 3, 3), parse("a{3}"))
                assertEquals(rep(char('a'), 3, 3), parse("(a{3})"))
                assertEquals(rep(char('a'), 0, null), parse("a*"))
                assertEquals(rep(char('a'), 0, null), parse("(a*)"))
                assertEquals(rep(char('a'), 1, null), parse("a+"))
                assertEquals(rep(char('a'), 1, null), parse("(a+)"))
                assertEquals(rep(char('a'), 0, 1), parse("a?"))
                assertEquals(rep(char('a'), 0, 1), parse("(a?)"))
                assertEquals(star(alt(listOf(char('a'), char('b')))), parse("(a|b)*"))
                assertEquals(star(alt(listOf(char('a'), char('b')))), parse("((a|b)*)"))
                assertEquals(plus(alt(listOf(char('a'), char('b')))), parse("(a|b)+"))
                assertEquals(plus(alt(listOf(char('a'), char('b')))), parse("((a|b)+)"))
                assertEquals(opt(alt(listOf(char('a'), char('b')))), parse("(a|b)?"))
                assertEquals(opt(alt(listOf(char('a'), char('b')))), parse("((a|b)?)"))
                assertEquals(rep(alt(listOf(char('a'), char('b'))), 3, 5), parse("(a|b){3,5}"))
                assertEquals(rep(alt(listOf(char('a'), char('b'))), 3, 5), parse("((a|b){3,5})"))
                assertEquals(rep(alt(listOf(char('a'), char('b'))), 3, null), parse("(a|b){3,}"))
                assertEquals(rep(alt(listOf(char('a'), char('b'))), 3, null), parse("((a|b){3,})"))
                assertEquals(rep(alt(listOf(char('a'), char('b'))), 3, 3), parse("(a|b){3}"))
                assertEquals(rep(alt(listOf(char('a'), char('b'))), 3, 3), parse("((a|b){3})"))
                // Char ranges
                assertEquals(chars("a"), parse("[a]"))
                assertEquals(chars("a"), parse("([a])"))
                assertEquals(chars("a").opt, parse("[a]|"))
                assertEquals(chars("a").opt, parse("|[a]"))
                assertEquals(chars("a").opt, parse("|[a]|"))
                assertEquals(chars("a"), parse("[a]|[]"))
                assertEquals(chars("a"), parse("[]|[a]"))
                assertEquals(chars("a"), parse("[]|[a]|[]"))
                assertEquals(chars("a"), parse("[a]|[a]"))
                assertEquals(chars("ab"), parse("[ab]"))
                assertEquals(chars("ab"), parse("[a-b]"))
                assertEquals(chars("abc"), parse("[a-bc]"))
                assertEquals(chars("abc"), parse("[a-c]"))
                assertEquals(chars("abce"), parse("[a-ce]"))
                assertEquals(oneOf(CharSet.of("def").invert()), parse("[^d-f]"))

                // parse [a-zA-Z0-9._%+-]
                assertEquals(
                    oneOf(
                    CharSet.range('a', 'z') union
                            CharSet.range('A', 'Z') union
                            CharSet.range('0', '9') union
                            CharSet.of("._%+-")
                ), parse("[a-zA-Z0-9._%+-]"))


            }
        }
//        println(Parser("a|b").parse())
//        println(Parser("(a|b)*").parse())
//        println(Parser("(0|1)*22*").parse())

//        val simpleEmailRegex = Parser("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").parse()
//        println(simpleEmailRegex)

//        println(Parser("[a-z]+").parse())

//        val emailRegex = "(?:[a-z0-9!#\$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#\$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])"
//        println(Parser(emailRegex).parse())
    }
}
