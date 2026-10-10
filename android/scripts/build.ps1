param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
. (Join-Path $PSScriptRoot 'environment.ps1')
Initialize-MonogatariEnvironment
$TaskDigest = [Security.Cryptography.SHA256]::Create()
$TaskLockName = [BitConverter]::ToString($TaskDigest.ComputeHash([Text.Encoding]::UTF8.GetBytes($MonogatariAndroidRoot))).Replace('-', '')
$TaskMutex = [Threading.Mutex]::new($false, "Local\MonogatariGradle-$TaskLockName")
$TaskLocked = $false
try {
    $TaskLocked = $TaskMutex.WaitOne([TimeSpan]::FromMinutes(30))
    if (-not $TaskLocked) { throw 'Another Monogatari build is still running.' }
    Push-Location $MonogatariAndroidRoot
    try {
        & (Join-Path $MonogatariAndroidRoot 'gradlew.bat') @Tasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
    } finally { Pop-Location }
} finally { if ($TaskLocked) { $TaskMutex.ReleaseMutex() }; $TaskMutex.Dispose(); $TaskDigest.Dispose() }
