package io.ucc.core.singbox.android

import io.nekohasekai.libbox.StringIterator

internal class StringArrayIterator(values: List<String>) : StringIterator {
    private val list = values
    private var index = 0
    override fun len(): Int = list.size
    override fun hasNext(): Boolean = index < list.size
    override fun next(): String = list[index++]
}

internal fun StringIterator.toList(): List<String> {
    val out = ArrayList<String>(len())
    while (hasNext()) out += next()
    return out
}

internal fun List<String>.toStringIterator(): StringIterator = StringArrayIterator(this)
