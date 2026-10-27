# ---------------------------------------------------------------
# 从备份恢复源码
#
# 用法：
#   .\tools\restore.ps1 -List            看所有备份点
#   .\tools\restore.ps1 -Check           看当前有哪些未提交改动
#   .\tools\restore.ps1 -Last            丢弃未提交改动，回到上次备份
#   .\tools\restore.ps1 -Hash 6641c0a    恢复到指定提交
#   .\tools\restore.ps1 -Tag v0.43.0     恢复到指定标签
#
# 设计要点（都是踩坑换来的）：
#   · -Last 绝不先做 rescue 提交 —— 那会把坏状态变成 HEAD，
#     然后恢复到的正好是刚存下的坏状态，等于没恢复。
#   · -Last 丢弃前先把 diff 存成 patch 文件，手滑了还能捡回来。
#   · -Hash/-Tag 恢复前会做 rescue 提交（这次是对的，因为目标提交明确）。
# ---------------------------------------------------------------

param(
    [switch]$List,
    [switch]$Check,
    [switch]$Last,
    [string]$Hash = "",
    [string]$Tag = ""
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

if (-not (Test-Path (Join-Path $Root ".git"))) {
    Write-Host "没有备份仓库（.git 不存在）" -ForegroundColor Red
    exit 1
}

# ================================================================
# 列出备份点（无参数时的默认行为）
# ================================================================
if ($List -or (-not $Check -and -not $Last -and -not $Hash -and -not $Tag)) {
    Write-Host ""
    Write-Host "=== 备份历史（新 -> 旧，最多 30 条）===" -ForegroundColor Cyan
    git log --pretty=format:"  %h  %ad  %s" --date=format:'%m-%d %H:%M' -30
    Write-Host ""
    Write-Host ""

    $tags = git tag
    if ($tags) {
        Write-Host "=== 标签 ===" -ForegroundColor Cyan
        foreach ($t in $tags) {
            $h = (git rev-list -n 1 $t).Trim()
            if ($h.Length -ge 7) { $h = $h.Substring(0, 7) }
            Write-Host "  $t  ($h)"
        }
        Write-Host ""
    }

    Write-Host "恢复到某个提交： .\tools\restore.ps1 -Hash <前7位>" -ForegroundColor DarkGray
    Write-Host "丢弃未提交改动： .\tools\restore.ps1 -Last" -ForegroundColor DarkGray
    Write-Host ""
    exit 0
}

# ================================================================
# 查看当前未提交改动
# ================================================================
if ($Check) {
    $status = git status --porcelain
    if ([string]::IsNullOrWhiteSpace($status)) {
        Write-Host "当前没有未提交的改动" -ForegroundColor Green
    } else {
        Write-Host ""
        Write-Host "=== 当前未提交的改动 ===" -ForegroundColor Yellow
        git status --short
        Write-Host ""
        Write-Host "丢弃这些改动： .\tools\restore.ps1 -Last" -ForegroundColor DarkGray
    }
    Write-Host ""
    exit 0
}

# ================================================================
# -Last：丢弃未提交改动，回到最近一次备份
# ================================================================
if ($Last) {
    $status = git status --porcelain
    if ([string]::IsNullOrWhiteSpace($status)) {
        Write-Host "  [restore] 没有未提交的改动，已经是上次备份的状态" -ForegroundColor DarkGray
        Write-Host ""
        exit 0
    }

    # 先把要丢弃的内容存成 patch（比 commit 安全，也不污染历史）
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $rescueDir = Join-Path $Root "build\rescue"
    New-Item -ItemType Directory -Force -Path $rescueDir | Out-Null
    $patchPath = Join-Path $rescueDir "dropped-$stamp.patch"
    git diff > $patchPath 2>&1 | Out-Null
    if ((Test-Path $patchPath) -and ((Get-Item $patchPath).Length -gt 0)) {
        Write-Host "  [restore] 被丢弃的改动已存为 build\rescue\dropped-$stamp.patch" -ForegroundColor DarkYellow
    } elseif (Test-Path $patchPath) {
        Remove-Item $patchPath -Force -ErrorAction SilentlyContinue
    }

    # 恢复已跟踪文件到 HEAD
    git checkout -- . 2>&1 | Out-Null
    # 删掉新增的未跟踪文件（保留 build / .gradle / zip）
    git clean -fd -e build -e .gradle -e "*.zip" 2>&1 | Out-Null

    Write-Host "  [restore] 已丢弃未提交改动，回到最近一次备份" -ForegroundColor Green
    Write-Host ""
    exit 0
}

# ================================================================
# -Hash / -Tag：恢复到指定提交
# ================================================================
$target = ""
if ($Tag) {
    $target = (git rev-list -n 1 $Tag 2>&1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($target)) {
        Write-Host "找不到标签：$Tag" -ForegroundColor Red
        exit 1
    }
} elseif ($Hash) {
    $target = (git rev-parse --verify "$Hash^{commit}" 2>&1).Trim()
    if ($LASTEXITCODE -ne 0) {
        Write-Host "找不到提交：$Hash" -ForegroundColor Red
        exit 1
    }
} else {
    exit 0
}

$short = $target
if ($short.Length -ge 7) { $short = $short.Substring(0, 7) }

# 这次做 rescue 提交是对的：目标明确，当前状态值得留档
$status = git status --porcelain
if (-not [string]::IsNullOrWhiteSpace($status)) {
    git add -A 2>&1 | Out-Null
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    git commit -q -m "rescue: 恢复到 $short 之前的状态" -m "时间：$stamp" 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [restore] 当前状态已存为 rescue 提交（可再恢复回来）" -ForegroundColor DarkYellow
    }
}

Write-Host ""
Write-Host "确认把源码恢复到 $short 吗？" -ForegroundColor Yellow
$answer = Read-Host "输入 y 确认"
if ($answer -ne 'y') {
    Write-Host "已取消" -ForegroundColor DarkGray
    exit 0
}

git checkout $target -- . 2>&1 | Out-Null
Write-Host "  [restore] 源码已恢复到 $short" -ForegroundColor Green
Write-Host ""
Write-Host "注意：恢复的是源码。已构建的 jar 还在各模块 build/libs 下，" -ForegroundColor DarkGray
Write-Host "      要重新构建才会在游戏里生效。" -ForegroundColor DarkGray
Write-Host ""
