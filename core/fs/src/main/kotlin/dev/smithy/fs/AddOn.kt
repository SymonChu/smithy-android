package dev.smithy.fs

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files as NioFiles
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * 可选组件：App 不打包进去、按需拿到手机上的东西 —— rootfs、native 编译工具链、JDK……
 *
 * 文档里叫「可选模块」（M5），代码里叫 AddOn：Magisk 模块已经占了 `Module*` 这一族名字，
 * 两套东西混在一个命名空间里早晚出事。
 *
 * 它们其实是同一件事：**一个大文件 + 解到什么目录 + 许可是什么**。所以下载（可续传）、
 * 校验（SHA-256）、解包（符号链接/权限位/strip）、安装、卸载都只有一份实现，
 * 谁需要就往 [AddOnCatalog] 里加一条。
 *
 * 三条不妥协：
 * - **有 sha256 才下**：没有校验值的条目宁可不提供 —— 「下了个坏包还以为装好了」
 *   比「没有这个选项」糟得多。
 * - **路径要检查**：远端归档里的 `..`/绝对路径一律丢掉（这条是从网上往设备上落文件，
 *   不能被归档本身牵着走）。
 * - **失败要说清下一步**：网络断了、下了一半、校验不过、解包缺空间，各给各的说法。
 */

/** 组件种类 —— 决定它装在哪个目录下（`filesDir/<dirName>` 或 `/data/local/tmp/smithy/<dirName>`）。 */
enum class AddOnKind(val dirName: String) {
    ROOTFS("rootfs"),

    /**
     * 目标 ABI 的 sysroot（bionic 头 + 桩库），交叉编 arm64 要它。
     *
     * **单独一个目录**，不跟 [TOOLCHAIN] 共用：装一份会先清掉目标目录，
     * 两条共用一个目录就会互相覆盖（先下 sysroot 再灌 bundle，sysroot 就没了）。
     */
    SYSROOT("sysroot"),
    TOOLCHAIN("native-toolchain"),
    OTHER("addon"),
}

enum class AddOnArchive(val ext: String) {
    TAR_GZ(".tar.gz"),
    ZIP(".zip"),
}

data class AddOnSpec(
    val id: String,
    val name: String,
    val summary: String,
    val kind: AddOnKind,
    val url: String,
    /** 上游公布的体积（实测过）。用来判断「下完了吗」，也用来在界面上先说清代价。 */
    val bytes: Long,
    val sha256: String,
    val archive: AddOnArchive,
    val license: String,
    val homepage: String,
    /** 解包时去掉几层目录：minirootfs 的条目本来就在根上 → 0；套了一层目录的 bundle → 1 */
    val stripComponents: Int = 0,
    /** 真要用起来需要 root（装到可执行目录、chroot……）—— 界面上如实标出，不猜。 */
    val needsRoot: Boolean = false,
    /** 只从归档里取这些前缀下的条目（去掉 NDK 那种「只想要 sysroot」的浪费）。空 = 全要。 */
    val onlyPaths: List<String> = emptyList(),
    /**
     * zip 里的可执行位。
     *
     * `java.util.zip` 不暴露 unix 权限（`tar` 有 mode 字段，zip 没有），所以 zip 只能按路径
     * 约定来：这些前缀下的文件解出来就是可执行的。默认值覆盖常见布局（bin/usr/bin/...），
     * 特殊 bundle 自己加。
     */
    val executablePrefixes: List<String> = listOf("bin", "sbin", "usr/bin", "usr/sbin", "libexec"),
)

/** 装好之后的落点。 */
data class AddOnInstall(
    val spec: AddOnSpec,
    val dir: File,
    val bytes: Long,
    val installedAt: Long,
)

data class AddOnProgress(val phase: Phase, val done: Long, val total: Long) {
    enum class Phase { DOWNLOAD, VERIFY, EXTRACT }

    val percent: Int get() = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
}

