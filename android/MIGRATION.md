# 物语原生 Android 移植与验证说明

> 当前交付：安卓版名称为“物语”，白底粉红“漫”字图标；Android iCloud、手动章节历史迁移、来源列表管理、Discord、Ko-Fi和关于页编译编号按用户要求删除。书库和在线阅读已修通，阅读与书架设置保留原分组和控件。最新APK通过229项单元测试、Android15的41项和Android8兼容范围的35项设备测试，lint为0错误、192项警告。项目链接指向 https://github.com/Teikaraku/monogatari-reader 。公开仓库的简明检查摘要见 `../docs/VERIFICATION.md`；下文完整过程日志保存在开发机，不随公开源码上传。

原 iOS 项目保留，新增独立 Kotlin / Jetpack Compose Android 项目。业务逻辑、设置键、语言和资源按原源码迁移；Windows 预览显示真实模拟器中的 APK。两个平台分别维护页面，修改 Android 页面不会自动改写 Swift。

## 用户定制：书库与拷贝漫画

Android 第二个主入口改为“书库”，使用本项目的白色与粉色主题、原生 Compose 控件。首页含漫画推荐横幅、新书入库、热门更新、今日热门、完结佳作与精选专题；内部提供发现、分类、排行榜、完结、专题和搜索。排行榜支持日/周/月/总榜及少年向/少女向，漫画目录支持更新/人气排序。图源管理入口保留，可以停用、卸载或重新添加拷贝图源。

实现文件为 `ui/BookstoreScreens.kt`、`sources/CopySourceClient.kt` 和 `sources/CopySourceBackend.kt`。内置分类表包含官网实际提供的68个分类。漫画、榜单、专题及分页内容由 JSON 接口提供，专题简介中的格式片段转换为原生文字；页面没有嵌入整站。原版 iOS 浏览页没有同步修改。

当前只读实测成功的接口包括首页、漫画目录、官网 JSON 搜索、排行榜、完结集合、专题列表、专题资料、专题漫画分页，以及漫画资料、章节目录和章节图片。浏览数据使用 `https://api.copy2000.online`，详情与阅读使用网页数据服务 `https://www.manga2026.xyz`（接口兼容版本 `2024.4.28`），官网与搜索入口为 `https://www.2026copy.com/`；两组地址和版本均可在图源设置中修改。生产请求使用自己的 `Monogatari/0.9` 客户端名称，没有伪装官方客户端或携带他人的账号凭证。第三方站点未提供稳定性承诺，域名和接口可能变化。

**已修复的接口问题：** 移动端详情和 `chapter2` 路径曾返回 `results:null` 或升级客户端提示，这并不能证明公开网页数据接口不可用。改用网页数据服务的 `comic2`、分组章节与 `chapter` JSON 路径后，实际取得《與她女友的不純初戀》的资料、6章目录和第1章43张图片地址。原失败响应保留作历史诊断。所有页面使用原生控件，阅读使用现有原生阅读器；无需加载整站。付费、锁定或需账号权限的章节仍遵守服务端权限，不用假图片代替。详见 `verification/bookstore-source-review.json` 和 `verification/copy-source/` 的实际响应。

按最新界面要求，书库首页使用56dp工具栏，漫画详情使用48dp返回/操作栏；删除首页的大“书库”标题及详情中重复的大漫画名，只保留封面旁的漫画名。

## 安装和预览

安装文件：`android/deliverables/物语-Android-0.9.apk`。包名 `app.monogatari.android`，版本 `0.9` / code `3`，最低 Android 8.0（API26），target35，compile36。APK 使用 Android debug 签名，适合模拟器和开发测试；正式商店发布需项目所有者的发布签名。完整大小、SHA-256、测试结果见 `verification/final-verification.json`，签名见 `verification/project-publish-apk-signature.txt`。

