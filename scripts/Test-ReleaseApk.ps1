#Requires -Version 7.2

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $ApkPath,

    [Parameter(Mandatory)]
    [ValidatePattern('\Av(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\z', Options = 'CultureInvariant')]
    [string] $Tag,

    [Parameter(Mandatory)]
    [string] $ManifestPath,

    [ValidateRange(33, 2147483646)]
    [int] $PreviousVersionCode = 33,

    [string] $BuildToolsPath,

    [switch] $Generate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-BuildTool {
    param([string] $Command, [string[]] $Arguments)

    $output = & (Join-Path $BuildToolsPath $Command) @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Command rejected the APK (exit $LASTEXITCODE)."
    }
    $output -join "`n"
}

function Get-ApkAbis {
    param([string] $Path)

    $entries = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $abis = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $supportedAbis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
    $apk = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        foreach ($required in @('AndroidManifest.xml', 'classes.dex')) {
            $entry = $apk.GetEntry($required)
            if (!$entry -or $entry.Length -le 0) {
                throw "The APK is missing $required."
            }
        }
        foreach ($entry in $apk.Entries) {
            $pathWithoutTrailingSlash = $entry.FullName -creplace '/\z', ''
            if (!$entries.Add($entry.FullName) -or $pathWithoutTrailingSlash -cmatch '\\|(?:\A|/)\.{0,2}(?:/|\z)') {
                throw 'The APK contains unsafe or duplicate ZIP entries.'
            }
            if ($entry.FullName -cmatch '\Alib/([^/]+)/[^/]+\.so\z') {
                $abi = $Matches[1]
                if ($abi -cnotin $supportedAbis) {
                    throw 'The APK contains an unsupported ABI.'
                }
                $reader = [System.IO.BinaryReader]::new($entry.Open())
                try {
                    if ([Convert]::ToHexString($reader.ReadBytes(4)) -cne '7F454C46') {
                        throw 'A native library in the APK is not an ELF file.'
                    }
                } finally {
                    $reader.Dispose()
                }
                [void] $abis.Add($abi)
            }
        }
    } finally {
        $apk.Dispose()
    }

    if ($abis.Count -eq 0) {
        'universal'
    } else {
        $sortedAbis = [string[]] $abis
        [Array]::Sort($sortedAbis, [StringComparer]::Ordinal)
        $sortedAbis
    }
}

function Assert-UpdateManifest {
    param([string] $Path, [System.Collections.IDictionary] $Expected)

    $file = Get-Item -LiteralPath $Path
    if ($file.Length -le 0 -or $file.Length -gt 64KB) {
        throw 'update.json must contain between 1 and 65536 bytes.'
    }
    $document = [System.Text.Json.JsonDocument]::Parse([System.IO.File]::ReadAllText($file.FullName))
    try {
        $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $fieldNames = @($Expected.Keys)
        foreach ($property in $document.RootElement.EnumerateObject()) {
            $name = $property.Name
            if ($name -cnotin $fieldNames -or !$seen.Add($name)) {
                throw 'update.json has an unexpected or duplicate field.'
            }
            $value = $property.Value
            $matchesApk = switch ($name) {
                'versionCode' { $value.GetInt32() -eq $Expected[$name] }
                'minSdk' { $value.GetInt32() -eq $Expected[$name] }
                'size' { $value.GetInt64() -eq $Expected[$name] }
                'abis' {
                    $abis = @($value.EnumerateArray() | ForEach-Object { $_.GetString() })
                    ($abis -join "`n") -ceq ($Expected[$name] -join "`n") -and
                        $abis.Count -eq $Expected[$name].Count
                }
                default { $value.GetString() -ceq $Expected[$name] }
            }
            if (!$matchesApk) {
                throw "update.json field '$name' does not match the APK."
            }
        }
        if ($seen.Count -ne $Expected.Count) {
            throw 'update.json is missing one or more required fields.'
        }
    } finally {
        $document.Dispose()
    }
}

# Extracted from the publicly distributed Aurio-1.3.0.apk after signature verification.
$releaseCertificate = '077492d3eaab9bf217db69590656c6289b8d48bc80404813c3570f6e2579537c'
$versionName = $Tag.Substring(1)
if (![version]::TryParse($versionName, [ref] $null)) {
    throw 'The release tag version components must fit in 32-bit integers.'
}
$assetName = "Aurio-$versionName.apk"
$maxApkBytes = 2GB - 1
$apk = Get-Item -LiteralPath $ApkPath
if ($apk.Name -cne $assetName -or $apk.PSIsContainer -or $apk.Length -le 0 -or $apk.Length -gt $maxApkBytes) {
    throw "Expected an APK named $assetName containing between 1 and $maxApkBytes bytes."
}
if ([System.IO.Path]::GetFileName($ManifestPath) -cne 'update.json') {
    throw 'The manifest must be named update.json.'
}

