//package std.parsing;
//
//import std.parsing.CharMatcher;
//
//public final class TestMatcher implements CharMatcher {
//    private int state = 0;
//    private boolean nullable = false;
//
//    public int state() { return state; }
//
//    public void reset() { state = 0; }
//
////    public boolean feed(char ch) { return false; }
//    public int feed(char[] chunk, int from, int to) {
//        int state = this.state;
//        boolean nullable = this.nullable;
//        if (state == -1) return 0;
//
//        for (int i = from; i < to; i++) {
//            int c = chunk[i];
//            switch (state) {
//                case 0:
//                    // [ ] . [ ]* . (? | [+\-]) . [0-9] . [0-9]* . [ ] . [ ]*
//                    if (c == ' ') { state = 1; nullable = false; break; }
//                    state = -1; this.state = state; this.nullable = false; return i - from + 1;
//                case 1:
//                    // [ ]* . (? | [+\-]) . [0-9] . [0-9]* . [ ] . [ ]*
//                    if (c == ' ') { /* stay */ break; }
//                    if (c == '+' || c == '-') { state = 2; nullable = false; break; }
//                    if (('0' <= c && c <= '9')) { state = 3; nullable = false; break; }
//                    state = -1; this.state = state; this.nullable = false; return i - from + 1;
//                case 2:
//                    // [0-9] . [0-9]* . [ ] . [ ]*
//                    if (('0' <= c && c <= '9')) { state = 3; nullable = false; break; }
//                    state = -1; this.state = state; this.nullable = false; return i - from + 1;
//                case 3:
//                    // [0-9]* . [ ] . [ ]*
//                    if (c == ' ') { state = 4; nullable = true; break; }
//                    if (('0' <= c && c <= '9')) { /* stay */ break; }
//                    state = -1; this.state = state; this.nullable = false; return i - from + 1;
//                case 4:
//                    // [ ]*
//                    if (c == ' ') { /* stay */ break; }
//                    state = -1; this.state = state; this.nullable = false; return i - from + 1;
//                default:
//                    throw new IllegalStateException();
//            }
//        }
//        this.state = state;
//        this.nullable = nullable;
//        return to + 1;
//    }
//
//    public boolean nullable() {
//        return nullable;
//    }
//
//    public boolean failed() {
//        return state == -1;
//    }
//}
