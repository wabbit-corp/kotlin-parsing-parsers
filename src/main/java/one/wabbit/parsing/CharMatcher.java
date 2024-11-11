package one.wabbit.parsing;

import java.nio.CharBuffer;

public interface CharMatcher extends Matcher<Character> {
    public int state();
    public void reset();
    public boolean nullable();
    public boolean failed();

    public int feed(char[] chunk, int from, int until);
    public int feed(String chunk, int from, int until);
    public int feed(CharSequence chunk, int from, int until);
    public int feed(CharBuffer chunk, int from, int until);

    public default boolean matches(CharSequence s) {
        reset();
        // System.err.println("Matching: " + s + " with " + this + " (" + this.getClass() + ")");
        // System.err.println("State: " + state() + " (nullable: " + nullable() + ")" + " (failed: " + failed() + ")");
        int n = feed(s, 0, s.length());
        // System.err.println("Consumed: " + n);
        // System.err.println("State: " + state() + " (nullable: " + nullable() + ")" + " (failed: " + failed() + ")");
        return nullable();
    }
}
