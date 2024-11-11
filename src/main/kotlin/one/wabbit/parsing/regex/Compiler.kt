package one.wabbit.parsing.regex

import one.wabbit.parsing.charset.CharSet
import one.wabbit.parsing.charset.Topology
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import one.wabbit.data.Cord
import one.wabbit.parsing.CharMatcher
import java.lang.Math.abs
import java.util.concurrent.atomic.AtomicLong
import kotlin.text.StringBuilder

object Compiler {
    data class DFA<S : Any>(val states: List<State<S>>) {
        data class State<S : Any>(val regex: Regex<S>, val nullable: Boolean, val transitions: Map<Int, S>)
    }

    context(Topology<Element, Set, Top>)
    fun <Element, Set : Any, Top> buildDFA(r: Regex<Set>): DFA<Set> {
        val topology = this@Topology

        var top = topology.trivial()

        val stateIds    = mutableMapOf<Regex<Set>, Int>()
        val states      = mutableListOf<Regex<Set>>()
        val transitions = mutableListOf<MutableMap<Int, Set>>()

        fun getId(r: Regex<Set>): Int {
//            println("states.size = ${states.size}")
//            println("states.unique.size = ${states.map { it.toString() }.toSet().size}")
//            val allUnique = states.map { it.toString() }.toSet().size == states.size
//            if (!allUnique) {
//                // Make a matrix of comparisons for equality:
//                for (i in states.indices) {
//                    for (j in states.indices) {
//                        if (i == j) continue
//                        val a = states[i]
//                        val b = states[j]
//                        if (a == b) {
//                            println("(states[$i] = ${a.show()} == states[$j] = ${b.show()}) = " +
//                                    "\n${a == b} ${a.show() == b.show()} ${a.toString() == b.toString()} ${a.hashCode() == b.hashCode()}")
//                            println("stateIds[$i] = ${stateIds[a]}")
//                            println("stateIds[$j] = ${stateIds[b]}")
//                        }
//                    }
//                }
//            }
//            check(allUnique)

            val x = stateIds[r]
            if (x != null) return x

            val id = states.size
            stateIds[r] = id
            states += r
            transitions += mutableMapOf()
            return id
        }

        val visited = mutableSetOf<Regex<Set>>()
        val queue = java.util.ArrayDeque<Regex<Set>>()
        queue.add(r)
        while (queue.size > 0) {
            val source = queue.removeFirst()

            //println("new source = ${source.show()} ${source} ${source in visited}")

            if (source !in visited) {
                visited.add(source)
                //println(". new source = ${source.show()} ${source} ${source in visited}")

                val sourceId = getId(source)

                val cachedDerivatives = mutableMapOf<Set, Regex<Set>>()
                while (true) {
                    var refined = false
                    for (baseSet in topology.basis(top)) {
                        val aD: Regex<Set>

                        if (baseSet in cachedDerivatives) {
                            aD = cachedDerivatives[baseSet]!!
                        } else try {
                            aD = source.brzozowskiL(baseSet)
                            cachedDerivatives[baseSet] = aD
                        } catch (e: Regex.UnrefinedTopologyException) {
                            val newTop = topology.refineViaSet(top, e.set as Set)
                            //println("  refining $top via ${e.set} to $newTop")
                            top = newTop
                            refined = true
                            break
                        }

                        //println("  derivative w.r.t. $baseSet = ${aD.show()}")

                        if (aD.fastIsEmptyOrNull() == YesNoUnknown.YES) continue
                        val target = getId(aD)

                        //println("  transitioning to state[$target] = ${states[target].show()}")

                        val prev = transitions[sourceId][target]
                        if (prev == null) {
                            transitions[sourceId][target] = baseSet
                        } else {
                            transitions[sourceId][target] = topology.union(prev, baseSet)
                        }

                        if (aD !in visited && aD != r) queue.add(aD)
                    }
                    if (!refined) break
                    //println("  refined, retrying")
                    //println("  top = $top")
                }
            }
        }

        // FIXME
//        if (this::class.java.desiredAssertionStatus()) {
//            // println("validating outgoing transitions")
//            for ((from, ts) in transitions.withIndex()) {
//                val list = ts.toList()
//                for (t in list.withIndex()) {
//                    val (to, set) = t
//                    for ((to1, set1) in list.withIndex()) {
//                        if (to1 == to) continue
//                        val intersection = topology.intersect(set.second, set1.second)
////                        println("  ${states[from].show()} -> ${states[to].show()} -> ${states[to1].show()} = ${set.second} -> ${set1.second} = $intersection")
//                        assert(intersection == topology.empty())
//                    }
//                }
//            }
//        }

        return DFA(states.mapIndexed { i, r ->
            DFA.State(
                r,
                nullable = r.nullable,
                transitions = transitions[i].toMap()
            )
        })
    }

    context(Topology<Element, Set, Top>)
    fun <Element, Set : Any, Top> dfaToRegex(dfa: DFA<Set>): Regex<Set> {
        // using Arden's algorithm

        data class Eq(val c0: Regex<Set>, val list: MutableMap<Int, Regex<Set>>)

        val system = mutableListOf<Eq>()
        system.add(Eq(Regex.empty, mutableMapOf(1 to Regex.eps)))
        dfa.states.forEachIndexed { i, state ->
            val list = state.transitions.map { (to, set) -> (to + 1) to Regex.oneOf(set) }.toMap().toMutableMap()
            system += Eq(if (state.nullable) Regex.eps else Regex.empty, list)
        }

        while (system.size > 1) {
            fun choose(system: List<Eq>): Int = system.size - 1
            val index = choose(system)

            var (q0, q1) = system.removeAt(index)

            // println("index = $index, q0 = $q0, q1 = $q1")

            val u = q1[index]
            if (u != null) {
                q1.remove(index)
                val uStar = Regex.star(u)
                q0 = Regex.seq(uStar, q0)
                for (k in q1.keys) {
                    q1[k] = Regex.seq(uStar, q1[k]!!)
                }
            }

            // println("index = $index, q0 = $q0, q1 = $q1")
            // println(system)

            // Substitute
            for (i in system.indices) {
                var (c0, c1) = system[i]
                val v = c1[index]
                if (v != null) {
                    c1.remove(index)
                    c0 = Regex.alt(Regex.seq(v, q0), c0)
                    for (k in q1.keys) {
                        val p = Regex.seq(v, q1[k]!!)
                        if (k in c1) {
                            c1[k] = Regex.alt(p, c1[k]!!)
                        } else {
                            c1[k] = p
                        }
                    }
                }
                system[i] = Eq(c0, c1)
            }

            // println(system)
        }

        return system[0].c0
    }

