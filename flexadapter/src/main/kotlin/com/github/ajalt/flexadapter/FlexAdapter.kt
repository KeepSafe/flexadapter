@file:Suppress("unused")

package com.github.ajalt.flexadapter

import android.support.annotation.LayoutRes
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.github.ajalt.flexadapter.internal.ObservableArrayList
import com.github.ajalt.flexadapter.internal.ObservableList
import java.util.*

/** @see FlexAdapterItemBase */
internal interface FlexAdapterItemAttrs {
    val span: Int
    val swipeDirs: Int
    val dragDirs: Int
}

private interface ItemAttrs : FlexAdapterItemAttrs {
    val layout: Int
}

private data class PlainItemAttrs(@LayoutRes override val layout: Int, override val span: Int,
                                  override val swipeDirs: Int, override val dragDirs: Int,
                                  val viewBinder: (Any, View, Int) -> Unit) : ItemAttrs

private data class SelectableItemAttrs(@LayoutRes override val layout: Int, override val span: Int,
                                       override val swipeDirs: Int, override val dragDirs: Int,
                                       val viewBinder: (Any, View, Boolean, Int) -> Unit) : ItemAttrs

/**
 * A [RecyclerView.Adapter] that handles multiple item layouts with per-item swipe, drag, and span
 * behavior.
 *
 * @param registerAutomatically When true, the adapter will setup the item touch helper whenever it
 *                              is attached to a [RecyclerView]. (default true)
 */
