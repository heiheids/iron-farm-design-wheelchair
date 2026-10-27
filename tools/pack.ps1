# 打包脚本：把程序 + 源码 + 四种文档打成一个 zip
#
# 用法（从工程根目录）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\pack.ps1
#
# 注意（见 经验与踩坑 坑 9）：本文件含中文，必须保存为 UTF-8 **带 BOM**，
# 否则 Windows PowerShell 5.1 会按 GBK 读，中文把引号吃掉导致解析失败。

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Version = '0.51.0'
$StageName = "ironfarm-$Version"
$Stage = Join-Path $Root "build/pack/$StageName"
$ZipPath = Join-Path $Root "刷铁机设计轮椅-$Version.zip"

Write-Host "root   = $Root"
Write-Host "stage  = $Stage"
Write-Host "zip    = $ZipPath"

# 清空暂存区
if (Test-Path $Stage) { Remove-Item $Stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Stage | Out-Null

# 1) 程序（jar）
$libs = Join-Path $Root 'engine/build/libs'
$jar = Get-ChildItem $libs -Filter '*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notmatch 'sources|javadoc' } |
       Select-Object -First 1
if (-not $jar) {
    throw "找不到 jar，请先跑 gradle :engine:build"
}

# 0) 引用完整性检查：任何一条 Minecraft 引用对不上就拒绝打包。
#    理由：remap 错了的 jar 能正常打出来、也能被加载器接受，
#    但一进游戏就 NoSuchMethodError。必须在打包前拦住。
Write-Host "--- 引用完整性检查 ---"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root "tools/verify-refs.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "引用完整性检查失败，拒绝打包"
}

# 0a) 语言文件必须是 JSON，且必须真的进了 jar。
#     理由：Minecraft 只读 assets/<modid>/lang/*.json。
#     我曾写了 164 条 .lang 文本，游戏里全部显示原始键名。
Add-Type -AssemblyName System.IO.Compression.FileSystem
$libsForLang = Join-Path $Root 'fabric-1.21.1/build/libs'
$jarForLang = Get-ChildItem $libsForLang -Filter *.jar |
              Where-Object { $_.Name -notmatch 'sources|javadoc|dev' } |
              Select-Object -First 1
if (-not $jarForLang) { throw "找不到模组 jar" }
$zipLang = [System.IO.Compression.ZipFile]::OpenRead($jarForLang.FullName)
try {
    $jsonLangs = @($zipLang.Entries | Where-Object { $_.FullName -match "assets/ironfarm/lang/.*\.json$" })
} finally {
    $zipLang.Dispose()
}
if ($jsonLangs.Count -lt 2) {
    throw "语言文件缺失：jar 里只有 $($jsonLangs.Count) 个 .json（至少要 zh_cn + en_us）"
}
Write-Host "PASS  语言文件 JSON：$($jsonLangs.Count) 份"

