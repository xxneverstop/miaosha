#requires -Version 5.1
<#
.SYNOPSIS
    启动 Windows 本机开发环境，前台运行 Maven。
.DESCRIPTION
    使用 Windows 指南中首次创建的容器和 Topic。请使用已有实例的密码。
    当前终端已设置 MYSQL_PASSWORD 和 REDIS_PASSWORD 时可直接复用。
.EXAMPLE
    .\scripts\start-dev.ps1
.EXAMPLE
    .\scripts\start-dev.ps1 -JavaHome 'C:\Java\jdk8'
#>
[CmdletBinding()]
param([string]$JavaHome = 'D:\software\jdk-8')

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectRoot = Split-Path -Parent $PSScriptRoot
$containers = @('miaosha-mysql', 'miaosha-redis', 'miaosha-rocketmq-namesrv', 'miaosha-rocketmq-broker')

function Invoke-LocalDocker {
    # 显式选择本机 Desktop，避免当前 Docker 上下文指向远程服务器。
    $ErrorActionPreference = 'Continue'
    & docker --context desktop-linux @args
}

function Wait-Dependency {
    param([string]$Name, [scriptblock]$Probe, [string]$Hint)

    Write-Host "  等待 $Name 就绪…"
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        if (& $Probe) {
            Write-Host "  [通过] $Name" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Name 未就绪。$Hint"
}

function Test-LocalPort {
    param([int]$Port)

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        return $client.ConnectAsync('127.0.0.1', $Port).Wait(1000) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

Write-Host '[1/5] 检查 JDK 8、Maven 和本机 Docker Desktop' -ForegroundColor Cyan
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw "找不到 $java，请用 -JavaHome 指定 JDK 8。" }
$jdkPath = (Resolve-Path -LiteralPath $JavaHome).Path
Get-Command mvn, docker -ErrorAction Stop | Out-Null
$endpoint = Invoke-LocalDocker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}' 2>$null
if ($LASTEXITCODE -ne 0 -or $endpoint -ne 'npipe:////./pipe/dockerDesktopLinuxEngine') {
    throw '找不到 Docker Desktop 的本机 Linux Engine 上下文。请打开 Docker Desktop 并切到 Linux 容器。'
}
$engine = Invoke-LocalDocker version --format '{{.Server.Version}}' 2>$null
if ($LASTEXITCODE -ne 0 -or -not $engine) { throw '连不上本机 Docker Engine，请确认 Docker Desktop 已就绪。' }
Write-Host "  Docker Engine $engine"
if (Get-NetTCPConnection -LocalPort 18091 -State Listen -ErrorAction SilentlyContinue) {
    throw '端口 18091 已被占用。请检查当前监听进程，并在原终端停止旧后端。'
}

$available = @(Invoke-LocalDocker ps --all --filter 'network=miaosha-net' --format '{{.Names}}')
if ($LASTEXITCODE -ne 0) { throw '查询本机开发容器失败。' }
$missing = @($containers | Where-Object { $_ -notin $available })
if ($missing.Count -gt 0) {
    throw "开发容器缺失（$($missing -join '、')）。首次创建请参照 Windows 指南第 4 节。"
}

# 环境变量只用于这次脚本及其子进程，退出后恢复原终端状态。
$settings = @{
    JAVA_HOME = $jdkPath
    Path = "$jdkPath\bin;$env:Path"
    SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:13306/miaosha?useUnicode=true&characterEncoding=UTF-8&useSSL=false'
    SPRING_DATASOURCE_USERNAME = 'root'
    SPRING_DATASOURCE_PASSWORD = $env:MYSQL_PASSWORD
    SPRING_REDIS_HOST = '127.0.0.1'
    SPRING_REDIS_PORT = '16379'
    SPRING_REDIS_PASSWORD = $env:REDIS_PASSWORD
    MQ_NAMESERVER_ADDR = '127.0.0.1:19876'
    MQ_TOPICNAME = 'stock_topic'
}
$saved = @{}
foreach ($name in @($settings.Keys) + @('MYSQL_PWD', 'REDISCLI_AUTH')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name)
}

