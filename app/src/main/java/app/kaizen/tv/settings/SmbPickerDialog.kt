@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.settings

import app.kaizen.R
import app.kaizen.commons.appString
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.SmbDiscovery
import app.kaizen.commons.SmbErrors
import app.kaizen.commons.SmbFolder
import app.kaizen.commons.logError
import app.kaizen.tv.ui.KaizenToast
import app.kaizen.tv.ui.components.FocusCard
import app.kaizen.tv.ui.components.KaizenButton
import app.kaizen.tv.ui.theme.Background
import app.kaizen.tv.ui.theme.FailureRed
import app.kaizen.tv.ui.theme.StatusOk
import app.kaizen.tv.ui.theme.Text14
import app.kaizen.tv.ui.theme.Text16
import app.kaizen.tv.ui.theme.Text18
import app.kaizen.tv.ui.theme.White60
import app.kaizen.tv.ui.theme.White70
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configuración › Copia de seguridad › Carpeta de red: dejar la carpeta SMB lista sin teclear una dirección con protocolo. Una sola pantalla con lo que hay que decir, en el orden en que
 * se piensa: **servidor** (con los encontrados en la red como sugerencia, ver [SmbDiscovery]), **usuario** y **contraseña** (o invitado) y la **carpeta compartida** (`video/kaizen`, sin
 * protocolo ni servidor). «Conectar y elegir carpeta» comprueba servidor, usuario y contraseña (lista los recursos) y deja elegir la carpeta recorriendo el servidor; «Verificar y guardar» comprueba que todo vale y entonces (solo entonces) lo guarda: cancelar no toca una
 * configuración que funciona. Atrás sube un nivel al examinar y, desde el formulario, cierra.
 */
