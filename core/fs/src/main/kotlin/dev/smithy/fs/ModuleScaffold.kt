package dev.smithy.fs

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 新建一个模块要填的东西。
 *
 * [id] 就是 Magisk 的模块标识，也是刷入后 `/data/adb/modules/<id>` 的目录名 ——
 * 所以它必须合法（[ModuleProp.validateId]），非法时 Magisk **静默跳过**整个模块：
 * 模块列表里干脆不出现，没有任何提示。
 */
data class ModuleSkeletonSpec(
    val id: String,
    val name: String = id,
    val version: String = "v1.0",
    val versionCode: Int = 1,
    val author: String = "",
    val description: String = "",
    val flavour: Flavour = Flavour.SHELL,
) {
    /**
     * 骨架的两档。
     *
     * 差别只有一个：要不要 native 源码。**shell 档刷进去就能生效**，不需要任何编译链；
     * zygisk 档只是把源码摆好，`.so` 得自己编出来（构建模块 / M6-B）。
     */
    enum class Flavour {
        /** 纯脚本模块：`service.sh` / `post-fs-data.sh` / `system.prop`。 */
        SHELL,

        /** Zygisk 模块：额外给 `jni/` 下的 native 骨架。 */
        ZYGISK,
    }
}

/**
 * 模块骨架的生成器。
 *
 * **为什么要有它**：在这之前，模块这条线只能「打开一个已有的模块 zip 来改」——
 * 想从头写一个模块，得自己把结构拼对。而模块的结构约束是**静默生效**的：
 *
 * - 条目必须直接在根上（套一层 `<id>/` 目录，Magisk 会当成「没有 module.prop」）；
 * - `id` 只允许字母数字与 `. _ -`；
 * - `versionCode` 必须是整数；
 * - Zygisk 的 `.so` 必须叫 `zygisk/<abi>.so`（Magisk 按**文件名**认 ABI，
 *   写成 `libfoo.so` 根本不会被加载）；
 * - 脚本得有执行位。
 *
 * 错任何一条的现象都是「刷进去成功、但什么都没发生」。骨架把这些一次钉对，
 * 剩下的就只有「要让它干什么」。
 *
 * 两档的取舍见 [ModuleSkeletonSpec.Flavour]。**zygisk 档不放占位的 .so**：
 * 放一个假文件会让「装上了但模块没生效」变得极难排查，宁可让它明确地「还没编」。
 *
 * 这一层不碰设备、不依赖 Android（core:fs 是纯 JVM 模块），所以能直接单测。
 */
object ModuleScaffold {

    /**
     * zip 条目用固定时间戳。
     *
     * 同一份输入生成两次应该**逐字节相同** —— 不然「这个包和上次那个是不是一样」
     * 永远只能靠猜，也没法写确定性测试。模块的版本信息在 module.prop 里，
     * 不靠 zip 的时间戳表达。
     */
    private const val STAMP = 1577836800000L // 2020-01-01T00:00:00Z

    /** shell 里的 `$` 在 Kotlin 字符串里要转义，集中一处，免得漏写一个变成插值。 */
    private const val D = "$"

    /** 名字留空时用 id 顶替：Magisk 的模块列表里空名字会显示成一片空白，看不出是谁。 */
    private val ModuleSkeletonSpec.title: String get() = name.ifBlank { id }

