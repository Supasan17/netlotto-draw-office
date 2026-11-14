param([string]$OutputDirectory = 'dist')
# Optional: builds a desk that runs on Windows computers without Java installed.
# Result: packages\DrawOffice-desk-windows.zip  (unzip, then run DrawOffice\DrawOffice.exe)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $projectRoot
try {
    & (Join-Path $PSScriptRoot 'build.ps1') -SkipTests -OutputDirectory $OutputDirectory
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    } else {
        $java = (Get-Command java).Source
    }
    if (Test-Path packages) { Remove-Item packages -Recurse -Force }
    New-Item -ItemType Directory -Path 'packages\input' | Out-Null
    Copy-Item (Join-Path $OutputDirectory 'drawoffice-desk.jar') 'packages\input'
    & $java -m jdk.jpackage/jdk.jpackage.main.Main --type app-image --name DrawOffice --input packages\input `
        --main-jar drawoffice-desk.jar --main-class lk.netlotto.desk.DeskApp --add-modules java.desktop `
        --dest packages --app-version 2.0
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
    Remove-Item 'packages\input' -Recurse -Force
    Compress-Archive -Path 'packages\DrawOffice' -DestinationPath 'packages\DrawOffice-desk-windows.zip' -Force
    Write-Output 'Created packages\DrawOffice-desk-windows.zip'
} finally { Pop-Location }
