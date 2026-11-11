# KQuickJS 性能基准运行器（Windows PowerShell）
#
# 用法：
#   pwsh -File scripts/perf/run_perf.ps1                          # 全部基准
#   pwsh -File scripts/perf/run_perf.ps1 -SkipMicrobench          # 跳过耗时的 microbench
#   pwsh -File scripts/perf/run_perf.ps1 -MicrobenchOnly          # 只跑 microbench
#   pwsh -File scripts/perf/run_perf.ps1 -EngineFilter native,c-o2
#
# 输出：perf-results/result-<时间戳>.json，以及各引擎的 microbench 文本输出。

param(
    [string]$Jar = "kotlin\build\libs\kquickjs-1.0.0.jar",
    [string]$NativeExe = "kotlin\build\native\nativeCompile\kquickjs.exe",
    [string]$Mqjs = "cmake-build-c-o2\mqjs.exe",
    [string]$WorkloadDir = "scripts\perf\workloads",
    [string]$OutDir = "perf-results",
    [int]$Reps = 5,
    [string]$EngineFilter = "jvm,jvm-opt,native,c-o2",
    [switch]$SkipMicrobench,
    [switch]$MicrobenchOnly
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$runId = Get-Date -Format "yyyyMMdd-HHmmss"

# 引擎配置：名称 -> 可执行文件 + 固定前缀参数
$engineCfgs = [ordered]@{
    "jvm"     = @{ exe = "java"; prefix = @("-jar", $Jar) }
    "jvm-opt" = @{ exe = "java"; prefix = @("-XX:-DontCompileHugeMethods", "-jar", $Jar) }
    "native"  = @{ exe = (Resolve-Path $NativeExe); prefix = @() }
    "c-o2"    = @{ exe = (Resolve-Path $Mqjs); prefix = @() }
}

$EngineList = @($EngineFilter -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })
foreach ($name in $EngineList) {
    if (-not $engineCfgs.Contains($name)) { throw "未知引擎：$name（可选 jvm / jvm-opt / native / c-o2）" }
}

