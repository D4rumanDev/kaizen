# Quita de este VS Code las extensiones que Kaizen no usa (proyecto Kotlin/Gradle con herramientas en Node, Python y PowerShell).
#
# Uso (desde la raíz del repositorio):
#   pwsh tools/vscode-extensions.ps1                      # ensayo: solo enseña qué quitaría
#   pwsh tools/vscode-extensions.ps1 -Apply               # desinstala de verdad
#   pwsh tools/vscode-extensions.ps1 -Apply -JavaServer   # además quita redhat.java (el servidor de Java)
#
# Sin -Apply no se cambia nada. Las extensiones son un ajuste de usuario de VS Code, no del repositorio: solo afecta a este equipo.
# Se pueden volver a instalar con «code --install-extension <id>».

[CmdletBinding()]
param(
    [switch]$Apply,
    [switch]$JavaServer
)

$ErrorActionPreference = 'Stop'

# Por qué se quita cada una. El orden importa: primero las que dependen de otras (java-test, java-debug y java-dependency dependen de redhat.java).
$remove = [ordered]@{
    'fwcd.kotlin'                  = 'Servidor de Kotlin que no lee el classpath de Android/Gradle: marca como error código que compila'
    'vscjava.vscode-java-test'     = 'Los tests son Kotlin con Gradle; solo hay 2 ficheros Java'
    'vscjava.vscode-java-debug'    = 'Depurador de Java; la app se depura con Gradle y ADB'
    'vscjava.vscode-java-dependency' = 'Vista de dependencias de Java/Maven; el proyecto usa Gradle'
    'vscjava.vscode-maven'         = 'El proyecto no usa Maven'
    'adelphes.android-dev-ext'     = 'Depurador Android pensado para Java; con Kotlin funciona mal'
    'vscjava.vscode-java-pack'     = 'Solo es el paquete que instala las anteriores'
}
if ($JavaServer) { $remove['redhat.java'] = 'Servidor de Java (2 ficheros Java en todo el proyecto)' }

# Nunca se tocan: la de esta propia sesión y la que el usuario quiere conservar.
$keep = @('anthropic.claude-code', 'google.google-antigravity')

$code = Get-Command code -ErrorAction SilentlyContinue
if (-not $code) { throw "No encuentro la orden «code». En VS Code: Ctrl+Mayús+P › «Shell Command: Install 'code' command in PATH»." }

$installed = (& code --list-extensions) | ForEach-Object { $_.Trim().ToLowerInvariant() } | Where-Object { $_ }

$todo = @()
foreach ($id in $remove.Keys) {
    if ($keep -contains $id) { throw "Error del script: $id está en la lista de protegidas." }
    if ($installed -contains $id.ToLowerInvariant()) { $todo += $id }
}

if (-not $todo) { Write-Host 'No hay nada que quitar: ninguna de las extensiones de la lista está instalada.'; return }

Write-Host ($(if ($Apply) { 'Se desinstalarán:' } else { 'Ensayo (no se cambia nada). Se desinstalarían:' }))
foreach ($id in $todo) { Write-Host ("  - {0,-34} {1}" -f $id, $remove[$id]) }

if (-not $Apply) { Write-Host "`nPara hacerlo de verdad: pwsh tools/vscode-extensions.ps1 -Apply"; return }

$failed = @()
foreach ($id in $todo) {
    Write-Host "`nDesinstalando $id…"
    & code --uninstall-extension $id
    if ($LASTEXITCODE -ne 0) { $failed += $id }
}

$after = (& code --list-extensions) | ForEach-Object { $_.Trim().ToLowerInvariant() }
$left = $todo | Where-Object { $after -contains $_.ToLowerInvariant() }
Write-Host "`nComprobación: instaladas ahora (de la lista): $(if ($left) { $left -join ', ' } else { 'ninguna' })"
foreach ($id in $keep) { Write-Host ("  conservada: {0} -> {1}" -f $id, $(if ($after -contains $id) { 'instalada' } else { 'NO aparece instalada' })) }
if ($failed) { Write-Warning "Fallaron: $($failed -join ', '). Cierra VS Code y repite (una extensión en uso no siempre se puede quitar)."; exit 1 }
Write-Host "`nHecho. Recarga la ventana de VS Code (Ctrl+Mayús+P › «Developer: Reload Window»)."
