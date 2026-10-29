﻿﻿﻿﻿﻿# 组装 26.3 NeoForge 的 jar（手工编译后重新打包）
#
# 为什么需要这个脚本：NeoForge 的 NeoForm 反混淆需要联网，
# 网络不通时只能手工 javac + 重新打包。见经验总结 B8 节。
#
# 用法：先跑 tools/_compile-nf263 生成 build/manual-classes，再跑本脚本。
# 用 System.IO.Compression（PowerShell 原生，此前一直工作正常）

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Ver = ((Select-String -Path (Join-Path $Root 'gradle.properties') -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value).Trim()
$Out  = Join-Path $Root "neoforge-26.3\build\manual-classes"
$Libs = Join-Path $Root "neoforge-26.3\build\libs"
$NewJar = Join-Path $Libs "ironfarm-neoforge-26.3-$Ver.jar"

Get-ChildItem $Libs -Filter '*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
New-Item -ItemType Directory -Force -Path $Libs | Out-Null

$tpl = Get-ChildItem 'D:\26.3F\.minecraft\versions\26.3-NeoForge086\mods' -Filter 'ironfarm*.jar' |
       Select-Object -First 1
if (-not $tpl) { throw '找不到模板 jar' }
Write-Host "模板: $($tpl.Name)"

# 用模板作基底，Update 模式替换条目
Copy-Item $tpl.FullName $NewJar -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($NewJar, 'Update')

# 1) 删旧 mod class
@($zip.Entries | Where-Object { $_.FullName -match '^com/heishandashu/ironfarm/neoforge263/.*\.class$' }) |
    ForEach-Object { $_.Delete() }

# 2) 加新 class
$added = 0
Get-ChildItem $Out -Recurse -File -Filter '*.class' | ForEach-Object {
    $rel = $_.FullName.Substring($Out.Length).TrimStart('\').Replace('\', '/')
    $ex = $zip.GetEntry($rel)
    if ($ex) { $ex.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $_.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    $added++
}
Write-Host "class 替换: $added 个"

# 3) 换 engine
$engEntry = $zip.GetEntry('META-INF/jarjar/ironfarm.ironfarm-engine.jar')
if ($engEntry) { $engEntry.Delete() }
$engSrc = Join-Path $Root 'engine\build\libs\ironfarm-engine.jar'
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, $engSrc, 'META-INF/jarjar/ironfarm.ironfarm-engine.jar',
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null

# 4) 换语言 JSON
$langDir = Join-Path $Root 'engine\build\resources\main\assets\ironfarm\lang'
foreach ($f in (Get-ChildItem $langDir -Filter '*.json')) {
    $rel = "assets/ironfarm/lang/$($f.Name)"
    $e = $zip.GetEntry($rel)
    if ($e) { $e.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $f.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
}

# 5) 改版本号（重写 toml 条目）
$tomlEntry = $zip.GetEntry('META-INF/neoforge.mods.toml')
$sr = New-Object System.IO.StreamReader($tomlEntry.Open())
$toml = $sr.ReadToEnd()
$sr.Close()
$tomlEntry.Delete()
$toml = [regex]::Replace($toml, 'version = "0\.\d+\.\d+"', "version = `"$Ver`"")
$newEntry = $zip.CreateEntry('META-INF/neoforge.mods.toml')
$sw = New-Object System.IO.StreamWriter($newEntry.Open())
$sw.Write($toml)
$sw.Close()

$zip.Dispose()

Write-Host ""
Write-Host "=== 组装结果 ==="
$fi = Get-Item $NewJar
Write-Host "$($fi.Name)  $([math]::Round($fi.Length/1KB,1)) KB"

# 校验
$zip = [System.IO.Compression.ZipFile]::OpenRead($NewJar)
$verLine = ($zip.Entries | Where-Object { $_.FullName -eq 'META-INF/neoforge.mods.toml' } |
            ForEach-Object {
                $s = New-Object System.IO.StreamReader($_.Open())
                $t = $s.ReadToEnd(); $s.Close()
                ($t -split "`r?`n" | Where-Object { $_ -match '^version =' })
            })
Write-Host "版本: $verLine"
Write-Host "class 数: $(($zip.Entries | Where-Object { $_.FullName -match 'neoforge263/.*\.class' }).Count)"
$eng = $zip.Entries | Where-Object { $_.FullName -eq 'META-INF/jarjar/ironfarm.ironfarm-engine.jar' }
Write-Host "engine: $($eng.Length) bytes"
$zip.Dispose()

# ── 部署四版 ──
Write-Host ""
Write-Host "=== 部署 ==="
$targets = @(
    @{ M = 'fabric-1.21.1';   D = 'D:\1.21.1F-NF\.minecraft\versions\1.21.1-Fabric\mods' },
    @{ M = 'neoforge-1.21.1'; D = 'D:\1.21.1F-NF\.minecraft\versions\1.21.1-NeoForge\mods' },
    @{ M = 'fabric-26.3';     D = 'D:\26.3F\.minecraft\versions\26.3-Fabric\mods' },
    @{ M = 'neoforge-26.3';   D = 'D:\26.3F\.minecraft\versions\26.3-NeoForge086\mods' }
)
foreach ($t in $targets) {
    $jar = Get-ChildItem (Join-Path $Root "$($t.M)\build\libs") -Filter "*$Ver*.jar" -ErrorAction SilentlyContinue |
           Select-Object -First 1
    if (-not $jar) { Write-Host "  ⚠ $($t.M) 无 jar"; continue }
    Get-ChildItem $t.D -Filter 'ironfarm*.jar' -ErrorAction SilentlyContinue |
        ForEach-Object {
            try { Remove-Item $_.FullName -Force -ErrorAction Stop }
            catch { Write-Host "  ⚠ $($_.Name) 被占用" }
        }
    Copy-Item $jar.FullName $t.D -Force
    Write-Host "  $($t.M): $($jar.Name)"
}
$Out  = Join-Path $Root "neoforge-26.3\build\manual-classes"
$Libs = Join-Path $Root "neoforge-26.3\build\libs"
$NewJar = Join-Path $Libs "ironfarm-neoforge-26.3-$Ver.jar"

Get-ChildItem $Libs -Filter '*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
New-Item -ItemType Directory -Force -Path $Libs | Out-Null

$tpl = Get-ChildItem 'D:\26.3F\.minecraft\versions\26.3-NeoForge086\mods' -Filter 'ironfarm*.jar' |
       Select-Object -First 1
if (-not $tpl) { throw '找不到模板 jar' }
Write-Host "模板: $($tpl.Name)"

# 用模板作基底，Update 模式替换条目
Copy-Item $tpl.FullName $NewJar -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($NewJar, 'Update')

# 1) 删旧 mod class
@($zip.Entries | Where-Object { $_.FullName -match '^com/heishandashu/ironfarm/neoforge263/.*\.class$' }) |
    ForEach-Object { $_.Delete() }

# 2) 加新 class
$added = 0
Get-ChildItem $Out -Recurse -File -Filter '*.class' | ForEach-Object {
    $rel = $_.FullName.Substring($Out.Length).TrimStart('\').Replace('\', '/')
    $ex = $zip.GetEntry($rel)
    if ($ex) { $ex.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $_.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    $added++
}
Write-Host "class 替换: $added 个"

# 3) 换 engine
$engEntry = $zip.GetEntry('META-INF/jarjar/ironfarm.ironfarm-engine.jar')
if ($engEntry) { $engEntry.Delete() }
$engSrc = Join-Path $Root 'engine\build\libs\ironfarm-engine.jar'
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, $engSrc, 'META-INF/jarjar/ironfarm.ironfarm-engine.jar',
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null

# 4) 换语言 JSON
$langDir = Join-Path $Root 'engine\build\resources\main\assets\ironfarm\lang'
foreach ($f in (Get-ChildItem $langDir -Filter '*.json')) {
    $rel = "assets/ironfarm/lang/$($f.Name)"
    $e = $zip.GetEntry($rel)
    if ($e) { $e.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $f.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
}

# 5) 改版本号（重写 toml 条目）
$tomlEntry = $zip.GetEntry('META-INF/neoforge.mods.toml')
$sr = New-Object System.IO.StreamReader($tomlEntry.Open())
$toml = $sr.ReadToEnd()
$sr.Close()
$tomlEntry.Delete()
$toml = [regex]::Replace($toml, 'version = "0\.\d+\.\d+"', "version = `"$Ver`"")
$newEntry = $zip.CreateEntry('META-INF/neoforge.mods.toml')
$sw = New-Object System.IO.StreamWriter($newEntry.Open())
$sw.Write($toml)
$sw.Close()

$zip.Dispose()

Write-Host ""
Write-Host "=== 组装结果 ==="
$fi = Get-Item $NewJar
Write-Host "$($fi.Name)  $([math]::Round($fi.Length/1KB,1)) KB"

# 校验
$zip = [System.IO.Compression.ZipFile]::OpenRead($NewJar)
$verLine = ($zip.Entries | Where-Object { $_.FullName -eq 'META-INF/neoforge.mods.toml' } |
            ForEach-Object {
                $s = New-Object System.IO.StreamReader($_.Open())
                $t = $s.ReadToEnd(); $s.Close()
                ($t -split "`r?`n" | Where-Object { $_ -match '^version =' })
            })
Write-Host "版本: $verLine"
Write-Host "class 数: $(($zip.Entries | Where-Object { $_.FullName -match 'neoforge263/.*\.class' }).Count)"
$eng = $zip.Entries | Where-Object { $_.FullName -eq 'META-INF/jarjar/ironfarm.ironfarm-engine.jar' }
Write-Host "engine: $($eng.Length) bytes"
$zip.Dispose()

# ── 部署四版 ──
Write-Host ""
Write-Host "=== 部署 ==="
$targets = @(
    @{ M = 'fabric-1.21.1';   D = 'D:\1.21.1F-NF\.minecraft\versions\1.21.1-Fabric\mods' },
    @{ M = 'neoforge-1.21.1'; D = 'D:\1.21.1F-NF\.minecraft\versions\1.21.1-NeoForge\mods' },
    @{ M = 'fabric-26.3';     D = 'D:\26.3F\.minecraft\versions\26.3-Fabric\mods' },
    @{ M = 'neoforge-26.3';   D = 'D:\26.3F\.minecraft\versions\26.3-NeoForge086\mods' }
)
foreach ($t in $targets) {
    $jar = Get-ChildItem (Join-Path $Root "$($t.M)\build\libs") -Filter "*$Ver*.jar" -ErrorAction SilentlyContinue |
           Select-Object -First 1
    if (-not $jar) { Write-Host "  ⚠ $($t.M) 无 jar"; continue }
    Get-ChildItem $t.D -Filter 'ironfarm*.jar' -ErrorAction SilentlyContinue |
        ForEach-Object {
            try { Remove-Item $_.FullName -Force -ErrorAction Stop }
            catch { Write-Host "  ⚠ $($_.Name) 被占用" }
        }
    Copy-Item $jar.FullName $t.D -Force
    Write-Host "  $($t.M): $($jar.Name)"
}