    private fun formatJavaChar(ch: Char): String {
        if (ch == '\'') return "\\'"
        else if (ch == '\\') return "\\\\"
        else if (ch in ' '..'~') return "$ch"
        else if (ch.code <= 0xFF) return "\\x${ch.code.toString(16).padStart(2, '0')}"
        else return "\\u${ch.code.toString(16).padStart(4, '0')}"
    }

    private fun formatCharSetAsCondition(chars: CharSet, varName: String): Cord {
        var result = Cord.empty
        for ((i, range) in chars.toRangeList().withIndex()) {
            if (i > 0) result += " || "
            if (range.first == range.last) {
                result += "c == '${formatJavaChar(range.first)}'"
            } else {
                result += "('${formatJavaChar(range.first)}' <= c && c <= '${formatJavaChar(range.last)}')"
            }
        }
        return result
    }

    fun compileToJava(regex: Regex<CharSet>, className: String): String {
        val dfa: DFA<CharSet> = with(Topology.charRanges) { return@with buildDFA(regex) }

        val firstIsNullable = dfa.states[0].nullable

        val builder = StringBuilder()
        builder.append("public final class $className implements CharMatcher {\n")
        builder.append("    private int state = 0;\n")
        builder.append("    private boolean nullable = $firstIsNullable;\n")

        builder.append("\n")
        builder.append("    public int state() { return state; }\n")

        builder.append("\n")
        builder.append("    public void reset() { state = 0; nullable = $firstIsNullable; }\n")

        builder.append("\n")
        builder.append("    public int feed(char[] chunk, int from, int to) {\n")
        builder.append("        int state = this.state;\n")
        builder.append("        boolean nullable = this.nullable;\n")
        builder.append("        if (state == -1) return 0;\n")
        builder.append("\n")
        builder.append("        for (int i = from; i < to; i++) {\n")
        builder.append("            int c = chunk[i];\n")
        builder.append("            switch (state) {\n")
        for ((stateId, state) in dfa.states.withIndex()) {
            builder.append("                case $stateId:\n")

            val str = with (Topology.charRanges) { return@with state.regex.show() }

            builder.append("                    // $str\n")

            for ((targetId, chars) in state.transitions) {
                builder.append("                    if (")
                builder.append(formatCharSetAsCondition(chars, "c"))
                if (targetId == stateId) {
                    builder.append(") { /* stay */ break; }\n")
                } else {
                    val targetIsNullable = dfa.states[targetId].nullable

                    builder.append(") { state = $targetId; nullable = $targetIsNullable; break; }\n")
                }
            }

            builder.append("                    state = -1; this.state = state; this.nullable = false; return i - from + 1;\n")

        }

        builder.append("                default:\n")
        builder.append("                    throw new IllegalStateException();\n")

        builder.append("            }\n")
        builder.append("        }\n")
        builder.append("        this.state = state;\n")
        builder.append("        this.nullable = nullable;\n")
        builder.append("        return to + 1;\n")
        builder.append("    }\n")


//        builder.append("\n")
//        builder.append("    public boolean feed(char c) {\n")
//        builder.append("        switch (state) {\n")
//        for ((stateId, state) in dfa.states.withIndex()) {
//            builder.append("                case $stateId:\n")
//
//            val str = with (Topology.charRanges) { return@with state.regex.show() }
//
//            builder.append("                    // $str\n")
//
//            for ((targetId, chars) in state.transitions) {
//                builder.append("                    if (")
//                builder.append(formatCharSetAsCondition(chars, "c"))
//                if (targetId == stateId) {
//                    builder.append(") { /* stay */ break; }\n")
//                } else {
//                    val targetIsNullable = dfa.states[targetId].nullable
//
//                    builder.append(") { state = $targetId; nullable = $targetIsNullable; break; }\n")
//                }
//            }
//
//            builder.append("                    state = -1; this.state = state; this.nullable = false; return false;\n")
//        }
//
//        builder.append("                default:\n")
//        builder.append("                    throw new IllegalStateException();\n")
//
//        builder.append("            }\n")
//        builder.append("        }\n")
//        builder.append("        this.state = state;\n")
//        builder.append("        this.nullable = nullable;\n")
//        builder.append("        return to + 1;\n")
//        builder.append("    }\n")

        builder.append("\n")
        builder.append("    public boolean nullable() {\n")
        builder.append("        return nullable;\n")
        builder.append("    }\n")

        builder.append("\n")
        builder.append("    public boolean failed() {\n")
        builder.append("        return state == -1;\n")
        builder.append("    }\n")

        builder.append("}\n")

        return builder.toString()
    }

    interface Builder<S> {
        fun build(): S
    }

    class MyChecker(val cw: ClassWriter) : org.objectweb.asm.util.CheckClassAdapter(cw, false)

    private val nextId = AtomicLong(0)

