$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$build = Join-Path $root "build"
$classes = Join-Path $build "classes"

if (-not $env:ANDROID_HOME) {
    throw "ANDROID_HOME is not set; point it at your Android SDK"
}

$androidJar = Join-Path $env:ANDROID_HOME "platforms\android-36\android.jar"
if (-not (Test-Path $androidJar)) {
    throw "android.jar not found: $androidJar"
}

$d8 = Get-ChildItem -Path (Join-Path $env:ANDROID_HOME "build-tools") -Recurse -Filter "d8.bat" |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $d8) {
    throw "d8.bat not found under $env:ANDROID_HOME\build-tools"
}

Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root "src"), (Join-Path $root "stubs") -Recurse -Filter *.java |
    Select-Object -ExpandProperty FullName

& javac -source 11 -target 11 -classpath $androidJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

& $d8.FullName --release --lib $androidJar --output $build (Get-ChildItem $classes -Recurse -Filter *.class |
    Select-Object -ExpandProperty FullName)
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Copy-Item (Join-Path $root "manifest.json") (Join-Path $build "manifest.json") -Force

$plugin = Join-Path $build "hello.plugin"
Remove-Item $plugin -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($plugin, "Create")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $build "manifest.json"), "manifest.json") | Out-Null
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $build "classes.dex"), "classes.dex") | Out-Null
$zip.Dispose()

"built: $plugin"
