package com.example.quickrow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.example.quickrow.ui.theme.QuickRowTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QuickRowTheme {
                val vm: TableViewModel by viewModels<TableViewModel>()
                val state by vm.state.collectAsState()
                AppScreen(state, vm::dispatch)
            }
        }
    }
}

// ───────────────────────── Root ─────────────────────────

@Composable
fun AppScreen(state: AppState, onIntent: (Intent) -> Unit) {
    Scaffold(
        topBar = {
            Surface(tonalElevation = 3.dp) {
                TabRow(
                    selectedTabIndex = state.tab.ordinal,
                    modifier = Modifier.statusBarsPadding()
                ) {
                    TabId.entries.forEach { tab ->
                        Tab(
                            selected = tab == state.tab,
                            onClick = { onIntent(Intent.SelectTab(tab)) },
                            // Фітс: Tab займає 1/3 ширини екрана й 48+ dp висоти
                            modifier = Modifier.heightIn(min = 48.dp),
                            text = { Text(tab.title) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // Варіант 1, Фітс: великий FAB у зоні великого пальця (правий нижній кут)
            if (state is AppState.Dialog && state.form is DialogForm.Closed) {
                LargeFloatingActionButton(onClick = { onIntent(Intent.DialogIntent.Open) }) {
                    Text("+", style = MaterialTheme.typography.headlineLarge)
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                is AppState.Dialog -> DialogVariant(state, onIntent)
                is AppState.Inline -> InlineVariant(state, onIntent)
                is AppState.Duplicate -> DuplicateVariant(state, onIntent)
            }
        }
    }
}

// ───────────────────────── Shared bits ─────────────────────────

@Composable
private fun HeaderRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        COLUMNS.forEach {
            Text(it, Modifier.weight(1f), fontWeight = FontWeight.Bold)
        }
    }
    HorizontalDivider()
}

@Composable
private fun CellsRow(cells: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        cells.forEach { Text(it, Modifier.weight(1f)) }
    }
}

@Composable
private fun CellField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    imeAction: ImeAction,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { focus.moveFocus(FocusDirection.Next) },
            onDone = { onDone() }
        )
    )
}

// ───────────────────────── Variant 1: FAB + діалог ─────────────────────────

