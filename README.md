# Editor Deck

[简体中文](README.zh-CN.md)

Editor Deck is an IntelliJ Platform plugin that adds a compact, groupable open-editor deck to the IDE, plus quick access to dependency POM files from JARs and classes.

The repository is based on the IntelliJ Platform Plugin Template and keeps the template-style Gradle setup, resource bundle internationalization, changelog support, and GitHub-ready project layout.

## Features

- Dedicated **Editor Deck** tool window for open files.
- Vertical group mode with collapsible groups, manual group height resizing, drag sorting, and a narrow icon-only mode.
- Horizontal group mode backed by native IntelliJ tool window content tabs and overflow handling.
- File rows with close buttons, pin state, preview-tab styling, multi-select actions, and drag-and-drop movement between groups.
- Group actions for creating, renaming, dissolving, closing, sorting, and moving groups.
- `Open Maven POM` action for dependency JARs and classes, with support for JAR-embedded POM files, Maven local repositories, and Gradle cache layouts.
- Project-level persistence for group order, group sizes, collapsed state, pin state, selected horizontal group, and display preferences.

## Build

This project keeps the local development setup used while building the MVP:

- Gradle Wrapper downloads Gradle from the Tencent Cloud mirror.
- Maven dependencies resolve through the Aliyun Maven mirror first.
- The project keeps the template's newer Gradle wrapper and IntelliJ Platform Gradle Plugin setup.
- Local verification can use the workspace init script:

```powershell
.\gradlew.bat -I D:\repo\gradle\init.gradle test verifyPluginProjectConfiguration buildPlugin --no-daemon
```

The plugin targets IntelliJ Platform `2025.1+` and uses Kotlin with Java 21.

## References

Editor Deck was implemented with reference to:

- [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template), used as the GitHub-ready plugin project scaffold.
- [IntelliJ Community](https://github.com/JetBrains/intellij-community), especially the Project tool window, tool window content UI, editor tab, and Swing interaction patterns.
- Visual ideas from VS Code's open editors list and Microsoft Edge-style vertical tabs.

## Collaboration

This plugin was built collaboratively by Isarg and Codex.

Isarg drove the product direction: requirements, UX feedback, edge-case discovery, manual testing, and acceptance checks. Codex handled implementation, refactoring, test coverage, Gradle verification, and iterative fixes based on that feedback.

## Status

The current version is an MVP focused on the Editor Deck tool window and dependency POM lookup. Marketplace publishing metadata and release automation can be refined after manual review.

## License

Editor Deck is licensed under the [MIT License](LICENSE).