```powershell
# 从 D:\Aidoku-main 运行
powershell -ExecutionPolicy Bypass -File android\scripts\build.ps1
powershell -ExecutionPolicy Bypass -File android\scripts\start-preview.ps1 -Rebuild
```

预览入口 `http://127.0.0.1:8765/`。页面使用 ADB 取得真实屏幕，并向真实 APK 发送点击、拖动、键盘和 Unicode 粘贴。当前准备环境为 Java21、`D:\Android\Sdk`、`D:\Android\.gradle`。预览脚本检查 AVD、安装 APK、启动本机服务。关闭电脑后需重新启动该脚本。

## 原版内容与实现落点

逐文件清单 `MIGRATION_MATRIX.json` 覆盖当前 467 个应用 Swift 文件及14个测试/支持文件，总481个；每条记录源文件哈希、Android 实现模块、测试证据和差异。文件映射不等于每个 Swift 方法已逐行为或逐像素证明一致。

原资源导入：41种语言、813条英文基准键、34个图片资源。原默认值109项、设置 schema 94条；按用户要求删除Android的iCloud相关项目后，保留107项默认值和93条设置 schema。保留原白色/系统/深色外观与粉色强调色；原资源不重写。新增 Android 文案采用英文/中文回退，并未宣称都有41种翻译。

下表Java/Kotlin路径相对 `android/app/src/main/java/app/monogatari/android/`；模型工具路径相对 `android/`。

| 功能 | Android 实现 |
| --- | --- |
| 五个主入口、独立导航和页面恢复 | `MonogatariViewModel.kt`、`ui/MonogatariApp.kt`、`ui/CallerContextPopup.kt` |
| 书架分类、筛选、网格/列表、锁定、更新、选中范围与边缘滚动 | `library/`、`ui/MangaCollectionSelection.kt`、`ui/LibraryWorkbenchScreens.kt` |
| 漫画资料、章节/语言/翻译组、阅读进度、孤立下载、迁移 | `ui/MangaScreens.kt`、`reader/ChapterListing.kt`、`library/SourceMigrationJournal.kt` |
| 持久下载、暂停/继续/取消、Wi-Fi约束、完整CBZ导出 | `downloads/` |
| 原文件名规则、本地ComicInfo、ZIP/CBZ/文本/EPUB、页说明 | `local/`、`data/ArchiveImporter.kt`、`local/ArchiveXml.kt` |
| 左右/竖向/连续/webtoon、双页/分半、缩放、裁边、亮度/滤镜、预取、自动滚动、键盘与音量 | `reader/`、`ui/ReaderScreen.kt` |
| 真实现代/legacy WASM、图源安装更新、首页/列表/搜索/设置、JS/DOM/cookies/POST | `sources/`、`ui/SourceRuntimeScreens.kt`；详细合同见 `SOURCE_RUNTIME.md` |
| Komga / Kavita / Suwayomi、八种追踪器与OAuth/PKCE/持久同步重试 | `sources/BuiltInBackend.kt`、`tracking/` |
| 原格式备份、选择恢复、未知字段、源/媒体/词典/模型、失败回滚 | `backup/`、`ui/CompleteBackupsScreen.kt` |
| Yomitan、词频/音调、原Hoshi规则、图上OCR、富释义、词汇上下文与原页跳转、Anki | `dictionary/`、`ui/DictionaryScreens.kt`、`ui/VocabularyDetails.kt` |
| 两个原AI模型、实际ONNX推理、目录更新/源导出/自定义转换导入 | `reader/Upscale*`、`model-conversion/` |

每本漫画沿用原 `source.manga` 设置后缀；内部 `source::manga` 转换仅处理第一个分隔符。Android独立扩展 JSON 保存未知字段，并随备份/迁移处理。阅读器保留整图裁边→下采样或AI→分半顺序；下采样优先于AI。实际页码、字符锚点和滚动位置分别保存；普通打开已完成章节回到开头，词汇明确指定原页时优先跳转。

