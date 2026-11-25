#requires -Version 5.1
<#
.SYNOPSIS
    停止 Windows 本机开发容器并保留数据。
.EXAMPLE
    .\scripts\stop-dev.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
Get-Command docker -ErrorAction Stop | Out-Null

Write-Host '[1/2] 检查本机 Docker Desktop' -ForegroundColor Cyan
# 固定本机 Desktop 上下文，且只选开发网络中的准确容器名。
$endpoint = & {
    $ErrorActionPreference = 'Continue'
    & docker --context desktop-linux context inspect desktop-linux --format '{{.Endpoints.docker.Host}}' 2>$null
}
if ($LASTEXITCODE -ne 0 -or $endpoint -ne 'npipe:////./pipe/dockerDesktopLinuxEngine') {
    throw '找不到 Docker Desktop 的本机 Linux Engine 上下文。请打开 Docker Desktop 并切到 Linux 容器。'
}
$running = @(& docker --context desktop-linux ps --filter 'network=miaosha-net' --format '{{.Names}}')
if ($LASTEXITCODE -ne 0) { throw '查询本机容器失败，请确认 Docker Desktop 已就绪。' }

Write-Host '[2/2] 停止开发依赖，保留容器和数据卷' -ForegroundColor Cyan
Write-Host '  前台 Maven 后端请先回原终端按 Ctrl+C。'
$failed = @()
# 先停 Broker，再停其依赖的 NameServer。
foreach ($container in @('miaosha-rocketmq-broker', 'miaosha-rocketmq-namesrv', 'miaosha-redis', 'miaosha-mysql')) {
    if ($container -notin $running) {
        Write-Host "  [跳过] $container 未运行"
        continue
    }
    & docker --context desktop-linux stop $container | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [已停止] $container" -ForegroundColor Green
    } else {
        Write-Host "  [失败] $container" -ForegroundColor Red
        $failed += $container
    }
}
if ($failed.Count -gt 0) { throw "未能停止 $($failed -join '、')，请查看上方 Docker 输出。" }
Write-Host '开发依赖已停止，容器和数据卷已保留。' -ForegroundColor Green
