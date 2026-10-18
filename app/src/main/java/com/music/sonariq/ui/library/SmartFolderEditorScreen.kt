package com.music.sonariq.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.music.sonariq.R
import com.music.sonariq.data.library.PlaylistFolders
import com.music.sonariq.data.library.SmartFolder

/**
 * The rule editor for a smart folder: a title, an all/any match mode, and
 * the list of rules. Saving writes through [PlaylistFolders]; a folder with
 * no rules cannot be saved, because a rule-less smart folder would be every
 * song or no songs depending on the reader's mood.
 *
 * @param folder the folder being edited, or null to create a new one.
 * @param onDone closes the editor (after save).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartFolderEditorScreen(
    folder: SmartFolder?,
    onDone: () -> Unit,
) {
    var title by remember { mutableStateOf(folder?.title ?: "") }
    var matchAll by remember { mutableStateOf(folder?.matchAll ?: true) }
    var rules by remember { mutableStateOf(folder?.rules ?: emptyList()) }
    var showAddRule by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_smart_folder_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val trimmed = title.trim()
                            if (trimmed.isEmpty() || rules.isEmpty()) return@TextButton
                            if (folder == null) {
                                PlaylistFolders.createSmartFolder(trimmed, rules, matchAll)
                            } else {
                                PlaylistFolders.updateSmartFolder(
                                    folder.copy(title = trimmed, rules = rules, matchAll = matchAll),
                                )
                            }
                            onDone()
                        },
                        enabled = title.trim().isNotEmpty() && rules.isNotEmpty(),
                    ) { Text(stringResource(R.string.save)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddRule = true }) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.library_add_rule))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.library_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "match-mode") {
                Column {
                    Text(
                        stringResource(R.string.library_rule_match_mode),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = matchAll,
                            onClick = { matchAll = true },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text(stringResource(R.string.library_rule_match_all)) }
                        SegmentedButton(
                            selected = !matchAll,
                            onClick = { matchAll = false },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text(stringResource(R.string.library_rule_match_any)) }
                    }
                }
            }
            item(key = "rules-header") {
                Text(
                    stringResource(R.string.library_rules),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (rules.isEmpty()) {
                item(key = "no-rules") {
                    Text(
                        stringResource(R.string.library_no_rules),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rules, key = { it.hashCode().toString() + rules.indexOf(it) }) { rule ->
                ListItem(
                    headlineContent = { Text(PlaylistFolders.describe(rule)) },
                    trailingContent = {
                        IconButton(onClick = { rules = rules - rule }) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    },
                )
            }
            item(key = "bottom-space") { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showAddRule) {
        AddRuleDialog(
            onDismiss = { showAddRule = false },
            onAdd = { rule ->
                rules = rules + rule
                showAddRule = false
            },
        )
    }
}

/** The eight rule kinds the library metadata can actually answer. */
private enum class RuleKind(val titleRes: Int, val hintRes: Int, val input: RuleInput) {
    ARTIST(R.string.library_rule_artist, R.string.library_rule_artist_hint, RuleInput.TEXT),
    ALBUM(R.string.library_rule_album, R.string.library_rule_album_hint, RuleInput.TEXT),
    ADDED_DAYS(R.string.library_rule_added_days, R.string.library_rule_days_hint, RuleInput.NUMBER),
    MIN_PLAYS(R.string.library_rule_min_plays, R.string.library_rule_plays_hint, RuleInput.NUMBER),
    MAX_PLAYS(R.string.library_rule_max_plays, R.string.library_rule_plays_hint, RuleInput.NUMBER),
    QUIET_DAYS(R.string.library_rule_quiet_days, R.string.library_rule_days_hint, RuleInput.NUMBER),
    EXPLICIT(R.string.library_rule_explicit, R.string.library_rule_explicit_hint, RuleInput.BOOLEAN),
    QUALITY(R.string.library_rule_quality, R.string.library_rule_quality_hint, RuleInput.QUALITY),
}

private enum class RuleInput { TEXT, NUMBER, BOOLEAN, QUALITY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (PlaylistFolders.SmartRule) -> Unit,
) {
    var kind by remember { mutableStateOf(RuleKind.ARTIST) }
    var expanded by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var boolean by remember { mutableStateOf(true) }
    var quality by remember { mutableStateOf("LOSSLESS") }

    val canAdd = when (kind.input) {
        RuleInput.TEXT -> text.isNotBlank()
        RuleInput.NUMBER -> number.toIntOrNull()?.let { it > 0 } == true
        RuleInput.BOOLEAN, RuleInput.QUALITY -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_add_rule)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = stringResource(kind.titleRes),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.library_rule_kind)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        RuleKind.entries.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(stringResource(entry.titleRes)) },
                                onClick = { kind = entry; expanded = false },
                            )
                        }
                    }
                }
                when (kind.input) {
                    RuleInput.TEXT -> OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(kind.hintRes)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RuleInput.NUMBER -> OutlinedTextField(
                        value = number,
                        onValueChange = { number = it.filter(Char::isDigit) },
                        label = { Text(stringResource(kind.hintRes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RuleInput.BOOLEAN -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(kind.hintRes))
                        Spacer(Modifier.weight(1f))
                        Switch(checked = boolean, onCheckedChange = { boolean = it })
                    }
                    RuleInput.QUALITY -> {
                        var qExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = qExpanded, onExpandedChange = { qExpanded = it }) {
                            OutlinedTextField(
                                value = quality,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(kind.hintRes)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(qExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                            )
                            ExposedDropdownMenu(expanded = qExpanded, onDismissRequest = { qExpanded = false }) {
                                listOf("LOSSLESS", "HIGH", "LOW").forEach { q ->
                                    DropdownMenuItem(
                                        text = { Text(q) },
                                        onClick = { quality = q; qExpanded = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val rule: PlaylistFolders.SmartRule = when (kind) {
                        RuleKind.ARTIST -> PlaylistFolders.SmartRule.ArtistContains(text.trim())
                        RuleKind.ALBUM -> PlaylistFolders.SmartRule.AlbumContains(text.trim())
                        RuleKind.ADDED_DAYS -> PlaylistFolders.SmartRule.AddedInLastDays(number.toInt())
                        RuleKind.MIN_PLAYS -> PlaylistFolders.SmartRule.MinPlays(number.toInt())
                        RuleKind.MAX_PLAYS -> PlaylistFolders.SmartRule.MaxPlays(number.toInt())
                        RuleKind.QUIET_DAYS -> PlaylistFolders.SmartRule.NotPlayedInDays(number.toInt())
                        RuleKind.EXPLICIT -> PlaylistFolders.SmartRule.IsExplicit(boolean)
                        RuleKind.QUALITY -> PlaylistFolders.SmartRule.SourceQuality(quality)
                    }
                    onAdd(rule)
                },
                enabled = canAdd,
            ) { Text(stringResource(R.string.library_action_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