# 0b) Mixin 校验：目标类/方法/@Shadow 字段必须真实存在。
#     理由：Mixin 的失败是静默的，编译能过、jar 能打，进游戏只 warn 一句。
Write-Host "--- Mixin 校验 ---"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root "tools/verify-mixins.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "Mixin 校验失败，拒绝打包"
}
$progDir = Join-Path $Stage '程序'
New-Item -ItemType Directory -Force -Path $progDir | Out-Null
Copy-Item $jar.FullName (Join-Path $progDir $jar.Name)
Copy-Item (Join-Path $Root 'engine/src/main/resources/assets/ironfarm/icon.png') `
          (Join-Path $progDir 'icon.png')
Write-Host "engine = $($jar.Name) ($($jar.Length) bytes)"

# Fabric 模组 jar（可直接放进 mods 文件夹）
$fj = Get-ChildItem (Join-Path $Root 'fabric-1.21.1/build/libs') -Filter '*.jar' -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -notmatch 'sources|javadoc|dev' } | Select-Object -First 1
if ($fj) {
    Copy-Item $fj.FullName (Join-Path $progDir $fj.Name)

# ---- 另外三个加载器/版本的 jar ----
# 四个产物都放进 zip，玩家按自己的环境取一个。
$a11 = Get-ChildItem (Join-Path $Root 'neoforge-1.21.1/build/libs') -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|dev|slim' } | Select-Object -First 1
if ($a11) {
    Copy-Item $a11.FullName (Join-Path $progDir $a11.Name)
    Write-Host "  打包：$($a11.Name)"
}
$a263 = Get-ChildItem (Join-Path $Root 'fabric-26.3/build/libs') -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|dev|slim' } | Select-Object -First 1
if ($a263) {
    Copy-Item $a263.FullName (Join-Path $progDir $a263.Name)
    Write-Host "  打包：$($a263.Name)"
}
$a263n = Get-ChildItem (Join-Path $Root 'neoforge-26.3/build/libs') -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|dev|slim' } | Select-Object -First 1
if ($a263n) {
    Copy-Item $a263n.FullName (Join-Path $progDir $a263n.Name)
    Write-Host "  打包：$($a263n.Name)"
}
    Write-Host "fabric = $($fj.Name) ($($fj.Length) bytes)"
} else {
    throw "找不到 Fabric 模组 jar，请先跑 gradle :fabric-1.21.1:build"
}

# 2) 源码
$srcDir = Join-Path $Stage '源码'
New-Item -ItemType Directory -Force -Path $srcDir | Out-Null
foreach ($d in @('engine/src', 'fabric-1.21.1/src', 'neoforge-1.21.1/src', 'fabric-26.3/src', 'neoforge-26.3/src', 'tools')) {
    $target = Join-Path $srcDir $d
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    Copy-Item (Join-Path $Root $d) $target -Recurse -Force
}
foreach ($f in @('build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat')) {
    Copy-Item (Join-Path $Root $f) (Join-Path $srcDir $f) -Force
}
Copy-Item (Join-Path $Root 'gradle') (Join-Path $srcDir 'gradle') -Recurse -Force
Copy-Item (Join-Path $Root 'docs') (Join-Path $srcDir 'docs') -Recurse -Force

# 3) 文档
$docDir = Join-Path $Stage '文档'
New-Item -ItemType Directory -Force -Path $docDir | Out-Null
foreach ($f in @('使用说明-中文.txt', '使用说明-英文.txt', '技术说明.txt', '技术说明-英文.txt',
                 '刷铁机设计轮椅-经验与踩坑.txt', '备份与恢复说明.txt')) {
    Copy-Item (Join-Path $Root $f) (Join-Path $docDir $f) -Force
}

# 4) 测试报告（证明 98 条断言真的跑过）
$reportDir = Join-Path $Stage '测试报告'
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
$xmls = Get-ChildItem (Join-Path $Root 'engine/build/test-results/test') -Filter '*.xml' -ErrorAction SilentlyContinue
$totalTests = 0; $totalFail = 0; $totalErr = 0
$summary = New-Object System.Text.StringBuilder
[void]$summary.AppendLine("测试汇总（$Version）")
[void]$summary.AppendLine("=" * 60)
foreach ($x in $xmls) {
    [xml]$doc = Get-Content $x.FullName
    $t = [int]$doc.testsuite.tests
    $f = [int]$doc.testsuite.failures
    $e = [int]$doc.testsuite.errors
    $totalTests += $t; $totalFail += $f; $totalErr += $e
    Copy-Item $x.FullName (Join-Path $reportDir $x.Name)
    [void]$summary.AppendLine(("{0,-70} tests={1,3} fail={2} err={3}" -f $doc.testsuite.name, $t, $f, $e))
}
[void]$summary.AppendLine("=" * 60)
[void]$summary.AppendLine("合计：$totalTests 条，失败 $totalFail，错误 $totalErr")
[void]$summary.AppendLine("")
[void]$summary.AppendLine("构建命令：gradle clean :engine:build")
[void]$summary.AppendLine("构建日志：见 构建日志/build-final.txt")
$summaryPath = Join-Path $reportDir '测试汇总.txt'
[System.IO.File]::WriteAllText($summaryPath, $summary.ToString(), (New-Object System.Text.UTF8Encoding($false)))

# 5) 构建日志
$logDir = Join-Path $Stage '构建日志'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Copy-Item (Join-Path $Root 'build-final.txt') (Join-Path $logDir 'build-final.txt') -Force

# 记录要部署的 jar（在打包之前取，避免歧义）
$deployedJar = Get-ChildItem (Join-Path $Root 'fabric-1.21.1/build/libs') -Filter *.jar |
               Where-Object { $_.Name -notmatch 'sources|javadoc|dev' } |
               Select-Object -First 1
if (-not $deployedJar) { throw "找不到要部署的模组 jar" }
# 6) 打包
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
Compress-Archive -Path $Stage -DestinationPath $ZipPath -CompressionLevel Optimal

$zipInfo = Get-Item $ZipPath
$count = ([System.IO.Compression.ZipFile]::OpenRead($ZipPath)).Entries.Count

Write-Host ""
Write-Host "=== 打包完成 ==="
Write-Host "文件：$($zipInfo.FullName)"
Write-Host "大小：$([math]::Round($zipInfo.Length / 1KB, 1)) KB"
Write-Host "条目：$count"
Write-Host "测试：$totalTests 条，失败 $totalFail，错误 $totalErr"

# 7) 部署到测试客户端 mods 目录
#    这样每次构建完直接进游戏就能测，不用手动拷贝。
#    ⚠ 必须先删掉旧版本：两个版本的 mod id 相同，Fabric 会因
#      "duplicate mod id" 直接启动失败，而不是选用新版本。
# ---------------------------------------------------------------------------
# 部署：四个目标各放一份
#
# 每个模块的 jar 名字不同（fabric/neoforge + 版本号），
# 所以按模块目录取，不按文件名猜。
# ---------------------------------------------------------------------------
# 部署目录：优先读环境变量，没配置就跳过部署（只打包）。
#
# 开源版不写死任何本机路径 —— 每个人把 jar 放进自己的 mods 目录即可。
# 想自动部署的人可以在系统里设这四个环境变量：
#   IRONFARM_DIR_FABRIC_1211 / IRONFARM_DIR_NEOFORGE_1211
#   IRONFARM_DIR_FABRIC_263   / IRONFARM_DIR_NEOFORGE_263
#
# ⚠ 顺带说明：如果没有 Neoforge 26.3 的构建产物（需要联网拉 NeoForm），
#   这一项会在下面被跳过，不影响其余三版打包。
$DeployTargets = @(
    @{ Module = 'fabric-1.21.1';   Dir = $env:IRONFARM_DIR_FABRIC_1211 },
    @{ Module = 'neoforge-1.21.1'; Dir = $env:IRONFARM_DIR_NEOFORGE_1211 },
    @{ Module = 'fabric-26.3';     Dir = $env:IRONFARM_DIR_FABRIC_263 },
    @{ Module = 'neoforge-26.3';   Dir = $env:IRONFARM_DIR_NEOFORGE_263 }
) | Where-Object { $_.Dir }

foreach ($t in $DeployTargets) {
    $libsDir = Join-Path $Root "$($t.Module)/build/libs"
    $jar = Get-ChildItem $libsDir -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|dev|slim' } |
        Select-Object -First 1

    if (-not $jar) {
        Write-Host "  ⚠ 跳过 $($t.Module)：还没构建（找不到 jar）"
        continue
    }
    if (-not (Test-Path $t.Dir)) {
        Write-Host "  ⚠ 跳过 $($t.Module)：目标目录不存在 $($t.Dir)"
        continue
    }

    $old = Get-ChildItem $t.Dir -Filter 'ironfarm-*.jar' -ErrorAction SilentlyContinue
    foreach ($o in $old) {
        if ($o.Name -ne $jar.Name) {
            try {
                Remove-Item $o.FullName -Force -ErrorAction Stop
                Write-Host "  移除旧版本：$($o.Name)  [$($t.Module)]"
            } catch {
                # ⚠ jar 被占用 = 游戏还开着。
                #    必须让用户先关游戏，不能静默跳过 ——
                #    两个版本的 mod id 相同，加载器会直接启动失败：
                #      "Duplicate mod id: ironfarm"
                throw "无法删除旧版本 $($o.Name)（文件被占用，游戏可能还开着）。请先关闭 Minecraft 再重新打包。目标：$($t.Dir)"
            }
        }
    }
    Copy-Item $jar.FullName $t.Dir -Force
    Write-Host "  部署：$($jar.Name) → $($t.Dir)"
}

Write-Host ""
Write-Host "=== 已部署到四个测试客户端 ==="
foreach ($t in $DeployTargets) {
    $cur = Get-ChildItem $t.Dir -Filter 'ironfarm-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cur) {
        Write-Host ("  {0,-28} {1}" -f $t.Module, $cur.Name)
    }
}
