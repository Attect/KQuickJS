package app.muka.project.kquickjs.runtime

import app.muka.project.kquickjs.JSValue
import app.muka.project.kquickjs.JS_UNDEFINED
import app.muka.project.kquickjs.JvmRoot

/**
 * 解释器操作数栈：用 LongArray 存储 JSValue，避免 MutableList<Long> 的装箱与
 * 每指令对象分配（对照 C 的 JSValue *sp 原生栈）。
 */
class ValueStack(initialCapacity: Int = 32) : JvmRoot {
    private var data = LongArray(if (initialCapacity > 0) initialCapacity else 32)
    var size = 0
        private set

    override val rootSize: Int get() = size
    override fun rootGet(i: Int): JSValue = data[i]
    override fun rootSet(i: Int, v: JSValue) { data[i] = v }

    fun push(v: JSValue) {
        if (size == data.size) {
            val n = if (data.size < 16) 32 else data.size * 2
            data = data.copyOf(n)
        }
        data[size++] = v
    }

    fun pop(): JSValue {
        size--
        return data[size]
    }

    fun top(): JSValue = data[size - 1]

    operator fun get(i: Int): JSValue = data[i]
    operator fun set(i: Int, v: JSValue) { data[i] = v }

    fun removeAt(i: Int): JSValue {
        val v = data[i]
        val n = size - i - 1
        if (n > 0) System.arraycopy(data, i + 1, data, i, n)
        size--
        return v
    }

    fun clear() { size = 0 }
    fun isEmpty(): Boolean = size == 0

    fun joinToString(separator: String, transform: (JSValue) -> String): String {
        val sb = StringBuilder()
        for (i in 0 until size) {
            if (i > 0) sb.append(separator)
            sb.append(transform(data[i]))
        }
        return sb.toString()
    }
}

/** 函数局部变量表：定长 LongArray（对照 C 的 JSValue *vars）。 */
class ValueArray(override val rootSize: Int) : JvmRoot {
    val size: Int get() = rootSize
    private val data = LongArray(rootSize) { JS_UNDEFINED }

    override fun rootGet(i: Int): JSValue = data[i]
    override fun rootSet(i: Int, v: JSValue) { data[i] = v }

    operator fun get(i: Int): JSValue = data[i]
    operator fun set(i: Int, v: JSValue) { data[i] = v }

    fun joinToString(separator: String, transform: (JSValue) -> String): String {
        val sb = StringBuilder()
        for (i in 0 until rootSize) {
            if (i > 0) sb.append(separator)
            sb.append(transform(data[i]))
        }
        return sb.toString()
    }
}