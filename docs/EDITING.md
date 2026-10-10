# 页面修改指南

## 先修改安卓，再对应修改 iPhone

在 Windows 上，先运行安卓版，修改一个页面，再重新运行检查。确认满意后，再找到 iPhone 项目中相同功能的页面进行修改。两端是两个原生项目，所以布局代码需要分别维护。

| 页面或内容 | 安卓文件 | iPhone 对应目录 |
| --- | --- | --- |
| 底部导航、页面切换 | `ui/MonogatariApp.kt` | `Monogatari/App`、`Monogatari/Features` |
| 书架、作品分组、筛选 | `ui/LibraryWorkbenchScreens.kt` | `Monogatari/Features/Library` |
| 书库、分类、排行榜 | `ui/BookstoreScreens.kt` | 原版浏览页面位于 `Monogatari/Features/Browse`；物语书库需要在 iPhone 端单独实现。 |
| 漫画详情、章节列表 | `ui/MangaScreens.kt` | `Monogatari/Features/Manga` |
| 阅读界面 | `ui/ReaderScreen.kt` | `Monogatari/Features/Reader` |
| 设置和关于页面 | `ui/SettingsScreens.kt`、`ui/OriginalSettingsScreen.kt` | `Monogatari/Features/Settings` |
| 颜色和通用控件 | `ui/MonogatariTheme.kt`、`ui/Components.kt` | `Monogatari/Features` 下的通用页面和控件 |

表中安卓文件的共同起点是 `android/app/src/main/java/app/monogatari/android/`。如果不知道某个功能在哪，可以在编辑器中搜索页面上显示的文字，或查看 `android/MIGRATION_MATRIX.json`。

## 修改名称、项目链接和图标

- 应用名称在 `AppBrand.kt` 和 `android/app/src/main/res/values/strings.xml` 中。
- “关于”页面的项目链接使用 `AppBrand.REPOSITORY_URL`，当前指向本仓库。
- 白底粉红“漫”字图标原稿在 `docs/assets/monogatari-icon.png`。
- 安卓图标图片在 `android/app/src/main/res/drawable-nodpi/monogatari_icon.png`。`mipmap-anydpi-v26/ic_launcher.xml` 和 `drawable/monogatari_icon_foreground.xml` 负责让它适应圆形和圆角桌面图标。

安卓的安装识别名仍保留 `app.monogatari.android`，方便已有安装直接升级。这个名称与桌面显示的“物语”是两件事。如果修改安装识别名，安卓会把它当作另一个应用，旧安装的数据不会自动转移。

## 修改书库内容和分类

`BookstoreScreens.kt` 决定书库中的按钮、分类选择、作品排列和各个列表的展示方式。`sources/CopySourceClient.kt` 负责请求漫画数据、整理详情、读取章节和图片地址。

页面样式和提供方的数据请求分别放在这两个文件中。调整分类展示时，先修改页面；提供方更换地址或返回格式时，再修改数据读取文件。书库显示自己的原生界面，不直接嵌入整个网站。

## 修改阅读和书架设置

`OriginalSettingsScreen.kt` 根据 `android/app/src/main/assets/ios/settings-schema.json` 显示原版设置。这个清单由 `android/scripts/import-ios-settings.mjs` 从 Swift 设置中生成。

如果需要重新导入原版设置，在仓库根目录执行：

```text
node android/scripts/import-ios-settings.mjs
```

脚本会继续排除用户明确删除的 iCloud、来源列表管理和手动章节历史迁移，避免重新导入后又出现这些入口。阅读和书架的设置仍然按原分组显示。

## 检查修改结果

普通文字、颜色和布局调整，可以通过重新运行应用检查。涉及章节读取、进度保存、备份、下载或数据转换时，也需要运行相关测试，避免页面看起来正常但实际记录出错。

完整命令和安装包位置见 [构建与预览说明](BUILDING.md)。