@Composable
internal fun SmbPickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { SmbFolder.splitUrl(KaizenSettings.smbUrl.orEmpty()) }
    var host by remember { mutableStateOf(saved?.first ?: KaizenSettings.smbLastServer.orEmpty()) }
    var user by remember { mutableStateOf(KaizenSettings.smbUser.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var guest by remember { mutableStateOf(saved != null && KaizenSettings.smbUser.isNullOrBlank()) }
    val hasSavedPassword = remember { !KaizenSettings.smbPassword.isNullOrEmpty() }
    // La carpeta compartida elegida: [recurso, carpetas…]. Es la ruta del formulario y también por donde se va examinando.
    var path by remember { mutableStateOf(saved?.second.orEmpty()) }
    var browsing by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var returnTo by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<TextItem?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<SmbDiscovery.Found>() }

    // Sin contraseña escrita, la guardada: así volver a conectar no obliga a teclearla otra vez.
    fun effectiveUser(): String? = if (guest) null else user.trim().ifBlank { null }
    fun effectivePassword(): String = if (guest) "" else password.ifEmpty { KaizenSettings.smbPassword.orEmpty() }
    fun folder(parts: List<String>) = SmbFolder(SmbFolder.urlOf(host, parts), effectiveUser(), effectivePassword())

    // Servidores que se anuncian en la red (si no está apagado en los ajustes).
    LaunchedEffect(Unit) {
        if (KaizenSettings.smbDiscovery) runCatching { SmbDiscovery.find(context).collect { f -> if (found.none { it.address == f.address }) found.add(f) } }
    }

    // La lista de la carpeta que se examina: se pide al entrar y cada vez que cambia la ruta o se pide reintentar.
    LaunchedEffect(browsing, path, reload) {
        if (!browsing) return@LaunchedEffect
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) { runCatching { folder(path).folders() } }
        result.onSuccess { entries = it }.onFailure { logError(it); entries = null; error = SmbErrors.describe(it, host) }
        loading = false
    }

    fun save(createdLater: Boolean) {
        KaizenSettings.smbUrl = SmbFolder.urlOf(host, path)
        KaizenSettings.smbUser = effectiveUser()
        KaizenSettings.smbPassword = effectivePassword().ifEmpty { null }
        KaizenSettings.smbLastServer = host
        KaizenToast.toastLong("Carpeta de red guardada: ${SmbFolder.display(SmbFolder.urlOf(host, path))}" + if (createdLater) " (se crea con la primera copia)" else "")
        onDismiss()
    }

    /** Comprueba servidor, usuario, contraseña y carpeta; si todo vale (o solo falta crear la última carpeta) lo guarda. */
    fun verify() {
        if (busy) return
        busy = true
        statusIsError = false
        status = "Comprobando con $host…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        folder(path).exists() -> 0
                        path.size > 1 && folder(path.dropLast(1)).exists() -> 1
                        path.size == 1 -> 2
                        else -> 3
                    }
                }
            }
            busy = false
            result.onSuccess { code ->
                when (code) {
                    0 -> save(createdLater = false)
                    1 -> save(createdLater = true)
                    2 -> { statusIsError = true; status = "En $host no existe el recurso compartido «${path.first()}». Usa «Conectar y elegir carpeta» para ver los que hay." }
                    else -> { statusIsError = true; status = "No existe esa ruta en $host. Usa «Conectar y elegir carpeta» para elegirla." }
                }
            }.onFailure { logError(it); statusIsError = true; status = SmbErrors.describe(it, host) }
        }
    }

    fun back() {
        when {
            browsing && path.isNotEmpty() -> { returnTo = path.last(); path = path.dropLast(1) }
            browsing -> { browsing = false; entries = null; error = null }
            else -> onDismiss()
        }
    }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(appString(R.string.ui_carpeta_de_red), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (!browsing) {
                SmbForm(
                    host = host, guest = guest, user = user, password = password, hasSavedPassword = hasSavedPassword, path = path, hasSaved = saved != null,
                    status = status, statusIsError = statusIsError, busy = busy,
                    suggestions = (listOfNotNull(KaizenSettings.smbLastServer?.takeIf { it.isNotBlank() }?.let { SmbDiscovery.Found("Último usado", it) }) + found).distinctBy { it.address },
                    onHost = { host = it; status = null },
                    onEdit = { editing = it },
                    onGuest = { guest = !guest; status = null },
                    onUser = { user = it; status = null },
                    onPassword = { password = it; status = null },
                    onPath = { newHost, parts -> if (newHost != null) host = newHost; path = parts; status = null },
                    onBrowse = { browsing = true; entries = null; error = null; status = null },
                    onVerify = ::verify,
                    onCancel = onDismiss
                )
            } else {
                SmbBrowser(
                    host = host, path = path, entries = entries, loading = loading, error = error, returnTo = returnTo,
                    onEnter = { name -> path = path + name },
                    onUp = ::back,
                    onRetry = { reload++ },
                    onFromStart = { path = emptyList() },
                    onChangeServer = { browsing = false; entries = null; error = null },
                    onUse = { browsing = false; entries = null; error = null; statusIsError = false; status = "Carpeta elegida. Pulsa «Verificar y guardar» para comprobarla y guardarla." },
                    onNewFolder = {
                        editing = TextItem("Nueva carpeta", { "" }, { "" }, { name ->
                            scope.launch {
                                val done = withContext(Dispatchers.IO) { runCatching { folder(path).makeFolder(name) } }
                                done.onSuccess { reload++ }.onFailure { logError(it); KaizenToast.toastLong("No se pudo crear la carpeta: ${SmbErrors.describe(it, host)}") }
                            }
                        }, validate = ::folderNameProblem, hint = "Kaizen", help = "Se crea dentro de ${if (path.isEmpty()) host else path.last()}.")
                    }
                )
            }
        }
    }
    editing?.let { item -> TextEditDialog(item) { editing = null } }
}

/** Por qué un nombre de carpeta no vale (null si vale): vacío o con los caracteres que SMB no admite. */
internal fun folderNameProblem(name: String): String? = when {
    name.isBlank() -> "Escribe un nombre"
    name.any { it in "/\\:*?\"<>|" } -> "Sin / \\ : * ? \" < > |"
    name.startsWith(".") || name.endsWith(".") -> "No puede empezar ni acabar en punto"
    else -> null
}

