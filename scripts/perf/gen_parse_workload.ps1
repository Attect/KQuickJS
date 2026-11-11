# 生成解析/编译吞吐基准脚本：大量小函数 + 少量顶层调用。
#
# 用法：
#   pwsh -File scripts/perf/gen_parse_workload.ps1 -Count 4000 -Out perf-results/parse_large.js

param(
    [int]$Count = 4000,
    [string]$Out = "perf-results\parse_large.js"
)

$ErrorActionPreference = "Stop"
$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

$sb = [System.Text.StringBuilder]::new()
for ($i = 0; $i -lt $Count; $i++) {
    [void]$sb.AppendLine("function f$i(a, b) { var x = a + b; var y = x * 2; if (y > 10) { y = y - 1; } return y + $i; }")
}
[void]$sb.AppendLine("var total = 0;")
[void]$sb.AppendLine("for (var k = 0; k < $Count; k += 97) { total += f0(k, k + 1); }")
[void]$sb.AppendLine("print(total);")

Set-Content -Path $Out -Value $sb.ToString() -Encoding UTF8
$len = (Get-Item $Out).Length
Write-Host ("生成 {0}：{1} 个函数，{2:n0} 字节" -f $Out, $Count, $len)