function Invoke-Measured {
    param([string]$Exe, [string[]]$ArgList, [double]$TimeoutSec = 3600)

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = ($ArgList | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $proc.Start() | Out-Null
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    $peak = 0L
    while (-not $proc.HasExited) {
        try {
            $proc.Refresh()
            if ($proc.PeakWorkingSet64 -gt $peak) { $peak = $proc.PeakWorkingSet64 }
        } catch { }
        Start-Sleep -Milliseconds 10
        if ($sw.Elapsed.TotalSeconds -gt $TimeoutSec) { $proc.Kill(); break }
    }
    $proc.WaitForExit()
    $sw.Stop()
    return [pscustomobject]@{
        ms     = [math]::Round($sw.Elapsed.TotalMilliseconds, 1)
        peakMB = [math]::Round($peak / 1MB, 1)
        exit   = $proc.ExitCode
        stdout = $outTask.Result
        stderr = $errTask.Result
    }
}

function Measure-Case {
    param([string]$Name, [string]$Script, [string[]]$Extra = @(), [string]$EngineKey)

    $cfg = $engineCfgs[$EngineKey]
    $argList = @($cfg.prefix) + @($Script) + $Extra
    $runs = @()
    for ($i = 0; $i -lt $Reps; $i++) {
        $r = Invoke-Measured -Exe $cfg.exe -ArgList $argList
        if ($r.exit -ne 0) {
            $note = (($r.stderr + " " + $r.stdout) -replace "\s+", " ").Trim()
            if ($note.Length -gt 160) { $note = $note.Substring(0, 160) }
            return [pscustomobject]@{ engine = $EngineKey; case = $Name; ok = $false; ms = $null; bestMs = $null; peakMB = $null; note = $note }
        }
        $runs += $r
    }
    $sorted = $runs | Sort-Object ms
    return [pscustomobject]@{
        engine = $EngineKey
        case   = $Name
        ok     = $true
        ms     = $sorted[[int][math]::Floor($sorted.Count / 2)].ms
        bestMs = $sorted[0].ms
        peakMB = ($runs | Measure-Object peakMB -Maximum).Maximum
        note   = ""
    }
}

$cases = @(
    @{ name = "startup_eval";  script = "-e"; extra = @("1+1") },
    @{ name = "fib27";         script = (Join-Path $WorkloadDir "fib.js"); extra = @() },
    @{ name = "alloc_gc";      script = (Join-Path $WorkloadDir "alloc_gc.js"); extra = @() },
    @{ name = "string_ops128M"; script = (Join-Path $WorkloadDir "string_ops.js"); extra = @("--memory-limit", "128M") },
    @{ name = "mandelbrot";    script = "tests\mandelbrot.js"; extra = @() },
    @{ name = "parse_compile"; script = "-o"; extra = @((Join-Path $OutDir "parse_large.qbc"), (Join-Path $OutDir "parse_large.js")) },
    @{ name = "parse_exec";    script = (Join-Path $OutDir "parse_large.js"); extra = @() }
)

if (-not $MicrobenchOnly -and -not (Test-Path (Join-Path $OutDir "parse_large.js"))) {
    & (Join-Path $PSScriptRoot "gen_parse_workload.ps1") -Count 4000 -Out (Join-Path $OutDir "parse_large.js")
}

$results = @()

if (-not $MicrobenchOnly) {
    foreach ($name in $EngineList) {
        foreach ($c in $cases) {
            Write-Host ("[{0}] {1} ..." -f $name, $c.name) -NoNewline
            $r = Measure-Case -Name $c.name -Script $c.script -Extra $c.extra -EngineKey $name
            $results += $r
            if ($r.ok) { Write-Host (" {0} ms / {1} MB" -f $r.ms, $r.peakMB) }
            else { Write-Host (" FAILED: {0}" -f $r.note) }
        }
    }
}

if (-not $SkipMicrobench) {
    $microScript = (Resolve-Path "tests\microbench.js").Path
    foreach ($name in $EngineList) {
        Write-Host ("[microbench] {0} ..." -f $name)
        $cfg = $engineCfgs[$name]
        $outFile = Join-Path $OutDir "microbench-$name-$runId.txt"
        $argList = @($cfg.prefix) + @($microScript)

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $cfg.exe
        $psi.Arguments = ($argList | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $proc = New-Object System.Diagnostics.Process
        $proc.StartInfo = $psi
        $proc.Start() | Out-Null
        $outTask = $proc.StandardOutput.ReadToEndAsync()
        $errTask = $proc.StandardError.ReadToEndAsync()
        $peak = 0L
        while (-not $proc.HasExited) {
            try {
                $proc.Refresh()
                if ($proc.PeakWorkingSet64 -gt $peak) { $peak = $proc.PeakWorkingSet64 }
            } catch { }
            Start-Sleep -Milliseconds 50
        }
        $proc.WaitForExit()
        $sw.Stop()
        Set-Content -Path $outFile -Value $outTask.Result -Encoding UTF8
        $results += [pscustomobject]@{
            engine = $name; case = "microbench"; ok = ($proc.ExitCode -eq 0)
            ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 1); bestMs = $null
            peakMB = [math]::Round($peak / 1MB, 1); note = $outFile
        }
        Write-Host ("  完成：{0:n1} s，峰值 {1} MB -> {2}" -f $sw.Elapsed.TotalSeconds, ($peak / 1MB), $outFile)
    }
}

$json = Join-Path $OutDir "result-$runId.json"
$results | ConvertTo-Json -Depth 4 | Set-Content $json -Encoding UTF8
Write-Host "`n结果已写入 $json"
$results | Where-Object { $_.case -ne "microbench" } |
    Format-Table engine, case, ok, ms, bestMs, peakMB -AutoSize