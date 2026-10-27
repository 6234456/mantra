# CI 备份下载异步清理收尾

> 2026-10-27；基于 `b6e6c4c`，沿用 Draft PR #15。

[远端 run 40314026685](https://github.com/6234456/mantra/actions/runs/40314026685)
的 verify job `121541991024` 已完成：前置质量／JVM／ABI／CLI／文档步骤通过；前端
56 文件／569 项断言也全部通过，但 Vitest 捕获一个未处理异常，因此前端步骤失败。
该 run 未执行后续浏览器步骤，不能据此判断前一轮浏览器重载修复的远端结果。

## 原因与修正

`TemplateRecoveryPanel` 下载 Blob 后在下一个任务中调用 `URL.revokeObjectURL`。
`TemplateDraftRecovery.test.tsx` 的完整下载测试等待了 FileReader，却没有等待 object URL
清理。两个任务没有规定完成先后：测试 afterEach 恢复全局 URL 后，延迟回调可能读取
jsdom 中没有 revokeObjectURL 的 URL，抛出 `TypeError: URL.revokeObjectURL is not a function`。
CI 将异常归到后一个 oversized-paste 测试，实际下载发生在前一个测试。

本轮只补齐完整下载测试的异步生命周期：在恢复 mock 前等待准确的 Blob URL 被撤销，
并断言只撤销一次。没有吞掉异常、关闭 Vitest 未处理异常检查、增加重试／全局超时，
也没有改动产品下载行为、恢复格式或模板 API。

## 验证

- 恢复专项：23 项通过，无未处理异常。
- 前端 check（格式／lint／类型）通过。
- 完整前端：56 文件／569 项通过，无未处理异常；日志 `/tmp/mantra-ci-full-tests.log`。
- 本轮未修改产品源码，不重复声明上一轮浏览器／JVM 结果；推送后跟进新 CI。