data class AddOnResult(
    val ok: Boolean,
    val install: AddOnInstall? = null,
    val message: String? = null,
    /** 失败时的下一步。 */
    val hint: String? = null,
)

/**
 * 现成的可选组件。
 *
 * 只放**真能下、且我核过校验值**的：URL 与 sha256 都是实际下一遍对出来的
 * （Alpine 那条还和上游公布的 `.sha256` 文件对过），体积也按真实字节写。
 * 加新条目请照做 —— 编一个「大概这么大、大概这个地址」的条目，等于让用户下一趟再失败。
 */
object AddOnCatalog {

    /** Alpine 的 aarch64 minirootfs：4MB 起一个 Linux 用户态，是「在手机上编译」的宿主。 */
    val rootfsAlpine = AddOnSpec(
        id = "rootfs-alpine",
        name = "Alpine rootfs（aarch64）",
        summary = "一个 4MB 的 Alpine 根文件系统。可以在它里面装 clang 等构建工具，" +
            "两条「在手机上编译」的路（发行版 clang、自建工具链）都要先有它",
        kind = AddOnKind.ROOTFS,
        url = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.10-aarch64.tar.gz",
        bytes = 3_952_266,
        sha256 = "61ac877fdbcee6914731bc22a4ed5668ea3470f201f97a7078931c48b71bbeec",
        archive = AddOnArchive.TAR_GZ,
        license = "各组件各自带许可（含 GPL-2.0 的 busybox 等）；minirootfs 不改变组件本身的许可",
        homepage = "https://alpinelinux.org/",
        stripComponents = 0,
        needsRoot = true,
    )

    /**
     * NDK 里的 sysroot，**只要 arm64 那一片**。
     *
     * 交叉编 arm64 的 so 必须有 bionic 的头（`jni.h`、`android/log.h`）与桩库（`liblog.so`、
     * libc++），这些只在 NDK 里。整个 NDK 是 656MB 而其中要用到的约 54MB —— 靠解包时的
     * `onlyPaths` 只留 `sysroot/usr/include` 与 `sysroot/usr/lib/aarch64-linux-android`，
     * 宿主机那份 x86_64 clang 一个字节都不落盘。
     *
     * 下载体积是实测的（668,556,491 字节），sha256 也是实际算的。
     */
    val sysrootNdkArm64 = AddOnSpec(
        id = "sysroot-ndk-arm64",
        name = "Android sysroot（arm64-v8a）",
        summary = "从官方 NDK 里只取 arm64 的 bionic 头与桩库（约 54MB），交叉编 so 的必要条件。" +
            "下载 656MB 但只留 sysroot 那一片，宿主机 clang 不留",
        kind = AddOnKind.SYSROOT,
        url = "https://dl.google.com/android/repository/android-ndk-r26d-linux.zip",
        bytes = 668_556_491,
        sha256 = "eefeafe7ccf177de7cc57158da585e7af119bb7504a63604ad719e4b2a328b54",
        archive = AddOnArchive.ZIP,
        license = "Android NDK（Apache-2.0 为主，另有 BSD/MIT 组件，见 NDK 内的 NOTICE）",
        homepage = "https://developer.android.com/ndk",
        // android-ndk-r26d/toolchains/llvm/prebuilt/linux-x86_64 这五层去掉
        stripComponents = 5,
        onlyPaths = listOf("sysroot/usr/include", "sysroot/usr/lib/aarch64-linux-android"),
    )

    val all: List<AddOnSpec> = listOf(rootfsAlpine, sysrootNdkArm64)

    fun find(id: String): AddOnSpec? = all.firstOrNull { it.id == id.trim() }
}

/**
 * 下载 + 装 + 卸。
 *
 * [root] 是安装根目录：App 私有目录（不需要 root，但**能不能执行**要看设备策略），
 * 或 `/data/local/tmp/smithy`（要 root，一定可执行 —— 工具链要跑起来就得是这里）。
 */
