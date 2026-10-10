# 构建与预览说明

## 安卓版：先准备工具

Windows、Mac 和 Linux 都可以构建安卓版。首次运行需要下载开发工具和依赖，因此要保持网络连接。

需要安装：

- Android Studio，以及它提供的 Android SDK。打开 SDK Manager，安装 **Android 36**、**Build Tools 35.0.0** 和 **Platform Tools**。
- JDK 17 或 21。使用命令行构建时，把 `JAVA_HOME` 设置为 JDK 的安装目录。
- Python 3.10 或更新版本。准备模型的脚本只使用 Python 自带功能，不需要另外安装软件包。
- 手机或模拟器，系统为 Android 8.0 或更新版本。

## 第一次打开项目

在仓库根目录执行：

```text
python android/scripts/prepare-assets.py
```

脚本会从本仓库的版本附件下载 `monogatari-build-assets-0.9.zip`，校验后把文件放入 `android/app/src/main/assets`。再次运行会先检查已有文件；完整的文件不会重复下载。

如果已经手动下载了模型压缩包，可以这样使用：

```text
python android/scripts/prepare-assets.py --from-file "模型压缩包的完整路径.zip"
```

然后用 Android Studio 打开 `android` 文件夹，等待同步结束。选择手机或模拟器，点击 **Run**。运行的是完整的安卓应用，修改源码后再次运行，就会看到修改结果。

## 使用命令行构建

进入 `android` 文件夹，在 Windows 中运行：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Mac 或 Linux 中运行：

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

构建成功后，安装包位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

如果提示找不到 Android SDK，先用 Android Studio 打开项目，或者在 `android/local.properties` 中写入本机的 SDK 路径。这个文件因每个人的安装位置不同，不上传到仓库。

## 在 Windows 中使用网页控制预览

网页预览显示的是正在模拟器中运行的安卓应用。页面提供画面、点击和键盘控制，实际界面由 APK 绘制。

需要已经安装 Android SDK、安卓模拟器、JDK 和 Node.js。先构建安装包，再从仓库根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File android\scripts\start-preview.ps1
```

随后打开 `http://127.0.0.1:8765/`。脚本优先使用开发用模拟器 `emulator-5580`，不存在时会准备对应的模拟器。SDK 尚未安装的组件需要下载和足够的磁盘空间。

如果需要先编译修改再启动，可以在命令末尾加上 `-Rebuild`。如需使用已经连接的其他设备，加上 `-Device 设备编号`。设备编号可以通过 `adb devices` 查看。

## 正式签名的安卓安装包

正式安装包需要自己的签名文件。签名文件和密码不要放进仓库：

1. 用 JDK 自带的 `keytool` 生成签名文件，例如 `keytool -genkeypair -keystore monogatari-release.jks -alias monogatari -keyalg RSA -keysize 4096 -validity 36500`。
2. 复制 `android/keystore.properties.example` 为 `android/keystore.properties`，填写签名文件路径、别名，以及密码或密码文件路径。这个文件已被 `.gitignore` 排除。
3. 在 `android` 文件夹运行 `./gradlew assembleRelease`（Windows 为 `.\gradlew.bat assembleRelease`），安装包位于 `android/app/build/outputs/apk/release/app-release.apk`。

以后发布的每个版本都必须使用同一个签名文件，否则手机无法覆盖安装。请把签名文件和密码另外备份。

## 直接让 GitHub 构建

打开仓库的 **Actions**：

- **生成安卓安装包**：准备模型、编译应用、运行单元测试并检查代码，结果在该次运行的 **Artifacts** 中下载。它不会替代手机或模拟器上的实际阅读体验检查。
- **构建 iPhone 安装包**：在 GitHub 提供的 Mac 上编译 iPhone / iPad 版，生成未签名的 `monogatari-ios-版本号-unsigned.ipa`。修改 `Monogatari` 文件夹并推送到 `main` 时也会自动运行。

## iPhone / iPad 版

未签名的 IPA 需要用 AltStore、SideStore 等侧载工具以自己的 Apple ID 签名后安装。

在 Mac 上修改时，用支持项目所需系统版本的 Xcode 打开 `Monogatari.xcodeproj`，等待依赖下载完成后选择设备或模拟器运行。安装到个人 iPhone 时，在 Xcode 的签名设置中选择自己的开发者账号；应用标识为 `app.monogatari.Monogatari`。

Windows 无法运行 Xcode 或 iPhone 原生模拟器。安卓版中的页面调整也不会自动写入 Swift 页面；请按对应文件清单分别修改。

## 模型与许可

安卓源码使用 Kotlin 编写，页面使用 Jetpack Compose；iPhone 源码使用 Swift 编写，页面使用 SwiftUI 和 UIKit。普通页面调整可以先从 [页面修改指南](EDITING.md) 开始，不需要先理解全部底层实现。

识别和图片增强模型的文件路径、大小、校验值记录在 `android/asset-manifest.json`。来源和许可保存在 `android/app/src/main/assets/licenses`、`dictionary` 与 `upscale` 文件夹中。`android/model-conversion/README.md` 另有自定义图片增强模型的转换说明。

如需用模型转换工具重新生成图片增强模型，运行 `python android/scripts/prepare-assets.py --with-original-models`。它会额外下载 `monogatari-model-sources-0.9.zip`，准备 Core ML 原始模型。普通安卓构建和阅读不需要这一步。
