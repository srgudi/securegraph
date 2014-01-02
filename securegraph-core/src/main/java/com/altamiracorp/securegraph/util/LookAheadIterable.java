package com.altamiracorp.securegraph.util;

import java.util.Iterator;

public abstract class LookAheadIterable<TSource, TDest> implements Iterable<TDest> {
    @Override
    public Iterator<TDest> iterator() {
        final Iterator<TSource> it = createIterator();

        return new Iterator<TDest>() {
            private TDest next;
            private TDest current;

            @Override
            public boolean hasNext() {
                loadNext();
                return next != null;
            }

            @Override
            public TDest next() {
                loadNext();
                this.current = this.next;
                this.next = null;
                return this.current;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }

            private void loadNext() {
                if (this.next != null) {
                    return;
                }

                while (it.hasNext()) {
                    TDest obj = convert(it.next());
                    if (!isIncluded(obj)) {
                        continue;
                    }

                    this.next = obj;
                    break;
                }
            }
        };
    }

    protected abstract boolean isIncluded(TDest obj);

    protected abstract TDest convert(TSource next);

    protected abstract Iterator<TSource> createIterator();
}