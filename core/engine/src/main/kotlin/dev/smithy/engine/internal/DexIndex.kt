package dev.smithy.engine.internal

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.MultiDexContainer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * APK 内 dex 的**只读**索引。
 *
 * 为什么不一次性解析：一个包常有十几个 dex（本仓库自己的 debug 包就有 11 个），
 * 单个 dex 上限 65k 方法、86k 字符串。全量反序列化成对象树必然 OOM，
 * 所以这里只持有 dexlib2 的**懒加载句柄** —— 它按需从 zip 读字节。
 *
 * 写能力不在这里：字符串替换见 [DexEditor]，smali 往返见 [SmaliBridge]。
 */
internal class DexIndex(private val apkFile: File, val apiLevel: Int) : AutoCloseable {

    // loadDexContainer 声明为 MultiDexContainer<? extends DexBackedDexFile>，Kotlin 侧要 out 投影
    private val container: MultiDexContainer<out DexBackedDexFile> =
        DexFileFactory.loadDexContainer(apkFile, Opcodes.forApi(apiLevel))

    /** 包内 dex 名，按数字序：classes.dex, classes2.dex … classes10.dex */
    val names: List<String> = container.dexEntryNames.sortedWith(DEX_ORDER)

    private val cache = ConcurrentHashMap<String, DexBackedDexFile>()

    /**
     * 倒原始字节用的独立句柄。
     *
     * dexlib2 的 container 是「解析」入口，拿不到条目的原始字节；而 [dump] 要的是原样字节。
     * 两个句柄都只读打开同一个文件，不冲突。懒建是因为只有 jadx 这类库才用得上。
     */
    private val zipLazy = lazy { java.util.zip.ZipFile(apkFile) }

    fun dex(name: String): DexBackedDexFile? {
        cache[name]?.let { return it }
        val loaded = runCatching { container.getEntry(name)?.dexFile }.getOrNull() ?: return null
        cache[name] = loaded
        return loaded
    }

    /** 逐个 dex 的 (名字, 句柄) 序列。 */
    fun all(): Sequence<Pair<String, DexBackedDexFile>> =
        names.asSequence().mapNotNull { n -> dex(n)?.let { n to it } }

    /**
     * 类描述符（`Lcom/a/B;`）落在哪个 dex。
     *
     * 遍历各 dex 的类表，O(类数)，但只在改代码时调用；不缓存映射，
     * 因为一个包的类数上万，建全量映射比每次扫一遍更占内存。
     */
    fun dexOfClass(descriptor: String): String? {
        for ((name, dex) in all()) {
            for (classDef in dex.classes) if (classDef.type == descriptor) return name
        }
        return null
    }

    /**
     * 把某个 dex 的**原始字节**导出成文件。
     *
     * 不能用「dexlib2 读出来再写一遍」代替：`DexBackedDexFile` 重新序列化会重排常量池与偏移，
     * 得到的已不是原包里那个 dex。只认文件输入的库（jadx、外部工具）必须拿到原样字节。
     */
    fun dump(name: String, outFile: File): File? {
        val src = runCatching { zipLazy.value }.getOrNull() ?: return null
        val entry = src.getEntry(name) ?: return null
        outFile.parentFile?.mkdirs()
        src.getInputStream(entry).use { ins -> outFile.outputStream().use { ins.copyTo(it) } }
        return outFile
    }

    override fun close() {
        cache.clear()
        if (zipLazy.isInitialized()) runCatching { zipLazy.value.close() }
    }

    companion object {
        /** `classes.dex` 的捕获组是空串，等价于 1 */
        private val NUM = Regex("""classes(\d*)\.dex""")

        /** 字典序会把 classes10.dex 排到 classes2.dex 前面，所以要按数字比 */
        private val DEX_ORDER = Comparator<String> { a, b -> index(a).compareTo(index(b)) }

        private fun index(name: String): Int {
            val g = NUM.find(name)?.groupValues?.get(1).orEmpty()
            return if (g.isEmpty()) 1 else g.toIntOrNull() ?: Int.MAX_VALUE
        }

        /** `com.a.B` / `com.a.B$C` / `Lcom/a/B;` → `Lcom/a/B;` */
        fun descriptor(className: String): String =
            if (className.startsWith("L") && className.endsWith(";")) className
            else "L" + className.replace('.', '/') + ";"

        /** `Lcom/a/B;` → `com.a.B` */
        fun prettyName(descriptor: String): String =
            descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
    }
}
