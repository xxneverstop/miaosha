#requires -Version 5.1
<#
.SYNOPSIS
在 Windows 本机的专用测试库里注册一个新用户并验证一笔真实秒杀订单。
.EXAMPLE
.\scripts\smoke-order.ps1 -TestDatabase miaosha
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$TestDatabase,
    [string]$AppLogPath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'logs/local-dev-app.log')
)

$ErrorActionPreference = 'Stop'
$baseUrl = 'http://127.0.0.1:18091'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$stockQuery = "SELECT CONCAT_WS('|', s.stock, i.sales) FROM item_stock s JOIN item i ON i.id = s.item_id WHERE i.id = 6;"

function Read-TestSql([string]$Sql) {
    # 用容器原有的 MySQL 密码，SQL 走标准输入。
    $rows = $Sql | docker --context desktop-linux exec -i miaosha-mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD exec mysql -uroot -NB --connect-timeout=5 -D $1' sh $TestDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "无法查询 miaosha-mysql 中的测试库 $TestDatabase，请确认容器和测试库已就绪。"
    }
    return ($rows -join "`n").Trim()
}

function Read-TestRedis([string[]]$Command) {
    $result = docker --context desktop-linux exec --env REDISCLI_AUTH miaosha-redis redis-cli --raw -n 10 @Command
    if ($LASTEXITCODE -ne 0 -or $result -match '^(ERR|NOAUTH|WRONGPASS|AUTH failed|Could not connect)') {
        throw '无法读取 miaosha-redis 的 DB 10，请确认容器就绪及 Redis 密码正确。'
    }
    return ($result -join "`n").Trim()
}

function Invoke-TestApi([string]$Path, [hashtable]$Form) {
    $request = @{
        Uri = "$baseUrl$Path"
        WebSession = $session
        TimeoutSec = 10
        MaximumRedirection = 0
    }
    if ($null -ne $Form) {
        $request.Method = 'Post'
        $request.ContentType = 'application/x-www-form-urlencoded'
        $request.Body = $Form
    }

    try {
        $response = Invoke-RestMethod @request
    } catch {
        throw "请求 $Path 失败。$($_.Exception.Message)"
    }
    if ($response.status -cne 'success') {
        throw "$Path 业务失败 [$($response.data.errCode)] $($response.data.errMsg)"
    }
    return $response.data
}

