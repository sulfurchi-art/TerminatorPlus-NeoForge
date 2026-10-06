$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repoRoot = Join-Path $taskRoot 'outputs/TerminatorPlus-NeoForge'
$outRoot = Join-Path $taskRoot 'outputs'
$workRoot = Join-Path $taskRoot 'work'
$jarName = 'TerminatorPlus-NeoForge-1.21.1-4.17.0-BETA.jar'
$zipName = 'TerminatorPlus-NeoForge-4.17.0-内测包.zip'
$jarPath = Join-Path $repoRoot "build/libs/$jarName"
$outJarPath = Join-Path $outRoot $jarName
$zipPath = Join-Path $outRoot $zipName
$expectedJarHash = 'CF5BBEC5675E17DCCAD3F0492E35EDB55BF0D92074B1AEF30960FBE1F78A0230'

function Assert-Condition([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}
function Hash-Bytes([byte[]] $Bytes) {
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes))
}
function Read-ZipEntry($Entry) {
    $stream = $Entry.Open()
    $buffer = [IO.MemoryStream]::new()
    try {
        $stream.CopyTo($buffer)
        return ,$buffer.ToArray()
    } finally {
        $stream.Dispose()
        $buffer.Dispose()
    }
}
function Verify-TestLog([string] $Path, [int] $Passes, [int] $Fails, [string] $Summary) {
    $log = [IO.File]::ReadAllText($Path)
    Assert-Condition ([regex]::Matches($log, '\[SelfTest\] PASS ').Count -eq $Passes) "PASS count mismatch: $Path"
    Assert-Condition ([regex]::Matches($log, '\[SelfTest\] FAIL ').Count -eq $Fails) "FAIL count mismatch: $Path"
    Assert-Condition ($log.Contains($Summary)) "Final summary missing: $Path"
    Assert-Condition ($log.Contains('BUILD SUCCESSFUL')) "Run not finished: $Path"
}

