package play.libs;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import play.libs.F.Function0;

public class LazyDirtyMap<K, V> implements Map<K, V> {

    public boolean isDirty = false;

    private Map<K, V> delegate;
    private Function0<Map<K, V>> delegateGetter;

    public LazyDirtyMap(Map<K, V> delegate) {
        this.delegate = delegate;
    }

    public LazyDirtyMap(Function0<Map<K, V>> delegateGetter) {
        this.delegateGetter = delegateGetter;
    }

    protected Map<K, V> delegate() {
        if (delegate == null) {
            try {
                delegate = delegateGetter.apply();
            } catch (Throwable e) {
                throw new RuntimeException("Unhandled Throwable getting delegate", e);
            }
            delegateGetter = null; // So we get an error if we somehow try to construct twice
        }
        return delegate;
    }

    public void markDirty() {
        isDirty = true;
    }

    @Override
    public int size() {
        return delegate().size();
    }

    @Override
    public boolean isEmpty() {
        return delegate().isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return delegate().containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return delegate().containsValue(value);
    }

    @Override
    public V get(Object key) {
        return delegate().get(key);
    }

    @Override
    public V put(K key, V value) {
        markDirty();
        return delegate().put(key, value);
    }

    @Override
    public V remove(Object key) {
        markDirty();
        return delegate().remove(key);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        markDirty();
        delegate().putAll(m);
    }

    @Override
    public void clear() {
        markDirty();
        delegate().clear();
    }

    @Override
    public Set<K> keySet() {
        return new LazyDirtySet<K>(delegate().keySet());
    }

    @Override
    public Collection<V> values() {
        return new LazyDirtyCollection<V>(delegate.values());
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return new LazyDirtySet<Map.Entry<K, V>>(delegate().entrySet());
    }

    private class LazyDirtySet<E> implements Set<E> {

        private Set<E> delegateSet;

        public LazyDirtySet(Set<E> delegateSet) {
            this.delegateSet = delegateSet;
        }

        @Override
        public int size() {
            return delegateSet.size();
        }

        @Override
        public boolean isEmpty() {
            return delegateSet.isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            return delegateSet.contains(o);
        }

        @Override
        public Iterator<E> iterator() {
            return new LazyDirtyIterator<E>(delegateSet.iterator());
        }

        @Override
        public Object[] toArray() {
            return delegateSet.toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return delegateSet.toArray(a);
        }

        @Override
        public boolean add(E e) {
            markDirty();
            return delegateSet.add(e);
        }

        @Override
        public boolean remove(Object o) {
            markDirty();
            return delegateSet.remove(o);
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return delegateSet.containsAll(c);
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            markDirty();
            return delegateSet.addAll(c);
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            markDirty();
            return delegateSet.retainAll(c);
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            markDirty();
            return delegateSet.removeAll(c);
        }

        @Override
        public void clear() {
            markDirty();
            delegateSet.clear();
        }

    };

    private class LazyDirtyIterator<E> implements Iterator<E> {

        private Iterator<E> delegateIterator;

        public LazyDirtyIterator(Iterator<E> delegateIterator) {
            this.delegateIterator = delegateIterator;
        }

        @Override
        public boolean hasNext() {
            return delegateIterator.hasNext();
        }

        @Override
        public E next() {
            return delegateIterator.next();
        }

        @Override
        public void remove() {
            markDirty();
            delegateIterator.remove();
        }

    }

    private class LazyDirtyCollection<E> implements Collection<E> {


        private Collection<E> delegateCollection;

        public LazyDirtyCollection(Collection<E> delegateCollection) {
            this.delegateCollection = delegateCollection;
        }

        @Override
        public int size() {
            return delegateCollection.size();
        }

        @Override
        public boolean isEmpty() {
            return delegateCollection.isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            return delegateCollection.contains(o);
        }

        @Override
        public Iterator<E> iterator() {
            return new LazyDirtyIterator<E>(delegateCollection.iterator());
        }

        @Override
        public Object[] toArray() {
            return delegateCollection.toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return delegateCollection.toArray(a);
        }

        @Override
        public boolean add(E e) {
            markDirty();
            return delegateCollection.add(e);
        }

        @Override
        public boolean remove(Object o) {
            markDirty();
            return delegateCollection.remove(o);
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return delegateCollection.containsAll(c);
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            markDirty();
            return delegateCollection.addAll(c);
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            markDirty();
            return delegateCollection.retainAll(c);
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            markDirty();
            return delegateCollection.removeAll(c);
        }

        @Override
        public void clear() {
            markDirty();
            delegateCollection.clear();
        }
        
    }

}