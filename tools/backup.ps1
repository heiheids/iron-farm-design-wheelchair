# ---------------------------------------------------------------
# 自动备份：把当前源码状态提交到 git
#
# 用法：
#   .\tools\backup.ps1                     # 自动生成说明
#   .\tools\backup.ps1 -Message "改了什么"  # 自定义说明
#   .\tools\backup.ps1 -Tag v0.44.0        # 同时打标签（重要节点）
#
# 设计原则：
#   · 没变化就不提交（不产生空提交污染历史）
#   · 只备份源码，不备份 build 产物（见 .gitignore）
#   · 失败不打断主流程 —— 备份是保险，不是任务本身
# ---------------------------------------------------------------

param(
    [string]$Message = "",
    [string]$Tag = ""
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

# 没有 git 就静默跳过，不要因为备份问题打断开发
$git = Get-Command git -ErrorAction SilentlyContinue
if (-not $git) {
    Write-Host "  [backup] 未安装 git，跳过" -ForegroundColor DarkYellow
    exit 0
}
if (-not (Test-Path (Join-Path $Root ".git"))) {
    Write-Host "  [backup] 未初始化仓库，跳过" -ForegroundColor DarkYellow
    exit 0
}

# ---- 检查有没有实际变化 ----
$status = git status --porcelain
if ([string]::IsNullOrWhiteSpace($status)) {
    Write-Host "  [backup] 无改动，跳过提交" -ForegroundColor DarkGray
    if ($Tag) {
        git tag -f $Tag 2>&1 | Out-Null
        Write-Host "  [backup] 已在当前提交打标签 $Tag" -ForegroundColor DarkGray
    }
    exit 0
}

$changedCount = ($status -split "`n" | Where-Object { $_.Trim() }).Count

# ---- 没给说明就自动生成 ----
if ([string]::IsNullOrWhiteSpace($Message)) {
    $version = "?"
    $propsPath = Join-Path $Root "gradle.properties"
    if (Test-Path $propsPath) {
        $m = Select-String -Path $propsPath -Pattern '^mod_version=(.+)$' | Select-Object -First 1
        if ($m) { $version = $m.Matches[0].Groups[1].Value.Trim() }
    }
    $Message = "v$version 自动备份（$changedCount 个文件变动）"
}

git add -A 2>&1 | Out-Null

# 再次确认暂存区非空（防止只有被 ignore 的变化）
$staged = git diff --cached --name-only
if ([string]::IsNullOrWhiteSpace($staged)) {
    Write-Host "  [backup] 改动都被忽略了，跳过" -ForegroundColor DarkGray
    exit 0
}

# 提交信息带时间戳，方便事后定位
$stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
git commit -q -m "$Message" -m "备份时间：$stamp" 2>&1 | Out-Null

if ($LASTEXITCODE -ne 0) {
    Write-Host "  [backup] 提交失败（退出码 $LASTEXITCODE）" -ForegroundColor Yellow
    exit 0
}

$hash = (git rev-parse --short HEAD).Trim()
Write-Host "  [backup] 已提交 $hash  $Message" -ForegroundColor DarkGreen

if ($Tag) {
    git tag -f $Tag 2>&1 | Out-Null
    Write-Host "  [backup] 已打标签 $Tag" -ForegroundColor DarkGreen
}
