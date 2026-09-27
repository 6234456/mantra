# 设计稿源文件

本目录保存工作台设计画布的 12 张画板（HTML 源文件，宽 1440 px）和画布布局文件 `canvas.json`。它们是 2026-09-26 版本的副本，在线画布见 https://claude.ai/artifact/STdcAnLZ5HeeHBVgME9rP4 （默认只有所有者可见）。

- **用途**：作为实现者的布局、视觉和交互参照。数据与行为以 [../contract.md](../contract.md) 和 [../ui-spec.md](../ui-spec.md) 为准，差异记录在界面规格 §8。
- **查看**：直接用浏览器打开 `.dc.html` 文件即可。文件首部引用的 `support.js` 属于设计工具，不在仓库中，缺少它不影响静态查看。
- **不要引用**：实现代码不得引用或复制这里的数值和文字，测试数据一律使用契约的 golden 文件。
- **修改流程**：设计先在在线画布中修改，再同步更新本目录，保证两边一致。

| 文件 | 画板 | 界面规格 |
| --- | --- | --- |
| `Main.dc.html` | 总览 · 主线 | §3、§4.1 |
| `Panel.dc.html` | 板块 · 罗盘与 Rechenweg | §4.2 |
| `Pruefpfad.dc.html` | 一个数的来源 | §4.3 |
| `Guenstiger.dc.html` | 择优 · 汇入三个步骤 | §4.4 |
| `Eingaben.dc.html` | 通用输入与录入 | §4.5 |
| `Parameter.dc.html` | 参数分层与 VZ 2026 比较 | §4.6 |
| `Datenquellen.dc.html` | 数据来源 · 导入映射 | §4.7 |
| `Export.dc.html` | Excel 导出（保留公式） | §4.8 |
| `Pruefungen.dc.html` | 校验 · 带位置的诊断 | §4.9 |
| `Anpassungen.dc.html` | 自定义 · slot 中的用户行 | §4.10 |
| `IAS36.dc.html` | 同一外壳 · IAS 36 矩阵 | §4.11 |
| `Navigation.dc.html` | 信息架构 | §4.12 |