    private fun MethodVisitor.visitIntConst(value: Int) {
        when (value) {
            -1 -> visitInsn(Opcodes.ICONST_M1)
            0 -> visitInsn(Opcodes.ICONST_0)
            1 -> visitInsn(Opcodes.ICONST_1)
            2 -> visitInsn(Opcodes.ICONST_2)
            3 -> visitInsn(Opcodes.ICONST_3)
            4 -> visitInsn(Opcodes.ICONST_4)
            5 -> visitInsn(Opcodes.ICONST_5)
            else -> if (value in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                visitIntInsn(Opcodes.BIPUSH, value)
            } else if (value in Short.MIN_VALUE..Short.MAX_VALUE) {
                visitIntInsn(Opcodes.SIPUSH, value)
            } else {
                visitLdcInsn(value)
            }
        }
    }

    private fun MethodVisitor.visitBoolConst(value: Boolean) {
        if (value) {
            visitInsn(Opcodes.ICONST_1)
        } else {
            visitInsn(Opcodes.ICONST_0)
        }
    }

    private fun MethodVisitor.visitReturnBool(value: Boolean) {
        visitBoolConst(value)
        visitInsn(Opcodes.IRETURN)
    }

//    fun compileCharMatcher(regex: Regex<CharSet>): Builder<CharMatcher> {
//        val dfa: DFA<CharSet> = with(Topology.charRanges) { return@with buildDFA(regex) }
//
//        val className = "RegexMatcher_${nextId.incrementAndGet()}"
//
//        val cw0 = ClassWriter(0)
//        val cw = cw0 // org.objectweb.asm.util.CheckClassAdapter(cw0, false) // MyChecker(cw0)
//
//        // public final class TestMatcher implements CharMatcher
//        cw.visit(
//            Opcodes.V1_5,
//            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,     // public class
//            className,      // package and name
//            null, // signature (null means not generic)
//            "java/lang/Object", // superclass
//            arrayOf("std/parsing/CharMatcher") // interfaces
//        )
//
//        // private int state = 0;
//        cw.visitField(
//            Opcodes.ACC_PRIVATE, // private
//            "state", // name
//            "I", // descriptor
//            null, // signature (null means not generic)
//            null // value (null means not initialized)
//        ).visitEnd()
//
//        // private boolean nullable = false;
//        cw.visitField(
//            Opcodes.ACC_PRIVATE, // private
//            "nullable", // name
//            "Z", // descriptor
//            null, // signature (null means not generic)
//            null // value (null means not initialized)
//        ).visitEnd()
//
//        // Constructor.
//        run {
//            val mv = cw.visitMethod(
//                Opcodes.ACC_PUBLIC,
//                "<init>", "()V",
//                null, null
//            )
//            // mv = CheckMethodAdapter(mv)
//
//            mv.visitCode()
//            mv.visitIntInsn(Opcodes.ALOAD, 0)
//            mv.visitMethodInsn(
//                Opcodes.INVOKESPECIAL,
//                "java/lang/Object", "<init>", "()V",
//                false
//            )
//            mv.visitIntInsn(Opcodes.ALOAD, 0)
//            mv.visitInsn(Opcodes.ICONST_0)
//            mv.visitFieldInsn(
//                Opcodes.PUTFIELD,
//                className,
//                "state",
//                "I"
//            )
//            mv.visitInsn(Opcodes.RETURN)
//            mv.visitMaxs(2, 1)
//            mv.visitEnd()
//        }
//
//        // public int state() { return state; }
//        run {
//            val mv = cw.visitMethod(
//                Opcodes.ACC_PUBLIC,
//                "state", "()I",
//                null, null
//            )
//            mv.visitCode()
//            mv.visitIntInsn(Opcodes.ALOAD, 0)
//            mv.visitFieldInsn(
//                Opcodes.GETFIELD,
//                className,
//                "state",
//                "I"
//            )
//            mv.visitInsn(Opcodes.IRETURN)
//            mv.visitMaxs(1, 1)
//            mv.visitEnd()
//        }
//
//        // public void reset() { state = 0; nullable = false; }
//        run {
//            val mv = cw.visitMethod(
//                Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
//                "reset", "()V",
//                null, null
//            )
//            mv.visitCode()
//            mv.visitIntInsn(Opcodes.ALOAD, 0)
//            mv.visitIntConst(0)
//            mv.visitFieldInsn(
//                Opcodes.PUTFIELD,
//                className,
//                "state",
//                "I"
//            )
//            mv.visitIntInsn(Opcodes.ALOAD, 0)
//            mv.visitIntConst(0)
//            mv.visitFieldInsn(
//                Opcodes.PUTFIELD,
//                className,
//                "nullable",
//                "Z"
//            )
//            mv.visitInsn(Opcodes.RETURN)
//            mv.visitMaxs(2, 1)
//            mv.visitEnd()
//        }
//
//        // public boolean failed() {
//        //        return state == -1;
//        //    }
//        run {
//            val mv = cw.visitMethod(
//                Opcodes.ACC_PUBLIC,
//                "failed", "()Z",
//                null, null
//            )
//            mv.visitCode()
//
//            val FAIL_LABEL = Label()
//
//            mv.visitIntInsn(Opcodes.ALOAD, 0)
//            mv.visitFieldInsn(
//                Opcodes.GETFIELD,
//                className,
//                "state",
//                "I"
//            )
//
//            mv.visitIntConst(-1)
//            mv.visitJumpInsn(Opcodes.IF_ICMPNE, FAIL_LABEL)
//            mv.visitReturnBool(true)
//
//            mv.visitLabel(FAIL_LABEL)
//            mv.visitReturnBool(false)
//
//            mv.visitMaxs(2, 1)
//            mv.visitEnd()
//        }
//
//        // public boolean nullable() { return nullable; }
//        run {
//            val mv = cw.visitMethod(
//                Opcodes.ACC_PUBLIC,
//                "nullable", "()Z",
//                null, null
//            )
//            mv.visitCode()
//            mv.visitIntInsn(Opcodes.ALOAD, 0)
//            mv.visitFieldInsn(
//                Opcodes.GETFIELD,
//                className,
//                "nullable",
//                "Z"
//            )
//            mv.visitInsn(Opcodes.IRETURN)
//            mv.visitMaxs(1, 1)
//            mv.visitEnd()
//        }
//
//        // public int feed(char[] c, int start, int end)
//        run {
//            var mv = cw.visitMethod(
//                Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL,
//                "feed", "([CII)I",
//                null, null
//            )
//
//            val THIS        = 0
//
//            val ARG_ARRAY   = 1
//            val ARG_START   = 2
//            val ARG_END     = 3
//
//            val LOCAL_INDEX    = 4 // int index
//            val LOCAL_STATE    = 5 // int state
//            val LOCAL_NULLABLE = 6 // boolean nullable
//            val LOCAL_CHAR     = 7 // char c
//
//            mv.visitCode()
//
//            val LBL_LOOP    = Label()
//            val LBL_SUCCEED = Label()
//            val LBL_FAIL    = Label()
//            val LBL_ERROR   = Label()
//            val LBL_STATES  = dfa.states.map { Label() }
//
//            ///////////////////////////////////////////////////////////////////
//            // Preamble
//            ///////////////////////////////////////////////////////////////////
//
//            // int index = start;
//            mv.visitIntInsn(Opcodes.ILOAD, ARG_START)
//            mv.visitIntInsn(Opcodes.ISTORE, LOCAL_INDEX)
//
//            // int state = this.state;
//            mv.visitIntInsn(Opcodes.ALOAD, THIS)
//            mv.visitFieldInsn(Opcodes.GETFIELD, className, "state", "I")
//            mv.visitIntInsn(Opcodes.ISTORE, LOCAL_STATE)
//
//            // boolean nullable = this.nullable;
//            mv.visitIntInsn(Opcodes.ALOAD, THIS)
//            mv.visitFieldInsn(Opcodes.GETFIELD, className, "nullable", "Z")
//            mv.visitIntInsn(Opcodes.ISTORE, LOCAL_NULLABLE)
//
//            ///////////////////////////////////////////////////////////////////
//            // Loop
//            ///////////////////////////////////////////////////////////////////
//
//            // loop:
//            mv.visitLabel(LBL_LOOP)
//
//            // if (index >= end) goto succeed;
//            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
//            mv.visitIntInsn(Opcodes.ILOAD, ARG_END)
//            mv.visitJumpInsn(Opcodes.IF_ICMPGE, LBL_SUCCEED)
//
//            // char c = array[index];
//            mv.visitIntInsn(Opcodes.ALOAD, ARG_ARRAY)
//            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
//            mv.visitInsn(Opcodes.CALOAD)
//            mv.visitIntInsn(Opcodes.ISTORE, LOCAL_CHAR)
//
//            // switch (state) {
//            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_STATE)
//            mv.visitTableSwitchInsn(0, dfa.states.size - 1, LBL_ERROR, *LBL_STATES.toTypedArray())
//
//            val DEBUG = false
//            fun print(c: String) {
//                if (!DEBUG) return
//                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;")
//                mv.visitLdcInsn(c)
//                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false)
//            }
//            fun printLocal(local: Int) {
//                if (!DEBUG) return
//                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;")
//                when (local) {
//                    LOCAL_INDEX, LOCAL_STATE -> {
//                        mv.visitIntInsn(Opcodes.ILOAD, local)
//                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(I)V", false)
//                    }
//                    LOCAL_NULLABLE -> {
//                        mv.visitIntInsn(Opcodes.ILOAD, local)
//                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Z)V", false)
//                    }
//                    LOCAL_CHAR -> {
//                        mv.visitIntInsn(Opcodes.ILOAD, local)
//                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(C)V", false)
//                    }
//                    else -> throw IllegalArgumentException("Unknown local variable: $local")
//                }
//
//            }
//            fun println() {
//                if (!DEBUG) return
//                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;")
//                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "()V", false)
//            }
//
//            // States
//            for ((stateId, state) in dfa.states.withIndex()) {
//                // case $stateId:
//                mv.visitLabel(LBL_STATES[stateId])
//
//                // print("state: $stateId");
//                print("state: $stateId")
//                println()
//
//                // print("char: " + c);
//                print("char: ")
//                printLocal(LOCAL_CHAR)
//                println()
//
//                print("transitions: ${state.transitions}")
//                println()
//
//                for ((targetId, chars) in state.transitions) {
//                    // FOREACH range IN chars
//                    for ((i, range) in chars.set.withIndex()) {
//                        val LBL_NEXT = Label() // represents the "next" state or "fail" state
//
//                        // print("  range: $range");
//                        print("  range: $range ${chars} (${range.first} - ${range.last})")
//                        println()
//
//                        // if (c < ${range.first}) goto next;
//                        mv.visitIntInsn(Opcodes.ILOAD, LOCAL_CHAR)
//                        mv.visitIntConst(range.first.toInt())
//                        mv.visitJumpInsn(Opcodes.IF_ICMPLT, LBL_NEXT)
//
//                        // print("  $c >= ${range.first}");
//                        print("  ")
//                        printLocal(LOCAL_CHAR)
//                        print(" >= ${range.first}")
//                        println()
//
//                        // if (c > ${range.last}) goto next;
//                        mv.visitIntInsn(Opcodes.ILOAD, LOCAL_CHAR)
//                        mv.visitIntConst(range.last.toInt())
//                        mv.visitJumpInsn(Opcodes.IF_ICMPGT, LBL_NEXT)
//
//                        // print("  $c <= ${range.last}");
//                        print("  ")
//                        printLocal(LOCAL_CHAR)
//                        print(" <= ${range.last}")
//                        println()
//
//                        // print("  matched: [${range.first}..${range.last}] -> $targetId");
//                        print("  matched: [${range.first}..${range.last}] -> $targetId")
//                        println()
//
//                        // state = $targetId;
//                        mv.visitIntConst(targetId)
//                        mv.visitIntInsn(Opcodes.ISTORE, LOCAL_STATE)
//
//                        // nullable = $targetIsNullable;
//                        mv.visitBoolConst(dfa.states[targetId].nullable)
//                        mv.visitIntInsn(Opcodes.ISTORE, LOCAL_NULLABLE)
//
//                        // index++;
//                        mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
//                        mv.visitIntConst(1)
//                        mv.visitInsn(Opcodes.IADD)
//                        mv.visitIntInsn(Opcodes.ISTORE, LOCAL_INDEX)
//
//                        // goto loop;
//                        mv.visitJumpInsn(Opcodes.GOTO, LBL_LOOP)
//
//                        // next:
//                        mv.visitLabel(LBL_NEXT)
//                    }
//                }
//
//                // FAIL CASE
//
//                // print("  failed");
//                print("  failed")
//                println()
//
//                // state = -1;
//                mv.visitIntConst(-1)
//                mv.visitIntInsn(Opcodes.ISTORE, LOCAL_STATE)
//
//                // nullable = false;
//                mv.visitIntConst(0)
//                mv.visitIntInsn(Opcodes.ISTORE, LOCAL_NULLABLE)
//
//                // goto fail;
//                mv.visitJumpInsn(Opcodes.GOTO, LBL_FAIL)
//            }
//
//            // Success.
//            mv.visitLabel(LBL_SUCCEED)
//
//            // this.state = state;
//            mv.visitIntInsn(Opcodes.ALOAD, THIS)
//            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_STATE)
//            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "state", "I")
//
//            // this.nullable = nullable;
//            mv.visitIntInsn(Opcodes.ALOAD, THIS)
//            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_NULLABLE)
//            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "nullable", "Z")
//
//            mv.visitInsn(Opcodes.ICONST_M1)
//            mv.visitInsn(Opcodes.IRETURN)
//
//            // Fail.
//            mv.visitLabel(LBL_FAIL)
//
//            // this.state = state;
//            mv.visitIntInsn(Opcodes.ALOAD, THIS)
//            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_STATE)
//            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "state", "I")
//
//            // this.nullable = nullable;
//            mv.visitIntInsn(Opcodes.ALOAD, THIS)
//            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_NULLABLE)
//            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "nullable", "Z")
//
//            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
//            mv.visitInsn(Opcodes.IRETURN)
//
//            // Error.
//            mv.visitLabel(LBL_ERROR)
//
//            // this.state = state;
//            mv.visitIntInsn(Opcodes.ALOAD, THIS)
//            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_STATE)
//            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "state", "I")
//
//            // this.nullable = nullable;
//            mv.visitIntInsn(Opcodes.ALOAD, THIS)
//            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_NULLABLE)
//            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "nullable", "Z")
//
//            mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
//            mv.visitInsn(Opcodes.DUP)
//            mv.visitMethodInsn(
//                Opcodes.INVOKESPECIAL,
//                "java/lang/IllegalStateException", "<init>", "()V",
//                false
//            )
//            mv.visitInsn(Opcodes.ATHROW)
//
//            mv.visitMaxs(4, 8)
//            mv.visitEnd()
//        }
//
//        cw.visitEnd()
//
//        val bytes = cw0.toByteArray()
//
//        val fout = FileOutputStream("Matcher.class")
//        fout.write(bytes)
//        fout.close()
//
//        org.objectweb.asm.util.CheckClassAdapter.main(arrayOf("Matcher.class"))
//
//        val loader = CodeLoader()
//        val clazz = loader.load(className, bytes)
//        return object : Builder<CharMatcher> {
//            override fun build(): CharMatcher {
//                return clazz.newInstance() as CharMatcher
//            }
//        }
//    }

