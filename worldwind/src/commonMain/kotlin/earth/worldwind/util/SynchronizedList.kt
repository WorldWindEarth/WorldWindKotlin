package earth.worldwind.util

expect class SynchronizedList<E>() : MutableList<E> {
    override fun contains(element: E): Boolean
    override fun containsAll(elements: Collection<E>): Boolean
    override fun get(index: Int): E
    override fun indexOf(element: E): Int
    override fun isEmpty(): Boolean
    override fun iterator(): MutableIterator<E>
    override fun lastIndexOf(element: E): Int
    override fun add(element: E): Boolean
    override fun remove(element: E): Boolean
    override fun addAll(elements: Collection<E>): Boolean
    override fun addAll(index: Int, elements: Collection<E>): Boolean
    override fun removeAll(elements: Collection<E>): Boolean
    override fun retainAll(elements: Collection<E>): Boolean
    override fun clear()
    override fun set(index: Int, element: E): E
    override fun add(index: Int, element: E)
    override fun removeAt(index: Int): E
    override fun listIterator(): MutableListIterator<E>
    override fun listIterator(index: Int): MutableListIterator<E>
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<E>
    override val size: Int
}