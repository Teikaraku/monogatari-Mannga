$ErrorActionPreference = 'Stop'
$MonogatariAndroidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function Initialize-MonogatariEnvironment {
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) {
        $TaskJava = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($TaskJava) { $env:JAVA_HOME = Split-Path (Split-Path $TaskJava.Source) }
        else {
            $TaskJdk = Get-ChildItem -LiteralPath 'C:/Program Files/Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') } | Sort-Object Name -Descending | Select-Object -First 1
            if (-not $TaskJdk) { throw 'Install JDK 17 or 21 and set JAVA_HOME.' }
            $env:JAVA_HOME = $TaskJdk.FullName
        }
    }
    $TaskSdk = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, 'D:/Android/Sdk', (Join-Path $env:LOCALAPPDATA 'Android/Sdk')) | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platform-tools/adb.exe')) } | Select-Object -First 1
    if (-not $TaskSdk) { throw 'Install Android SDK, then set ANDROID_HOME.' }
    $env:ANDROID_HOME = [IO.Path]::GetFullPath($TaskSdk)
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path (Split-Path $env:ANDROID_HOME) '.gradle' }
    $TaskSdkValue = $env:ANDROID_HOME.Replace('\', '/').Replace(':', '\:')
    [IO.File]::WriteAllText((Join-Path $MonogatariAndroidRoot 'local.properties'), "sdk.dir=$TaskSdkValue" + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}