    sealed class Tree<R : Any> {
        data class Choose<R : Any>(val arg: Char, val left: Tree<R>, val right: Tree<R>): Tree<R>()
        data class Leaf<R : Any>(val result: R?): Tree<R>()

        companion object {
            // print a tree using '|', '\', and '-' to draw the branches
            fun <R : Any> print(tree: Tree<R>): String {
                val sb: StringBuilder = StringBuilder()
                fun go(tree: Tree<R>, indentFirst: String, indentRest: String) {
                    when (tree) {
                        is Leaf -> {
                            sb.append(indentFirst)
                            sb.append(tree.result)
                            sb.append("\n")
                        }
                        is Choose -> {
                            // show the decision point
                            sb.append(indentFirst)
                            sb.append("<=")
                            sb.append(tree.arg)
                            sb.append("\n")
                            go(tree.left, indentRest + "|-", indentRest + "| ")
                            go(tree.right, indentRest + "\\-", indentRest + "  ")
                        }
                    }
                }
                go(tree, "", "")
                return sb.toString()
            }
        }
    }

    interface Measure<Set> {
        fun measure(chars: Set): Double

        companion object {
            val charUniform: Measure<CharSet> = object : Measure<CharSet> {
                override fun measure(chars: CharSet): Double =
                    chars.size / (Char.MAX_VALUE - Char.MIN_VALUE + 1).toDouble()
            }
        }
    }

