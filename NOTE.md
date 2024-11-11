# FastCodec
Fast Scala Codecs POC

## Regex -> DFA

https://www.ccs.neu.edu/home/turon/re-deriv.pdf

## DFA -> Java Bytecode

https://asm.ow2.io/asm4-guide.pdf


// a = And(args=[Star(r=Not(arg=Eps)), Star(r=OneOf(s=[a])), Star(r=OneOf(s=[a-b])), Star(r=OneOf(s=[d])), Not(arg=Alt(l=[Eps, OneOf(s=[\x00-bd-\uffff])]))])
                // val a = star(not(eps)) and star(chars("a")) and star(chars('a'..'b')) and star(chars("d")) and not(alt(eps, chars(Char.MIN_VALUE..'b', 'd'..Char.MAX_VALUE)))

                // a = ((# . [a])*&[b]*&[c]*&[d])
                // nullable = false
                // first = []
                // a is Regex.Empty = false

                // # means Regex.all
                // . means Regex.seq

                // Error type A
                //  a = !([d]* . #)
                //  first = [\x00-\uffff]
                //  c =
                //  aD = !

//                val a = not((chars("b") + (eps as Regex<CharSet>)) * all)
//
//                println("a = ${a.show()}")
//                println("nullable = ${a.nullable}")
//                println("first = ${a.first()}")
//                println("a is Regex.Empty = ${a is Regex.Empty}")
//                val aD = a.brzozowskiL(CharSet.of("a"))
//                println("aD = ${aD.show()}")

                // Error type A
                //  a = [a] . ([c]*&[b])
                //  first = [a]
                //  c = a
                //  aD = !

//                val a = chars("a") * (star(chars("c")) and chars("b"))
//
//                println("a = ${a.show()}")
//                println("nullable = ${a.nullable}")
//                println("first = ${a.first()}")
//                println("a is Regex.Empty = ${a is Regex.Empty}")
//                val aD = a.brzozowskiL(CharSet.of("a"))
//                println("aD = ${aD.show()}")

                // Error type A
                //  a = [c] . !(([c]* | !(e))) . # . # . #
                //  first = [c]
                //  c = c
                //  aD = !

//                val a = chars("c") * not(((eps as Regex<CharSet>) + not(eps)))
//
//                println("a = ${a.show()}")
//                println("nullable = ${a.nullable}")
//                println("first = ${a.first()}")
//                val aD = a.brzozowskiL(CharSet.of("c"))
//                println("aD = ${aD.show()}")

                // Error type B
                //  a = !((!(e))*)
                //  nullable = false
                //  first = []
                //  a is Regex.Empty = false

//                val b = not(star(not(eps)))
//
//                println(b.show())
//                println(b.nullable)
//                println(b.first())
//                println(b is Regex.Empty)
