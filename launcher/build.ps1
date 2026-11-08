$ErrorActionPreference = "Continue"

$SDK = "C:\klipper-build\android-sdk"
$BT = Join-Path $SDK "build-tools"
$PLATFORM = Join-Path $SDK "platform"
$ANDROID_JAR = Join-Path $PLATFORM "android.jar"
$KEYTOOL = "C:\Program Files\Java\jdk-27\bin\keytool.exe"

# aapt2/keytool do not cope with non-ASCII paths, so stage everything under C:\
$PROJECT = $PSScriptRoot
$SRC = "C:\klipper-build\launcher-src"
$BUILD = "C:\klipper-build\launcher-build"
$KS = "C:\klipper-build\klipper.keystore"
$OUT = Join-Path $PROJECT "klipper-launcher.apk"

if (!(Test-Path $ANDROID_JAR)) { throw "android.jar not found: $ANDROID_JAR" }

Remove-Item $SRC -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $BUILD -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $SRC | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD\dex" | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD\gen" | Out-Null
New-Item -ItemType Directory -Force -Path "$BUILD\res" | Out-Null

Copy-Item "$PROJECT\AndroidManifest.xml" "$SRC\AndroidManifest.xml"
Copy-Item "$PROJECT\src" "$SRC\src" -Recurse
Copy-Item "$PROJECT\res" "$SRC\res" -Recurse

Write-Host "== aapt2 compile =="
& "$BT\aapt2.exe" compile --dir "$SRC\res" -o "$BUILD\res\compiled.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== aapt2 link =="
& "$BT\aapt2.exe" link -o "$BUILD\app-unsigned.apk" `
    --manifest "$SRC\AndroidManifest.xml" `
    -I "$ANDROID_JAR" `
    --min-sdk-version 24 --target-sdk-version 34 `
    --version-code 1 --version-name 1.0 `
    --java "$BUILD\gen" `
    "$BUILD\res\compiled.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== javac =="
$javaFiles = @(
    Get-ChildItem "$SRC\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    Get-ChildItem "$BUILD\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
)
& javac -source 8 -target 8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$BUILD\classes" $javaFiles *> "$BUILD\javac.log"
if ($LASTEXITCODE -ne 0) {
    Get-Content "$BUILD\javac.log"
    throw "javac failed"
}

Write-Host "== d8 =="
$classes = Get-ChildItem "$BUILD\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --lib "$ANDROID_JAR" --min-api 24 --output "$BUILD\dex" $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== add classes.dex =="
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$BUILD\app-unsigned.apk", "Update")
$entry = $zip.GetEntry("classes.dex")
if ($entry) { $entry.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$BUILD\dex\classes.dex", "classes.dex") | Out-Null
$zip.Dispose()

Write-Host "== zipalign =="
& "$BT\zipalign.exe" -f 4 "$BUILD\app-unsigned.apk" "$BUILD\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

if (!(Test-Path $KS)) {
    Write-Host "== keytool (new keystore) =="
    & "$KEYTOOL" -genkeypair -keystore "$KS" -alias klipper -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass change-me -keypass change-me `
        -dname "CN=Klipper Launcher, OU=Klipper, O=Klipper, L=Home, ST=Home, C=RU"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "== apksigner =="
& "$BT\apksigner.bat" sign --ks "$KS" --ks-key-alias klipper `
    --ks-pass pass:change-me --key-pass pass:change-me `
    --out "$OUT" "$BUILD\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& "$BT\apksigner.bat" verify --print-certs "$OUT" | Select-Object -First 4
Write-Host "== built: $OUT =="
Get-Item $OUT | Select-Object FullName, Length
