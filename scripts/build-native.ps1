# Multi-platform build script for MQuickJS native images (Windows PowerShell)

param(
    [switch]$Jar,
    [switch]$Native,
    [switch]$All
)

$ErrorActionPreference = "Stop"

$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
$PROJECT_DIR = Split-Path -Parent $SCRIPT_DIR
$OUTPUT_DIR = Join-Path $PROJECT_DIR "dist"

$VERSION = "1.0.0"
$APP_NAME = "mqjs"

Write-Host "=== MQuickJS Native Image Build Script ===" -ForegroundColor Cyan
Write-Host "Project directory: $PROJECT_DIR"
Write-Host "Output directory: $OUTPUT_DIR"

# Create output directory
New-Item -ItemType Directory -Force -Path $OUTPUT_DIR | Out-Null

# Function to build for current platform
function Build-Native {
    Write-Host ""
    Write-Host "Building native image for Windows..." -ForegroundColor Yellow
    
    Set-Location "$PROJECT_DIR\kotlin"
    
    # Clean and build
    .\gradlew.bat clean jar
    
    # Build native image
    .\gradlew.bat nativeCompile
    
    # Copy the native executable to output directory
    $nativePath = "build\native\nativeCompile\$APP_NAME.exe"
    if (Test-Path $nativePath) {
        $arch = $env:PROCESSOR_ARCHITECTURE.ToLower()
        if ($arch -eq "amd64") { $arch = "x64" }
        $outputName = "$APP_NAME-windows-$arch.exe"
        Copy-Item $nativePath (Join-Path $OUTPUT_DIR $outputName)
        Write-Host "Native image created: $(Join-Path $OUTPUT_DIR $outputName)" -ForegroundColor Green
    } else {
        Write-Host "ERROR: Native image not found!" -ForegroundColor Red
        exit 1
    }
}

# Function to build JAR (platform independent)
function Build-Jar {
    Write-Host ""
    Write-Host "Building JAR..." -ForegroundColor Yellow
    
    Set-Location "$PROJECT_DIR\kotlin"
    .\gradlew.bat jar
    
    # Copy JAR to output directory
    $jarPath = Get-ChildItem "build\libs\*.jar" | Select-Object -First 1
    if ($jarPath) {
        $outputName = "$APP_NAME-$VERSION.jar"
        Copy-Item $jarPath.FullName (Join-Path $OUTPUT_DIR $outputName)
        Write-Host "JAR created: $(Join-Path $OUTPUT_DIR $outputName)" -ForegroundColor Green
    } else {
        Write-Host "ERROR: JAR not found!" -ForegroundColor Red
        exit 1
    }
}

# Default to building both if no options specified
if (-not $Jar -and -not $Native -and -not $All) {
    $Jar = $true
    $Native = $true
}

if ($All) {
    $Jar = $true
    $Native = $true
}

if ($Jar) {
    Build-Jar
}

if ($Native) {
    Build-Native
}

Write-Host ""
Write-Host "=== Build Complete ===" -ForegroundColor Cyan
Write-Host "Output files in: $OUTPUT_DIR"
Get-ChildItem $OUTPUT_DIR | Format-Table Name, Length, LastWriteTime
