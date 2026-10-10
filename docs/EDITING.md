# 页面修改指南

两端是两个原生项目，布局代码分别维护。修改一个页面后，在另一端的对应位置做同样的调整。

| 页面或内容 | 安卓文件 | iPhone 目录 |
| --- | --- | --- |
| 底部导航、页面切换 | `ui/MonogatariApp.kt` | `Monogatari/App/Root` |
| 书架、作品分组、筛选 | `ui/MonogatariApp.kt`、`ui/LibraryWorkbenchScreens.kt` | `Monogatari/Features/Library` |
| 书库、分类、排行榜 | `ui/BookstoreScreens.kt` | `Monogatari/Features/Bookstore` |
| 漫画详情、章节列表 | `ui/MangaScreens.kt` | `Monogatari/Features/Manga` |
| 阅读界面 | `ui/ReaderScreen.kt` | `Monogatari/Features/Reader` |
| 设置和关于页面 | `ui/SettingsScreens.kt`、`ui/OriginalSettingsScreen.kt` | `Monogatari/Features/Settings` |
| 菜单 | `ui/NativeMenu.kt` | 系统菜单 |
| 底部面板 | `ui/OptionSheet.kt` | 系统面板 |
| 颜色和通用控件 | `ui/MonogatariTheme.kt`、`ui/Components.kt` | `Monogatari/App/Common` |

表中安卓文件的起点是 `android/app/src/main/java/app/monogatari/android/`。不知道某个功能在哪时，可以在编辑器中搜索页面上显示的文字。

## 平板和横屏

两端按窗口宽度调整排版：手机保持原有布局；平板和横屏时，书库横幅并排显示、推荐网格改为六列、排行榜按列显示，设置和列表页面在中间保持适合阅读的宽度。安卓的宽度规则在 `ui/Components.kt`（`windowWidth`、`centeredInset`、`readableWidth`），iPhone 版在 `BookstoreView.swift` 的 `BookstoreLayout`。

## 名称、项目链接和图标

- 应用名称在 `AppBrand.kt`、`android/app/src/main/res/values/strings.xml` 和 iPhone 的 `InfoPlist.strings` 中。
- “关于”页面的项目链接使用 `AppBrand.REPOSITORY_URL`。
- 图标原稿在 `docs/assets/monogatari-icon.png`；安卓图标在 `android/app/src/main/res/drawable-nodpi/monogatari_icon.png`，iPhone 图标在 `Monogatari/App/Resources/`。

安卓包名是 `app.monogatari.android`，iPhone 应用标识是 `app.monogatari.Monogatari`。修改它们会被系统当作另一个应用，已有数据不会自动转移。

## 书库内容和分类

`BookstoreScreens.kt` 决定书库中的按钮、分类选择和各个列表的展示方式，`sources/CopySourceClient.kt` 负责请求漫画数据、整理详情、读取章节和图片地址。调整展示时修改页面文件；提供方更换地址或返回格式时修改数据文件。

## 阅读和书架设置

`OriginalSettingsScreen.kt` 根据 `android/app/src/main/assets/ios/settings-schema.json` 显示设置。这个清单由 `android/scripts/import-ios-settings.mjs` 从 iPhone 版的设置定义生成：

```text
node android/scripts/import-ios-settings.mjs
```

## 检查修改结果

文字、颜色和布局调整可以重新运行应用检查。涉及章节读取、进度保存、备份、下载或数据转换时，也要运行相关测试。完整命令见 [构建说明](BUILDING.md)。
