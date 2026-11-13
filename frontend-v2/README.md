# 当前正式前端

正式入口 index.html；Android 注入 android-back.js、production-read.js、production-market.js。appearance.js、initial-contributions.js 和 ui/ 为当前维护模块，变更时需保持与内嵌实现一致。

contracts/、tests/ 与 fixtures 保留作回归。历史预览和截图已删除；运行测试会重新生成 artifacts，构建会重新生成 dist。

设计/验收笔记归类至 ../docs/design/frontend，正式运维流程见 ../docs/maintenance/OPERATIONS.md。独立打开是 Mock 演示，不操作真实账户。

src/models/ui-models.d.ts 是仍被 contracts/backend-ports.d.ts 和契约检查引用的类型声明，必须保留；不能按编译残留删除。
