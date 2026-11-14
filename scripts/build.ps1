param([switch]$SkipTests, [string]$OutputDirectory = 'dist')
# Compiles NetLotto Draw Office, creates the runnable JARs in dist\ and runs the test suites.
# Uses the JDK in JAVA_HOME, or the javac found on PATH. Requires JDK 17 or newer.
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $projectRoot
try {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $bin = Join-Path $env:JAVA_HOME 'bin'
    } else {
        $javacCommand = Get-Command javac -ErrorAction SilentlyContinue
        if (-not $javacCommand) { throw 'javac was not found. Install JDK 17 or newer, or set JAVA_HOME.' }
        $bin = Split-Path $javacCommand.Source
    }
    $javac = Join-Path $bin 'javac.exe'
    $java = Join-Path $bin 'java.exe'
    function Invoke-Jar([string[]]$JarArgs) {
        $jarTool = Join-Path $bin 'jar.exe'
        if (Test-Path $jarTool) { & $jarTool @JarArgs } else { & $java -m jdk.jartool/sun.tools.jar.Main @JarArgs }
        if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed' }
    }

    $h2 = 'lib\h2-2.2.224.jar'
    if (-not (Test-Path $h2)) {
        throw "$h2 is missing. Download https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar into lib\."
    }

    foreach ($folder in @('out', 'test-out')) {
        if (Test-Path -LiteralPath $folder) { Remove-Item -LiteralPath $folder -Recurse -Force }
        New-Item -ItemType Directory -Path $folder | Out-Null
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $OutputDirectory 'lib') | Out-Null

    $sources = @(Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName)
    & $javac --release 17 -encoding UTF-8 -Xlint:all,-serial -d out $sources
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }

    # The server finds the H2 driver through the Class-Path entry, relative to the JAR's own folder.
    Set-Content -Path 'out\server-manifest.txt' -Value 'Class-Path: lib/h2-2.2.224.jar' -Encoding ascii
    Invoke-Jar @('--create', '--file', (Join-Path $OutputDirectory 'drawoffice-server.jar'), '--manifest', 'out\server-manifest.txt',
        '--main-class', 'lk.netlotto.server.DrawServer', '-C', 'out', 'lk')
    Invoke-Jar @('--create', '--file', (Join-Path $OutputDirectory 'drawoffice-desk.jar'),
        '--main-class', 'lk.netlotto.desk.DeskApp', '-C', 'out', 'lk')
    Copy-Item $h2 (Join-Path $OutputDirectory 'lib') -Force

    if (-not $SkipTests) {
        $tests = @(Get-ChildItem tests -Recurse -Filter *.java | ForEach-Object FullName)
        & $javac --release 17 -encoding UTF-8 -Xlint:all,-serial -cp out -d test-out $tests
        if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
        $classpath = "out;test-out;$h2"
        foreach ($test in @('lk.netlotto.core.RulesTest', 'lk.netlotto.server.StoreTest',
                            'lk.netlotto.server.ServerIntegrationTest', 'lk.netlotto.desk.DeskScreenTest',
                            'lk.netlotto.server.ServerWindowTest')) {
            Write-Output ''
            Write-Output "=== $test"
            & $java -cp $classpath $test
            if ($LASTEXITCODE -ne 0) { throw "Test failed: $test" }
        }
    }
    Write-Output ''
    Write-Output "Built $OutputDirectory\drawoffice-server.jar and $OutputDirectory\drawoffice-desk.jar"
} finally { Pop-Location }
