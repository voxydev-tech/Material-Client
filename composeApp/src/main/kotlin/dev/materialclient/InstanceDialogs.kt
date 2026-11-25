package dev.materialclient

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DlgAccent = Color(0xFF72E5D1)
private val DlgMuted = Color(0xFFADB4BE)
private val DlgSurface = Color(0xFF292E35)

internal fun instanceIconVector(key: String, loader: String): ImageVector = when (key) {
    "cube" -> Icons.Default.ViewInAr
    "grass" -> Icons.Default.Grass
    "sword" -> Icons.Default.Shield
    "pickaxe" -> Icons.Default.Hardware
    "star" -> Icons.Default.Star
    "flame" -> Icons.Default.LocalFireDepartment
    "heart" -> Icons.Default.Favorite
    "bolt" -> Icons.Default.Bolt
    "cloud" -> Icons.Default.Cloud
    "book" -> Icons.Default.MenuBook
    "tree" -> Icons.Default.Park
    "diamond" -> Icons.Default.Diamond
    else -> when (loader.lowercase()) {
        "fabric" -> Icons.Default.Extension
        "forge" -> Icons.Default.Build
        else -> Icons.Default.Computer
    }
}

private fun parseColor(hex: String): Color? {
    val clean = InstanceIcons.normalizeColor(hex)
    if (clean.isEmpty()) return null
    return Color(clean.substring(1).toLong(16) or 0xFF000000L)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%d KB", (bytes + 1023) / 1024)
}

private fun formatDate(millis: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(millis))

/** Значок экземпляра: своя картинка, встроенный значок с цветом или значок загрузчика. */
@Composable
internal fun InstanceIconBadge(profile: Instance, selected: Boolean, size: Dp = 48.dp, iconSize: Dp = 27.dp) {
    val imageFile = InstanceIcons.imageFile(profile.name)
    val stamp = if (profile.icon == InstanceIcons.IMAGE) imageFile.lastModified() else 0L
    val bitmap by produceState<ImageBitmap?>(initialValue = null, profile.name, profile.icon, stamp) {
        value = if (profile.icon == InstanceIcons.IMAGE && stamp > 0) {
            withContext(Dispatchers.IO) {
                try { ImageIO.read(imageFile)?.toComposeImageBitmap() } catch (_: Exception) { null }
            }
        } else null
    }
    val custom = parseColor(profile.iconColor)
    val tint = custom ?: if (selected) DlgAccent else DlgMuted
    val shown = bitmap
    Surface(
        color = if (custom != null) custom.copy(alpha = 0.2f) else if (selected) DlgAccent.copy(alpha = 0.16f) else DlgSurface,
        shape = RoundedCornerShape(size / 3.4f),
        modifier = Modifier.size(size)
    ) {
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize())
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    instanceIconVector(if (profile.icon == InstanceIcons.IMAGE) "" else profile.icon, profile.loader),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

private fun chooseFile(title: String, mode: Int, suggestion: String): File? {
    val dialog = FileDialog(null as Frame?, title, mode)
    dialog.file = suggestion
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }
}

@Composable
private fun StatusText(text: String) {
    if (text.isNotBlank()) {
        Text(text, color = if (I18n.isProblem(text)) MaterialTheme.colorScheme.error else DlgMuted, fontSize = 12.sp)
    }
}