## 已执行的验证与证据

最终统一构建成功：229/229单元测试、Android15完整41/41设备方法、Android8当前provider支持的35/35设备方法全部通过，无失败/跳过/进程崩溃。lint为0错误、192项警告，主要为KTX风格、公开API feature调用提示和依赖/配置建议；没有把零错误写成零警告。权威结果、每类unit数量及安装包哈希见 `verification/final-verification.json`。

- 主机构建：`verification/project-publish-build.log`、`app/build/test-results/testDebugUnitTest/TEST-*.xml`、`app/build/reports/lint-results-debug.xml`。
- Android15 / SDK35 完整41方法全部通过（`verification/project-publish-native35.log`）；Android8 / SDK26 当前provider可执行35方法全部通过（`verification/project-publish-native26.log`）。后者6个现代浏览器方法单独列为环境能力缺口，没有把跳过算作通过。
- 真实ICU完整335个原文件名示例；真实CBZ/EPUB XML、资源、UTF8/UTF16元数据、安全DTD拒绝；真实原WASM两ABI。
- 实际SAF导入、四页RTL/宽图分半、页说明/Reload、详情编辑与进程重启保留。公共900×1350PNG的1,215,000个RGBA像素全部与原Green页相同。原图Latin/日语OCR有实际识别证据。
- EPUB原CSS/ruby/table12/内嵌PNG、章节链接与返回、实际3/14页、20%重启恢复和同段落位置已验证。旧版本页数格式/完成章节重开的问题在新APK实际复验为 `Page 2` 和1/4。见 `verification/ui-fixtures/evidence/actual-ui26-acceptance.json`、`repaired-ui26-validation.json`；两份报告分别记录自己的安装哈希，不混作同一版本。
- 原生Yomitan富释义、实际导入/查询/保存以及SAF APKG/CSV导出有XML/截图和实际文件校验。词汇实际截图/来源保存、编辑Cancel/Done、原页与原系列跳转已完成；实际SAF导出的APKG/CSV为3词、3卡和1PNG，图片10,240个RGBA像素与原页完全相同。见 `verification/ui-fixtures/evidence/vocabulary-native-ui-validation.json`、`dictionary-media-export-validation.json`、`dictionary-context-pixel-validation.json`，旧两词报告仍单独保存。
- 通用模型转换器82项主机测试全通过。两个原官方模型在相同确定输入下，对两个独立portable重建图的完整输出张量比较均 `maxAbsDifference=0`、exactEqual；没有与Apple CoreML runtime逐像素比较。见 `verification/model-converter/latest-tests.log`、`official-graph-comparison.json`。
- APK签名和64位ELF LOAD/存储ZIP偏移的16KiB结构检查通过；这不等于已在16KiB Android设备执行。完整许可/模型来源与hash见 `verification/ocr-language-provenance.md`、`app/src/main/assets/licenses/`。

测试数据使用清楚标注的原创合成书籍、词典和图片。原应用数据没有通过 `pm clear` 清空；自动设备夹具使用独立文件、数据库和偏好上下文。Android26首次系统权限窗口的SystemUI空引用使用保数据重启恢复，不计为Aidoku应用崩溃。

## 无法直接照搬或尚需外部条件的部分