function Wait-AppLog([regex]$Pattern, [string]$Failure) {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        # OTP 只写到控制台，启动脚本把控制台输出也存到本地日志。
        $match = Select-String -LiteralPath $AppLogPath -Pattern $Pattern.ToString() | Select-Object -Last 1
        if ($null -ne $match) {
            return $match.Matches[0]
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw $Failure
}

$previousRedisAuth = $env:REDISCLI_AUTH
try {
    Write-Host "[1/6] 检查本机后端日志和专用测试库 $TestDatabase" -ForegroundColor Cyan
    if (-not (Test-Path -LiteralPath $AppLogPath -PathType Leaf)) {
        throw "找不到 $AppLogPath，请先用 start-dev.ps1 启动后端。"
    }

    # 固定使用指南里的本机后端和容器，避免把订单发到远端 Docker 环境。
    $dockerEndpoint = docker --context desktop-linux context inspect desktop-linux --format '{{.Endpoints.docker.Host}}'
    if ($LASTEXITCODE -ne 0 -or $dockerEndpoint -ne 'npipe:////./pipe/dockerDesktopLinuxEngine') {
        throw '找不到 Docker Desktop 的本机 Linux Engine 上下文。请打开 Docker Desktop 并切到 Linux 容器。'
    }

    $promo = Read-TestSql "SELECT CONCAT_WS('|', item_id, IF(NOW() BETWEEN start_date AND end_date, 1, 0)) FROM promo WHERE id = 1;"
    if ($promo -ne '6|1') {
        throw '测试活动 1 未关联商品 6 或当前不在活动期，请按开发指南第 7.2 节准备活动。'
    }
    $before = Read-TestSql $stockQuery
    if ($before -notmatch '^([1-9][0-9]*)\|([0-9]+)$') {
        throw '商品 6 缺少可用库存或销量数据，请核对测试库的种子数据。'
    }
    $initialStock = [long]$Matches[1]
    $initialSales = [long]$Matches[2]

    $env:REDISCLI_AUTH = $env:REDIS_PASSWORD
    if (-not $env:REDISCLI_AUTH) {
        $env:REDISCLI_AUTH = $env:SPRING_REDIS_PASSWORD
    }
    if (-not $env:REDISCLI_AUTH) {
        $secret = Read-Host '请输入本机 Redis 的现用密码' -AsSecureString
        $env:REDISCLI_AUTH = [System.Net.NetworkCredential]::new('', $secret).Password
    }
    if (-not $env:REDISCLI_AUTH) { throw 'Redis 密码不能为空。' }

    $cachedStock = Read-TestRedis @('GET', 'promo_item_stock_6')
    if ($cachedStock -ne '' -and $cachedStock -ne [string]$initialStock) {
        throw "预热前库存尚未对齐（Redis $cachedStock，MySQL $initialStock），请先等待此前订单同步并核对库存。"
    }

    Write-Host '[2/6] 申请 OTP，注册并登录测试账号' -ForegroundColor Cyan
    $phone = "199$(Get-Random -Minimum 10000000 -Maximum 100000000)"
    $password = [guid]::NewGuid().ToString('N')
    $userQuery = "SELECT id FROM user_info WHERE telphone = '$phone';"
    if (Read-TestSql $userQuery) { throw '生成的测试手机号已存在，请再运行一次。' }

    $null = Invoke-TestApi '/user/getotp' @{ telphone = $phone }
    $otp = Wait-AppLog "telphone = $phone & otpCode = ([0-9]+)" '未在后端日志里找到本次注册 OTP。'
    # OTP 和注册共用一个 HTTP 会话，登录凭证只放在后续请求的表单里。
    $null = Invoke-TestApi '/user/register' @{
        telphone = $phone; otpCode = $otp.Groups[1].Value; name = 'S0 Smoke'; gender = 1; age = 20; password = $password
    }
    $loginToken = [string](Invoke-TestApi '/user/login' @{ telphone = $phone; password = $password })
    if ($loginToken -cnotmatch '^[a-f0-9]{32}$') { throw '登录响应缺少有效的登录凭证。' }
    $userId = Read-TestSql $userQuery
    # 先确认后端确实使用所选 MySQL 和 Redis，随后才允许预热商品库存。
    if ($userId -notmatch '^[1-9][0-9]*$' -or (Read-TestRedis @('EXISTS', $loginToken)) -ne '1') {
        throw '未在目标测试库和 Redis 中找到本轮注册、登录记录，已停止预热。'
    }

    Write-Host "[3/6] 预热活动 1（商品 6，库存 $initialStock）" -ForegroundColor Cyan
    $null = Invoke-TestApi '/item/publishpromo?id=1'
    if ((Read-TestRedis @('GET', 'promo_item_stock_6')) -ne [string]$initialStock) {
        throw '预热后 Redis 库存与 MySQL 基线不一致。'
    }

    Write-Host '[4/6] 获取秒杀令牌，提交一笔购买 1 件的订单' -ForegroundColor Cyan
    $promoToken = [string](Invoke-TestApi '/order/generatetoken' @{
        token = $loginToken; itemId = 6; promoId = 1; verifyCode = ''
    })
    if (-not $promoToken) { throw '接口返回成功，但未发放秒杀令牌。' }
    $null = Invoke-TestApi '/order/createorder' @{
        token = $loginToken; itemId = 6; promoId = 1; promoToken = $promoToken; amount = 1
    }

    Write-Host '[5/6] 核对订单事务和库存流水，等待 RocketMQ 同步' -ForegroundColor Cyan
    $orderLog = Wait-AppLog "Order committed, userId=$userId, orderId=([0-9]+), stockLogId=([a-f0-9]{32})" '下单接口返回成功，但未找到本轮订单和库存流水的关联日志。'
    $orderId = $orderLog.Groups[1].Value
    $stockLogId = $orderLog.Groups[2].Value
    Write-Host "      orderId=$orderId  stockLogId=$stockLogId"

    $order = Read-TestSql "SELECT CONCAT_WS('|', id, item_id, promo_id, amount) FROM order_info WHERE user_id = $userId;"
    $stockLog = Read-TestSql "SELECT CONCAT_WS('|', item_id, amount, status) FROM stock_log WHERE stock_log_id = '$stockLogId';"
    if ($order -ne "$orderId|6|1|1" -or $stockLog -ne '6|1|2') {
        throw '订单数量、商品、活动或库存流水状态与本轮请求不符。'
    }

    $expectedStock = $initialStock - 1
    $expectedSales = $initialSales + 1
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $dbState = Read-TestSql $stockQuery
        $redisStock = Read-TestRedis @('GET', 'promo_item_stock_6')
        if ($dbState -eq "$expectedStock|$expectedSales" -and $redisStock -eq [string]$expectedStock) {
            Write-Host "[6/6] 冒烟通过。新增订单 1 笔，Redis 和 MySQL 库存 $initialStock -> $expectedStock，销量 $initialSales -> $expectedSales。" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "30 秒内库存在预期值上未收敛。Redis 库存=$redisStock，MySQL 库存|销量=$dbState，预期=$expectedStock|$expectedSales。订单已提交，请先排查 MQ 再重跑。"
} finally {
    $env:REDISCLI_AUTH = $previousRedisAuth
}