    fun <R : Any> encodeAsTree(
        cases: List<R>,
        chars: (R) -> CharSet,
        measure: Measure<CharSet> = Measure.charUniform
    ): Tree<R> {
        val algebra = Topology.charRanges

        var topology = algebra.trivial()
        val positive = mutableMapOf<CharRange, Int>()

        for ((index, r) in cases.withIndex()) {
            val set = chars(r)
            for (range in set.toRangeList()) {
                positive[range] = index
            }
            topology = algebra.refineViaSet(topology, set)
        }

        val basis = topology.basis

        // We want to recursively split the set into two parts such that
        // the probabilities of the two parts are as close as possible.

        // We can do this by finding the split point that minimizes the
        // difference between the probabilities of the two parts.

        fun go(from: Int, until: Int): Tree<R> {
            val size = until - from
            if (size == 1) {
                val set = basis[from]
                val id = positive[set]
                val result = if (id == null) null else cases[id]
                return Tree.Leaf(result)
            }

            val acc = DoubleArray(until - from)
            var sum = 0.0
            for (i in from until until) {
                sum += measure.measure(CharSet.of(basis[i].first))
                acc[i - from] = sum
            }

            // from = 0
            // until = 2
            // from until until = 0 until 2

            val index = (from until until - 1).minByOrNull { i ->
                val p1 = acc[i - from] / sum
                val p2 = (sum - acc[i - from]) / sum
                abs(p1 - p2)
            }!!

            return Tree.Choose(
                basis[index].last,
                go(from, index+1),
                go(index+1, until))
        }

        return go(0, basis.size)
    }