    /**
     * 官方 `zygisk.hpp`（0BSD）放在 resources 里**原样分发**。
     *
     * 不内联成 Kotlin 字符串，是因为这个文件里明写 `DO NOT MODIFY ANY CODE IN THIS HEADER`：
     * 字节级不动它，是遵守这条最省事的办法（`ModuleScaffoldTest` 用哈希钉住）。
     *
     * 读不到时返回 null —— 由调用方决定怎么降级，不在这里抛异常把「新建模块」整个弄挂。
     */
    fun zygiskHeader(): String? =
        ModuleScaffold::class.java.getResourceAsStream(ZYGISK_HEADER_RESOURCE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

    private const val ZYGISK_HEADER_RESOURCE = "/dev/smithy/fs/zygisk.hpp"

    /**
     * 骨架的全部条目（路径 → 文本内容），顺序即写入顺序。
     *
     * 独立于 [write] 暴露出来：调用方（工具层 / 界面）经常要先知道「会放进去哪些文件」，
     * 而不是先落盘。
     */
    fun entries(spec: ModuleSkeletonSpec): LinkedHashMap<String, String> {
        val prop = ModuleProp(
            id = spec.id,
            name = spec.name.ifBlank { spec.id },
            version = spec.version,
            versionCode = spec.versionCode,
            author = spec.author,
            description = spec.description,
        )
        // 先按 Magisk 的判定校验一遍：id 非法 / versionCode 为负在这里就断掉，
        // 不要生成一个「看着像模块、Magisk 不认」的 zip
        prop.validate()?.let { throw IllegalArgumentException(it) }

        val files = LinkedHashMap<String, String>()
        files[ModuleProject.ModulePropFile] = prop.toText()
        files["customize.sh"] = customizeScript(spec)
        files["service.sh"] = serviceScript(spec)
        files["post-fs-data.sh"] = postFsDataScript(spec)
        files["system.prop"] = SYSTEM_PROP
        if (spec.flavour == ModuleSkeletonSpec.Flavour.ZYGISK) {
            // 头文件随包给：不然用户要先自己找到它、还要知道「不许改」，而少了它
            // clang 只会报一串 file not found
            zygiskHeader()?.let { files["jni/zygisk.hpp"] = it }
            files["jni/module.cpp"] = zygiskSource(spec)
            files["jni/CMakeLists.txt"] = cmakeLists(spec)
            files["jni/build.sh"] = buildScript(spec)
            files["jni/README.md"] = NATIVE_README
        }
        return files
    }

    /**
     * 生成骨架 zip。返回写进去的条目名（顺序同 [entries]）。
     *
     * 目标文件已存在时**直接覆盖**：它的语义是「新建」，调用方要保证路径是新的
     * （工具层会把这件事说清，而不是悄悄改掉一个已有的模块）。
     */
    fun write(spec: ModuleSkeletonSpec, target: File): List<String> {
        val files = entries(spec)
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            files.forEach { (path, text) ->
                check(!path.startsWith("/") && !path.contains("..")) { "条目路径必须是包内相对路径：$path" }
                val entry = ZipEntry(path).apply { time = STAMP }
                zip.putNextEntry(entry)
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return files.keys.toList()
    }

    // ── 模板 ────────────────────────────────────────────────────

    /**
     * 安装期脚本。
     *
     * Magisk 刷入时会执行它（跑在 Magisk 的安装环境里，不是普通 shell），
     * 里面能用的 `set_perm` / `ui_print` 都由 Magisk 提供。
     *
     * 只做两件事：把脚本设成可执行（zip 里的执行位不一定能带过去）、把装了什么说出来。
     */
    private fun customizeScript(spec: ModuleSkeletonSpec): String = """
        #!/system/bin/sh
        # 刷入时执行一次。开机之后要做什么写在 service.sh 里 —— 那个才是模块生效的地方。

        set_perm_recursive "${D}MODPATH" 0 0 0755 0644
        for f in service.sh post-fs-data.sh; do
          [ -f "${D}MODPATH/${D}f" ] && set_perm "${D}MODPATH/${D}f" 0 0 0755
        done

        ui_print "- ${spec.title} ${spec.version}  (id: ${spec.id})"
        ui_print "- 开机脚本：${D}MODPATH/service.sh"
    """.trimIndent()

    /**
     * 开机脚本 —— 模块真正干活的地方。
     *
     * 骨架是个空操作（`:`），这样刷进去不会改变设备行为；要做的事写在下面再放开。
     */
    private fun serviceScript(spec: ModuleSkeletonSpec): String = """
        #!/system/bin/sh
        # late_start service：系统基本起来之后执行一次，跑在 root 身份下。
        # 模块真正干活的地方就是这里，这一段现在什么都没做。
        #
        # 改之前记住两件事：
        #   1) 每次开机都会跑一遍 —— 写错了的现象可能是「开不了机」或者「某个应用反复重启」；
        #   2) 出错不会弹任何提示，日志是唯一的线索（自己往 logcat 写 tag，或看 magisk --log）。
        #
        # 常见的几类动作：
        #   resetprop -n some.key some_value          改系统属性
        #   cmd package compile -m speed -f 包名       把某个应用整体编译一遍
        #   am force-stop 包名                        让某个应用下次启动时重新读环境
        #   mkdir -p /data/adb/${spec.id}             自留一处存状态的地方
        :
    """.trimIndent()

    /**
     * 更早的启动阶段（`/data` 已挂载、系统服务还没起来）。
     *
     * **它会阻塞开机**，所以默认也是空的：能放到 service.sh 的事就不该放这里。
     */
    private fun postFsDataScript(spec: ModuleSkeletonSpec): String = """
        #!/system/bin/sh
        # post-fs-data：开机最早能改文件的时候。/data 已经挂上，系统服务还没起来。
        # 这一段**会拖慢开机**，能放进 service.sh 的事就不要放这里。
        #
        # 同样是个空操作；确实需要「赶在系统起来之前」做的事，再往下面写。
        # 存状态的地方惯例是 /data/adb/${spec.id}。
        :
    """.trimIndent()

    private val SYSTEM_PROP = """
        # Magisk 在开机时读这个文件，把里面的 key=value 当系统属性写进去（等价于 resetprop）。
        # 一行一条，`#` 开头是注释。例：
        #   ro.sf.lcd_density=440
    """.trimIndent()

    /**
     * Zygisk native 骨架。
     *
     * 目标软件在 `preAppSpecialize` 里按进程名（`args->nice_name`，一般就是包名）筛出来 ——
     * 这是「只对某个软件生效」的关键一步：全进程注入既容易被检测到，也拖慢开机。
     */
    private fun zygiskSource(spec: ModuleSkeletonSpec): String = """
        // Zygisk 模块骨架（由 Smithy 生成，id: ${spec.id}）。
        //
        // zygisk.hpp 就在这个目录里：官方样例工程的原件，0BSD，文件里写着
        // 「DO NOT MODIFY ANY CODE IN THIS HEADER」—— 别改它。

        #include <jni.h>
        #include <android/log.h>
        #include <string.h>

        #include "zygisk.hpp"

        // 版本在头文件里（zygisk.hpp 顶部的 ZYGISK_API_VERSION）：
        //   v5 → Magisk 27000+   v4 → 26000+   v3 → 24300+
        // 这里把下限卡在编译期：拿老一版头文件编这一档骨架会直接断在这里，
        // 而不是等到运行时接口对不上、行为诡异才发现。
        #if !defined(ZYGISK_API_VERSION) || ZYGISK_API_VERSION < 4
        #error "需要 Zygisk API v4 及以上（Magisk 26000+）；更老的 Magisk 用 shell 那一档骨架"
        #endif

        namespace {

        constexpr const char *kTag = "${spec.id}";

        // ← 改成目标软件的进程名（一般就是包名）。空串 = 所有应用都注入，
        //   除非你真的要这么干，否则别留空。
        constexpr const char *kTargetProcess = "";

        class Module : public zygisk::ModuleBase {
        public:
            void onLoad(zygisk::Api *api, JNIEnv *env) override {
                this->api = api;
                this->env = env;
            }

            void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
                // 每个应用进程 fork 出来的时候都会走这里。此时进程还没有沙箱限制。
                // nice_name 就是进程名（一般等于包名）—— 「只对某个软件生效」靠它筛。
                const char *name = env->GetStringUTFChars(args->nice_name, nullptr);
                matches = kTargetProcess[0] == '\0' ||
                          (name != nullptr && strcmp(name, kTargetProcess) == 0);
                env->ReleaseStringUTFChars(args->nice_name, name);
            }

            void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
                // 进程已经 specialized、还没跑应用自己的代码 —— 装 hook 一般放这里。
                (void) args;  // 这里暂时用不上它；写出来是为了让 -Wall -Wextra 保持安静
                if (!matches) return;

                // TODO 针对目标软件做定制：改 JNI 实现、替掉某个方法、补一段初始化。
                //
                // 注意权限边界：这一段跑在**应用的沙箱里**，不是 root。要读写别的应用的数据，
                // 得走 root 伴生进程：api->connectCompanion() + REGISTER_ZYGISK_COMPANION(handler)。
                __android_log_print(ANDROID_LOG_INFO, kTag, "loaded");
            }

        private:
            zygisk::Api *api = nullptr;
            JNIEnv *env = nullptr;
            bool matches = false;
        };

        }  // namespace

        REGISTER_ZYGISK_MODULE(Module)
    """.trimIndent()

    private fun cmakeLists(spec: ModuleSkeletonSpec): String = """
        cmake_minimum_required(VERSION 3.22.1)
        project(${spec.id} CXX)

        add_library(${spec.id} SHARED module.cpp)

        # -fvisibility=hidden：只让 zygisk.hpp 里标了 default visibility 的入口符号暴露出去，
        # 其余全部藏起来（模块和别的模块/宿主同名符号撞上会很难查）。
        target_compile_options(${spec.id} PRIVATE -Wall -Wextra -fno-exceptions -fno-rtti -fvisibility=hidden)

        # -static-libstdc++：Zygisk 把模块 dlopen 进别人的进程，那个进程里没有
        # libc++_shared.so —— 动态依赖它 = 装上了但不加载，而且不报错。
        target_link_options(${spec.id} PRIVATE -static-libstdc++)
        target_link_libraries(${spec.id} PRIVATE log)
    """.trimIndent()

    /**
     * 编译并把产物放到 `zygisk/<abi>.so`。
     *
     * 产物名不是可选的：Magisk 按**文件名**认 ABI，`libfoo.so` 会被当成「不认识的 ABI」，
     * 模块装上了但不加载。
     */
    private fun buildScript(spec: ModuleSkeletonSpec): String = """
        #!/usr/bin/env bash
        # 编出 zygisk/<abi>.so。用法：ABIS="arm64-v8a" ./build.sh
        # 这是「在电脑上编」的那条路，需要 NDK。
        # 在手机上编走 Smithy 的 native 构建工具链（模块页的「编译 .so」/ module.build），
        # 不需要这个脚本 —— 它只是给不想把工具链搬到手机上的人留的出口。
        set -e

        ndk="${D}ANDROID_NDK_HOME"
        if [ -z "${D}ndk" ]; then
          echo "先把 ANDROID_NDK_HOME 指向 NDK 目录" >&2
          exit 1
        fi

        here="${D}(cd "${D}(dirname "${D}0")" && pwd)"
        root="${D}(cd "${D}here/.." && pwd)"
        abis="${D}{ABIS:-arm64-v8a}"

        for abi in ${D}abis; do
          cmake -S "${D}here" -B "${D}here/build/${D}abi" \
            -DCMAKE_TOOLCHAIN_FILE="${D}ndk/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI="${D}abi" \
            -DANDROID_PLATFORM=android-26
          cmake --build "${D}here/build/${D}abi"
          mkdir -p "${D}root/zygisk"
          cp "${D}here/build/${D}abi/lib${spec.id}.so" "${D}root/zygisk/${D}abi.so"
          echo "→ zygisk/${D}abi.so"
        done

        echo "产物在 zygisk/ 下。名字必须正好是 arm64-v8a / armeabi-v7a / x86 / x86_64。"
    """.trimIndent()

    private val NATIVE_README = """
        # 这一档怎么编出来

        `jni/` 只是**源码**，不是能直接生效的模块 —— Zygisk 要的是 `zygisk/<abi>.so`。

        1. 改目标：`module.cpp` 里的 `kTargetProcess` 填目标软件的进程名（一般就是包名）。
           `zygisk.hpp` 已经在本目录（官方原件，0BSD，文件里写着不许改它的内容）。
        2. 编译，两条路：
           - **手机上**：模块页的「编译 .so」，或者 `module.build`（AI 走这条）。
             产物会直接写进 zip 的 `zygisk/<abi>.so`。
           - **电脑上**：`ABIS="arm64-v8a" ./build.sh`（需要 NDK），再把产物拷回 `zygisk/`。
        3. 刷入：`module.install`（需要 root），生效要 `module.zygote_restart` 或重启。

        理由：Magisk 按**文件名**认 ABI，所以产物必须正好叫 `zygisk/arm64-v8a.so`。
        编译链要 clang + Android sysroot（约 300–400MB，按需下载，不进主包；见 docs/06 的 M6-B）。
        没有工具链时，手机上那条路会明确说出缺什么，而不是静默失败。
    """.trimIndent()
}
