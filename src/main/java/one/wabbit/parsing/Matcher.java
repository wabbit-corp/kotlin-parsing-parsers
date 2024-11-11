package one.wabbit.parsing;

import java.util.List;

public interface Matcher<S> {
    public int state();
    public void reset();
    public boolean nullable();
    public boolean failed();

    public int feed(S[] chunk, int from, int until);
    public int feed(List<S> chunk, int from, int until);
}
