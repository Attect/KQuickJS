package app.muka.project.kquickjs.bridge

import app.muka.project.kquickjs.JSContext

/* =====================================================================
 * platform 命名空间完整示例：演示宿主能力注入的推荐组织方式
 *
 * - platform.log.info(msg)：日志输出（sink 由宿主注入，可重定向到
 *   测试执行代理程序的日志通道）；
 * - platform.now()：当前 epoch 毫秒（演示 Long 返回值的自动转换）；
 * - platform.http.get(url, timeoutMs)：模拟 HTTP GET（演示"阻塞调用约定"：
 *   阻塞等待类宿主函数自带 timeout 参数，超时返回 {"timeout": true} 标记值；
 *   本示例不发真实网络请求，生产宿主应替换为真实实现）。
 * ===================================================================== */

object JSPlatformExample {

    /**
     * 安装 platform 命名空间到 [bridge]。
     * [logSink] 为日志汇聚函数（默认输出到 stdout）；
     * [httpHandler] 为 HTTP 处理函数（默认返回模拟响应），签名：
     * (url, timeoutMs) → 响应 Map。超时应返回含 "timeout"=true 的 Map，
     * 与"阻塞调用约定"推荐的超时标记值模式一致。
     */
    fun install(
        bridge: JSBridge,
        logSink: ((String) -> Unit)? = null,
        httpHandler: ((url: String, timeoutMs: Int) -> Map<String, Any?>)? = null
    ) {
        val sink: (String) -> Unit = logSink ?: { line -> println(line) }
        val handler: (String, Int) -> Map<String, Any?> = httpHandler ?: { url, _ ->
            /* 模拟响应：生产宿主应替换为真实 HTTP 客户端实现 */
            linkedMapOf(
                "status" to 200,
                "headers" to linkedMapOf("content-type" to "application/json"),
                "body" to """{"ok":true,"url":"$url"}"""
            )
        }

        bridge.namespace("platform").apply {
            /* platform.log 命名空间：log.info 为通用形态注册的示范 */
            namespace("log").apply {
                function("info", listOf("msg")) { _, args ->
                    sink("[info] ${args.string("msg")}")
                    null /* 返回 null → JS undefined */
                }
            }

            /* platform.now：0 参数定型签名，Long 返回值自动转 JS number */
            function("now") { _ -> System.currentTimeMillis() }

            /* platform.http.get：任务示例签名 (ctx, url: String, timeoutMs: Int)；
             * 转换失败时桥接层给出可定位的类型错误（函数路径 + 参数名 + 实际类型） */
            namespace("http").apply {
                function("get", "url", "timeoutMs") { _, url, timeoutMs ->
                    if (timeoutMs <= 0) {
                        /* 参数校验失败：抛出桥接层异常，JS 侧收到可定位的 TypeError */
                        throw JSBridgeException(
                            JSErrorKind.TYPE_ERROR,
                            "platform.http.get 的参数 1 'timeoutMs' 应为正整数，实际为 $timeoutMs"
                        )
                    }
                    val start = System.currentTimeMillis()
                    /* 演示约定：阻塞等待类宿主函数自带 timeout 参数（真实实现中
                     * 应在等待循环里检查超时并返回超时标记值，见桥接层文档） */
                    val response = handler(url, timeoutMs)
                    linkedMapOf(
                        "status" to (response["status"] ?: 0),
                        "headers" to (response["headers"] ?: emptyMap<String, Any?>()),
                        "body" to (response["body"] ?: ""),
                        "elapsedMs" to (System.currentTimeMillis() - start)
                    )
                }
            }

            /* 常量数据属性：版本信息 */
            property("version", "kotlin-bridge-demo-1.0")
        }
    }

    /** 便捷入口：创建桥接层并安装 platform 示例 */
    fun install(ctx: JSContext): JSBridge {
        val bridge = JSBridge(ctx)
        install(bridge)
        return bridge
    }
}