@Composable
private fun SmbForm(
    host: String, guest: Boolean, user: String, password: String, hasSavedPassword: Boolean, path: List<String>, hasSaved: Boolean,
    status: String?, statusIsError: Boolean, busy: Boolean, suggestions: List<SmbDiscovery.Found>,
    onHost: (String) -> Unit, onEdit: (TextItem) -> Unit, onGuest: () -> Unit, onUser: (String) -> Unit, onPassword: (String) -> Unit,
    onPath: (String?, List<String>) -> Unit, onBrowse: () -> Unit, onVerify: () -> Unit, onCancel: () -> Unit
) {
    val firstRow = remember { FocusRequester() }
    val verify = remember { FocusRequester() }
    // Con una carpeta ya configurada basta OK en «Verificar y guardar»; sin ella, se empieza por el servidor.
    LaunchedEffect(Unit) { runCatching { (if (hasSaved) verify else firstRow).requestFocus() } }
    Text("1. Servidor, usuario y contraseña; pulsa «Conectar» para comprobarlos. 2. Elige la carpeta compartida donde guardar las copias.", color = White70, fontSize = Text16)
    if (suggestions.isNotEmpty()) {
        Text(appString(R.string.ui_servidores_encontrados_en_la_red), color = White60, fontSize = Text14)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.take(4).forEach { s -> KaizenButton("${s.name} · ${s.address}", onClick = { onHost(s.address) }, fontSize = Text14) }
        }
    }
    FieldRow("Servidor", host.ifBlank { "Sin elegir" }, Modifier.focusRequester(firstRow)) {
        onEdit(TextItem("Servidor", { host }, { host }, { onHost(SmbFolder.normalizeServer(it) ?: it) },
            { if (it.isEmpty() || SmbFolder.normalizeServer(it) != null) null else "Escribe el nombre o la IP, por ejemplo nas o nas.local" },
            "nas", "El nombre o la IP del NAS o del PC, sin protocolo."))
    }
    FieldRow("Entrar como invitado", if (guest) "Sí (sin usuario ni contraseña)" else "No", Modifier, onGuest)
    if (!guest) {
        FieldRow("Usuario", user.ifBlank { "Sin usuario" }, Modifier) {
            onEdit(TextItem("Usuario", { user }, { user }, onUser, hint = "usuario", help = "El usuario del NAS (o DOMINIO\\usuario). Mejor una cuenta solo para esta carpeta."))
        }
        FieldRow("Contraseña", if (password.isNotEmpty()) "•".repeat(password.length.coerceAtMost(12)) else if (hasSavedPassword) "Guardada: se reutiliza" else "Sin contraseña", Modifier) {
            onEdit(TextItem("Contraseña", { "" }, { "" }, onPassword, hint = if (hasSavedPassword) "Vacío = la guardada" else "", masked = true,
                help = "Se guarda cifrada en esta tele y solo se envía a ese servidor. Con el botón Mostrar puedes ver lo que escribes."))
        }
    }
    FieldRow("Carpeta compartida", if (path.isEmpty()) "Sin elegir (recurso/carpeta)" else path.joinToString("/"), Modifier) {
        onEdit(TextItem("Carpeta compartida", { path.joinToString("/") }, { path.joinToString("/") },
            { text ->
                val parsed = SmbFolder.parseLocation(text, withServer = false)
                if (parsed != null) onPath(parsed.first, parsed.second)
            },
            { if (it.isEmpty() || SmbFolder.parseLocation(it, withServer = false) != null) null else "Sin espacios raros ni ? # : * \" < > |" },
            "video/kaizen", "El recurso compartido y la carpeta dentro, sin servidor ni protocolo: video/kaizen. O pulsa «Conectar y elegir carpeta»."))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        KaizenButton("Conectar y elegir carpeta", onClick = onBrowse, enabled = host.isNotBlank() && !busy)
        KaizenButton(if (busy) "Comprobando…" else "Verificar y guardar", onClick = onVerify, modifier = Modifier.focusRequester(verify), enabled = host.isNotBlank() && path.isNotEmpty() && !busy)
        KaizenButton("Cancelar", onClick = onCancel)
    }
    status?.let { Text(it, color = if (statusIsError) FailureRed else StatusOk, fontSize = Text18) }
}

