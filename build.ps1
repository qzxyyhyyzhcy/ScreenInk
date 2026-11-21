$ErrorActionPreference = 'Stop'

function Invoke-BuildTool([string]$Tool, [string[]]$Arguments) {
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$(Split-Path -Leaf $Tool) failed with exit code $LASTEXITCODE"
    }
}

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw 'Set ANDROID_HOME to your Android SDK.' }
$platform = Get-ChildItem -LiteralPath (Join-Path $sdk 'platforms') -Directory |
    Where-Object { $_.Name -match '^android-([0-9]+)(\.[0-9]+)?$' -and [double]$_.Name.Substring(8) -ge 36 } |
    Sort-Object { [double]$_.Name.Substring(8) } -Descending | Select-Object -First 1
if (-not $platform) { throw 'Install Android SDK Platform 36 or later.' }
$android = Join-Path $platform.FullName 'android.jar'
$tools = Join-Path $sdk 'build-tools\36.0.0'
if (-not (Test-Path -LiteralPath (Join-Path $tools 'aapt2.exe'))) {
    throw 'Install Android SDK Build Tools 36.0.0.'
}

$deliveries = Join-Path $PSScriptRoot 'out'
$tempBase = [IO.Path]::GetTempPath()
$out = Join-Path $tempBase ('ScreenInk-' + [Guid]::NewGuid().ToString('N'))
$stagedResources = Join-Path $out 'res'
$stagedSources = Join-Path $out 'src'
$generated = Join-Path $out 'generated'
$classes = Join-Path $out 'classes'
$compileOnlyClasses = Join-Path $out 'compile-only'
$assets = Join-Path $out 'assets'
$compileOnly = Join-Path $out 'xposed-api'
$signing = Join-Path $PSScriptRoot '.local-signing'
New-Item -ItemType Directory -Path $out, $generated, $classes, $compileOnlyClasses, $signing, $deliveries -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'res') -Destination $stagedResources -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'src') -Destination $stagedSources -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'assets') -Destination $assets -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'compile-only') -Destination $compileOnly -Recurse
$manifest = Join-Path $out 'AndroidManifest.xml'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'AndroidManifest.xml') -Destination $manifest
$compiledResources = Join-Path $out 'resources.zip'
$baseApk = Join-Path $out 'base.apk'
$unsignedApk = Join-Path $out 'unsigned.apk'
$alignedApk = Join-Path $out 'aligned.apk'
$result = Join-Path $out 'ScreenInk.apk'
$key = Join-Path $signing 'screenink.p12'
$protectedPassword = Join-Path $signing 'password.dpapi'

Invoke-BuildTool (Join-Path $tools 'aapt2.exe') @(
    'compile', '--dir', $stagedResources, '-o', $compiledResources
)
Invoke-BuildTool (Join-Path $tools 'aapt2.exe') @(
    'link', '-I', $android, '--manifest', $manifest,
    '--java', $generated, '-A', $assets, '-o', $baseApk, $compiledResources
)
$javac = (Get-Command javac).Source
$compileOnlySources = Get-ChildItem -LiteralPath $compileOnly -Recurse -Filter '*.java' -File
Invoke-BuildTool $javac (@('--release', '8', '-Xlint:-options', '-d', $compileOnlyClasses) + $compileOnlySources.FullName)
$sources = Get-ChildItem -LiteralPath $stagedSources, $generated -Recurse -Filter '*.java' -File
Invoke-BuildTool $javac (@(
    '--release', '8', '-encoding', 'UTF-8', '-Xlint:-options', '-cp', "$android;$compileOnlyClasses", '-d', $classes
) + $sources.FullName)
$classFiles = Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' -File
Invoke-BuildTool (Get-Command java).Source (@(
    '-cp', (Join-Path $tools 'lib\d8.jar'), 'com.android.tools.r8.D8',
    '--release', '--min-api', '30', '--lib', $android, '--classpath', $compileOnlyClasses, '--output', $out
) + $classFiles.FullName)
Copy-Item -LiteralPath $baseApk -Destination $unsignedApk -Force
Invoke-BuildTool (Get-Command jar).Source @('uf', $unsignedApk, '-C', $out, 'classes.dex')
Invoke-BuildTool (Join-Path $tools 'zipalign.exe') @('-f', '4', $unsignedApk, $alignedApk)

if ((Test-Path -LiteralPath $key) -ne (Test-Path -LiteralPath $protectedPassword)) {
    throw 'The signing key is incomplete. Restore the existing .local-signing directory.'
}
$createKey = -not (Test-Path -LiteralPath $key)
if ($createKey) {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $secret = ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
} else {
    $secret = ConvertTo-SecureString (Get-Content -LiteralPath $protectedPassword -Raw).Trim()
}
$env:SCREENINK_BUILD_SIGN_PASSPHRASE = [Net.NetworkCredential]::new('', $secret).Password
try {
    if ($createKey) {
        $newKey = Join-Path $signing 'screenink.pending.p12'
        $newPassword = Join-Path $signing 'password.pending.dpapi'
        if ((Test-Path -LiteralPath $newKey) -or (Test-Path -LiteralPath $newPassword)) {
            throw 'A previous key creation was interrupted. Inspect .local-signing before retrying.'
        }
        $secret | ConvertFrom-SecureString | Set-Content -LiteralPath $newPassword -Encoding ascii
        Invoke-BuildTool (Get-Command keytool).Source @(
            '-genkeypair', '-noprompt', '-storetype', 'PKCS12', '-keystore', $newKey,
            '-storepass:env', 'SCREENINK_BUILD_SIGN_PASSPHRASE', '-alias', 'screenink',
            '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=Local ScreenInk'
        )
        Move-Item -LiteralPath $newKey -Destination $key
        Move-Item -LiteralPath $newPassword -Destination $protectedPassword
    }
    Invoke-BuildTool (Get-Command java).Source @(
        '-jar', (Join-Path $tools 'lib\apksigner.jar'), 'sign', '--ks', $key,
        '--ks-key-alias', 'screenink', '--ks-pass', 'env:SCREENINK_BUILD_SIGN_PASSPHRASE',
        '--v4-signing-enabled', 'false', '--out', $result, $alignedApk
    )
    Invoke-BuildTool (Get-Command java).Source @(
        '-jar', (Join-Path $tools 'lib\apksigner.jar'), 'verify', '--print-certs', $result
    )
} finally {
    Remove-Item Env:\SCREENINK_BUILD_SIGN_PASSPHRASE -ErrorAction SilentlyContinue
}

$installedApk = Join-Path $deliveries 'ScreenInk.apk'
Copy-Item -LiteralPath $result -Destination $installedApk -Force
$resolvedWork = (Resolve-Path -LiteralPath $out).Path
$resolvedBase = (Resolve-Path -LiteralPath $tempBase).Path.TrimEnd('\')
if ((Split-Path -Parent $resolvedWork) -eq $resolvedBase -and
    (Split-Path -Leaf $resolvedWork) -match '^ScreenInk-[0-9a-f]{32}$') {
    Remove-Item -LiteralPath $resolvedWork -Recurse -Force
}
Write-Output $installedApk