@Composable
private fun DialogVariant(state: AppState.Dialog, onIntent: (Intent) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { HeaderRow() }
        items(state.rows, key = { it.id }) { row ->
            CellsRow(row.cells)
            HorizontalDivider()
        }
    }

    val form = state.form
    if (form is DialogForm.Open) {
        AlertDialog(
            onDismissRequest = { onIntent(Intent.DialogIntent.Dismiss) },
            title = { Text("Новий рядок") },
            text = {
                // Хік: лише потрібні поля + одна головна дія
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    COLUMNS.forEachIndexed { i, name ->
                        CellField(
                            value = form.fields[i],
                            label = name,
                            onChange = { onIntent(Intent.DialogIntent.Edit(i, it)) },
                            imeAction = if (i == COLUMNS.lastIndex) ImeAction.Done else ImeAction.Next,
                            onDone = { onIntent(Intent.DialogIntent.Submit) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                // Фітс: велика кнопка «Додати»
                Button(
                    onClick = { onIntent(Intent.DialogIntent.Submit) },
                    enabled = form.fields.all { it.isNotBlank() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Додати") }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(Intent.DialogIntent.Dismiss) }) { Text("Скасувати") }
            }
        )
    }
}

// ───────────────────────── Variant 2: inline-рядок ─────────────────────────

@Composable
private fun InlineVariant(state: AppState.Inline, onIntent: (Intent) -> Unit) {
    val listState = rememberLazyListState()
    val firstField = remember { FocusRequester() }

    // Після додавання: прокрутити донизу й повернути фокус у першу комірку нового чернеткового рядка
    LaunchedEffect(state.rows.size) {
        listState.animateScrollToItem(state.rows.size + 1)
        runCatching { firstField.requestFocus() }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
        item { HeaderRow() }
        items(state.rows, key = { it.id }) { row ->
            CellsRow(row.cells)
            HorizontalDivider()
        }
        item(key = "draft") {
            // Хік: єдина очевидна дія — заповнити та натиснути Enter; Фітс: поля займають всю ширину рядка
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                COLUMNS.forEachIndexed { i, name ->
                    CellField(
                        value = state.draft[i],
                        label = name,
                        onChange = { onIntent(Intent.InlineIntent.Edit(i, it)) },
                        imeAction = if (i == COLUMNS.lastIndex) ImeAction.Done else ImeAction.Next,
                        onDone = { onIntent(Intent.InlineIntent.Commit) },
                        modifier = Modifier
                            .weight(1f)
                            .then(if (i == 0) Modifier.focusRequester(firstField) else Modifier)
                    )
                }
            }
        }
    }
}

// ───────────────────────── Variant 3: довге натискання → дублювати ─────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DuplicateVariant(state: AppState.Duplicate, onIntent: (Intent) -> Unit) {
    val menu = state.menu

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { HeaderRow() }
        item {
            if (menu is RowMenu.Hidden) {
                Text(
                    "Довге натискання на рядку — меню додавання",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        items(state.rows, key = { it.id }) { row ->
            if (menu is RowMenu.Editing && menu.rowId == row.id) {
                EditingRow(menu.draft, onIntent)
            } else {
                Box {
                    CellsRow(
                        row.cells,
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { onIntent(Intent.DuplicateIntent.LongPress(row.id)) }
                        )
                    )
                    // Хік: у меню лише 2 пункти; Фітс: меню з'являється біля місця дотику
                    DropdownMenu(
                        expanded = menu is RowMenu.Shown && menu.rowId == row.id,
                        onDismissRequest = { onIntent(Intent.DuplicateIntent.DismissMenu) }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Дублювати рядок") },
                            onClick = { onIntent(Intent.DuplicateIntent.Duplicate(row.id)) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                        DropdownMenuItem(
                            text = { Text("Додати нижче") },
                            onClick = { onIntent(Intent.DuplicateIntent.AddBelow(row.id)) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun EditingRow(draft: List<String>, onIntent: (Intent) -> Unit) {
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            COLUMNS.forEachIndexed { i, name ->
                CellField(
                    value = draft[i],
                    label = name,
                    onChange = { onIntent(Intent.DuplicateIntent.EditCell(i, it)) },
                    imeAction = if (i == COLUMNS.lastIndex) ImeAction.Done else ImeAction.Next,
                    onDone = { onIntent(Intent.DuplicateIntent.Confirm) },
                    modifier = Modifier
                        .weight(1f)
                        .then(if (i == 0) Modifier.focusRequester(firstField) else Modifier)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { onIntent(Intent.DuplicateIntent.Cancel) }) { Text("Скасувати") }
            Button(
                onClick = { onIntent(Intent.DuplicateIntent.Confirm) },
                enabled = draft.all { it.isNotBlank() }
            ) { Text("OK") }
        }
    }
}

// ───────────────────────── Domain ─────────────────────────

val COLUMNS = listOf("Назва", "Категорія", "К-сть")
private val BLANK = List(COLUMNS.size) { "" }

data class Row(val id: Long, val cells: List<String>)

enum class TabId(val title: String) {
    Dialog("1. Діалог"),
    Inline("2. Inline"),
    Duplicate("3. Дублювання")
}

// ───────────────────────── State (sealed hierarchy) ─────────────────────────

/** Стан форми варіанту 1. */
sealed interface DialogForm {
    data object Closed : DialogForm
    data class Open(val fields: List<String> = BLANK) : DialogForm
}

/** Стан контекстного меню / редагування варіанту 3. */
sealed interface RowMenu {
    data object Hidden : RowMenu
    data class Shown(val rowId: Long) : RowMenu
    data class Editing(val rowId: Long, val draft: List<String>) : RowMenu
}

/** Кореневий стан: кожен варіант інтерфейсу — окремий підтип зі своїм під-станом. */
sealed interface AppState {
    val rows: List<Row>

    data class Dialog(
        override val rows: List<Row>,
        val form: DialogForm = DialogForm.Closed
    ) : AppState

    data class Inline(
        override val rows: List<Row>,
        val draft: List<String> = BLANK
    ) : AppState

    data class Duplicate(
        override val rows: List<Row>,
        val menu: RowMenu = RowMenu.Hidden
    ) : AppState
}

val AppState.tab: TabId
    get() = when (this) {
        is AppState.Dialog -> TabId.Dialog
        is AppState.Inline -> TabId.Inline
        is AppState.Duplicate -> TabId.Duplicate
    }

// ───────────────────────── Intents (sealed hierarchy) ─────────────────────────

sealed interface Intent {
    data class SelectTab(val tab: TabId) : Intent

    sealed interface DialogIntent : Intent {
        data object Open : DialogIntent
        data object Dismiss : DialogIntent
        data class Edit(val index: Int, val text: String) : DialogIntent
        data object Submit : DialogIntent
    }

    sealed interface InlineIntent : Intent {
        data class Edit(val index: Int, val text: String) : InlineIntent
        data object Commit : InlineIntent
    }

    sealed interface DuplicateIntent : Intent {
        data class LongPress(val rowId: Long) : DuplicateIntent
        data object DismissMenu : DuplicateIntent
        data class Duplicate(val rowId: Long) : DuplicateIntent
        data class AddBelow(val rowId: Long) : DuplicateIntent
        data class EditCell(val index: Int, val text: String) : DuplicateIntent
        data object Confirm : DuplicateIntent
        data object Cancel : DuplicateIntent
    }
}

// ───────────────────────── Reducer (pure) ─────────────────────────

fun reduce(state: AppState, intent: Intent): AppState = when (intent) {
    is Intent.SelectTab -> when (intent.tab) {
        TabId.Dialog -> AppState.Dialog(state.rows)
        TabId.Inline -> AppState.Inline(state.rows)
        TabId.Duplicate -> AppState.Duplicate(state.rows)
    }
    is Intent.DialogIntent ->
        if (state is AppState.Dialog) reduceDialog(state, intent) else state
    is Intent.InlineIntent ->
        if (state is AppState.Inline) reduceInline(state, intent) else state
    is Intent.DuplicateIntent ->
        if (state is AppState.Duplicate) reduceDuplicate(state, intent) else state
}

private fun reduceDialog(s: AppState.Dialog, i: Intent.DialogIntent): AppState = when (i) {
    Intent.DialogIntent.Open -> s.copy(form = DialogForm.Open())
    Intent.DialogIntent.Dismiss -> s.copy(form = DialogForm.Closed)
    is Intent.DialogIntent.Edit -> when (val f = s.form) {
        is DialogForm.Open -> s.copy(form = f.copy(fields = f.fields.set(i.index, i.text)))
        DialogForm.Closed -> s
    }
    Intent.DialogIntent.Submit -> when (val f = s.form) {
        is DialogForm.Open ->
            if (f.fields.isFilled()) s.copy(
                rows = s.rows + Row(s.rows.nextId(), f.fields),
                form = DialogForm.Closed
            ) else s
        DialogForm.Closed -> s
    }
}

private fun reduceInline(s: AppState.Inline, i: Intent.InlineIntent): AppState = when (i) {
    is Intent.InlineIntent.Edit -> s.copy(draft = s.draft.set(i.index, i.text))
    Intent.InlineIntent.Commit ->
        if (s.draft.isFilled()) s.copy(
            rows = s.rows + Row(s.rows.nextId(), s.draft),
            draft = BLANK
        ) else s
}

private fun reduceDuplicate(s: AppState.Duplicate, i: Intent.DuplicateIntent): AppState = when (i) {
    is Intent.DuplicateIntent.LongPress -> s.copy(menu = RowMenu.Shown(i.rowId))
    Intent.DuplicateIntent.DismissMenu -> s.copy(menu = RowMenu.Hidden)
    is Intent.DuplicateIntent.Duplicate -> {
        val src = s.rows.firstOrNull { it.id == i.rowId }
        if (src == null) s else s.insertBelow(i.rowId, src.cells)
    }
    is Intent.DuplicateIntent.AddBelow -> s.insertBelow(i.rowId, BLANK)
    is Intent.DuplicateIntent.EditCell -> when (val m = s.menu) {
        is RowMenu.Editing -> s.copy(menu = m.copy(draft = m.draft.set(i.index, i.text)))
        else -> s
    }
    Intent.DuplicateIntent.Confirm -> when (val m = s.menu) {
        is RowMenu.Editing ->
            if (m.draft.isFilled()) s.copy(
                rows = s.rows.map { if (it.id == m.rowId) it.copy(cells = m.draft) else it },
                menu = RowMenu.Hidden
            ) else s
        else -> s
    }
    Intent.DuplicateIntent.Cancel -> when (val m = s.menu) {
        is RowMenu.Editing -> s.copy(
            rows = s.rows.filterNot { it.id == m.rowId },
            menu = RowMenu.Hidden
        )
        else -> s
    }
}

private fun AppState.Duplicate.insertBelow(afterId: Long, cells: List<String>): AppState.Duplicate {
    val idx = rows.indexOfFirst { it.id == afterId }
    if (idx < 0) return this
    val newRow = Row(rows.nextId(), cells)
    return copy(
        rows = rows.take(idx + 1) + newRow + rows.drop(idx + 1),
        menu = RowMenu.Editing(newRow.id, cells)
    )
}

private fun List<String>.set(index: Int, value: String) =
    mapIndexed { i, old -> if (i == index) value else old }

private fun List<String>.isFilled() = all { it.isNotBlank() }
private fun List<Row>.nextId() = (maxOfOrNull { it.id } ?: 0L) + 1

// ───────────────────────── ViewModel ─────────────────────────

class TableViewModel : ViewModel() {
    private val _state = MutableStateFlow<AppState>(
        AppState.Dialog(
            rows = listOf(
                Row(1, listOf("Молоко", "Продукти", "2")),
                Row(2, listOf("Зошит", "Канцелярія", "5")),
                Row(3, listOf("Мило", "Побут", "3"))
            )
        )
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun dispatch(intent: Intent) = _state.update { reduce(it, intent) }
}