    enum class InputType {
        CHAR_ARRAY,
        OBJECT_ARRAY,
        OBJECT_LIST,
        CHAR_SEQUENCE,
        STRING,
        CHAR_BUFFER
    }

    fun compileCharMatcher_v2(regex: Regex<CharSet>): Builder<CharMatcher> {
        val dfa: DFA<CharSet> = with(Topology.charRanges) { return@with buildDFA(regex) }

        val className = "RegexMatcher_${nextId.incrementAndGet()}"

        val cw0 = ClassWriter(0)
        val cw = cw0 // org.objectweb.asm.util.CheckClassAdapter(cw0, false) // MyChecker(cw0)

        // public final class TestMatcher implements CharMatcher
        cw.visit(
            Opcodes.V1_5,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,     // public class
            className,      // package and name
            null, // signature (null means not generic)
            "java/lang/Object", // superclass
            arrayOf("std/parsing/CharMatcher") // interfaces
        )

        // private int state = 0;
        cw.visitField(
            Opcodes.ACC_PRIVATE, // private
            "state", // name
            "I", // descriptor
            null, // signature (null means not generic)
            null // value (null means not initialized)
        ).visitEnd()

        // private boolean nullable = false;
        cw.visitField(
            Opcodes.ACC_PRIVATE, // private
            "nullable", // name
            "Z", // descriptor
            null, // signature (null means not generic)
            null // value (null means not initialized)
        ).visitEnd()

        // Constructor.
        run {
            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC,
                "<init>", "()V",
                null, null
            )
            // mv = CheckMethodAdapter(mv)

            mv.visitCode()
            mv.visitIntInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V",
                false
            )
            mv.visitIntInsn(Opcodes.ALOAD, 0)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitFieldInsn(
                Opcodes.PUTFIELD,
                className,
                "state",
                "I"
            )
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(2, 1)
            mv.visitEnd()
        }

        // public int state() { return state; }
        run {
            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC,
                "state", "()I",
                null, null
            )
            mv.visitCode()
            mv.visitIntInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(
                Opcodes.GETFIELD,
                className,
                "state",
                "I"
            )
            mv.visitInsn(Opcodes.IRETURN)
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        }

        // public void reset() { state = 0; nullable = false; }
        run {
            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
                "reset", "()V",
                null, null
            )
            mv.visitCode()
            mv.visitIntInsn(Opcodes.ALOAD, 0)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitFieldInsn(
                Opcodes.PUTFIELD,
                className,
                "state",
                "I"
            )
            mv.visitIntInsn(Opcodes.ALOAD, 0)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitFieldInsn(
                Opcodes.PUTFIELD,
                className,
                "nullable",
                "Z"
            )
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(2, 1)
            mv.visitEnd()
        }

        // public boolean failed() {
        //        return state == -1;
        //    }
        run {
            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC,
                "failed", "()Z",
                null, null
            )
            mv.visitCode()
            mv.visitIntInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(
                Opcodes.GETFIELD,
                className,
                "state",
                "I"
            )
            val FAIL_LABEL = Label()
            mv.visitInsn(Opcodes.ICONST_M1)
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, FAIL_LABEL)
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitInsn(Opcodes.IRETURN)
            mv.visitLabel(FAIL_LABEL)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitInsn(Opcodes.IRETURN)
            mv.visitMaxs(2, 1)
            mv.visitEnd()
        }

        // public boolean nullable() { return nullable; }
        run {
            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC,
                "nullable", "()Z",
                null, null
            )
            mv.visitCode()
            mv.visitIntInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(
                Opcodes.GETFIELD,
                className,
                "nullable",
                "Z"
            )
            mv.visitInsn(Opcodes.IRETURN)
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        }

        // public int feed(CharSequence c, int start, int end)
        fun writeFeedMethod(inputType: InputType) {
            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL,
                "feed",
                when (inputType) {
                    InputType.CHAR_SEQUENCE -> "(Ljava/lang/CharSequence;II)I"
                    InputType.CHAR_ARRAY    -> "([CII)I"
                    InputType.STRING        -> "(Ljava/lang/String;II)I"
                    InputType.CHAR_BUFFER   -> "(Ljava/nio/CharBuffer;II)I"
                    InputType.OBJECT_ARRAY  -> "([Ljava/lang/Object;II)I"
                    InputType.OBJECT_LIST   -> "(Ljava/util/List;II)I"
                },
                null, null
            )

            val THIS        = 0

            val ARG_SEQ   = 1
            val ARG_START   = 2
            val ARG_END     = 3

            val LOCAL_INDEX    = 4 // int index
            val LOCAL_STATE    = 5 // int state
            val LOCAL_NULLABLE = 6 // boolean nullable
            val LOCAL_CHAR     = 7 // char c

            fun readChar() = when (inputType) {
                InputType.CHAR_SEQUENCE -> {
                    mv.visitIntInsn(Opcodes.ALOAD, ARG_SEQ)
                    mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
                    mv.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        "java/lang/CharSequence", "charAt", "(I)C",
                        true
                    )
                }
                InputType.CHAR_ARRAY -> {
                    mv.visitIntInsn(Opcodes.ALOAD, ARG_SEQ)
                    mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
                    mv.visitInsn(Opcodes.CALOAD)
                }
                InputType.STRING -> {
                    mv.visitIntInsn(Opcodes.ALOAD, ARG_SEQ)
                    mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/String", "charAt", "(I)C",
                        false
                    )
                }
                InputType.CHAR_BUFFER -> {
                    mv.visitIntInsn(Opcodes.ALOAD, ARG_SEQ)
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/nio/CharBuffer", "get", "()C",
                        false
                    )
                }
                InputType.OBJECT_ARRAY -> {
                    mv.visitIntInsn(Opcodes.ALOAD, ARG_SEQ)
                    mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
                    mv.visitInsn(Opcodes.AALOAD)
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character")
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/Character", "charValue", "()C",
                        false
                    )
                }
                InputType.OBJECT_LIST -> {
                    mv.visitIntInsn(Opcodes.ALOAD, ARG_SEQ)
                    mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
                    mv.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/List", "get", "(I)Ljava/lang/Object;",
                        true
                    )
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character")
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/Character", "charValue", "()C",
                        false
                    )
                }
            }

            mv.visitCode()

            val LBL_LOOP    = Label()
            val LBL_SUCCEED = Label()
            val LBL_FAIL    = Label()
            val LBL_ERROR   = Label()
            val LBL_STATES  = dfa.states.map { Label() }

            ///////////////////////////////////////////////////////////////////
            // Preamble
            ///////////////////////////////////////////////////////////////////

            // int index = start;
            mv.visitIntInsn(Opcodes.ILOAD, ARG_START)
            mv.visitIntInsn(Opcodes.ISTORE, LOCAL_INDEX)

            // int state = this.state;
            mv.visitIntInsn(Opcodes.ALOAD, THIS)
            mv.visitFieldInsn(Opcodes.GETFIELD, className, "state", "I")
            mv.visitIntInsn(Opcodes.ISTORE, LOCAL_STATE)

            // boolean nullable = this.nullable;
            mv.visitIntInsn(Opcodes.ALOAD, THIS)
            mv.visitFieldInsn(Opcodes.GETFIELD, className, "nullable", "Z")
            mv.visitIntInsn(Opcodes.ISTORE, LOCAL_NULLABLE)

            ///////////////////////////////////////////////////////////////////
            // Loop
            ///////////////////////////////////////////////////////////////////

            // loop:
            mv.visitLabel(LBL_LOOP)

            // if (index >= end) goto succeed;
            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
            mv.visitIntInsn(Opcodes.ILOAD, ARG_END)
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, LBL_SUCCEED)

            // switch (state) {
            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_STATE)
            mv.visitTableSwitchInsn(0, dfa.states.size - 1, LBL_ERROR, *LBL_STATES.toTypedArray())

            val DEBUG = false
            fun print(c: String) {
                if (!DEBUG) return
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;")
                mv.visitLdcInsn(c)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false)
            }
            fun printLocal(local: Int) {
                if (!DEBUG) return
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;")
                when (local) {
                    LOCAL_INDEX, LOCAL_STATE -> {
                        mv.visitIntInsn(Opcodes.ILOAD, local)
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(I)V", false)
                    }
                    LOCAL_NULLABLE -> {
                        mv.visitIntInsn(Opcodes.ILOAD, local)
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Z)V", false)
                    }
                    LOCAL_CHAR -> {
                        mv.visitIntInsn(Opcodes.ILOAD, local)
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print", "(C)V", false)
                    }
                    else -> throw IllegalArgumentException("Unknown local variable: $local")
                }
            }
            fun println() {
                if (!DEBUG) return
                mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "()V", false)
            }

            // States
            for ((stateId, state) in dfa.states.withIndex()) {
                // case $stateId:
                mv.visitLabel(LBL_STATES[stateId])

                // char c = array[index];
                readChar()
                mv.visitIntInsn(Opcodes.ISTORE, LOCAL_CHAR)

                // print("state: $stateId");
                print("state: $stateId")
                println()

                // print("char: " + c);
                print("char: ")
                printLocal(LOCAL_CHAR)
                println()

                print("transitions: ${state.transitions}")
                println()

                val targets = state.transitions.keys.sorted()
                val transitions = targets.map { it to state.transitions[it]!! }
                val tree = encodeAsTree(transitions, { it.second })

                fun go(tree: Tree<Pair<Int, CharSet>>) {
                    when (tree) {
                        is Tree.Leaf -> {
                            // print("  leaf: ${tree.value}");
                            print("  leaf: ${tree.result}")
                            println()

                            if (tree.result == null) {
                                // mv.visitJumpInsn(Opcodes.GOTO, LBL_TARGETS[tree.result.first])

                                // state = -1;
                                mv.visitIntConst(-1)
                                mv.visitIntInsn(Opcodes.ISTORE, LOCAL_STATE)

                                // nullable = false;
                                mv.visitBoolConst(false)
                                mv.visitIntInsn(Opcodes.ISTORE, LOCAL_NULLABLE)

                                // goto fail;
                                mv.visitJumpInsn(Opcodes.GOTO, LBL_FAIL)
                            }
                            else {
                                val targetId = tree.result.first

                                // state = $targetId;
                                if (targetId != stateId) {
                                    mv.visitIntInsn(Opcodes.BIPUSH, targetId)
                                    mv.visitIntInsn(Opcodes.ISTORE, LOCAL_STATE)
                                }

                                // nullable = $targetIsNullable;
                                if (dfa.states[targetId].nullable != dfa.states[stateId].nullable) {
                                    mv.visitIntInsn(Opcodes.BIPUSH, if (dfa.states[targetId].nullable) 1 else 0)
                                    mv.visitIntInsn(Opcodes.ISTORE, LOCAL_NULLABLE)
                                }

                                // index++;
                                mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
                                mv.visitIntInsn(Opcodes.BIPUSH, 1)
                                mv.visitInsn(Opcodes.IADD)
                                mv.visitIntInsn(Opcodes.ISTORE, LOCAL_INDEX)

                                // if (index >= end) goto succeed;
                                mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
                                mv.visitIntInsn(Opcodes.ILOAD, ARG_END)
                                mv.visitJumpInsn(Opcodes.IF_ICMPGE, LBL_SUCCEED)

                                // goto loop;
                                mv.visitJumpInsn(Opcodes.GOTO, LBL_STATES[targetId])
                            }
                        }
                        is Tree.Choose -> {
                            // print("  switch: ${tree.arg.code}");
                            print("  switch: ${tree.arg}")
                            println()
                            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_CHAR)
                            mv.visitIntConst(tree.arg.code)
                            val LBL_RIGHT = Label()
                            mv.visitJumpInsn(Opcodes.IF_ICMPGT, LBL_RIGHT)
                            go(tree.left)
                            mv.visitLabel(LBL_RIGHT)
                            go(tree.right)
                        }
                    }
                }

                go(tree)
            }

            // FAIL CASE
            // print("  failed");
            print("  failed")
            println()

            // state = -1;
            mv.visitIntInsn(Opcodes.BIPUSH, -1)
            mv.visitIntInsn(Opcodes.ISTORE, LOCAL_STATE)

            // nullable = false;
            mv.visitIntInsn(Opcodes.BIPUSH, 0)
            mv.visitIntInsn(Opcodes.ISTORE, LOCAL_NULLABLE)

            // goto fail;
            mv.visitJumpInsn(Opcodes.GOTO, LBL_FAIL)

            // Success.
            mv.visitLabel(LBL_SUCCEED)

            // this.state = state;
            mv.visitIntInsn(Opcodes.ALOAD, THIS)
            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_STATE)
            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "state", "I")

            // this.nullable = nullable;
            mv.visitIntInsn(Opcodes.ALOAD, THIS)
            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_NULLABLE)
            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "nullable", "Z")

            mv.visitInsn(Opcodes.ICONST_M1)
            mv.visitInsn(Opcodes.IRETURN)

            // Fail.
            mv.visitLabel(LBL_FAIL)

            // this.state = state;
            mv.visitIntInsn(Opcodes.ALOAD, THIS)
            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_STATE)
            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "state", "I")

            // this.nullable = nullable;
            mv.visitIntInsn(Opcodes.ALOAD, THIS)
            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_NULLABLE)
            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "nullable", "Z")

            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_INDEX)
            mv.visitInsn(Opcodes.IRETURN)

            // Error.
            mv.visitLabel(LBL_ERROR)

            // this.state = state;
            mv.visitIntInsn(Opcodes.ALOAD, THIS)
            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_STATE)
            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "state", "I")

            // this.nullable = nullable;
            mv.visitIntInsn(Opcodes.ALOAD, THIS)
            mv.visitIntInsn(Opcodes.ILOAD, LOCAL_NULLABLE)
            mv.visitFieldInsn(Opcodes.PUTFIELD, className, "nullable", "Z")

            mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
            mv.visitInsn(Opcodes.DUP)
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/IllegalStateException", "<init>", "()V",
                false
            )
            mv.visitInsn(Opcodes.ATHROW)

            mv.visitMaxs(4, 8)
            mv.visitEnd()
        }

        writeFeedMethod(InputType.CHAR_ARRAY)
        writeFeedMethod(InputType.STRING)
        writeFeedMethod(InputType.CHAR_SEQUENCE)
        writeFeedMethod(InputType.CHAR_BUFFER)
        writeFeedMethod(InputType.OBJECT_LIST)
        writeFeedMethod(InputType.OBJECT_ARRAY)

        cw.visitEnd()

        val bytes = cw0.toByteArray()