try {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $settings.JAVA_HOME)
    [Environment]::SetEnvironmentVariable('Path', $settings.Path)
    $mavenVersion = & mvn -B -version
    if ($LASTEXITCODE -ne 0 -or ($mavenVersion -join "`n") -notmatch 'Java version:\s+1\.8\.') {
        throw 'Maven 未使用 JDK 8，请检查 -JavaHome 和 mvn -version。'
    }
    $mavenVersion | Where-Object { $_ -match 'Apache Maven|Java version:' } | ForEach-Object { Write-Host "  $_" }

    Write-Host '[2/5] 读取已有本机实例的密码' -ForegroundColor Cyan
    foreach ($setting in @('SPRING_DATASOURCE_PASSWORD', 'SPRING_REDIS_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace($settings[$setting])) {
            $service = if ($setting -eq 'SPRING_DATASOURCE_PASSWORD') { 'MySQL' } else { 'Redis' }
            $password = Read-Host "请输入本机 $service 现用密码" -AsSecureString
            $settings[$setting] = [System.Net.NetworkCredential]::new('', $password).Password
            if ([string]::IsNullOrWhiteSpace($settings[$setting])) { throw "$service 密码不能为空。" }
        }
    }
    foreach ($name in $settings.Keys) { [Environment]::SetEnvironmentVariable($name, $settings[$name]) }

    Write-Host '[3/5] 启动已有开发容器' -ForegroundColor Cyan
    foreach ($container in $containers) {
        Invoke-LocalDocker start $container | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "启动 $container 失败。" }
        Write-Host "  [通过] $container 已接收启动请求"
    }

    Write-Host '[4/5] 验证数据库、Redis 和 RocketMQ' -ForegroundColor Cyan
    # 用进程环境传连接密码，避免出现在命令参数或终端输出里。
    $env:MYSQL_PWD = $settings.SPRING_DATASOURCE_PASSWORD
    $env:REDISCLI_AUTH = $settings.SPRING_REDIS_PASSWORD
    Wait-Dependency 'MySQL（127.0.0.1:13306）' {
        if (-not (Test-LocalPort 13306)) { return $false }
        Invoke-LocalDocker exec -e MYSQL_PWD miaosha-mysql mysql --protocol=TCP -h127.0.0.1 -uroot miaosha -e 'SELECT 1 FROM item_stock LIMIT 0' *> $null
        return $LASTEXITCODE -eq 0
    } '请检查现用密码、端口和 miaosha 库是否已初始化。'
    Wait-Dependency 'Redis（127.0.0.1:16379，DB 10）' {
        if (-not (Test-LocalPort 16379)) { return $false }
        $pong = Invoke-LocalDocker exec -e REDISCLI_AUTH miaosha-redis redis-cli -n 10 PING 2>$null
        return $LASTEXITCODE -eq 0 -and $pong -eq 'PONG'
    } '请检查现用密码和端口映射。'
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $saved.MYSQL_PWD)
    [Environment]::SetEnvironmentVariable('REDISCLI_AUTH', $saved.REDISCLI_AUTH)
    Wait-Dependency 'RocketMQ（stock_topic 路由和 VIP 端口）' {
        # 仅连上 NameServer 不代表 Broker 已注册，Producer 仍需要 VIP 端口。
        if (-not ((Test-LocalPort 19876) -and (Test-LocalPort 10911) -and (Test-LocalPort 10909))) { return $false }
        $route = Invoke-LocalDocker exec miaosha-rocketmq-broker sh mqadmin topicRoute -n miaosha-rocketmq-namesrv:9876 -t stock_topic 2>$null
        return $LASTEXITCODE -eq 0 -and ($route -join "`n") -match '127\.0\.0\.1:10911'
    } '请检查 Broker 通告地址、10909 端口和 stock_topic，见 Windows 指南第 4 节。'

    $logDir = Join-Path $projectRoot 'logs'
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    $logPath = Join-Path $logDir 'local-dev-app.log'
    Write-Host '[5/5] 在前台运行 Maven，按 Ctrl+C 停止后端' -ForegroundColor Cyan
    Write-Host "  后端 http://localhost:18091，看到 Started App 后可验证接口。"
    Write-Host "  本次日志 $logPath"
    Push-Location -LiteralPath $projectRoot
    try {
        # Maven 原生 stderr 也写入日志，实际失败以进程退出码为准。
        & {
            $ErrorActionPreference = 'Continue'
            & mvn spring-boot:run 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -LiteralPath $logPath
        }
        if ($LASTEXITCODE -ne 0) { throw "Maven 已退出，退出码 $LASTEXITCODE。请查看上方日志。" }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
}
