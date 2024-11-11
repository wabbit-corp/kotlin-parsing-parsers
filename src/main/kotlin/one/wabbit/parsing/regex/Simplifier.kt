package one.wabbit.parsing.regex//package fastcodec
//
///**
// * Created by alex on 2/24/15.
// */
//object Simplifier {
//    fun isZero<S>(re: Regex<S>): Boolean = re match {
//        case Sum(Nil) => true
//        case Not(And(Nil)) => true
//        case _ => false
//    }
//    fun isAll[E](re: Regex[E]): Boolean = re match {
//        case And(Nil) => true
//        case Not(Sum(Nil)) => true
//        case _ => false
//    }
//    def isOne[E](re: Regex[E]): Boolean = re match {
//        case Prod(Nil) => true
//        case _ => false
//    }
//
//    def simplify[E](self: Regex[E]): Regex[E] = {
//        type R = Regex[E]
//        type LR = List[Regex[E]]
//
//        flatten(self) match {
//            case Prod(args) =>
//            // ∅ ∘ r = ∅, r ∘ ∅ = ∅
//            // 1 ∘ r = r, r ∘ 1 = r
//            val simp = args.map(simplify).filter(!isOne(_))
//            if (simp.exists(isZero)) zero
//            else simp match {
//                case Nil => one
//                        case x :: Nil => x
//                case xs => Prod(xs)
//            }
//
//            case And(args) =>
//            // ∅ ∧ r = ∅, Σ ∧ r = r
//            val simp = args.map(simplify).filter(!isAll(_))
//            if (simp.exists(isZero)) zero
//            else simp match {
//                case Nil => all
//                        case x :: Nil => x
//                case xs => And(xs)
//            }
//
//            case Sum(args) =>
//            // Σ | r = Σ, ∅ | r = r
//            val simp = args.map(simplify).filter(!isZero(_))
//            if (simp.exists(isAll)) all
//            else simp match {
//                case Nil => one
//                        case x :: Nil => x
//                case xs => And(xs)
//            }
//
//            case Not(x) => Not(simplify(x))
//            case Star(x) => Star(simplify(x))
//            case Literal(x) => Literal(x)
//        }
//    }
//
//    def flatten[E](self: Regex[E]): Regex[E] = {
//        type R = Regex[E]
//        type LR = List[Regex[E]]
//
//        // r ∨ s = s ∨ r
//        // (r ∨ s) ∨ t = r ∨ (s ∨ t)
//        def flattenSum(a: LR, sum: LR = Nil): LR = {
//            def flattenSum0(a: LR, sum: LR = Nil): LR = a match {
//            case Nil => sum
//                    case Sum(x) :: xs => flattenSum0(xs, flattenSum(x) ::: sum)
//            case x :: xs => flattenSum0(xs, x :: sum)
//        }
//
//            flattenSum0(a, sum)
//        }
//
//        // r ∧ s = s ∧ r
//        // (r ∧ s) ∧ t = r ∧ (s ∧ t)
//        def flattenAnd(a: LR, sum: LR = Nil): LR = {
//            @tailrec def flattenAnd0(a: LR, sum: LR = Nil): LR = a match {
//            case Nil => sum
//                    case And(x) :: xs => flattenAnd0(xs, flattenAnd(x) ::: sum)
//            case x :: xs => flattenAnd0(xs, x :: sum)
//        }
//
//            flattenAnd0(a, sum)
//        }
//
//        // (r ∘ s) ∘ t = r ∘ (s ∘ t)
//        def flattenProd(a: LR, sum: LR = Nil): LR = {
//            @tailrec def flattenProd0(a: LR, sum: LR = Nil): LR = {
//            a match {
//                case Nil => sum
//                        case Prod(x) :: xs => flattenProd0(xs, sum ++ flattenProd(x))
//                case x :: xs => flattenProd0(xs, sum ++ List(x))
//            }
//        }
//
//            flattenProd0(a, sum)
//        }
//
//        @tailrec def flattenNot(e: R, apply: Boolean): R = e match {
//            // ¬∅ = Σ
//            case Sum(Nil) => if (apply) all else zero
//            // ¬Σ = ∅
//            case And(Nil) => if (apply) zero else all
//            // ¬¬r = r
//            case Not(x) => flattenNot(x, !apply)
//            case x => if (apply) Not(x) else x
//        }
//
//        // r** = r
//        // ε* = ε
//        // ∅* = ε
//        @tailrec def flattenStar(e: R): R = e match {
//            case Star(x) => flattenStar(x)
//            case x if isAll(x) => Regex.all[E]
//            case x if isOne(x) => Regex.one[E]
//            case x if isZero(x) => Regex.zero[E]
//        }
//
//        self match {
//            case Sum(a) => Sum(flattenSum(a).map(flatten))
//            case And(a) => And(flattenAnd(a).map(flatten))
//            case Prod(a) => Prod(flattenProd(a).map(flatten))
//            case Not(a) => flattenNot(a, apply = true)
//            case s@Star(_) => flattenStar(s)
//            case Literal(x) => Literal(x)
//        }
//    }
//}