class AddOnManager(val root: File) {

    /**
     * HTTP 客户端不放进公开签名：它会把 okhttp 变成「谁用 AddOnManager 谁就得依赖 okhttp」，
     * 而工具层（`:toolkit`）与界面只关心「装到哪儿、装没装」。
     */
    private val client: OkHttpClient = defaultClient()

    val catalog: List<AddOnSpec> get() = AddOnCatalog.all

    fun installed(id: String): AddOnInstall? = AddOnCatalog.find(id)?.let { installed(it) }

    fun installed(spec: AddOnSpec): AddOnInstall? {
        val dir = dirFor(spec)
        val marker = markerFor(spec)
        if (!marker.isFile || !dir.isDirectory) return null
        val kv = marker.readText().lines().mapNotNull { l ->
            val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
        }.toMap()
        // 上游换版本不会自动升级：记的是哪一版就是哪一版，sha 对不上就当没装（要重装）
        if (kv["sha256"] != spec.sha256) return null
        return AddOnInstall(spec, dir, kv["bytes"]?.toLongOrNull() ?: 0L, kv["at"]?.toLongOrNull() ?: 0L)
    }

    /**
     * 已装的东西 —— **包括不在这份清单里的**。
     *
     * 清单只登记「我们能按地址下的」；而用户自己灌进来的本地包（现在是拿到 arm64 clang 的
     * 唯一办法）不在这份清单里。只看清单的话，刚导进去的工具链在界面上会像没装过一样 ——
     * 那正是「装了个没用的东西」的来源。所以这里从磁盘上的记账文件反推。
     */
    fun installedAll(): List<AddOnInstall> {
        val fromCatalog = catalog.mapNotNull { installed(it) }
        val known = fromCatalog.map { it.spec.id }.toSet()
        val local = root.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(INSTALLED_SUFFIX) }
            .map { it.name.removeSuffix(INSTALLED_SUFFIX) }
            .filter { it !in known }
            .mapNotNull { readMarker(it) }
        return fromCatalog + local
    }

    fun dirFor(spec: AddOnSpec): File = File(root, spec.kind.dirName)

    fun markerFor(spec: AddOnSpec): File = File(root, spec.id + INSTALLED_SUFFIX)

    /** 从记账文件反推一个「不在清单里的」安装记录（本地灌进来的包走这里）。 */
    private fun readMarker(id: String): AddOnInstall? {
        val marker = File(root, id + INSTALLED_SUFFIX)
        if (!marker.isFile) return null
        val kv = marker.readText().lines().mapNotNull { l ->
            val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
        }.toMap()
        val kind = AddOnKind.entries.firstOrNull { it.name == kv["kind"] } ?: AddOnKind.OTHER
        val dir = File(root, kind.dirName)
        if (!dir.isDirectory) return null
        val bytes = kv["bytes"]?.toLongOrNull() ?: 0L
        return AddOnInstall(
            spec = AddOnSpec(
                id = id,
                name = kv["name"] ?: id,
                summary = "本地装的；清单里没有登记，所以不会自动更新",
                kind = kind,
                url = "",
                bytes = bytes,
                sha256 = kv["sha256"].orEmpty(),
                archive = AddOnArchive.ZIP,
                license = kv["license"].orEmpty(),
                homepage = "",
            ),
            dir = dir,
            bytes = bytes,
            installedAt = kv["at"]?.toLongOrNull() ?: 0L,
        )
    }

    /** 下载到 `<root>/<id><ext>`（`.part` 是半个文件，断了还能接着下）。 */
    fun download(spec: AddOnSpec, onProgress: (AddOnProgress) -> Unit = {}): File {
        root.mkdirs()
        val dest = File(root, spec.id + spec.archive.ext)
        val part = File(root, dest.name + ".part")
        var have = if (part.isFile) part.length() else 0L
        if (have > spec.bytes) have = 0L // 比预期还长的半个文件：不可能是它，重下

        val req = Request.Builder()
            .url(spec.url)
            .header("User-Agent", USER_AGENT)
            .apply { if (have > 0L) header("Range", "bytes=$have-") }
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("下载失败：HTTP ${resp.code} ${resp.message}（${spec.url}）")
            }
            // 服务器不认续传就从头来，别把两段接在一起
            if (have > 0L && resp.code != 206) have = 0L
            val body = resp.body ?: throw IOException("响应没有内容：${spec.url}")
            val total = when {
                have > 0L -> have + body.contentLength()
                else -> body.contentLength()
            }.let { if (it > 0L) it else spec.bytes }

            onProgress(AddOnProgress(AddOnProgress.Phase.DOWNLOAD, have, total))
            FileOutputStream(part, have > 0L).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = have
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(AddOnProgress(AddOnProgress.Phase.DOWNLOAD, done, total))
                    }
                }
            }
        }

        if (part.length() != spec.bytes) {
            throw IOException(
                "下载不完整：拿到 ${part.length()} 字节，应该有 ${spec.bytes}。" +
                    "网络稳的时候再点一次（会接着下）",
            )
        }
        if (!part.renameTo(dest)) part.copyTo(dest, overwrite = true).also { part.delete() }
        return dest
    }

    /** 校验文件内容的 sha256。 */
    fun verify(spec: AddOnSpec, file: File, onProgress: (AddOnProgress) -> Unit = {}): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var done = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                onProgress(AddOnProgress(AddOnProgress.Phase.VERIFY, done, total))
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        return got == spec.sha256.lowercase()
    }

    /**
     * 下 + 校验 + 解包 + 记账。
     *
     * 解包先在 `<dir>.part` 里做完再改名到位：中途失败不会留下一个「看起来装好了、
     * 其实只有一半」的目录 —— 那种状态比彻底失败难查。
     */
    fun install(spec: AddOnSpec, onProgress: (AddOnProgress) -> Unit = {}): AddOnResult {
        val archive = try {
            download(spec, onProgress)
        } catch (e: Exception) {
            return AddOnResult(false, message = e.message ?: "下载失败", hint = "检查网络后重试；重复点会接着下，不会从头来")
        }
        if (!verify(spec, archive, onProgress)) {
            archive.delete()
            return AddOnResult(
                false,
                message = "校验不过：网上拿到的这份和登记的 sha256 不一致",
                hint = "已经把它删了。重新下一次；要是总不对，说明上游换包了，需要更新登记里的 sha256",
            )
        }
        return extractInto(spec, archive, onProgress).also {
            if (it.ok) archive.delete() // 归档留着只占地方，需要就再下一份
        }
    }

    /** 从手机本地的归档装（电脑上传过来的 bundle 走这条）。 */
    fun installFromLocal(spec: AddOnSpec, archive: File, onProgress: (AddOnProgress) -> Unit = {}): AddOnResult {
        if (!archive.isFile) return AddOnResult(false, message = "找不到文件：$archive", hint = "确认路径")
        if (spec.sha256.isNotEmpty() && !verify(spec, archive, onProgress)) {
            return AddOnResult(
                false,
                message = "校验不过：这份文件和登记的 sha256 不一致",
                hint = "可能传坏了或传的是另一版。用登记里的那一版，或改登记",
            )
        }
        return extractInto(spec, archive, onProgress)
    }

    private fun extractInto(spec: AddOnSpec, archive: File, onProgress: (AddOnProgress) -> Unit): AddOnResult {
        val dir = dirFor(spec)
        dir.parentFile?.mkdirs()
        val staging = File(root, "${dir.name}.part")
        staging.deleteRecursively()
        staging.mkdirs()
        return try {
            val stats = ArchiveExtract.extract(
                archive = archive,
                kind = spec.archive,
                dest = staging,
                strip = spec.stripComponents,
                onlyPaths = spec.onlyPaths,
                execPrefixes = spec.executablePrefixes,
            ) { done, total -> onProgress(AddOnProgress(AddOnProgress.Phase.EXTRACT, done, total)) }
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) throw IOException("装不到位：$dir（可能没有写权限）")
            val installedAt = System.currentTimeMillis()
            val bytes = ArchiveExtract.dirSize(dir)
            markerFor(spec).writeText(
                "id=${spec.id}\nname=${spec.name}\nkind=${spec.kind.name}\nlicense=${spec.license}\n" +
                    "sha256=${spec.sha256}\nbytes=$bytes\nat=$installedAt\n" +
                    "files=${stats.files}\nlinks=${stats.links}\n",
            )
            AddOnResult(true, AddOnInstall(spec, dir, bytes, installedAt))
        } catch (e: Exception) {
            staging.deleteRecursively()
            AddOnResult(
                false,
                message = "解包失败：${e.message}",
                hint = if (e is IOException && e.message?.contains("space", ignoreCase = true) == true) {
                    "空间不够：先清出位置再试"
                } else {
                    "归档里最后一条报错的行就是源头；换一份归档或重下"
                },
            )
        }
    }

    fun remove(spec: AddOnSpec): Boolean {
        val ok = dirFor(spec).deleteRecursively()
        markerFor(spec).delete()
        return ok
    }

    companion object {
        /** 记账文件的后缀：`<root>/<id>.installed`，内容是 `key=value`（和 module.prop 一个路数）。 */
        const val INSTALLED_SUFFIX = ".installed"

        const val USER_AGENT = "Smithy/1.0"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/** 解包统计。 */
data class ExtractStats(val files: Int, val links: Int, val dirs: Int)

/**
 * 安装根目录的注册表。
 *
 * 「装到哪儿」只有 App 层知道（私有目录 / `/data/local/tmp/smithy`，见 App 的启动代码），
 * 工具层与界面只问这里要一个现成的 [AddOnManager] —— 和 ModuleChannels / NativeToolchains
 * 同一个套路。
 */
object AddOnHost {

    @Volatile
    private var manager: AddOnManager? = null

    fun install(m: AddOnManager?) {
        manager = m
    }

    fun current(): AddOnManager? = manager

    fun require(): AddOnManager = manager ?: throw IllegalStateException(
        "没有登记可选组件的安装位置 —— 这是 App 启动时的活（SmithyApp）；" +
            "在测试或纯 JVM 里得自己 new 一个 AddOnManager(root)",
    )
}

/**
 * 归档解包。
 *
 * 自己写而不是用 `TarReader.extractAll`：后者把符号链接当 0 字节普通文件写出来
 * （Alpine 的 `/bin/sh` 就是这么废掉的），也没有路径检查。要往设备上落**远端**归档，
 * 这两条都是硬的。
 */
internal object ArchiveExtract {

    fun extract(
        archive: File,
        kind: AddOnArchive,
        dest: File,
        strip: Int,
        onlyPaths: List<String>,
        execPrefixes: List<String> = emptyList(),
        onEntry: (Long, Long) -> Unit,
    ): ExtractStats {
        val total = archive.length()
        var done = 0L
        var files = 0
        var links = 0
        var dirs = 0
        when (kind) {
            AddOnArchive.TAR_GZ -> {
                TarReader.open(archive).use { r ->
                    while (true) {
                        val e = r.nextEntry() ?: break
                        done += 512L + e.size
                        onEntry(done, total)
                        val rel = stripPrefix(e.path, strip) ?: continue
                        if (onlyPaths.isNotEmpty() && onlyPaths.none { rel == it || rel.startsWith("$it/") }) continue
                        val target = safeTarget(dest, rel) ?: continue
                        when (e.typeflag) {
                            '5' -> { target.mkdirs(); dirs++ }
                            '2' -> {
                                linkTo(target, e.linkTarget)?.let { links++ }
                            }
                            '1' -> {
                                // 硬链接：指向归档里已经解出来的文件；目标不在就退化成空文件
                                // 注意参照点是**解包根**（tar 里的硬链接名相对归档根），不是条目所在目录
                                hardLink(dest, target, e.linkTarget)?.let { links++ }
                            }
                            else -> {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { out -> r.dataStream().copyTo(out) }
                                applyMode(target, e.mode)
                                files++
                            }
                        }
                    }
                }
            }
            AddOnArchive.ZIP -> {
                ZipFile(archive).use { zip ->
                    val names = zip.entries().asSequence().toList()
                    names.forEach { entry ->
                        done += entry.size.coerceAtLeast(0)
                        onEntry(done, total)
                        val rel = stripPrefix(entry.name, strip) ?: return@forEach
                        if (onlyPaths.isNotEmpty() && onlyPaths.none { rel == it || rel.startsWith("$it/") }) return@forEach
                        val target = safeTarget(dest, rel) ?: return@forEach
                        if (entry.isDirectory) {
                            target.mkdirs(); dirs++
                        } else {
                            target.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                target.outputStream().use { out -> input.copyTo(out) }
                            }
                            // zip 没有 unix 权限字段，只能按路径约定给可执行位
                            if (execPrefixes.any { rel == it || rel.startsWith("$it/") }) target.setExecutable(true)
                            files++
                        }
                    }
                }
            }
        }
        return ExtractStats(files, links, dirs)
    }

    /** 去掉前 [n] 层目录；不足 n 层就丢掉（那些是归档的壳）。 */
    internal fun stripPrefix(path: String, n: Int): String? {
        val clean = path.replace('\\', '/').removePrefix("./").trimEnd('/')
        if (clean.isEmpty()) return null
        val parts = clean.split('/').filter { it.isNotEmpty() }
        if (parts.size <= n) return null
        return parts.drop(n).joinToString("/")
    }

    /** 路径检查：绝对路径、`..`、盘符一律丢掉 —— 归档不能决定往哪儿写。 */
    internal fun safeTarget(base: File, rel: String): File? {
        val parts = rel.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        return File(base, parts.joinToString("/"))
    }

    /**
     * 建软/硬链接。
     *
     * **绝对指向要留着**（Alpine 里满是 `/bin/busybox` 这种），因为它们将来是在
     * chroot 里面解析的 —— 那里 `/` 就是 rootfs 自己的根。放开的是「链接本身」，
     * 不是「往 rootfs 外面写」。
     */
    private fun linkTo(target: File, link: String?): File? {
        if (link.isNullOrBlank()) return null
        target.parentFile?.mkdirs()
        target.delete()
        return try {
            NioFiles.createSymbolicLink(target.toPath(), Paths.get(link))
            target
        } catch (e: Exception) {
            null
        }
    }

    /** 硬链接：源在解包根下按名字找。找不到就放一个空的普通文件占位，不让整次安装失败。 */
    private fun hardLink(root: File, target: File, link: String?): File? {
        if (link.isNullOrBlank()) return null
        val src = safeTarget(root, link.trimStart('/')) ?: return null
        target.parentFile?.mkdirs()
        target.delete()
        return try {
            NioFiles.createLink(target.toPath(), src.toPath())
            target
        } catch (e: Exception) {
            runCatching { target.writeBytes(ByteArray(0)) }.getOrNull()?.let { target }
        }
    }

    private fun applyMode(file: File, mode: Int) {
        if (mode <= 0) return
        file.setReadable(true, false)
        file.setWritable(true, true)
        if (mode and 0b001_001_001 != 0) file.setExecutable(true, false)
    }

    fun dirSize(dir: File): Long {
        var sum = 0L
        dir.walkTopDown().forEach { if (it.isFile) sum += it.length() }
        return sum
    }
}
