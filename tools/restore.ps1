# ---------------------------------------------------------------
# 从备份恢复
#
# 用法：
#   .\tools\restore.ps1 -List                 # 看所有备份点
#   .\tools\restore.ps1 -Check                # 看当前有哪些未提交改动
#   .\tools\restore.ps1 -Last                 # 撤掉当前未提交的改动（回到上次备份）
#   .\tools\restore.ps1 -Hash 6641c0a         # 恢复到指定提交
#   .\tools\restore.ps1 -Tag v0.43.0          # 恢复到指定标签
#
# ⚠ 恢复前会把当前状态自动提交一次（命名为 rescue-...），
#   所以"恢复错了"还能再恢复回来。
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

# ---- 列出备份点 ----
if ($List -or (-not $Check -and -not $Last -and -not $Hash -and -not $Tag)) {
    Write-Host ""
    Write-Host "=== 备份历史（新 → 旧）===" -ForegroundColor Cyan
    git log --pretty=format:"  %h  %ad  %s" --date=format:'%m-%d %H:%M' -30
    Write-Host ""
    Write-Host ""
    $tags = git tag
    if ($tags) {
        Write-Host "=== 标签 ===" -ForegroundColor Cyan
        foreach ($t in $tags) {
            $h = (git rev-list -n 1 $t).Trim().Substring(0, 7)
            Write-Host "  $t  ($h)"
        }
        Write-Host ""
    }
    Write-Host "恢复到某个提交：.\tools\restore.ps1 -Hash <前7位>" -ForegroundColor DarkGray
    Write-Host "撤掉未提交改动：.\tools\restore.ps1 -Last" -ForegroundColor DarkGray
    Write-Host ""
    exit 0
}

# ---- 查看当前未提交改动 ----
if ($Check) {
    $status = git status --porcelain
    if ([string]::IsNullOrWhiteSpace($status)) {
        Write-Host "当前没有未提交的改动" -ForegroundColor Green
    } else {
        Write-Host ""
        Write-Host "=== 当前未提交的改动 ===" -ForegroundColor Yellow
        git status --short
        Write-Host ""
        Write-Host "撤掉这些改成上次备份：.\tools\restore.ps1 -Last" -ForegroundColor DarkGray
    }
    Write-Host ""
    exit 0
}

# ---- 恢复前先救命：把当前状态存一份 ----
$status = git status --porcelain
if (-not [string]::IsNullOrWhiteSpace($status)) {
    git add -A 2>&1 | Out-Null
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    git commit -q -m "rescue: 恢复前自动保存当前状态" -m "时间：$stamp" 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [restore] 当前状态已存为 rescue 提交（可再恢复回来）" -ForegroundColor DarkYellow
    }
}

# ---- -Last：撤销未提交改动 ----
if ($Last) {
    git checkout -- . 2>&1 | Out-Null
    git clean -fd -e '*.zip' -e '.gradle' -e 'build' 2>&1 | Out-Null
    Write-Host "  [restore] 已回到最近一次备份的状态" -ForegroundColor Green
    Write-Host ""
    exit 0
}

# ---- 恢复到指定提交/标签 ----
$target = ""
if ($Tag) {
    $target = (git rev-list -n 1 $Tag 2>&1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($target)) {
        Write-Host "找不到标签：$Tag" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [restore] 标签 $Tag → $($target.Substring(0,7))"
} elseif ($Hash) {
    $target = (git rev-parse --verify "$Hash^{commit}" 2>&1).Trim()
    if ($LASTEXITCODE -ne 0) {
        Write-Host "找不到提交：$Hash" -ForegroundColor Red
        exit 1
    }
} else {
    exit 0
}

Write-Host ""
Write-Host "确认要把源码恢复到 $($target.Substring(0,7)) 吗？" -ForegroundColor Yellow
Write-Host "（当前状态已自动保存，可以再恢复回来）"
$answer = Read-Host "输入 y 确认"
if ($answer -ne 'y') {
    Write-Host "已取消" -ForegroundColor DarkGray
    exit 0
}

# 用 checkout 移HEAD但保留历史，避免丢失后续提交
git checkout $target -- . 2>&1 | Out-Null
Write-Host "  [restore] 源码已恢复到 $($target.Substring(0,7))" -ForegroundColor Green
Write-Host ""
Write-Host "注意：恢复的是**源码**。已构建的 jar 还在各模块 build/libs 下，" -ForegroundColor DarkGray
Write-Host "      需要重新构建才能在游戏里生效。" -ForegroundColor DarkGray
Write-Host ""