1. **Apple原生框架与云容器授权。** CoreData/UIKit/SwiftUI/WebKit/Vision/CoreML等不能直接作为Android运行库，分别以SQLite/Compose/Chromium/OCR/ONNX实现原业务。`NSPersistentCloudKitContainer`不能在Android直接运行；仓库未提供原私有容器owner签发的Web API token、认证/origin配置和跨平台镜像schema。Android端已按用户要求删除iCloud同步功能；原iOS实现保留。当前可跨两端交换完整原格式备份。[Apple CloudKit Web Services](https://developer.apple.com/library/archive/documentation/DataManagement/Conceptual/CloudKitWebServicesReference/SettingUpWebServices.html)、[CloudKit JS](https://developer.apple.com/documentation/CloudKitJS)。
2. **Apple字体、图标和系统渲染。** 仓库内图片保留，Apple专有字体/SF Symbols没有无授权打包；Android使用自身字体与图标。排版、原生控件、动画内核、颜色/缩放/JPEG和Chromium文本布局不能保证Apple逐像素一致。裁边及分半算法已移植；模型FP32执行、Apple中间FP16舍入和Vision颜色转换仍可能不同。[Apple字体](https://developer.apple.com/fonts/)、[SF Symbols](https://developer.apple.com/design/human-interface-guidelines/sf-symbols)。
3. **自定义CoreML的输入条件与转换覆盖。** Android不直接执行Apple模型字节，需Windows转换后导入。两个官方模型已经真正转换并运行。仅有compiled `.mlmodelc` 且没有可恢复源图/权重，或仅有opaque custom kernel名称且没有计算实现，不能重建。公开NN/MIL有157/165个dispatch路线，但不是每个参数组合已数值证明；固定输入/中间rank、ROI/resample/scatter/NMS/recurrent等guard和未解析参数属于转换实现覆盖边界，不能冒充平台不可能。完整逐项guard/节点诊断在 `model-conversion/README.md`、`verification/model-converter/operator-coverage.json`；原RGB放大器未提供的MLState/额外必需输入不属于其可调用合同。
4. **浏览器provider与不可见请求体。** SDK35的实际WebView124已验证独立profile/document-start与fetch/XHR/form/sendBeacon桥；当前SDK26批准provider为Google Chrome69，缺少6项现代能力。已检查的完整官方新provider要求min29，标为min26的小包是stub；有效AOSP124包还涉及系统provider allowlist/签名条件。这是当前设备条件，不能把所有Android26都判为不可行。service-worker/内核内部POST body没有公开 `WebResourceRequest`读取接口；普通页面POST桥已实现。详见 `SOURCE_RUNTIME.md`。
5. **OCR与词库。** 原Auto/strict来源语言策略保留，bundled MLKit五脚本与官方离线Tesseract Arabic/Cyrillic/Greek/Hebrew/Thai/Devanagari均有真实运行。识别结果与Apple Vision/LiveText不保证相同；支持脚本不意味着附带各语言词库。模型/版权/六份68,664,283字节payload/hashes完整记录。API31+词典透明度使用公开Window背景模糊与系统能力监听；API26-30或系统禁用/不支持时保留可读不透明背景。[Android Window blur](https://developer.android.com/reference/android/view/Window#setBackgroundBlurRadius(int))。
6. **真实账号/服务器和iOS执行。** 八追踪器与内置服务的协议、认证和重试有实现/回归，用户账号、服务配置或OAuth owner凭据未提供，不伪称已登录真实账号。Windows没有Xcode/iOS runtime；Swift原版bug修复及26个新增/补充测试方法未在此编译执行，Android通过是独立证据。

白色/系统与深色外观已在真实APK中操作并检查稳定截图，最后恢复白色系统外观和标准布局，证据为 `verification/ui-fixtures/evidence/final-theme-validation.json`。源码及测试过程中的已解决失败保留在历史诊断日志中，不作为当前限制。

此外，当前浏览器自动化对localhost预览的 `getTab` 因URL安全策略被拒绝，未改用其他浏览器/CDP/HTTP写操作绕过。页面/PNG只读HTTP检查与真实Android原生交互分别验收；这项限制不阻止用户自行打开已提供预览。

## 同步修改两端

先在真实Android APK中修改并检查页面，再按 `MIGRATION_MATRIX.json` 找到对应Swift页面与模块修改iOS。原设置键、资源键、备份字段和图源协议作为共同合同；两端界面源代码独立，iOS变化最终需Mac/Xcode构建与设备验证。
