package org.securegraph.query;

public interface IterableWithTermsResults<T> extends Iterable<T> {
    TermsResult getTermsResults(String name);
}
