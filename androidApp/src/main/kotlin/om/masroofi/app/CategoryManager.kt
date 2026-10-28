package om.masroofi.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import om.masroofi.core.*

@Composable internal fun CategoryManager(state: LedgerState, dismiss: () -> Unit, add: (String) -> Unit, rename: (String,String) -> Unit, delete: (String) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }; var name by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<Category?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("فئاتك") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("أنشئ الفئات المناسبة لك. تعديل الاسم لا يغيّر مبالغ العمليات.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it.take(40); error = null }, label = { Text(if (editing == null) "اسم فئة جديدة" else "اسم الفئة") }, singleLine = true)
            Row {
                Button(onClick = {
                    try { val id = editing; if (id == null) add(name) else rename(id,name); editing = null; name = "" }
                    catch (failure: Exception) { error = failure.message?.take(150) ?: "تعذر حفظ الفئة" }
                }, enabled = name.isNotBlank()) { Text(if (editing == null) "إنشاء فئة" else "حفظ الاسم") }
                if (editing != null) TextButton(onClick = { editing = null; name = "" }) { Text("إلغاء التعديل") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                state.categories.forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editing = item.id; name = item.name; error = null }, modifier = Modifier.weight(1f)) { Text(item.name) }
                    IconButton(onClick = { removing = item }) { Icon(Icons.Default.Delete, "حذف فئة ${item.name}") }
                } }
                if (state.categories.isEmpty()) Text("لا توجد فئات بعد. أضف أول فئة من الأعلى.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("تم") } })
    removing?.let { item -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("حذف ${item.name}؟") },
        text = { Text("تبقى عملياتك ومبالغها محفوظة، وتصبح العمليات المرتبطة بدون فئة. تزال حدود الفئة وقواعد تصنيفها.") },
        confirmButton = { TextButton(onClick = {
            try { delete(item.id); if (editing == item.id) { editing = null; name = "" }; removing = null }
            catch (failure: Exception) { error = failure.message?.take(150); removing = null }
        }) { Text("حذف الفئة") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("إلغاء") } }) }
}
