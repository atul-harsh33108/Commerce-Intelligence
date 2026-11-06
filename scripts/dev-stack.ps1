<#
.SYNOPSIS
  Start, stop and check the Java services on Windows without Docker.

.DESCRIPTION
  `docker compose --profile apps up` is the supported way to run everything, and it is what the
  README documents. This script exists for the development loop it cannot serve: rebuilding one
  service and restarting only that one, in seconds, against the infrastructure containers.

  It is deliberately PowerShell rather than bash. On Windows, `nohup java ... &` from Git Bash
  produces a process that the same shell cannot reliably kill later - `pkill -f` matches nothing,
  because the process was started by a POSIX emulation layer and is owned by Windows. That was not a
  guess: it cost a wrong-jar debugging session in Step 1, where a stale gateway kept serving a route
  table that had been fixed twenty minutes earlier.

.EXAMPLE
  .\scripts\dev-stack.ps1 start
  .\scripts\dev-stack.ps1 restart catalog-service
  .\scripts\dev-stack.ps1 status
  .\scripts\dev-stack.ps1 stop
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'restart', 'status')]
    [string]$Action = 'status',

    [Parameter(Position = 1)]
    [string]$Service
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$platform = Join-Path $root 'ecommerce-platform'
$logs = Join-Path $root '.logs'

# Start order matters, and only for the first two: everything else fails fast without the config
# server and would simply refuse to start.
$services = [ordered]@{
    'config-server'    = 8888
    'discovery-server' = 8761
    'api-gateway'      = 9090   # actuator port; 8080 carries traffic
    'user-service'     = 8081
    'catalog-service'  = 8082
    'inventory-service' = 8083
    'order-service'    = 8084
    'payment-service'  = 8085
    'search-service'   = 8086
}

function Get-ServiceProcess([string]$name) {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like "*$name-0.0.1-SNAPSHOT.jar*" }
}

function Start-One([string]$name) {
    $jar = Join-Path $platform "$name\target\$name-0.0.1-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) {
        Write-Host "  $name : no jar, skipping (run ./mvnw package)" -ForegroundColor DarkYellow
        return
    }
    if (Get-ServiceProcess $name) {
        Write-Host "  $name : already running" -ForegroundColor DarkGray
        return
    }
    if (-not (Test-Path $logs)) { New-Item -ItemType Directory -Path $logs | Out-Null }
    Start-Process -FilePath 'java' `
        -ArgumentList '-jar', "target\$name-0.0.1-SNAPSHOT.jar" `
        -WorkingDirectory (Join-Path $platform $name) `
        -RedirectStandardOutput (Join-Path $logs "$name.log") `
        -RedirectStandardError (Join-Path $logs "$name.err") `
        -WindowStyle Hidden | Out-Null
    Write-Host "  $name : started" -ForegroundColor Green
}

function Stop-One([string]$name) {
    $procs = Get-ServiceProcess $name
    if (-not $procs) { Write-Host "  $name : not running" -ForegroundColor DarkGray; return }
    $procs | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Write-Host "  $name : stopped" -ForegroundColor Yellow
}

function Wait-Healthy([string]$name, [int]$port, [int]$timeoutSeconds = 90) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-RestMethod -Uri "http://localhost:$port/actuator/health" -TimeoutSec 2
            if ($r.status -eq 'UP') { return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    return $false
}

$targets = if ($Service) { @($Service) } else { $services.Keys }

switch ($Action) {
    'start' {
        Write-Host "Starting:" -ForegroundColor Cyan
        foreach ($name in $targets) {
            Start-One $name
            # The config server is the one hard ordering constraint: everything else has
            # `fail-fast: true` and gives up after ~30s of retries if it is not there yet.
            if ($name -eq 'config-server') {
                if (-not (Wait-Healthy $name 8888)) { throw "config-server never became healthy" }
            }
        }
    }
    'stop' {
        Write-Host "Stopping:" -ForegroundColor Cyan
        foreach ($name in $targets) { Stop-One $name }
    }
    'restart' {
        foreach ($name in $targets) { Stop-One $name }
        Start-Sleep -Seconds 2
        foreach ($name in $targets) { Start-One $name }
    }
    'status' {
        foreach ($name in $services.Keys) {
            $proc = Get-ServiceProcess $name
            $port = $services[$name]
            $health = 'n/a'
            if ($proc) {
                try { $health = (Invoke-RestMethod "http://localhost:$port/actuator/health" -TimeoutSec 2).status }
                catch { $health = 'starting' }
            }
            $pidText = if ($proc) { ($proc | Select-Object -First 1).ProcessId } else { '-' }
            '{0,-18} pid {1,-8} {2}' -f $name, $pidText, $health
        }
    }
}