if (!$BuildToolsPath) {
    $sdk = $env:ANDROID_HOME
    if (!$sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (!$sdk -and $IsWindows) { $sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    if (!$sdk) { throw 'Set ANDROID_HOME or pass -BuildToolsPath.' }
    $BuildToolsPath = Join-Path $sdk 'build-tools/36.0.0'
}

$exe = if ($IsWindows) { '.exe' } else { '' }
$signer = if ($IsWindows) { 'apksigner.bat' } else { 'apksigner' }
$signature = Invoke-BuildTool $signer @('verify', '--verbose', '--print-certs', $apk.FullName)
if ($signature -cnotmatch '(?m)^Number of signers: 1\r?$' -or
    $signature -cnotmatch "(?m)^Signer #1 certificate SHA-256 digest: $releaseCertificate\r?$") {
    throw 'APK signing certificate does not match the official Aurio release certificate.'
}

$badging = Invoke-BuildTool "aapt2$exe" @('dump', 'badging', $apk.FullName)
if ($badging -cnotmatch "(?m)^package: name='jp\.noppy\.lumamusic' versionCode='([1-9][0-9]*)' versionName='([^']+)'(?: |\r?$)") {
    throw 'APK metadata did not identify a versioned jp.noppy.lumamusic package.'
}
$versionCode = [int] $Matches[1]
if ($Matches[2] -cne $versionName) {
    throw 'APK versionName does not match the release tag.'
}
if ($versionCode -le $PreviousVersionCode) {
    throw "APK versionCode must be greater than $PreviousVersionCode."
}
if ($badging -cmatch '(?m)^application-debuggable\b') {
    throw 'The APK is debuggable.'
}

$tree = Invoke-BuildTool "aapt2$exe" @('dump', 'xmltree', $apk.FullName, '--file', 'AndroidManifest.xml')
if ($tree -cmatch '(?m)^\s+(?:E: uses-split\b|A: split[=(]|A: \S+:(?:configForSplit|isFeatureSplit|requiredSplitTypes|splitTypes)\b)' -or
    $tree -cmatch '(?m)^\s+A: \S+:isSplitRequired\([^)]*\)=(?!false(?:\r?$))' -or
    $tree -cmatch 'com\.android\.vending\.splits\.required' -or
    $tree -cmatch '(?m)^\s+A: \S+:versionCodeMajor\([^)]*\)=(?!0(?:\r?$))') {
    throw 'Expected a standalone APK with a standard integer versionCode.'
}
if ($tree -cnotmatch '(?m)^\s+A: http://schemas\.android\.com/apk/res/android:minSdkVersion\([^)]*\)=([1-9][0-9]*)\r?$') {
    throw 'APK manifest must declare an integer minSdkVersion.'
}
$minSdk = [int] $Matches[1]
$null = Invoke-BuildTool "zipalign$exe" @('-c', '-P', '16', '4', $apk.FullName)

$expected = [ordered] @{
    versionName = $versionName
    versionCode = $versionCode
    minSdk = $minSdk
    abis = @(Get-ApkAbis $apk.FullName)
    assetName = $assetName
    size = $apk.Length
    sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}

$manifestFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ManifestPath)
if ($Generate) {
    $temporaryFile = Join-Path (Split-Path -Parent $manifestFile) "update-$([Guid]::NewGuid()).tmp"
    try {
        $json = ConvertTo-Json -InputObject $expected
        [System.IO.File]::WriteAllText($temporaryFile, $json + "`n")
        Assert-UpdateManifest $temporaryFile $expected
        [System.IO.File]::Move($temporaryFile, $manifestFile)
    } finally {
        [System.IO.File]::Delete($temporaryFile)
    }
}
Assert-UpdateManifest $manifestFile $expected
Write-Output ('Verified {0} (versionCode {1}, minSdk {2}, ABI {3}, SHA-256 {4})' -f
    $assetName, $versionCode, $minSdk, ($expected.abis -join ', '), $expected.sha256)