# Do not replace an already delivered release archive.
Assert-Condition (-not (Test-Path -LiteralPath $zipPath)) '4.17 ZIP already exists; preserve the sealed archive.'
Assert-Condition (-not (Test-Path -LiteralPath $outJarPath)) '4.17 JAR already exists; preserve the sealed artifact.'
Assert-Condition ((Get-Item -LiteralPath $jarPath).Length -eq 731274) 'Unexpected JAR size.'
Assert-Condition ((Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash -eq $expectedJarHash) 'Unexpected JAR SHA256.'

$snapshotPath = Join-Path $workRoot '4.17-test-source-snapshot.json'
$snapshot = [IO.File]::ReadAllText($snapshotPath) | ConvertFrom-Json -AsHashtable
Assert-Condition ($snapshot.Count -eq 109) 'Unexpected source snapshot size.'
foreach ($relative in $snapshot.Keys) {
    Assert-Condition ((Get-FileHash -LiteralPath (Join-Path $repoRoot $relative) -Algorithm SHA256).Hash -eq $snapshot[$relative]) "Source changed after final validation: $relative"
}

$nativeLog = Join-Path $workRoot 'ai-hardness-4.17-warfare-full-final.log'
$baseLog = Join-Path $workRoot 'ai-hardness-4.17-base-full.log'
$buildLog = Join-Path $workRoot 'ai-hardness-4.17-build.log'
Verify-TestLog $nativeLog 80 0 'ALL 80 CHECKS PASSED'
Verify-TestLog $baseLog 127 3 '3 of 130 checks FAILED'
Assert-Condition ([IO.File]::ReadAllText($buildLog).Contains('BUILD SUCCESSFUL')) 'Build did not complete.'

# Preserve previous released files, including the post-seal baseline.
$oldHashes = @{
    'TerminatorPlus-NeoForge-1.21.1-4.16.0-BETA.jar' = '0896467EE0C3D1D0248B1A35AC8BA8FDC2EA71B2519C72DB2DACCC40B45A1032'
    'TerminatorPlus-NeoForge-4.16.0-内测包.zip' = '4F8B9E1F5982774D06FD80D2631456FB6508685B7430561487D321671ADB9056'
}
foreach ($old in $oldHashes.Keys) {
    Assert-Condition ((Get-FileHash -LiteralPath (Join-Path $outRoot $old) -Algorithm SHA256).Hash -eq $oldHashes[$old]) "Prior release changed: $old"
}

Add-Type -AssemblyName System.IO.Compression.ZipFile
$jar = [IO.Compression.ZipFile]::OpenRead($jarPath)
$classes = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'build/classes/java/main') -Recurse -Filter '*.class' -File)
Assert-Condition ($classes.Count -eq 174) 'Unexpected compiled class count.'
try {
    $classRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'build/classes/java/main'))
    foreach ($file in $classes) {
        $relative = [IO.Path]::GetRelativePath($classRoot, $file.FullName).Replace('\', '/')
        $entry = $jar.GetEntry($relative)
        Assert-Condition ($null -ne $entry) "Missing JAR class: $relative"
        Assert-Condition ((Hash-Bytes (Read-ZipEntry $entry)) -eq (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash) "Class mismatch: $relative"
    }
    $metadata = [Text.Encoding]::UTF8.GetString((Read-ZipEntry ($jar.GetEntry('META-INF/neoforge.mods.toml'))))
    Assert-Condition ($metadata.Contains('version="4.17.0-BETA"')) 'Incorrect mod metadata version.'
    Assert-Condition ($metadata.Contains('versionRange="[21.1.1,)"')) 'Minimum NeoForge declaration changed.'
    Assert-Condition ($null -ne $jar.GetEntry('META-INF/accesstransformer.cfg')) 'Missing explicit AT resource.'
    Assert-Condition ($metadata.Contains('accesstransformer.cfg')) 'Missing explicit AT declaration.'
    Assert-Condition (@($jar.Entries | Where-Object { $_.FullName -match '^(com/atsuishio/|assets/superbwarfare/|data/superbwarfare/)' }).Count -eq 0) 'Foreign mod content included.'
} finally { $jar.Dispose() }

# Confirm that the vehicle work did not edit infantry production sources.
$checkpoint = [IO.Compression.ZipFile]::OpenRead((Join-Path $workRoot 'TerminatorPlus-NeoForge-4.16-source-checkpoint.zip'))
$sourceChanges = [Collections.Generic.List[string]]::new()
try {
    foreach ($entry in $checkpoint.Entries) {
        $name = $entry.FullName
        if (-not $name.StartsWith('src/main/java/')) { continue }
        $currentPath = Join-Path $repoRoot $name
        if (-not (Test-Path -LiteralPath $currentPath)) { throw "Checkpoint production source disappeared: $name" }
        if ((Hash-Bytes (Read-ZipEntry $entry)) -ne (Get-FileHash -LiteralPath $currentPath -Algorithm SHA256).Hash) {
            $sourceChanges.Add($name)
            Assert-Condition ($name.Contains('/compat/') -or $name.EndsWith('/utils/SelfTest.java')) "Unexpected infantry source change: $name"
        }
    }
} finally { $checkpoint.Dispose() }

[IO.File]::Copy($jarPath, $outJarPath, $false)
$logCopies = @{
    'ai-hardness-4.17.0-warfare.log' = $nativeLog
    'ai-hardness-4.17.0-selftest.log' = $baseLog
    'ai-hardness-4.17.0-build.log' = $buildLog
}
foreach ($name in $logCopies.Keys) { [IO.File]::Copy($logCopies[$name], (Join-Path $outRoot $name), $false) }
$jarHashFile = Join-Path $outRoot 'SHA256-4.17.0-jar.txt'
[IO.File]::WriteAllText($jarHashFile, "$expectedJarHash  $jarName`n", [Text.UTF8Encoding]::new($false))

$contents = [ordered]@{}
$contents[$jarName] = $outJarPath
$contents['内测说明.md'] = Join-Path $outRoot '4.17.0-内测说明.md'
$contents['validation-4.17.0.txt'] = Join-Path $outRoot 'validation-4.17.0.txt'
$contents['SHA256-4.17.0-jar.txt'] = $jarHashFile
foreach ($doc in @('README.md', 'AGENTS.md', 'AI_HARDNESS.md', 'AI_HARDNESS_PROGRESS.md', 'SUPERB_WARFARE.md', 'docs/vehicle-ai-research-4.17.md', 'docs/validation-4.17.md', 'docs/validation-4.16-postseal.md')) {
    $contents[$doc] = Join-Path $repoRoot $doc
}
foreach ($name in $logCopies.Keys) { $contents[$name] = Join-Path $outRoot $name }
$contents['source-snapshot-4.17.json'] = $snapshotPath
foreach ($name in @('ai-hardness-4.17-navigation-1.log', 'ai-hardness-4.17-navigation-2.log', 'ai-hardness-4.17-navigation-3.log', 'ai-hardness-4.17-navigation-final.log', 'ai-hardness-4.17-navigation-verified.log', 'ai-hardness-4.17-air-cooperation.log', 'ai-hardness-4.17-warfare-full.log')) {
    $contents["process/$name"] = Join-Path $workRoot $name
}

$zip = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($name in $contents.Keys) {
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $contents[$name], $name, [IO.Compression.CompressionLevel]::Optimal)
    }
} finally { $zip.Dispose() }

$verify = [IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    Assert-Condition ($verify.Entries.Count -eq $contents.Count) 'Unexpected ZIP contents.'
    foreach ($name in $contents.Keys) {
        $entry = $verify.GetEntry($name)
        Assert-Condition ($null -ne $entry) "Missing ZIP entry: $name"
        Assert-Condition ((Hash-Bytes (Read-ZipEntry $entry)) -eq (Get-FileHash -LiteralPath $contents[$name] -Algorithm SHA256).Hash) "ZIP entry mismatch: $name"
    }
} finally { $verify.Dispose() }
$zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
[IO.File]::WriteAllText((Join-Path $outRoot 'SHA256-4.17.0.txt'), "$expectedJarHash  $jarName`n$zipHash  $zipName`n", [Text.UTF8Encoding]::new($false))
Write-Output ([ordered]@{
    jar = $outJarPath
    zip = $zipPath
    jarBytes = (Get-Item -LiteralPath $outJarPath).Length
    zipBytes = (Get-Item -LiteralPath $zipPath).Length
    jarSHA256 = $expectedJarHash
    zipSHA256 = $zipHash
    verifiedClasses = $classes.Count
    frozenSources = $snapshot.Count
    verifiedArchiveFiles = $contents.Count
    native = '80/80 PASS'
    base = '127/130 PASS; 3 unresolved FAIL'
    changedCheckpointSources = @($sourceChanges)
} | ConvertTo-Json -Depth 3)