open class FlexAdapter<T : Any>(private val registerAutomatically: Boolean = true) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    interface ItemSwipedListener<in T> {
        fun onItemSwiped(item: T)
    }

    interface ItemDraggedListener<in T> {
        fun onItemDragged(item: T, from: Int, to: Int)
    }

    private val listListener = object : ObservableList.OnListChangedCallback<ObservableList<T>> {
        var enabled = true
        override fun onChanged(sender: ObservableList<T>) = if (enabled) {
            recordAllItems()
            notifyDataSetChanged()
        } else Unit

        override fun onItemChanged(sender: ObservableList<T>, index: Int, oldItem: Any?) = if (enabled) {
            selectedItems.remove(oldItem)
            recordItems(index..index)
            notifyItemChanged(index)
        } else Unit

        override fun onItemRangeInserted(sender: ObservableList<T>, start: Int, count: Int) = if (enabled) {
            recordItems(start until start + count)
            notifyItemRangeInserted(start, count)
        } else Unit

        override fun onItemRangeRemoved(sender: ObservableList<T>, start: Int, count: Int) = if (enabled) {
            recordAllItems()
            notifyItemRangeRemoved(start, count)
        } else Unit

        override fun onItemRemoved(sender: ObservableList<T>, index: Int, item: Any?) = if (enabled) {
            markItemRemoved(item)
            notifyItemRemoved(index)
        } else Unit
    }

    private var itemDraggedListener: ((T, Int, Int) -> Unit)? = null
    private var selectedItems: MutableSet<T> = HashSet()
    /** A map of item type to factories created from [FlexAdapterItemBase.viewHolderFactory] */
    private val viewHolderFactoriesByItemType = HashMap<Int, (ViewGroup) -> RecyclerView.ViewHolder>()
    /**
     * A map of item type to [ItemAttrs].
     *
     * The key is either the default item type, or a custom view type if one is registered.
     */
    private val itemAttrsByItemType = HashMap<Int, ItemAttrs>()
    /**
     * The keys in [itemAttrsByItemType] that correspond to cached entries.
     *
     * This is necessary since we need to invalidate cached entries when a base class is
     * re-registered.
     */
    private val cachedItemsTypes = HashSet<Int>()
    /**
     * A map of base class to a pair containing a callback to test for subclasses and the attrs.
     *
     * This is necessary so that you can [register] interfaces or base classes. The subclass check
     * is only called once per subclass that wasn't directly registered, so it doesn't impact
     * performance.
     */
    private val itemAttrsByBaseClass = IdentityHashMap<Class<*>, Pair<(Class<*>, Any) -> Boolean, ItemAttrs>>()
    /** A map of concrete class view type to custom view type. */
    private val customViewTypes = HashMap<Int, Int>()
    private var callDragListenerOnDropOnly: Boolean = true

    /**
     * The items in this adapter.
     *
     * Changes to this list will automatically be reflected in the adapter.
     */
    val items: MutableList<T> = ObservableArrayList(listListener)
        @JvmName("items")
        get

    /**
     * Clear the list of items and replace them with at new list.
     *
     * This is equivalent to calling `items.clear(); items.addAll(newItems)`, but doesn't cause as
     * many update notifications to be emitted.
     */
    @Synchronized
    open fun resetItems(items: Collection<T>) {
        listListener.enabled = false
        this.items.clear()
        this.items.addAll(items)
        recordAllItems()
        listListener.enabled = true
        notifyDataSetChanged()
    }

    /** Set or clear a listener that will be notified when an item is dismissed with a swipe. */
    open var itemSwipedListener: ((item: T) -> Unit)? = null

    /** A version of [itemSwipedListener] that takes an interface that's easier to call from Java. */
    open fun setItemSwipedListener(listener: ItemSwipedListener<T>) {
        itemSwipedListener = { listener.onItemSwiped(it) }
    }

    /**
     * Set or clear a listener that will be called when an item in the adapter is dragged.
     *
     * @param listener       The callback that will be called when an item is dragged
     * @param callOnDropOnly If true, the listener will be be called when an item is dropped in new
     *                       location. If false, it will be called while an item is being dragged.
     */
    open fun setItemDraggedListener(callOnDropOnly: Boolean = true, listener: ((item: T, from: Int, to: Int) -> Unit)?) {
        itemDraggedListener = listener
        callDragListenerOnDropOnly = callOnDropOnly
    }

    /**
     * A version of [setItemDraggedListener] that takes an interface that's easier to call from Java.
     */
    @JvmOverloads
    open fun setItemDraggedListener(callOnDropOnly: Boolean = true, listener: ItemDraggedListener<T>) {
        itemDraggedListener = { item, from, to -> listener.onItemDragged(item, from, to) }
        callDragListenerOnDropOnly = callOnDropOnly
    }


    /** Return a set containing any [FlexAdapterSelectableItem]s that are currently selected. */
    open fun selectedItems(): Set<T> = selectedItems.toSet()

    /** The number of items currently selected. */
    open val selectedItemCount: Int get() = selectedItems.size

    /** Return true if the given item is marked as selected */
    open fun isSelected(item: T) = selectedItems.contains(item)

    /**
     * Mark an item as selected.
     *
     * This will cause the view to update. Note that the given item must inherit from
     * [FlexAdapterSelectableItem], or be registered as a selectable item. Otherwise this call will
     * have no effect.
     *
     * @param item The item to mark as selected.
     * @throws IllegalArgumentException If the [item] is not already in the adapter.
     */
    open fun selectItem(item: T) {
        val i = items.indexOf(item)
        require(i >= 0) { "Cannot select item that is not in adapter." }
        selectItemImpl(item, i)
    }

    /**
     * Mark the item at the given [index] as selected.
     *
     * @see selectItem
     * @param index The index of the item to mark as selected.
     */
    open fun selectItemAt(index: Int) {
        require(index in items.indices) { "Cannot select index that is not in adapter." }
        selectItemImpl(items[index], index)
    }

    private fun selectItemImpl(item: T, i: Int) {
        if (isSelectable(item)) {
            selectedItems.add(item)
            notifyItemChanged(i)
        }
    }

    /**
     * Mark an item as not selected.
     *
     * This will cause the view to update. Note that the given item must inherit from
     * [FlexAdapterSelectableItem], or be registered as a selectable item. Otherwise this call will
     * have no effect.
     *
     * If the item is not already in the adapter, this call has no effect.
     *
     * @param item The item to mark as not selected.
     */
    open fun deselectItem(item: T) {
        val i = items.indexOf(item)
        if (i < 0) return
        deselectItemImpl(item, i)
    }

    /**
     * Mark the item at [index] as not selected.
     *
     * @see deselectItem
     * @param index The index of the item to deselect.
     * @throws IllegalArgumentException if [index] is not a valid index in [items]
     */
    open fun deselectItemAt(index: Int) {
        require(index in items.indices) { "Cannot deselect index that is not in adapter." }
        deselectItemImpl(items[index], index)
    }

    private fun deselectItemImpl(item: T, i: Int) {
        if (isSelectable(item)) {
            selectedItems.remove(item)
            notifyItemChanged(i)
        }
    }

    /**
     * Set all [FlexAdapterSelectableItem]s in this adapter to be deselected
     *
     * The deselected items will remain in the adapter and are otherwise unchanged.
     *
     * If there are no [FlexAdapterSelectableItem]s in the adapter, this call has no effect.
     */
    open fun deselectAllItems() {
        if (selectedItems.isEmpty()) return

        for ((i, item) in items.withIndex()) {
            if (selectedItems.remove(item)) notifyItemChanged(i)
            if (selectedItems.isEmpty()) return
        }
    }

    /**
     * Set all [FlexAdapterSelectableItem]s in this adapter to be selected
     *
     * The selected items will remain in the adapter and are otherwise unchanged.
     *
     * If there are no [FlexAdapterSelectableItem]s in the adapter, this call has no effect.
     */
    open fun selectAllItems() {
        if (selectedItemCount >= itemCount) return
        for ((i, item) in items.withIndex()) {
            if (isSelectable(item) && selectedItems.add(item)) {
                notifyItemChanged(i)
            }
        }
    }

    /**
     * Move an item from within the adapter.
     *
     * Both arguments must be valid indexes into the list of items.
     */
    open fun moveItem(from: Int, to: Int) {
        require(from >= 0 && from < items.size) { "Invalid index $from for list of size ${items.size}" }
        require(to >= 0 && to < items.size) { "Invalid index $to for list of size ${items.size}" }

        if (from == to) return

        // Stop listening to changes, since we're sending a move notification manually.
        listListener.enabled = false
        if (from < to) {
            Collections.rotate(items.subList(from, to + 1), -1)
        } else {
            Collections.rotate(items.subList(to, from + 1), 1)
        }
        listListener.enabled = true
        notifyItemMoved(from, to)
    }

    /**
     * When true, any changes to [items] will automatically notify the adapter. (default true)
     *
     * You usually want to leave this enabled, but it can be useful to disable temporarily e.g. if
     * you want to use `DiffUtil` to calculate a more specific set of notifications.
     */
    open var automaticallyNotifyOnItemChanges: Boolean
        get() = listListener.enabled
        set(value) {
            listListener.enabled = value
        }

    /**
     * A SpanSizeLookup for grid layouts.
     *
     * If this adapter is attached to a RecyclerView with a grid layout, and any items in the
     * adapter might have custom span sizes, the return value of this method should be passed to
     * [GridLayoutManager.setSpanSizeLookup]
     */
    open val spanSizeLookup: GridLayoutManager.SpanSizeLookup
        get() = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = attrsAt(position).span
        }

    /**
     * An ItemTouchHelper for RecyclerViews.
     *
     * If any of the items in your RecyclerView might support drag or swipe, you should pass your
     * RecyclerView to the [ItemTouchHelper.attachToRecyclerView] method of the returned helper.
     */
    open val itemTouchHelper: ItemTouchHelper
        get() = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            private var dragFrom: Int = -1
            private var dragTo: Int = -1

            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val i = viewHolder.adapterPosition
                if (i < 0 || i >= items.size) {
                    return 0
                }

                val item = attrsAt(i)
                return makeMovementFlags(item.dragDirs, item.swipeDirs)
            }

            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from < 0 || from >= items.size ||
                        to < 0 || to >= items.size ||
                        attrsAt(to).dragDirs == 0) {
                    dragFrom = -1
                    dragTo = -1
                    return false
                }

                dragTo = to
                if (dragFrom < 0) dragFrom = from

                moveItem(from, to)

                if (!callDragListenerOnDropOnly) {
                    itemDraggedListener?.invoke(items[to], from, to)
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val i = viewHolder.adapterPosition
                if (i < 0 || i >= items.size) return

                itemSwipedListener?.invoke(items[i])
                items.removeAt(i)
            }

            override fun clearView(recyclerView: RecyclerView?, viewHolder: RecyclerView.ViewHolder?) {
                super.clearView(recyclerView, viewHolder)

                if (callDragListenerOnDropOnly && dragFrom >= 0 && dragTo >= 0 && dragFrom != dragTo) {
                    itemDraggedListener?.invoke(items[dragTo], dragFrom, dragTo)
                }

                dragFrom = -1
                dragTo = -1
            }
        })

    /** Return the number of items currently in the adapter. */
    override fun getItemCount(): Int = items.size

    /**
     * Notify that an item has changed.
     *
     * @see RecyclerView.Adapter.notifyItemChanged
     */
    open fun notifyItemObjectChanged(item: T) = notifyItemChanged(items.indexOf(item))

    @Synchronized
    private fun recordItems(range: IntRange) {
        for (i in range) {
            val item = items[i]
            // Calling attrsOf here has two side effects: First, if the item is a subclass of a
            // registered base class, it ensures that the subclass item type is recorded. Second, if
            // the item hans't been registered, it will throw so that we fail when an item is added
            // instead of bound.
            when (item) {
                is FlexAdapterItemBase<*> -> recordItemType(item)
                else -> attrsOf(item)
            }
        }
    }

    private fun recordItemType(item: FlexAdapterItemBase<RecyclerView.ViewHolder>) {
        val type = item.itemType
        if (!viewHolderFactoriesByItemType.containsKey(type)) {
            viewHolderFactoriesByItemType.put(type, item.viewHolderFactory())
        }
    }

    private fun markItemRemoved(item: Any?) {
        selectedItems.remove(item)
    }

    private fun recordAllItems() {
        viewHolderFactoriesByItemType.clear()
        if (selectedItems.isEmpty()) {
            recordItems(items.indices)
            return
        }

        if (items.isEmpty()) {
            selectedItems.clear()
            return
        }

        val newSelection = HashSet<T>(Math.min(selectedItems.size, items.size))
        for (item in items) {
            if (item is FlexAdapterItemBase<*>) recordItemType(item)
            if (item in selectedItems) newSelection.add(item)
        }
        selectedItems = newSelection
    }

    /** @suppress */
    override fun getItemViewType(position: Int): Int = items[position].let {
        (it as? FlexAdapterItemBase<*>)?.itemType ?: itemAttrsKey(it)
    }

    /** @suppress */
    @Synchronized
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return viewHolderFactoriesByItemType[viewType]?.invoke(parent) ?: createViewHolderFromAttr(parent, viewType)
    }

    private fun createViewHolderFromAttr(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val attrs = itemAttrsByItemType[viewType] ?: throw unregisteredTypeError()
        return FlexAdapterExtensionItem.ViewHolder(
                LayoutInflater.from(parent.context).inflate(attrs.layout, parent, false))
    }

    /** @suppress */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val attrs = attrsOf(item)
        when (attrs) {
            is FlexAdapterItem<*> -> attrs.bindErasedViewHolder(holder, position)
            is PlainItemAttrs -> attrs.viewBinder(item, holder.itemView, position)
            is FlexAdapterSelectableItem<*> -> attrs.bindErasedViewHolder(holder, item in selectedItems, position)
            is SelectableItemAttrs -> attrs.viewBinder(item, holder.itemView, item in selectedItems, position)
            else -> throw IllegalStateException("Cannot bind to $item")
        }
    }

    /** @suppress */
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        if (registerAutomatically) {
            itemTouchHelper.attachToRecyclerView(recyclerView)
        }
    }

    @PublishedApi
    open internal fun registerType(cls: Class<*>, @LayoutRes layout: Int, span: Int, swipeDirs: Int,
                                   dragDirs: Int, viewType: Int?, viewBinder: (Any, View, Int) -> Unit) {
        require(!FlexAdapterItemBase::class.java.isAssignableFrom(cls)) {
            "Cannot register types inheriting from FlexAdapterItemBase."
        }
        putItemAttrs(cls, viewType, PlainItemAttrs(layout, span, swipeDirs, dragDirs, viewBinder))
    }

    @PublishedApi
    open internal fun registerSelectableType(cls: Class<*>, @LayoutRes layout: Int, span: Int, swipeDirs: Int,
                                             dragDirs: Int, viewType: Int?, viewBinder: (Any, View, Boolean, Int) -> Unit) {
        require(!FlexAdapterItemBase::class.java.isAssignableFrom(cls)) {
            "Cannot register types inheriting from FlexAdapterItemBase."
        }
        putItemAttrs(cls, viewType, SelectableItemAttrs(layout, span, swipeDirs, dragDirs, viewBinder))
    }

    @Synchronized
    private fun putItemAttrs(cls: Class<*>, itemType: Int?, itemAttrs: ItemAttrs) {
        val predicate = { clz: Class<*>, it: Any -> clz.isAssignableFrom(it.javaClass) }
        val reregistered = itemAttrsByBaseClass.put(cls, predicate to itemAttrs) != null
        if (reregistered) invalidateItemTypeCache()
        val hashCode = System.identityHashCode(cls)
        itemAttrsByItemType.put(itemType ?: hashCode, itemAttrs)
        if (itemType != null) {
            customViewTypes.put(hashCode, itemType)
        }

        // We need to record all items again so that their view types added back to the cache
        if (reregistered) recordAllItems()
    }

    private fun invalidateItemTypeCache() {
        for (itemType in cachedItemsTypes) {
            itemAttrsByItemType.remove(itemType)
            customViewTypes.remove(itemType)
        }
    }

    @Synchronized
    private fun attrsOf(it: T): FlexAdapterItemAttrs =
            if (it is FlexAdapterItemAttrs) it
            else itemAttrsByItemType[itemAttrsKey(it)] ?:
                    baseClassAttrsOf(it) ?:
                    throw unregisteredTypeError()

    private fun attrsAt(index: Int) = attrsOf(items[index])

    @Synchronized
    private fun itemAttrsKey(item: T): Int {
        val hashCode = System.identityHashCode(item.javaClass)
        // Check isEmpty first to avoid extra map reads in the common case of no custom itemTypes
        if (customViewTypes.isEmpty()) return hashCode
        return customViewTypes[hashCode] ?: hashCode
    }

    @Synchronized
    private fun baseClassAttrsOf(item: T): FlexAdapterItemAttrs? {
        val (cls, pair) = itemAttrsByBaseClass.filter { it.value.first(it.key, item) }.entries.firstOrNull() ?: return null
        // Cache this subclass to avoid future lookups.
        val hashCode = System.identityHashCode(item.javaClass)
        val customType = customViewTypes[System.identityHashCode(cls)]
        if (customType == null) {
            itemAttrsByItemType.put(hashCode, pair.second)
        } else {
            customViewTypes.put(hashCode, customType)
        }
        cachedItemsTypes.add(hashCode)
        return pair.second
    }

    private fun isSelectable(item: T) = item is FlexAdapterSelectableItem<*> || attrsOf(item) is SelectableItemAttrs

    private fun unregisteredTypeError() = IllegalArgumentException(
            "Must register type before adding it to adapter. " +
                    "Registered types are: ${itemAttrsByBaseClass.keys.map { it.name }}")
}

