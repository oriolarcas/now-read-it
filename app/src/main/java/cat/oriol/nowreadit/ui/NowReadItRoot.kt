package cat.oriol.nowreadit.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cat.oriol.nowreadit.NowReadItApplication
import cat.oriol.nowreadit.data.TtsSettings
import cat.oriol.nowreadit.data.local.AudioStatus
import cat.oriol.nowreadit.data.local.LibraryItemEntity
import cat.oriol.nowreadit.data.local.hasCurrentAudio
import cat.oriol.nowreadit.data.local.hasGenerationForCurrentText
import cat.oriol.nowreadit.data.local.needsAudioForCurrentText

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DETAIL = "detail"

@Composable
fun NowReadItRoot(
    application: NowReadItApplication,
    incomingSharedUrl: String?,
    onSharedUrlConsumed: () -> Unit,
) {
    MaterialTheme {
        val navController = rememberNavController()
        NavHost(
            navController = navController,
            startDestination = ROUTE_LIBRARY,
        ) {
            composable(ROUTE_LIBRARY) {
                val viewModel: LibraryViewModel = viewModel(
                    factory = LibraryViewModelFactory(application.appContainer.libraryRepository),
                )
                LibraryScreen(
                    viewModel = viewModel,
                    incomingSharedUrl = incomingSharedUrl,
                    onSharedUrlConsumed = onSharedUrlConsumed,
                    onOpenItem = { itemId -> navController.navigate("$ROUTE_DETAIL/$itemId") },
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                )
            }
            composable(ROUTE_SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(
                    settingsStore = application.appContainer.settingsStore,
                    repository = application.appContainer.libraryRepository,
                ),
            )
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "$ROUTE_DETAIL/{itemId}",
                arguments = listOf(navArgument("itemId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val itemId = backStackEntry.arguments?.getLong("itemId") ?: return@composable
                val viewModel: ItemDetailViewModel = viewModel(
                    factory = ItemDetailViewModelFactory(
                        itemId = itemId,
                        repository = application.appContainer.libraryRepository,
                    ),
                )
                ItemDetailScreen(
                    itemId = itemId,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    viewModel: LibraryViewModel,
    incomingSharedUrl: String?,
    onSharedUrlConsumed: () -> Unit,
    onOpenItem: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var inputUrl by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(incomingSharedUrl) {
        incomingSharedUrl?.let { sharedUrl ->
            viewModel.importUrl(sharedUrl) { itemId ->
                itemId?.let(onOpenItem)
            }
            onSharedUrlConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Read It") },
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Text("Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Import a web page and keep the text and generated MP3 in one local item.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                label = { Text("Article URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val url = inputUrl
                    viewModel.importUrl(url) { itemId ->
                        inputUrl = ""
                        itemId?.let(onOpenItem)
                    }
                },
                enabled = !isImporting && inputUrl.isNotBlank(),
            ) {
                Text(if (isImporting) "Importing..." else "Save to library")
            }
            Spacer(modifier = Modifier.height(20.dp))
            if (items.isEmpty()) {
                Text(
                    text = "No saved items yet. Add a URL here or share one from another app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        LibraryItemCard(
                            item = item,
                            onOpen = { onOpenItem(item.id) },
                            onDelete = { viewModel.deleteItem(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryItemCard(
    item: LibraryItemEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = listOfNotNull(item.siteName, formatTimestamp(item.importedAt)).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Import: ${item.importStatus.name.lowercase()} • Audio: ${item.audioStatus.name.lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (item.hasGenerationForCurrentText()) {
                AudioProgress(
                    status = item.audioStatus,
                    progressPercent = item.audioProgressPercent,
                )
            }
            item.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatDuration(item.audioDurationMs) ?: "No MP3 yet",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onDelete) {
                    Text("Delete")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDetailScreen(
    itemId: Long,
    viewModel: ItemDetailViewModel,
    onBack: () -> Unit,
) {
    val item by viewModel.item.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editorText by rememberSaveable(itemId) { mutableStateOf("") }
    var lastBoundItemId by remember { mutableStateOf<Long?>(null) }
    var confirmGeneration by rememberSaveable { mutableStateOf(false) }
    var isEditing by rememberSaveable(itemId) { mutableStateOf(false) }

    LaunchedEffect(item?.id, item?.extractedText) {
        val current = item ?: return@LaunchedEffect
        if (lastBoundItemId != current.id || editorText != current.extractedText) {
            editorText = current.extractedText
            lastBoundItemId = current.id
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit text" else item?.title ?: "Item") },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            if (isEditing) {
                                isEditing = false
                                editorText = item?.extractedText.orEmpty()
                            } else {
                                onBack()
                            }
                        },
                    ) {
                        Text("Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        val currentItem = item
        if (currentItem == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            ) {
                Text("Item not found.")
            }
            return@Scaffold
        }

        if (isEditing) {
            EditArticleText(
                item = currentItem,
                editorText = editorText,
                onTextChange = { editorText = it },
                onSave = {
                    viewModel.saveText(currentItem.id, editorText)
                    isEditing = false
                },
                modifier = Modifier.padding(paddingValues),
            )
        } else {
            ArticleDetailContent(
                libraryItem = currentItem,
                onEdit = { isEditing = true },
                onReadNow = { confirmGeneration = true },
                onPlayAudio = { viewModel.playAudio(currentItem) },
                modifier = Modifier.padding(paddingValues),
            )
        }

        if (confirmGeneration) {
            AlertDialog(
                onDismissRequest = { confirmGeneration = false },
                title = { Text("Read it now?") },
                text = {
                    Text(
                        "This will generate an MP3 for ${estimateReadingDuration(currentItem.extractedText)} of audio.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmGeneration = false
                            viewModel.generateAudio(currentItem.id)
                        },
                    ) {
                        Text("Generate")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmGeneration = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun ArticleDetailContent(
    libraryItem: LibraryItemEntity,
    onEdit: () -> Unit,
    onReadNow: () -> Unit,
    onPlayAudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasCurrentAudio = libraryItem.hasCurrentAudio()
    val hasGenerationForCurrentText = libraryItem.hasGenerationForCurrentText()
    val needsAudioForCurrentText = libraryItem.needsAudioForCurrentText()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text(
                text = libraryItem.sourceUrl,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Imported ${formatTimestamp(libraryItem.importedAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (hasGenerationForCurrentText) {
            item {
                AudioProgress(
                    status = libraryItem.audioStatus,
                    progressPercent = libraryItem.audioProgressPercent,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEdit) {
                    Text("Edit text")
                }
                if (needsAudioForCurrentText) {
                    Button(onClick = onReadNow) {
                        Text("Read it now")
                    }
                }
            }
        }
        item {
            if (libraryItem.audioPath != null) {
                Button(onClick = onPlayAudio) {
                    val duration = formatDuration(libraryItem.audioDurationMs)?.let { " ($it)" }.orEmpty()
                    Text("${if (hasCurrentAudio) "Play MP3" else "Play previous MP3"}$duration")
                }
            }
        }
        item {
            libraryItem.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            SelectionContainer {
                Text(
                    text = libraryItem.extractedText,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun EditArticleText(
    item: LibraryItemEntity,
    editorText: String,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        OutlinedTextField(
            value = editorText,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = { Text("Article text") },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onSave,
            enabled = editorText != item.extractedText,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }
    }
}

@Composable
private fun AudioProgress(
    status: AudioStatus,
    progressPercent: Int?,
) {
    val visible = status == AudioStatus.QUEUED || status == AudioStatus.GENERATING
    if (!visible) return

    val percent = progressPercent?.coerceIn(0, 100) ?: 0
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (status == AudioStatus.QUEUED) "Waiting" else "Generating audio",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    LinearProgressIndicator(
        progress = { percent / 100f },
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var voice by rememberSaveable { mutableStateOf("") }
    var speed by rememberSaveable { mutableStateOf("1.0") }
    var loaded by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let(viewModel::exportLibrary)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importLibrary)
    }

    LaunchedEffect(settings) {
        if (!loaded || apiKey.isBlank()) {
            apiKey = settings.apiKey
            model = settings.model
            voice = settings.voice
            speed = settings.speed.toString()
            loaded = true
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "The OpenAI API key stays on-device in encrypted local storage.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("OpenAI API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("TTS model") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = voice,
                onValueChange = { voice = it },
                label = { Text("Voice") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = speed,
                onValueChange = { speed = it },
                label = { Text("Speed") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = {
                    viewModel.updateSettings(
                        TtsSettings(
                            apiKey = apiKey,
                            model = model.ifBlank { "gpt-4o-mini-tts" },
                            voice = voice.ifBlank { "alloy" },
                            speed = speed.toFloatOrNull() ?: 1.0f,
                        ),
                    )
                    viewModel.save()
                },
            ) {
                Text("Save settings")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { exportLauncher.launch("now-read-it-library.zip") },
                ) {
                    Text("Export library")
                }
                Button(
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                ) {
                    Text("Import library")
                }
            }
        }
    }
}
