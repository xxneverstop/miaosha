#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$smokeScript = Join-Path (Split-Path -Parent $PSScriptRoot) 'smoke-order.ps1'
$savedPassword = $env:REDIS_PASSWORD
$savedAuth = $env:REDISCLI_AUTH

# 从被测脚本调用模拟命令时仍使用同一份状态。
$global:MiaoshaSmokeFixture = @{
    Log = [IO.Path]::GetTempFileName()
    OrderId = '2026112300000001'
    StockLogId = 'acef0123acef0123acef0123acef0123'
    LoginToken = 'abcde012abcde012abcde012abcde012'
    Requests = @()
}

function docker {
    $fixture = $global:MiaoshaSmokeFixture
    $global:LASTEXITCODE = 0
    if ($args[2] -eq 'context') { return 'npipe:////./pipe/dockerDesktopLinuxEngine' }

    if ($args -contains 'miaosha-mysql') {
        switch -Regex ($input | Out-String) {
            'FROM promo WHERE' { return '6|1' }
            'FROM item_stock' { if ($fixture.Placed) { return '9|6' }; return '10|5' }
            'FROM user_info' { if ($fixture.Registered) { return '42' }; return }
            'FROM order_info' { return "$($fixture.OrderId)|6|1|1" }
            'FROM stock_log' { return '6|1|2' }
        }
    }
    if ($args -contains 'miaosha-redis') {
        if ($args -contains 'EXISTS') { if ($fixture.Mismatch) { return '0' }; return '1' }
        if ($fixture.Placed) { return '9' }
        return '10'
    }
    throw "Unexpected Docker arguments: $args"
}

function Invoke-RestMethod {
    param($Uri, $WebSession, $TimeoutSec, $MaximumRedirection, $Method, $ContentType, $Body)
    $fixture = $global:MiaoshaSmokeFixture
    $path = ([uri]$Uri).PathAndQuery
    $fixture.Requests += $path
    $data = $null

    switch ($path) {
        '/user/getotp' {
            $fixture.Session = $WebSession
            Add-Content -LiteralPath $fixture.Log -Value "telphone = $($Body.telphone) & otpCode = 12345"
        }
        '/user/register' {
            if ($WebSession -ne $fixture.Session -or $Body.otpCode -ne '12345') { throw 'Wrong OTP session' }
            $fixture.Registered = $true
        }
        '/user/login' { $data = $fixture.LoginToken }
        '/item/publishpromo?id=1' {}
        '/order/generatetoken' {
            if ($Body.token -ne $fixture.LoginToken) { throw 'Wrong login token' }
            $data = 'fixture-promo-token'
        }
        '/order/createorder' {
            if ($Body.amount -ne 1 -or $Body.promoToken -ne 'fixture-promo-token') { throw 'Wrong order' }
            $fixture.Placed = $true
            Add-Content -LiteralPath $fixture.Log -Value "Order committed, userId=42, orderId=$($fixture.OrderId), stockLogId=$($fixture.StockLogId)"
        }
        default { throw "Unexpected request: $path" }
    }
    return @{ status = 'success'; data = $data }
}

try {
    $env:REDIS_PASSWORD = 'fake-password'
    $env:REDISCLI_AUTH = 'previous-auth'
    $fixture = $global:MiaoshaSmokeFixture
    & $smokeScript -TestDatabase miaosha -AppLogPath $fixture.Log
    if (-not $fixture.Placed -or $fixture.Requests.Count -ne 6 -or $env:REDISCLI_AUTH -ne 'previous-auth') {
        throw 'Smoke order did not complete as expected'
    }

    Clear-Content -LiteralPath $fixture.Log
    $fixture.Registered = $false
    $fixture.Placed = $false
    $fixture.Mismatch = $true
    $fixture.Requests = @()
    try {
        & $smokeScript -TestDatabase miaosha -AppLogPath $fixture.Log
        throw 'Expected a Redis target mismatch'
    } catch {
        if ($_.Exception.Message -notlike '*已停止预热*' -or $fixture.Requests.Count -ne 3 -or $fixture.Placed) {
            throw
        }
    }
    if ($env:REDISCLI_AUTH -ne 'previous-auth') { throw 'Redis environment was not restored on failure' }
    Write-Host '冒烟脚本模拟验证通过。' -ForegroundColor Green
} finally {
    Remove-Item -LiteralPath $global:MiaoshaSmokeFixture.Log
    Remove-Variable -Name MiaoshaSmokeFixture -Scope Global
    $env:REDIS_PASSWORD = $savedPassword
    $env:REDISCLI_AUTH = $savedAuth
}