/**
 * A callback that binds a model to a view.
 *
 * The arguments to the callback are
 *
 *  - The model to be bound
 *  - The view to bind to
 *  - The index of the item in the adapter
 */
typealias FlexAdapterViewBinder<T> = (T, View, Int) -> Unit

/**
 * A callback that binds a selectable model to a view.
 *
 * The arguments to the callback are
 *
 *  - The model to be bound
 *  - The view to bind to
 *  - Whether or not the item is currently selected
 *  - The index of the item in the adapter
 */
typealias FlexAdapterSelectableViewBinder<T> = (T, View, Boolean, Int) -> Unit

@PublishedApi internal val viewBinderStub: (Any, View, Int) -> Unit = { _, _, _ -> }
@PublishedApi internal val selectableViewBinderStub: (Any, View, Boolean, Int) -> Unit = { _, _, _, _ -> }

/**
 * Register a type with a [FlexAdapter].
 *
 * This must be called for each type that you want to add to the adapter,
 * unless the type is derived from [FlexAdapterItemBase].
 *
 * You may call this more than once on the same type. Subsequent calls will replace the registered
 * [viewBinder]. If you want to also change the [layout], you must also give a new value for
 * [viewType]. Passing the [layout] resource as [viewType] is one option.
 *
 * You may omit the [viewBinder] if there is no information to bind (for example, inserting a divider).
 *
 * @param T The type to register.
 * @param layout The layout resource to use for items of this type.
 * @param span The span to use for items of this type when the layout manager supports it.
 * @param swipeDirs The swipe direction flags to use with the [ItemTouchHelper].
 * @param dragDirs The drag direction flags to use with the [ItemTouchHelper].
 * @param viewType If given, the value to return from [FlexAdapter.getItemViewType]. This is usually not necessary.
 * @param viewBinder The implementation of [RecyclerView.Adapter.bindViewHolder] for items of this type.
 */