@Composable
private fun FieldRow(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = Text18, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(value, color = White70, fontSize = Text18, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ColumnScope.SmbBrowser(
    host: String, path: List<String>, entries: List<String>?, loading: Boolean, error: String?, returnTo: String?,
    onEnter: (String) -> Unit, onUp: () -> Unit, onRetry: () -> Unit, onFromStart: () -> Unit, onChangeServer: () -> Unit, onUse: () -> Unit, onNewFolder: () -> Unit
) {
    Text((listOf(host) + path).joinToString("  ›  "), color = White70, fontSize = Text16, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Column(Modifier.weight(1f).fillMaxWidth()) {
        when {
            loading -> Text("Conectando con $host y comprobando usuario y contraseña…", color = White70, fontSize = Text18)
            error != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error, color = FailureRed, fontSize = Text18)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val retry = remember { FocusRequester() }
                    LaunchedEffect(error) { runCatching { retry.requestFocus() } }
                    KaizenButton("Reintentar", onClick = onRetry, modifier = Modifier.focusRequester(retry))
                    if (path.isNotEmpty()) KaizenButton("Empezar desde el servidor", onClick = onFromStart)
                    KaizenButton("Volver al formulario", onClick = onChangeServer)
                }
            }
            entries != null -> FolderList(path.isEmpty(), entries, returnTo, onEnter, onUp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KaizenButton("Usar esta carpeta", onClick = onUse, enabled = path.isNotEmpty() && !loading && error == null)
        KaizenButton("Nueva carpeta", onClick = onNewFolder, enabled = path.isNotEmpty() && !loading && error == null)
        KaizenButton("Volver al formulario", onClick = onChangeServer)
        if (path.isEmpty() && !loading && error == null) Text(appString(R.string.ui_entra_en_un_recurso_compartido), color = White60, fontSize = Text14, modifier = Modifier.padding(start = 8.dp))
    }
}

/** «..  Subir» y las carpetas (o, en la raíz, los recursos compartidos). El foco vuelve a la carpeta de la que se salió, o a «Subir». */
@Composable
private fun FolderList(atRoot: Boolean, entries: List<String>, returnTo: String?, onEnter: (String) -> Unit, onUp: () -> Unit) {
    val shown = entries.take(MAX_SHOWN)
    val up = remember { FocusRequester() }
    val requesters = remember(entries) { shown.associateWith { FocusRequester() } }
    LaunchedEffect(entries) { runCatching { (returnTo?.let { requesters[it] } ?: up).requestFocus() } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "..") {
            FocusCard(Modifier.fillMaxWidth().focusRequester(up), onClick = onUp) {
                Text(if (atRoot) "..  Volver al formulario" else "..  Subir", color = Color.White, fontSize = Text18)
            }
        }
        if (shown.isEmpty()) item(key = "vacio") {
            Text(if (atRoot) "Este servidor no comparte ningún recurso con estas credenciales." else "Esta carpeta no tiene subcarpetas. Puedes usarla o crear una.", color = White70, fontSize = Text16,
                modifier = Modifier.padding(top = 8.dp))
        }
        items(shown, key = { it }) { name ->
            FocusCard(Modifier.fillMaxWidth().focusRequester(requesters.getValue(name)), onClick = { onEnter(name) }) {
                Text(name, color = Color.White, fontSize = Text18, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (entries.size > MAX_SHOWN) item(key = "mas") {
            Text("Hay ${entries.size - MAX_SHOWN} más que no se enseñan; escribe la ruta en el formulario.", color = White60, fontSize = Text14)
        }
    }
}

/** Tope de carpetas que se pintan de una vez (más no caben en el mando). */
private const val MAX_SHOWN = 200