/** Выбор значка, цвета и своей картинки для экземпляра. */
@Composable
internal fun IconPickerDialog(profile: Instance, onDismiss: () -> Unit, onSave: (Instance) -> Unit) {
    var key by remember { mutableStateOf(profile.icon) }
    var color by remember { mutableStateOf(profile.iconColor) }
    var status by remember { mutableStateOf("") }
    val preview = profile.copy(icon = key, iconColor = color)
    // Картинка пишется на диск сразу, поэтому запоминаем исходную, чтобы «Отмена» ничего не меняла.
    val originalImage = remember { runCatching { InstanceIcons.imageFile(profile.name).takeIf { it.isFile }?.readBytes() }.getOrNull() }
    fun cancel() {
        runCatching {
            val f = InstanceIcons.imageFile(profile.name)
            if (originalImage != null) f.writeBytes(originalImage) else f.delete()
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { cancel() },
        title = { Text(tr("Значок экземпляра")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    InstanceIconBadge(preview, selected = true, size = 64.dp, iconSize = 36.dp)
                    Column {
                        Text(profile.name, fontSize = 16.sp)
                        Text("${profile.version} • ${profile.loader}", color = DlgMuted, fontSize = 12.sp)
                    }
                }
                Text(tr("Значок"), color = DlgMuted, fontSize = 12.sp)
                for (row in listOf("") .plus(InstanceIcons.presets).chunked(7)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (item in row) {
                            val selected = key == item
                            Surface(
                                color = if (selected) DlgAccent.copy(alpha = 0.16f) else DlgSurface,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) DlgAccent else Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { key = item }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(instanceIconVector(item, profile.loader), contentDescription = null, tint = parseColor(color) ?: DlgMuted, modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
                Text(tr("Цвет"), color = DlgMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (hex in listOf("") + InstanceIcons.palette) {
                        val selected = color == hex
                        val c = parseColor(hex)
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(c ?: DlgSurface)
                                .clickable { color = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF0B1F1B), modifier = Modifier.size(16.dp))
                            else if (c == null) Text("—", color = DlgMuted, fontSize = 12.sp)
                        }
                    }
                }
                Text(tr("Своя картинка"), color = DlgMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val file = chooseFile(tr("Выбери картинку (PNG или JPG)"), FileDialog.LOAD, "*.png;*.jpg;*.jpeg") ?: return@OutlinedButton
                        try {
                            InstanceIcons.setImage(profile.name, file)
                            key = InstanceIcons.IMAGE
                            status = ""
                        } catch (e: Exception) {
                            status = e.message ?: tr("Ошибка")
                        }
                    }) { Text(tr("Выбрать картинку…")) }
                    if (key == InstanceIcons.IMAGE) {
                        TextButton(onClick = { InstanceIcons.removeImage(profile.name); key = ""; status = "" }) { Text(tr("Убрать картинку")) }
                    }
                }
                StatusText(status)
            }
        },
        confirmButton = { Button(onClick = { onSave(profile.copy(icon = InstanceIcons.normalizeKey(key), iconColor = InstanceIcons.normalizeColor(color))) }) { Text(tr("Сохранить")) } },
        dismissButton = { TextButton(onClick = { cancel() }) { Text(tr("Отмена")) } }
    )
}

/** Миры экземпляра и их резервные копии. [running] — игра запущена: восстановление тогда запрещено. */
@Composable
internal fun WorldsDialog(profile: Instance, running: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var worlds by remember { mutableStateOf<List<WorldInfo>>(emptyList()) }
    var backups by remember { mutableStateOf<List<BackupInfo>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    var confirmRestore by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(profile.name, refresh) {
        withContext(Dispatchers.IO) {
            worlds = WorldBackups.worlds(profile.name)
            backups = WorldBackups.list(profile.name)
        }
    }
    fun run(start: String, work: () -> String) {
        if (busy) return
        busy = true
        status = start
        scope.launch {
            try {
                status = withContext(Dispatchers.IO) { work() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = tr("Ошибка: {0}", e.message ?: tr("ошибка"))
            } finally {
                busy = false
                refresh++
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Миры и резервные копии")) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Копии хранятся отдельно от экземпляра, поэтому не пропадут вместе с ним."), color = DlgMuted, fontSize = 12.sp)
                Text(tr("Миры"), fontSize = 14.sp)
                if (worlds.isEmpty()) Text(tr("В этом экземпляре пока нет миров."), color = DlgMuted, fontSize = 12.sp)
                for (world in worlds) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(world.folder, fontSize = 13.sp)
                            Text("${formatBytes(world.sizeBytes)} • ${formatDate(world.lastModified)}", color = DlgMuted, fontSize = 11.sp)
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                run(tr("Сохраняем копию мира «{0}»…", world.folder)) {
                                    WorldBackups.backup(profile.name, world.folder)
                                    tr("Копия мира «{0}» сохранена.", world.folder)
                                }
                            }
                        ) { Text(tr("Сохранить копию")) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(tr("Резервные копии"), fontSize = 14.sp)
                if (backups.isEmpty()) Text(tr("Копий пока нет."), color = DlgMuted, fontSize = 12.sp)
                for (backup in backups) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(backup.world, fontSize = 13.sp)
                            Text("${formatDate(backup.createdAt)} • ${formatBytes(backup.sizeBytes)}", color = DlgMuted, fontSize = 11.sp)
                        }
                        TextButton(
                            enabled = !busy && !running,
                            onClick = {
                                if (confirmRestore == backup.file) {
                                    confirmRestore = null
                                    run(tr("Восстанавливаем мир «{0}»…", backup.world)) {
                                        val name = WorldBackups.restore(profile.name, backup.file)
                                        tr("Мир «{0}» восстановлен. Прежняя версия, если была, сохранена рядом как «… (до восстановления)».", name)
                                    }
                                } else {
                                    confirmRestore = backup.file
                                    confirmDelete = null
                                }
                            }
                        ) { Text(if (confirmRestore == backup.file) tr("Точно?") else tr("Восстановить")) }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                if (confirmDelete == backup.file) {
                                    confirmDelete = null
                                    run(tr("Удаляем копию…")) { WorldBackups.delete(backup.file); tr("Копия удалена.") }
                                } else {
                                    confirmDelete = backup.file
                                    confirmRestore = null
                                }
                            }
                        ) { Text(if (confirmDelete == backup.file) tr("Точно?") else tr("Удалить")) }
                    }
                }
                if (running) Text(tr("Пока игра запущена, восстанавливать копии нельзя."), color = DlgMuted, fontSize = 12.sp)
                StatusText(status)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Закрыть")) } }
    )
}