inline fun <reified T> FlexAdapter<*>.register(@LayoutRes layout: Int, span: Int = 1, swipeDirs: Int = 0,
                                               dragDirs: Int = 0, viewType: Int? = null, crossinline viewBinder: FlexAdapterViewBinder<T>) {
    registerType(T::class.java, layout, span, swipeDirs, dragDirs, viewType) { any, v, i -> viewBinder(any as T, v, i) }
}

inline fun <reified T> FlexAdapter<*>.register(@LayoutRes layout: Int, span: Int = 1, swipeDirs: Int = 0,
                                               dragDirs: Int = 0, viewType: Int? = null) {
    registerType(T::class.java, layout, span, swipeDirs, dragDirs, viewType, viewBinderStub)
}

/**
 * Register a selectable type with a [FlexAdapter].
 *
 * This is identical to the other overload of this function, except the binder function takes an
 * extra parameter of whether or not the given item is currently marked as selected.
 *
 * You may call this more than once on the same type. Subsequent calls will replace the registered
 * [viewBinder]. If you want to also change the [layout], you must also give a new value for
 * [viewType]. Passing the [layout] resource as [viewType] is one option.
 *
 * You may omit the [viewBinder] if there is no information to bind (for example, inserting a divider).
 *
 * @param T The type to register.
 * @param layout The layout resource to use for items of this type.
 * @param span The span to use for items of this type when the layout manager supports it.
 * @param swipeDirs The swipe direction flags to use with the [ItemTouchHelper].
 * @param dragDirs The drag direction flags to use with the [ItemTouchHelper].
 * @param viewType If given, the value to return from [FlexAdapter.getItemViewType]. This is usually not necessary.
 * @param viewBinder The implementation of [RecyclerView.Adapter.bindViewHolder] for items of this type.
 */
inline fun <reified T> FlexAdapter<*>.registerSelectable(@LayoutRes layout: Int, span: Int = 1, swipeDirs: Int = 0,
                                                         dragDirs: Int = 0, viewType: Int? = null, crossinline viewBinder: FlexAdapterSelectableViewBinder<T>) {
    registerSelectableType(T::class.java, layout, span, swipeDirs, dragDirs, viewType) { any, v, s, i -> viewBinder(any as T, v, s, i) }
}

inline fun <reified T> FlexAdapter<*>.registerSelectable(@LayoutRes layout: Int, span: Int = 1, swipeDirs: Int = 0,
                                                         dragDirs: Int = 0, viewType: Int? = null) {
    registerSelectableType(T::class.java, layout, span, swipeDirs, dragDirs, viewType, selectableViewBinderStub)
}
