# 引用完整性验证。
#
# 为什么需要：本机跑不起 Minecraft 客户端（LWJGL/SDL 初始化失败），
# 而模组的 environment 是 client，所以 gradle runServer 根本不会加载它 ——
# "无头服务端能起来"对这个模组是**无效证据**。
#
# 能替代的、真正有意义的验证：把模组 jar 里每一个对 net/minecraft 的
# 类/字段/方法引用，逐个到真实的 1.21.1 intermediary jar 里核对。
# 这就是 JVM 链接阶段会做的事，提前到构建期做。
#
# 它能抓到的典型问题：
#   - remap 错了（方法名对不上）
#   - 跨版本搬运代码时签名变了
#   - AccessWidener 开放了一个不存在的字段
#
# 用法（从工程根目录）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-refs.ps1
#
# 退出码：0 = 通过，1 = 有缺失引用。

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

# 1) 找模组 jar
$libs = Join-Path $Root 'fabric-1.21.1/build/libs'
$mod = Get-ChildItem $libs -Filter '*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notmatch 'sources|javadoc|dev' } |
       Select-Object -First 1
if (-not $mod) {
    throw "找不到模组 jar，请先跑 gradle :fabric-1.21.1:build"
}

# 2) 找 intermediary 的 Minecraft jar
#    ⚠ 必须用 intermediary 版，不能用 named 版：
#      模组 jar 里的引用是 intermediary 名（class_1646 / method_xxxx），
#      拿 named 版对照会把每一个引用都报成缺失（实测误报 39 条 class + 34 条 member）。
$candidates = @(
    "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven",
    (Join-Path $Root '.gradle/loom-cache/minecraftMaven')
)
$mc = $null
foreach ($dir in $candidates) {
    if (-not (Test-Path $dir)) { continue }
    $found = Get-ChildItem $dir -Recurse -Filter '*intermediary*.jar' -ErrorAction SilentlyContinue |
             Where-Object { $_.Name -notmatch 'sources' } |
             Select-Object -First 1
    if ($found) { $mc = $found.FullName; break }
}
if (-not $mc) {
    throw "找不到 intermediary Minecraft jar，请先跑一次 gradle :fabric-1.21.1:build"
}

# 3) 找 JDK（需要 22+ 才能用 java.lang.classfile）
$java = $null
$jdks = @(
    'D:\工程-泡世界\mcmod\tools\jdk25\bin\java.exe',
    "$env:JAVA_HOME\bin\java.exe"
)
foreach ($j in $jdks) {
    if ($j -and (Test-Path $j)) { $java = $j; break }
}
if (-not $java) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $java = $cmd.Source }
}
if (-not $java) {
    throw "找不到 java（需要 JDK 22+ 才有 java.lang.classfile）"
}

Write-Host "mod = $($mod.FullName)"
Write-Host "mc  = $mc"
Write-Host "java= $java"
Write-Host ""

& $java (Join-Path $Root 'tools/VerifyReferences.java') $mod.FullName $mc
exit $LASTEXITCODE
