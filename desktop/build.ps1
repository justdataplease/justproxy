param(
    [switch]$InstallPyInstaller
)

$desktopRoot = $PSScriptRoot
$repositoryRoot = Split-Path -Parent $desktopRoot
$sdkSource = Join-Path $repositoryRoot 'python\src\justproxy_client'
$specFile = Join-Path $desktopRoot 'JustProxyDesktop.spec'

if (-not (Test-Path -LiteralPath $sdkSource -PathType Container)) {
    throw "Sibling SDK not found at $sdkSource"
}

$pythonLauncher = Get-Command py -ErrorAction SilentlyContinue
if ($null -eq $pythonLauncher) {
    $pythonLauncher = Get-Command python -ErrorAction SilentlyContinue
}
if ($null -eq $pythonLauncher) {
    throw 'Python 3.9 or newer is required.'
}

if ($InstallPyInstaller) {
    & $pythonLauncher.Source -m pip install 'pyinstaller>=6,<7'
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not install PyInstaller.'
    }
}

& $pythonLauncher.Source -c 'import PyInstaller'
if ($LASTEXITCODE -ne 0) {
    throw 'PyInstaller is not installed. Run .\build.ps1 -InstallPyInstaller once.'
}

Push-Location $desktopRoot
try {
    & $pythonLauncher.Source -m PyInstaller --noconfirm --clean $specFile
    if ($LASTEXITCODE -ne 0) {
        throw 'PyInstaller build failed.'
    }
} finally {
    Pop-Location
}

Write-Host "Built $desktopRoot\dist\JustProxyDesktop.exe"
