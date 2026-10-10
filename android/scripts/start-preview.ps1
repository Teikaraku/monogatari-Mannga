param([string]$Device = 'emulator-5580', [int]$Port = 8765, [switch]$Rebuild)
. (Join-Path $PSScriptRoot 'environment.ps1')
Initialize-MonogatariEnvironment
$TaskAdb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$TaskEmulator = Join-Path $env:ANDROID_HOME 'emulator/emulator.exe'
$TaskApk = Join-Path $MonogatariAndroidRoot 'app/build/outputs/apk/debug/app-debug.apk'
if ($Rebuild -or -not (Test-Path -LiteralPath $TaskApk)) { & (Join-Path $PSScriptRoot 'build.ps1') -Tasks @('assembleDebug') }
$TaskDevices = & $TaskAdb devices
if (-not ($TaskDevices -match "^$([Regex]::Escape($Device))\s+device")) {
    if ($Device -ne 'emulator-5580') { throw "Connect the device $Device first." }
    $env:ANDROID_AVD_HOME = Join-Path (Split-Path $env:ANDROID_HOME) 'avd'
    New-Item -ItemType Directory -Path $env:ANDROID_AVD_HOME -Force | Out-Null
    $TaskAvd = Join-Path $env:ANDROID_AVD_HOME 'MonogatariPreview.avd'
    if (-not (Test-Path -LiteralPath $TaskAvd)) {
        $TaskImage = 'system-images;android-35;default;x86_64'
        $TaskSdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/sdkmanager.bat'
        $TaskAndroidCli = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/android.exe'
        if (Test-Path -LiteralPath $TaskAndroidCli) { & $TaskAndroidCli "--sdk=$env:ANDROID_HOME" sdk install $TaskImage }
        else { & $TaskSdkManager "--sdk_root=$env:ANDROID_HOME" $TaskImage }
        if ($LASTEXITCODE -ne 0) { throw 'SDK image installation failed. Review SDK licenses and disk space.' }
        'no' | & (Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/avdmanager.bat') create avd --name MonogatariPreview --package $TaskImage --path $TaskAvd
        if ($LASTEXITCODE -ne 0) { throw 'Emulator creation failed.' }
    }
    $TaskRegistration = Join-Path $env:ANDROID_AVD_HOME 'MonogatariPreview.ini'
    if (-not (Test-Path -LiteralPath $TaskRegistration)) {
        # avdmanager may have registered an existing checkout under the user's default AVD home.
        [IO.File]::WriteAllText($TaskRegistration, "avd.ini.encoding=UTF-8`npath=$TaskAvd`ntarget=android-35`n", [Text.UTF8Encoding]::new($false))
    }
    Start-Process -FilePath $TaskEmulator -ArgumentList @('-avd', 'MonogatariPreview', '-port', '5580', '-no-window', '-no-audio', '-no-boot-anim', '-gpu', 'swiftshader_indirect') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $env:ANDROID_AVD_HOME 'preview.log') -RedirectStandardError (Join-Path $env:ANDROID_AVD_HOME 'preview-error.log') | Out-Null
    $TaskDeadline = [DateTime]::UtcNow.AddMinutes(4)
    while ([DateTime]::UtcNow -lt $TaskDeadline) {
        if ((& $TaskAdb -s $Device shell getprop sys.boot_completed 2>$null) -match '1') { break }
        Start-Sleep -Seconds 2
    }
    if ((& $TaskAdb -s $Device shell getprop sys.boot_completed) -notmatch '1') { throw 'Emulator startup timed out. Check hardware virtualization and preview-error.log.' }
}
& $TaskAdb -s $Device install -r $TaskApk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
& $TaskAdb -s $Device shell am start -n 'app.monogatari.android/.MainActivity'
$env:AIDOKU_ADB = $TaskAdb; $env:AIDOKU_DEVICE = $Device; $env:AIDOKU_PREVIEW_PORT = "$Port"
$TaskNode = (Get-Command node.exe -ErrorAction Stop).Source
try { Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -TimeoutSec 2 -UseBasicParsing | Out-Null; $TaskRunning = $true } catch { $TaskRunning = $false }
if (-not $TaskRunning) {
    Start-Process -FilePath $TaskNode -ArgumentList @((Join-Path $PSScriptRoot 'preview-server.mjs')) -WindowStyle Hidden -WorkingDirectory $MonogatariAndroidRoot -RedirectStandardOutput (Join-Path $MonogatariAndroidRoot 'preview-server.log') -RedirectStandardError (Join-Path $MonogatariAndroidRoot 'preview-server-error.log') | Out-Null
}
Write-Host "Native APK preview: http://127.0.0.1:$Port/"
Write-Host 'Screens and input are streamed from the real Android emulator.'
