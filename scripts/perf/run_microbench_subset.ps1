# KQuickJS microbench 单项对比：为每个用例生成只运行该用例的脚本副本，
# 便于在 JVM / 原生镜像 / C 参考实现之间逐项对比（完整套件耗时过长时使用）。
#
# 用法：
#   pwsh -File scripts/perf/run_microbench_subset.ps1
#   pwsh -File scripts/perf/run_microbench_subset.ps1 -Tests func_call,prop_read -EngineFilter jvm,c-o2

param(
    [string]$Tests = "empty_loop,prop_read,prop_write,array_read,array_write,array_push,array_pop,closure_read,func_call,int_arith,float_arith,string_build1,string_build2,int_to_string,regexp_ascii,sort_bench",
    [string]$EngineFilter = "jvm,jvm-opt,native,c-o2",
    [string]$Jar = "kotlin\build\libs\kquickjs-1.0.0.jar",
    [string]$NativeExe = "kotlin\build\native\nativeCompile\kquickjs.exe",
    [string]$Mqjs = "cmake-build-c-o2\mqjs.exe",
    [string]$OutDir = "perf-results",
    [int]$Reps = 1
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$runId = Get-Date -Format "yyyyMMdd-HHmmss"

$engineCfgs = [ordered]@{
    "jvm"     = @{ exe = "java"; prefix = @("-jar", $Jar) }
    "jvm-opt" = @{ exe = "java"; prefix = @("-XX:-DontCompileHugeMethods", "-jar", $Jar) }
    "native"  = @{ exe = (Resolve-Path $NativeExe); prefix = @() }
    "c-o2"    = @{ exe = (Resolve-Path $Mqjs); prefix = @() }
}

$EngineList = @($EngineFilter -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$testList = @($Tests -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$src = Get-Content "tests\microbench.js" -Raw

function Invoke-Proc {
    param([string]$Exe, [string[]]$ArgList)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = ($ArgList | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $p.Start() | Out-Null
    $o = $p.StandardOutput.ReadToEnd()
    $e = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    $sw.Stop()
    return [pscustomobject]@{ ms = $sw.Elapsed.TotalMilliseconds; exit = $p.ExitCode; out = $o; err = $e }
}

$rows = @()
foreach ($test in $testList) {
    $patched = $src -replace 'main\(scriptArgs\.length, scriptArgs, this\);', "main(2, [`"x`", `"$test`"], this);"
    $benchFile = Join-Path $OutDir "mb-$test.js"
    Set-Content -Path $benchFile -Value $patched -Encoding UTF8

    foreach ($name in $EngineList) {
        $cfg = $engineCfgs[$name]
        $best = $null
        for ($i = 0; $i -lt $Reps; $i++) {
            $r = Invoke-Proc -Exe $cfg.exe -ArgList (@($cfg.prefix) + @($benchFile))
            if ($r.exit -ne 0) { break }
            $line = ($r.out -split "`r?`n") | Where-Object { $_ -match "^\s*$test\s" } | Select-Object -Last 1
            if ($line -match "^\s*(\S+)\s+(\d+)\s+([\d.]+)") {
                $ns = [double]$Matches[3]
                if ($null -eq $best -or $ns -lt $best) { $best = $ns }
            }
        }
        $rows += [pscustomobject]@{ test = $test; engine = $name; ns = $best }
        Write-Host ("{0,-16} {1,-8} {2}" -f $test, $name, $(if ($null -eq $best) { "FAILED" } else { "$best ns" }))
    }
}

$csv = Join-Path $OutDir "microbench-subset-$runId.csv"
$rows | Export-Csv -Path $csv -NoTypeInformation -Encoding UTF8
Write-Host "`n结果已写入 $csv"

$pivot = $rows | Group-Object test | ForEach-Object {
    $o = [ordered]@{ test = $_.Name }
    foreach ($r in $_.Group) { $o[$r.engine] = $r.ns }
    [pscustomobject]$o
}
$pivot | Format-Table -AutoSize