# Gself 项目规则

本文件只记项目事实、计数口径与必要限制。全局工程规范以 Sumicya/selfs 默认分支的
GLOBAL.md 为准，**上次同步 = 第二十二版（2026-10-06）**。与全局规范冲突时以 GLOBAL.md 为准。

## 项目事实

- Gself：LSPosed 纯 Hook 模块，applicationId `sumicya.gself`；内部包名与入口类
  `sumicya.fcmself.XposedMain` 不变，CI 的 R8 入口校验查这个类名。
- 构建：`./gradlew test assembleDebug`，产物 `app/build/outputs/apk/debug/app-debug.apk`。
- CI（`.github/workflows/android.yml`）：零 secrets，push 与 PR 都出包；跑单测 → 编译 debug →
  校验 R8 入口类 → 上传 `Gself-<五段版本>.apk`。CI 只出包不发版：不创建 Release、正式 tag
  或 Release asset；正式发版须主人事先对项目、版本和触发条件明确授权，获准人工发版后
  按数量规则清理。
- 本沙箱无 JDK / Android SDK：编译验证只能由 CI 完成，汇报须如实区分本地与远端状态。

## 版本计数口径（五段：yy.m.d.当日序号.总序号，日界 UTC+8）

- 来源：android.yml 的真实运行记录（GitHub API 可查、可追溯），锚定本次运行的创建时间
  与 run id，不取查询时的最新运行冒充本次；同次重试复用同一版本，不重复加号、不回退。
- 触发事件：push 与 PR 同一序号池，都消耗当日序号与总序号。
- 失败运行：同样计数（序号按运行创建记录，失败的号不回收，保证单调递增）。
- 本地构建：非发行开发版，版本回落 `0.0.0.0.<fcmself.buildNumber>`，不参与发行序号；
  正式产物只认 CI 出包。（第二十二版要求产物五段与构建地点无关；主人已裁定：本地
  构建不计入正式序号，正式产物只认 CI 出包。）
- 映射：versionCode = 总序号；产物文件名与 artifact 名统一 `Gself-<五段版本>`。
- 版本数据缺失、取数失败或校验不通过时停止出包与上传（此前存在版本为空仍上传
  `Gself-` 空名产物的运行，已在 workflow 中堵死）。

## 数量清理

- Actions artifact 全项目合计保留最近 **5 个**（跨分支、跨触发事件，含改名前遗留名）：
  android.yml 的 cleanup job 在上传成功后执行，完整分页、按创建时间排序，删除前在
  job summary 列出保留与删除清单，只清理归属本项目的对象。另设 `retention-days: 5`
  作时间兜底，不代替数量清理。
- Release 只保留最近 **1 个**（含关联 tag）：本仓库当前没有 Release，无清理对象；
  日后获准人工发版后的清理按 GLOBAL.md 执行。

## 必要限制

- 任何本轮本地改动必须提交并推送到当前远端分支；任务结束不留未提交、不留已提交未推送。
- CI 只读原则：构建与检查只给 `contents: read`；写权限只给清理任务（`actions: write`），
  且不执行未信任的 fork PR 代码——fork PR 的 token 无写权限，清理在其上无法执行，
  由后续 push 运行补清，属环境限制，如实报告。
- 对外动作（正式发版、删除远程分支、删除标签等）不得由普通 CI 触发器隐式执行。
