# 内存/极限探针：最小可用引擎堆、OOM 行为与 16MB 堆下的大数组切分失败点。
#
# 用法：
#   pwsh -File scripts/perf/probe_memory.ps1
#
# 说明：Kotlin 版低于 JSStdlib.MIN_MEM_SIZE(160KB) 时不会初始化标准库；
# C 版 JS_NewContext2 在堆过小时可能直接失败。表中记录“首个成功的堆大小”。

param(
    [string]$Jar = "kotlin\build\libs\kquickjs-1.0.0.jar",
    [string]$NativeExe = "kotlin\build\native\nativeCompile\kquickjs.exe",
    [string]$Mqjs = "cmake-build-c-o2\mqjs.exe"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$engines = [ordered]@{
    "jvm"    = @{ exe = "java"; prefix = @("-jar", $Jar) }
    "native" = @{ exe = (Resolve-Path $NativeExe); prefix = @() }
    "c-o2"   = @{ exe = (Resolve-Path $Mqjs); prefix = @() }
}

function Invoke-Engine {
    param([string]$Name, [string[]]$EngineArgs, [int]$TimeoutSec = 60)
    $cfg = $engines[$Name]
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $cfg.exe
    $all = @($cfg.prefix) + $EngineArgs
    $psi.Arguments = ($all | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    $p.Start() | Out-Null
    $o = $p.StandardOutput.ReadToEndAsync()
    $e = $p.StandardError.ReadToEndAsync()
    if (-not $p.WaitForExit($TimeoutSec * 1000)) { try { $p.Kill() } catch { } }
    return [pscustomobject]@{ exit = $p.ExitCode; out = $o.Result.Trim(); err = $e.Result.Trim() }
}

# 1) 最小可用堆：`-e 1+1` 与 `-e var a=[1,2,3];print(a.length)`
$sizes = @("64K", "96K", "128K", "160K", "192K", "256K", "384K", "512K", "1M")
$probes = @(
    @{ name = "eval_print"; code = "print(1+1)"; expect = "2" },
    @{ name = "array";      code = "var a = [1, 2, 3]; print(a.length)"; expect = "3" }
)
Write-Host "== 最小可用堆 =="
foreach ($pr in $probes) {
    foreach ($name in $engines.Keys) {
        $first = $null
        foreach ($s in $sizes) {
            $r = Invoke-Engine -Name $name -EngineArgs @("--memory-limit", $s, "-e", $pr.code)
            if ($r.exit -eq 0 -and $r.out.Trim() -eq $pr.expect) { $first = $s; break }
        }
        "{0,-20} {1,-8} 首个成功堆 = {2}" -f $pr.name, $name, $(if ($first) { $first } else { "1M 仍失败" })
    }
}

# 2) 分配型脚本的最小可用堆（alloc_gc.js 通过即为成功）
Write-Host "`n== alloc_gc.js 最小可用堆 =="
foreach ($name in $engines.Keys) {
    $first = $null
    foreach ($s in @("1M", "2M", "4M", "8M", "16M")) {
        $r = Invoke-Engine -Name $name -EngineArgs @("--memory-limit", $s, "scripts\perf\workloads\alloc_gc.js") -TimeoutSec 120
        if ($r.exit -eq 0) { $first = $s; break }
    }
    "{0,-20} 首个成功堆 = {1}" -f $name, $(if ($first) { $first } else { "16M 仍失败" })
}

# 3) 大数组 split 在不同堆大小下的正确性（16MB 触发 GC 压缩，128MB 不触发）
Write-Host "`n== string_ops.js 在不同堆下的正确性 =="
foreach ($limit in @("16M", "32M", "64M", "128M")) {
    foreach ($name in $engines.Keys) {
        $r = Invoke-Engine -Name $name -EngineArgs @("--memory-limit", $limit, "scripts\perf\workloads\string_ops.js") -TimeoutSec 180
        "{0,-20} limit={1,-5} exit={2} out={3}" -f $name, $limit, $r.exit, ($r.out -replace "\s+", " ")
    }
}

# 4) 无界分配直到内存耗尽的错误行为（5 秒超时）
Write-Host "`n== 无界分配（OOM）行为 =="
foreach ($name in $engines.Keys) {
    $r = Invoke-Engine -Name $name -EngineArgs @("--memory-limit", "1M", "-e", "var a = []; for (var i = 0; i >= 0; i++) a.push(i)") -TimeoutSec 10
    "{0,-20} exit={1} out={2} err={3}" -f $name, $r.exit, $r.out, ($r.err -replace "\s+", " ")
}
