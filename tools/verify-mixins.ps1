# Mixin 配置校验。
#
# 为什么需要：Mixin 的失败是**静默的** —— 目标方法名写错、目标类不存在、
# @Shadow 字段拼错，编译全过、jar 能打出来，加载时只 warn 一句，
# 结果就是"这个功能莫名其妙不生效"。
#
# 设计决定：本工具用 **named**（Mojang 映射）的 jar 做对照。
#   - 源码里 @Mixin(MerchantScreen.class) / @Inject(method="init") 都是 named 名
#   - intermediary 层的 remap 正确性由 verify-refs.ps1 覆盖
#   - 硬要用 intermediary 对照，就得自己解析 tiny 的类映射 + 方法映射 +
#     "方法声明在父类上"，连踩三个坑，工具比被测对象还复杂（已试过，放弃了）
#
# 用法（从工程根目录）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-mixins.ps1

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

# 1) 模组 jar
$libs = Join-Path $Root 'fabric-1.21.1/build/libs'
$mod = Get-ChildItem $libs -Filter '*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notmatch 'sources|javadoc|dev' } |
       Select-Object -First 1
if (-not $mod) {
    throw "找不到模组 jar，请先跑 gradle :fabric-1.21.1:build"
}

# 2) named 的 Minecraft jar（注意：与 verify-refs 用的 intermediary 不是同一个）
$mc = $null
foreach ($dir in @((Join-Path $Root '.gradle/loom-cache/minecraftMaven'),
                   "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven")) {
    if (-not (Test-Path $dir)) { continue }
    $found = Get-ChildItem $dir -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
             Where-Object { $_.Name -match 'minecraft-merged-' -and
                            $_.Name -notmatch 'sources|intermediary' } |
             Select-Object -First 1
    if ($found) { $mc = $found.FullName; break }
}
if (-not $mc) {
    throw "找不到 named Minecraft jar，请先跑一次 gradle :fabric-1.21.1:build"
}

# 3) mixin 配置
$mixinJson = Join-Path $Root 'fabric-1.21.1/src/main/resources/ironfarm.mixins.json'
if (-not (Test-Path $mixinJson)) {
    throw "找不到 $mixinJson"
}

# 4) java
$java = $null
foreach ($j in @('D:\工程-泡世界\mcmod\tools\jdk25\bin\java.exe', "$env:JAVA_HOME\bin\java.exe")) {
    if ($j -and (Test-Path $j)) { $java = $j; break }
}

Write-Host "mod   = $($mod.FullName)"
Write-Host "mc    = $mc"
Write-Host "mixin = $mixinJson"
Write-Host ""

Push-Location $Root
try {
    & $java (Join-Path $Root 'tools/MixinCheck.java') $mod.FullName $mc $mixinJson
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
