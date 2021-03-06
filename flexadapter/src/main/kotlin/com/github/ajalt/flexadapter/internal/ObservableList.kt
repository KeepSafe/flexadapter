package com.github.ajalt.flexadapter.internal

import android.os.Build
import android.support.annotation.RequiresApi
import java.util.*
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.function.UnaryOperator

/** A list interface that notifies a listener when it's contents change. */
internal interface ObservableList<T> : MutableList<T> {
    interface OnListChangedCallback<in T : ObservableList<*>> {
        fun onChanged(sender: T)
        fun onItemChanged(sender: T, index: Int, oldItem: Any?)
        fun onItemRangeInserted(sender: T, start: Int, count: Int)
        fun onItemRangeRemoved(sender: T, start: Int, count: Int)
        fun onItemRemoved(sender: T, index: Int, item: Any?)
    }
}

internal class ObservableArrayList<T>(var listener: ObservableList.OnListChangedCallback<ObservableList<T>>?)
    : ArrayList<T>(), ObservableList<T> {

    override fun add(element: T): Boolean = super.add(element).apply { notifyAdd(size - 1, 1) }

    override fun add(index: Int, element: T) = super.add(index, element).apply { notifyAdd(index, 1) }

    override fun addAll(elements: Collection<T>): Boolean {
        val oldSize = size
        val added = super.addAll(elements)
        if (added) {
            notifyAdd(oldSize, size - oldSize)
        }
        return added
    }

    override fun addAll(index: Int, elements: Collection<T>): Boolean
            = super.addAll(index, elements).apply { if (this) notifyAdd(index, elements.size) }

    override fun clear() {
        val oldSize = size
        super.clear()
        if (oldSize != 0) {
            notifyRangeRemove(0, oldSize)
        }
    }

    override fun set(index: Int, element: T): T = super.set(index, element).apply { notifyChange(index, this) }

    override fun removeAt(index: Int): T = super.removeAt(index).apply { notifyRemove(index, this) }

    override fun remove(element: T): Boolean {
        val index = indexOf(element)
        if (index >= 0) {
            removeAt(index)
            return true
        }
        return false
    }

    override fun removeAll(elements: Collection<T>): Boolean
            = super.removeAll(elements).apply { notifyAllChange() }

    override fun retainAll(elements: Collection<T>): Boolean
            = super.retainAll(elements).apply { notifyAllChange() }

    override fun removeRange(fromIndex: Int, toIndex: Int)
            = super.removeRange(fromIndex, toIndex).apply { notifyRangeRemove(fromIndex, toIndex - fromIndex) }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun forEach(action: Consumer<in T>?) = super<ArrayList>.forEach(action)

    @RequiresApi(Build.VERSION_CODES.N)
    override fun spliterator(): Spliterator<T> {
        return super<ArrayList>.spliterator()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun replaceAll(operator: UnaryOperator<T>)
            = super<ArrayList>.replaceAll(operator).apply { notifyAllChange() }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun sort(c: Comparator<in T>?)
            = super<ArrayList>.sort(c).apply { notifyAllChange() }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun removeIf(filter: Predicate<in T>): Boolean
            = super<ArrayList>.removeIf(filter).apply { notifyAllChange() }

    private fun notifyAdd(start: Int, count: Int) = listener?.onItemRangeInserted(this, start, count)
    private fun notifyRangeRemove(start: Int, count: Int) = listener?.onItemRangeRemoved(this, start, count)
    private fun notifyRemove(index: Int, item: Any?) = listener?.onItemRemoved(this, index, item)
    private fun notifyChange(index: Int, oldItem: Any?) = listener?.onItemChanged(this, index, oldItem)
    private fun notifyAllChange() = listener?.onChanged(this)
}

