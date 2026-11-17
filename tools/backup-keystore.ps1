# Prepara la copia del keystore de KAIZEN para la nota segura de Vaultwarden.
# Deja el .jks en Base64 en el portapapeles (no lo imprime) y muestra alias y huella para comprobar la nota.
# La contraseña (.pass) no se lee ni se muestra: se copia a mano desde el fichero.
#
# Uso:    pwsh tools/backup-keystore.ps1 [-Dir <carpeta del keystore>]
# Restaurar (con el Base64 de la nota en el portapapeles):
#   [IO.File]::WriteAllBytes("$env:USERPROFILE\.android\kaizen-tvlibre.jks", [Convert]::FromBase64String((Get-Clipboard)))

param(
    [string]$Dir = (Join-Path $env:USERPROFILE ".android")
)

$ErrorActionPreference = "Stop"

$keystore = Join-Path $Dir "kaizen-tvlibre.jks"
$passFile = Join-Path $Dir "kaizen-tvlibre.pass"

foreach ($f in $keystore, $passFile) {
    if (-not (Test-Path $f)) { throw "no existe $f" }
}

$keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $keytool) {
    $keytool = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Recurse -Filter keytool.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $keytool) { throw "keytool no encontrado (instala JDK 17 o añádelo al PATH)" }

# -storepass:file evita pasar la contraseña como argumento.
Write-Host "== Contenido del keystore"
& $keytool -list -keystore $keystore "-storepass:file" $passFile | Select-String "Alias|SHA-256|^\S+, .*Entry|,\s+\d+ .* 20\d\d"
if ($LASTEXITCODE -ne 0) { throw "keytool no pudo abrir el keystore (¿contraseña o fichero incorrectos?)" }

$bytes = [IO.File]::ReadAllBytes($keystore)
$base64 = [Convert]::ToBase64String($bytes)
Set-Clipboard -Value $base64

Write-Host ""
Write-Host "Base64 de $($bytes.Length) bytes ($($base64.Length) caracteres) copiado al portapapeles."
Write-Host "1. Pégalo en la nota segura de Vaultwarden (sustituye el contenido anterior)."
Write-Host "2. Contraseña: cópiala a mano desde $passFile (no se muestra aquí)."
Write-Host "3. Alias de la nota: origami."