//        val fout = FileOutputStream("Matcher.class")
//        fout.write(bytes)
//        fout.close()
//
//        org.objectweb.asm.util.CheckClassAdapter.main(arrayOf("Matcher.class"))

        val loader = CodeLoader()
        val clazz = loader.load(className, bytes)
        return object : Builder<CharMatcher> {
            override fun build(): CharMatcher {
                return clazz.newInstance() as CharMatcher
            }
        }
    }
}

//// public boolean nullable() { return nullable; }
//        run {
//            val mv = cw.visitMethod(
//                Opcodes.ACC_PUBLIC,
//                "nullable", "()Z",
//                null, null
//            )
//            mv.visitCode()
//            mv.visitIntInsn(Opcodes.ALOAD, 0)
//            mv.visitFieldInsn(
//                Opcodes.GETFIELD,
//                className,
//                "state",
//                "I"
//            )
//
//            val successLabel = Label()
//            val notSuccessLabel = Label()
//
//            for (i in dfa.states.indices) {
//                mv.visitInsn(Opcodes.DUP)
//                mv.visitLdcInsn(i)
//                if (dfa.states[i].nullable) {
//                    mv.visitJumpInsn(Opcodes.IF_ICMPNE, notSuccessLabel)
//                } else {
//                    mv.visitJumpInsn(Opcodes.IF_ICMPEQ, notSuccessLabel)
//                }
//            }
//
//            mv.visitInsn(Opcodes.POP)
//
//            mv.visitLabel(successLabel)
//            // return true
//            mv.visitInsn(Opcodes.ICONST_1)
//            mv.visitInsn(Opcodes.IRETURN)
//
//            mv.visitLabel(notSuccessLabel)
//            // return false
//            mv.visitInsn(Opcodes.ICONST_0)
//            mv.visitInsn(Opcodes.IRETURN)
//
//            // throw java/lang/IllegalStateException
//            mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
//            mv.visitInsn(Opcodes.DUP)
//            mv.visitMethodInsn(
//                Opcodes.INVOKESPECIAL,
//                "java/lang/IllegalStateException", "<init>", "()V",
//                false
//            )
//            mv.visitInsn(Opcodes.ATHROW)
//
//            mv.visitMaxs(3, 1)
//            mv.visitEnd()
//        }