/** Экспорт экземпляра в .mrpack. */
@Composable
internal fun ExportPackDialog(profile: Instance, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(profile.name) }
    var version by remember { mutableStateOf("1.0.0") }
    var worlds by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Экспорт сборки (.mrpack)")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("Файлы с Modrinth попадут в сборку ссылками, остальное, включая настройки, будет внутри архива. Такую сборку можно импортировать в этот лаунчер, Modrinth App и Prism."),
                    color = DlgMuted, fontSize = 12.sp
                )
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text(tr("Название сборки")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = version, onValueChange = { version = it.take(20) }, label = { Text(tr("Версия сборки")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = worlds, onCheckedChange = { worlds = it })
                    Text(tr("Добавить миры (архив станет больше)"), fontSize = 13.sp)
                }
                StatusText(status)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    val safe = name.trim().ifEmpty { profile.name }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val target = chooseFile(tr("Сохранить сборку"), FileDialog.SAVE, "$safe.mrpack") ?: return@Button
                    val finalTarget = if (target.name.endsWith(".mrpack", true)) target else File(target.parentFile, target.name + ".mrpack")
                    busy = true
                    status = tr("Готовим сборку…")
                    scope.launch {
                        try {
                            val report = withContext(Dispatchers.IO) {
                                MrPackExport.export(profile, finalTarget, name, version, worlds, { status = it })
                            }
                            status = report.message
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            status = tr("Ошибка: не удалось сохранить сборку: {0}", e.message ?: tr("ошибка"))
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text(tr("Сохранить как…")) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Закрыть")) } }
    )
}

/** Блок «Скин игрока» для офлайн-аккаунта: выбор PNG, превью лица и установка мода CustomSkinLoader. */
@Composable
internal fun SkinSettings(nick: String, instances: List<Instance>) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val skinFile = OfflineSkins.libraryFile(nick)
    val hasSkin = OfflineSkins.hasSkin(nick)
    val face by produceState<ImageBitmap?>(initialValue = null, nick, refresh) {
        value = if (hasSkin) withContext(Dispatchers.IO) {
            try { OfflineSkins.face(skinFile, 8).toComposeImageBitmap() } catch (_: Exception) { null }
        } else null
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Скин игрока"), fontSize = 16.sp)
        Text(
            tr("Скин привязан к нику. В офлайн-режиме Minecraft сам его не показывает, поэтому нужен мод CustomSkinLoader: лаунчер положит скин в папку каждого экземпляра, а мод можно поставить кнопкой ниже (только для Fabric, Forge, NeoForge и Quilt)."),
            color = DlgMuted, fontSize = 12.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(color = DlgSurface, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(64.dp)) {
                val shown = face
                if (shown != null) Image(shown, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
                else Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = null, tint = DlgMuted, modifier = Modifier.size(32.dp)) }
            }
            Column {
                Text(nick.ifBlank { tr("Ник не задан") }, fontSize = 14.sp)
                Text(if (hasSkin) tr("Скин выбран") else tr("Скин не выбран"), color = DlgMuted, fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busy && OfflineSkins.validNick(nick),
                onClick = {
                    val file = chooseFile(tr("Выбери скин (PNG 64×64)"), FileDialog.LOAD, "*.png") ?: return@Button
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                OfflineSkins.save(nick, file)
                                instances.forEach { OfflineSkins.applyTo(it.name, nick) }
                            }
                            status = tr("Скин сохранён для ника {0}.", nick)
                            refresh++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            status = e.message ?: tr("Ошибка")
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text(tr("Выбрать скин…")) }
            if (hasSkin) {
                TextButton(enabled = !busy, onClick = {
                    OfflineSkins.remove(nick)
                    instances.forEach { OfflineSkins.removeFrom(it.name, nick) }
                    status = tr("Скин удалён.")
                    refresh++
                }) { Text(tr("Убрать скин")) }
            }
            Box {
                OutlinedButton(enabled = !busy && instances.any { ModLoaders.canonical(it.loader) != null }, onClick = { menu = true }) { Text(tr("Поставить CustomSkinLoader…")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (instance in instances.filter { ModLoaders.canonical(it.loader) != null }) {
                        DropdownMenuItem(
                            text = { Text("${instance.name} (${instance.version}, ${instance.loader})") },
                            onClick = {
                                menu = false
                                busy = true
                                status = tr("Скачиваем {0}…", "CustomSkinLoader")
                                scope.launch {
                                    try {
                                        status = ModrinthApi.install(OfflineSkins.MOD_PROJECT, instance) { status = it }.message
                                        withContext(Dispatchers.IO) { OfflineSkins.applyTo(instance.name, nick) }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        status = tr("Ошибка установки {0}: {1}", "CustomSkinLoader", e.message ?: tr("ошибка"))
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
        StatusText(status)
    }
}
