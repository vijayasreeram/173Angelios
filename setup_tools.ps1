<#
.SYNOPSIS
    iTANTRA - Android Studio & Flutter Setup Helper
.DESCRIPTION
    Installs Android Studio and extracts Flutter SDK to C:\Users\CMRMuthuthiyagarajan\flutter,
    and configures the user PATH environment variable.
#>

param (
    [switch]$InstallStudio,
    [switch]$ExtractFlutter,
    [switch]$All
)

$DownloadsDir = "C:\Users\CMRMuthuthiyagarajan\Downloads"
$StudioExe = Join-Path $DownloadsDir "android-studio-2026.1.4.7-windows.exe"
$FlutterZip = Join-Path $DownloadsDir "flutter_windows_3.47.2-stable.zip"
$FlutterDest = "C:\Users\CMRMuthuthiyagarajan\flutter"

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host "   iTANTRA Environment Setup - Android Studio & Flutter" -ForegroundColor Green
Write-Host "====================================================" -ForegroundColor Cyan

if (-not $InstallStudio -and -not $ExtractFlutter -and -not $All) {
    $All = $true
}

# 1. Install Android Studio
if ($InstallStudio -or $All) {
    if (Test-Path $StudioExe) {
        Write-Host "`n[1/2] Found Android Studio Installer: $StudioExe" -ForegroundColor Green
        Write-Host "Launching Android Studio Installer GUI..." -ForegroundColor Yellow
        Start-Process -FilePath $StudioExe
        Write-Host "Installer window opened. Follow the on-screen wizard to complete setup." -ForegroundColor Green
    } else {
        Write-Host "`n[1/2] Android Studio installer is still downloading to $DownloadsDir..." -ForegroundColor DarkYellow
    }
}

# 2. Extract Flutter SDK
if ($ExtractFlutter -or $All) {
    if (Test-Path $FlutterZip) {
        Write-Host "`n[2/2] Found Flutter SDK Zip: $FlutterZip" -ForegroundColor Green
        if (-not (Test-Path $FlutterDest)) {
            Write-Host "Extracting Flutter SDK to $FlutterDest (this may take 1-2 minutes)..." -ForegroundColor Yellow
            Expand-Archive -Path $FlutterZip -DestinationPath "C:\Users\CMRMuthuthiyagarajan" -Force
            Write-Host "Flutter extracted successfully to $FlutterDest" -ForegroundColor Green
        } else {
            Write-Host "Flutter already exists at $FlutterDest" -ForegroundColor Green
        }

        # Add to PATH if not already present
        $FlutterBin = Join-Path $FlutterDest "bin"
        $UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
        if ($UserPath -notlike "*$FlutterBin*") {
            Write-Host "Adding $FlutterBin to User PATH..." -ForegroundColor Yellow
            [Environment]::SetEnvironmentVariable("Path", "$UserPath;$FlutterBin", "User")
            Write-Host "PATH updated! Open a new terminal to use the 'flutter' command." -ForegroundColor Green
        } else {
            Write-Host "Flutter bin is already in User PATH." -ForegroundColor Green
        }
    } else {
        Write-Host "`n[2/2] Flutter SDK zip is still downloading to $DownloadsDir..." -ForegroundColor DarkYellow
    }
}

Write-Host "`nSetup script finished." -ForegroundColor Cyan
