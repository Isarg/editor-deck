# Editor Deck

[English](README.md)

Editor Deck 是一个 IntelliJ Platform 插件，为 IDE 增加一个紧凑、可分组的打开编辑器列表，并支持从依赖 JAR 或 class 快速打开对应的 Maven/Gradle POM 文件。

本仓库基于 IntelliJ Platform Plugin Template 迁移整理，保留了模板风格的 Gradle 配置、资源包国际化、changelog 支持和 GitHub 项目结构。

## 功能

- 独立的 **Editor Deck** 工具窗口，用来管理当前打开的文件。
- 垂直分组模式：支持折叠分组、手动调整分组高度、拖拽排序、窄栏图标模式。
- 横向分组模式：复用 IntelliJ 原生 Tool Window Content tabs 和溢出下拉。
- 文件行支持关闭按钮、置顶状态、预览标签样式、多选操作、跨分组拖拽移动。
- 分组支持新建、重命名、解散、关闭、排序、移动位置。
- `Open Maven POM` 支持依赖 JAR 和 JAR 内 class，优先打开 JAR 内嵌 POM，并兼容 Maven 本地仓库和 Gradle cache。
- 项目级持久化：分组顺序、分组高度、折叠状态、置顶状态、横向分组选中项、显示偏好等都会保存。

## 构建

当前工程保留了 MVP 开发时使用的本地环境约束：

- Gradle Wrapper 通过腾讯云镜像下载 Gradle。
- Maven 依赖优先使用阿里云 Maven 镜像。
- 工程已适配模板中的较新 Gradle Wrapper 和 IntelliJ Platform Gradle Plugin 配置。
- 本地验证可以继续使用工作区 init 脚本：

```powershell
.\gradlew.bat -I D:\repo\gradle\init.gradle test verifyPluginProjectConfiguration buildPlugin --no-daemon
```

插件目标平台为 IntelliJ Platform `2025.1+`，使用 Kotlin 和 Java 21。

## 参考

Editor Deck 的实现参考了：

- [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)，作为 GitHub-ready 插件工程脚手架。
- [IntelliJ Community](https://github.com/JetBrains/intellij-community)，主要参考 Project 工具窗口、Tool Window Content UI、编辑器标签页和 Swing 交互模式。
- VS Code 打开编辑器列表和 Microsoft Edge 垂直选项卡的视觉与交互思路。

## 协作说明

这个插件由 Isarg 和 Codex 协作完成。

Isarg 负责产品方向：提出需求、体验反馈、边界场景发现、手工测试和验收检查。Codex 负责实现、重构、测试覆盖、Gradle 验证，以及根据反馈进行迭代修复。

## 状态

当前版本是围绕 Editor Deck 工具窗口和依赖 POM 查找能力完成的 MVP。Marketplace 发布元数据、签名和发布自动化可以在人工检查后继续完善。

## 开源协议

Editor Deck 使用 [MIT License](LICENSE) 开源。
