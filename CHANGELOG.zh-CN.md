<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Editor Deck 更新日志

[English](CHANGELOG.md)

## [Unreleased]

## [0.1.2] - 2026-05-31
### Changed
- 开启 JVM default no-compatibility 模式，移除 Marketplace 检测到的 ToolWindowFactory 默认方法兼容 stub 警告。
- 将内部 ToolWindowManagerListener 状态变更回调替换为公开的状态变更重载。
- 将已废弃的 Maven POM 选择对话框替换为 popup 选择器。

## [0.1.1] - 2026-05-31
### Changed
- 新增 IntelliJ Platform 插件签名和发布配置，用于生成已签名的发布包。
- 合并 Kotlin Gradle 插件 2.3.21 更新后重新构建发布版本。

## [0.1.0] - 2026-05-31
### Added
- 将 Editor Deck MVP 迁移到 IntelliJ Platform Plugin Template 工程结构。
- 新增 Editor Deck 工具窗口，支持打开编辑器的分组导航。
- 新增依赖 JAR/class 的 `Open Maven POM` 能力，支持 JAR 内嵌 POM、Maven 本地仓库和 Gradle cache。
