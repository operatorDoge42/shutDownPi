package dev.shutdownpi.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val Ink = Color(0xFF0B0F14)
private val Panel = Color(0xFF151B23)
private val Panel2 = Color(0xFF1C2530)
private val Text = Color(0xFFF2F5F8)
private val Muted = Color(0xFF9AA8B7)
private val Raspberry = Color(0xFFFF4568)
private val Green = Color(0xFF61D69A)

data class PiNode(val id: String = UUID.randomUUID().toString(), val name: String, val host: String,
                  val port: Int = 22, val username: String, val password: String)

class MainActivity : ComponentActivity() {
    private val host by lazy { Handler(Looper.getMainLooper()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(11, 15, 20)
        window.navigationBarColor = android.graphics.Color.rgb(11, 15, 20)
        window.decorView.systemUiVisibility = 0
        setContent { PiDashboard(this) }
    }

    fun verifyUnknownHost(fingerprint: String): Boolean {
        val latch = CountDownLatch(1)
        val answer = AtomicBoolean(false)
        host.post {
            // The dialog callback installed by the composable owns completion.
            pendingFingerprint = fingerprint
            pendingLatch = latch
            pendingAnswer = answer
            requestHostKeyDialog?.invoke()
        }
        return latch.await(2, TimeUnit.MINUTES) && answer.get()
    }

    @Volatile private var pendingFingerprint = ""
    @Volatile private var pendingLatch: CountDownLatch? = null
    @Volatile private var pendingAnswer: AtomicBoolean? = null
    @Volatile private var requestHostKeyDialog: (() -> Unit)? = null

    @Composable private fun PiDashboard(activity: MainActivity) {
        val prefs = remember { securePrefs() }
        var nodes by remember { mutableStateOf(loadNodes(prefs)) }
        var expanded by remember { mutableStateOf<String?>(nodes.firstOrNull()?.id) }
        var editing by remember { mutableStateOf<PiNode?>(null) }
        var showEditor by remember { mutableStateOf(false) }
        var showDelete by remember { mutableStateOf<PiNode?>(null) }
        var confirmAction by remember { mutableStateOf<Pair<PiNode, String>?>(null) }
        var status by remember { mutableStateOf("Your nodes, at a glance") }
        var working by remember { mutableStateOf<String?>(null) }
        var countdownCancelled by remember { mutableStateOf(false) }
        var countdown by remember { mutableIntStateOf(120) }
        var online by remember { mutableStateOf(setOf<String>()) }
        val scope = rememberCoroutineScope()
        var keyFingerprint by remember { mutableStateOf<String?>(null) }
        var keyDecision by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
        SideEffect {
            requestHostKeyDialog = { keyFingerprint = pendingFingerprint }
        }

        MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Raspberry,
            onBackground = Text, onSurface = Text, secondary = Green)) {
            Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).background(Panel2, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                            Text("π", color = Raspberry, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text("shutDownPi", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Text)
                            Text("REMOTE NODE CONTROL", fontSize = 10.sp, letterSpacing = 1.6.sp, color = Muted)
                        }
                        TextButton(onClick = { editing = null; showEditor = true }) { Text("＋  Add", color = Raspberry, fontWeight = FontWeight.SemiBold) }
                    }
                    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(17.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("YOUR NETWORK", fontSize = 10.sp, letterSpacing = 1.4.sp, color = Muted)
                            Spacer(Modifier.height(7.dp))
                            Text("${nodes.size} ${if (nodes.size == 1) "node" else "nodes"} saved", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp)); Text(status, fontSize = 12.sp, color = Muted)
                        }
                        if (working != null && status.startsWith("Shutdown in")) {
                            TextButton(onClick = { countdownCancelled = true }) { Text("Cancel", color = Raspberry) }
                        }
                        Box(Modifier.size(11.dp).background(if (working == null) Green else Raspberry, CircleShape))
                    }
                    Spacer(Modifier.height(17.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("NODES", fontSize = 11.sp, letterSpacing = 1.7.sp, color = Muted, modifier = Modifier.weight(1f))
                        Text("SSH · PORT 22", fontSize = 10.sp, letterSpacing = 1.sp, color = Muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (nodes.isEmpty()) {
                        Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("◉", color = Raspberry, fontSize = 44.sp)
                            Spacer(Modifier.height(10.dp)); Text("No nodes yet", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Text("Add a Raspberry Pi to get started.", color = Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(18.dp)); Button(onClick = { editing = null; showEditor = true }, colors = ButtonDefaults.buttonColors(containerColor = Raspberry)) { Text("Add your first node") }
                        }
                    } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(nodes, key = { it.id }) { node ->
                            NodeCard(node = node, isExpanded = expanded == node.id, isOnline = node.id in online,
                                busy = working == node.id, cancelCountdown = { countdownCancelled = true },
                                canCancel = working == node.id && status.startsWith("Shutdown in"), onExpand = { expanded = if (expanded == node.id) null else node.id },
                                onEdit = { editing = node; showEditor = true }, onDelete = { showDelete = node },
                                onShutdown = { confirmAction = node to "shutdown" }, onReboot = { confirmAction = node to "reboot" })
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp)) {
                        HorizontalDivider(color = Panel2)
                        Spacer(Modifier.height(10.dp))
                        Text("SSH host keys are verified and remembered on first approval.", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }

        if (showEditor) NodeEditor(editing, onDismiss = { showEditor = false }, onSave = { node ->
            val updated = if (editing == null) nodes + node else nodes.map { if (it.id == editing!!.id) node.copy(id = editing!!.id) else it }
            nodes = updated; saveNodes(prefs, updated); expanded = node.id; showEditor = false
        })
        showDelete?.let { node -> AlertDialog(onDismissRequest = { showDelete = null }, title = { Text("Remove ${node.name}?") },
            text = { Text("This deletes the saved login and remembered SSH host key from this device.") },
            confirmButton = { TextButton(onClick = { nodes = nodes.filterNot { it.id == node.id }; saveNodes(prefs, nodes); File(activity.filesDir, "ssh_known_hosts_${node.id}").delete(); showDelete = null }) { Text("Remove", color = Raspberry) } },
            dismissButton = { TextButton(onClick = { showDelete = null }) { Text("Cancel") } }, containerColor = Panel)
        }
        confirmAction?.let { (node, action) ->
            val reboot = action == "reboot"
            AlertDialog(onDismissRequest = { confirmAction = null }, title = { Text(if (reboot) "Reboot ${node.name}?" else "Shut down ${node.name}?") },
                text = { Column {
                    Text(if (reboot) "This will restart the Pi. SSH will disconnect while it reboots; the app will watch for it to reconnect." else "The app waits $countdown seconds before sending the shutdown command.")
                    Spacer(Modifier.height(12.dp))
                    if (!reboot) OutlinedTextField(value = countdown.toString(), onValueChange = { countdown = it.toIntOrNull()?.coerceIn(10, 300) ?: countdown },
                        label = { Text("Countdown (seconds)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, colors = PiFieldColors())
                    Spacer(Modifier.height(8.dp)); Text("For an idle Pi, this wait is likely safe, but the app can’t guarantee a safe shutdown. Check that the Pi isn’t doing important work.", color = Muted, fontSize = 12.sp)
                } },
                confirmButton = { TextButton(onClick = {
                    confirmAction = null; working = node.id; countdownCancelled = false
                    scope.launch {
                        if (!reboot) {
                            for (left in countdown downTo 1) {
                                if (countdownCancelled) { status = "Shutdown cancelled"; working = null; return@launch }
                                status = "Shutdown in ${left}s"; delay(1000)
                            }
                        }
                        status = if (reboot) "Sending reboot…" else "Sending shutdown…"
                        val result = withContext(Dispatchers.IO) { sendPowerAction(node, action) { fp -> activity.verifyUnknownHost(fp) } }
                        status = result
                        if (reboot && (result.startsWith("Reboot command sent") || result.startsWith("Reboot command may have been accepted"))) {
                            status = "Waiting for ${node.name} to reconnect…"
                            val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)
                            var reconnected = false
                            while (System.currentTimeMillis() < deadline && !reconnected) {
                                delay(5000)
                                reconnected = withContext(Dispatchers.IO) { canConnect(node, activity.filesDir, activity) }
                            }
                            status = if (reconnected) "Node back online" else "Still waiting for SSH; check the Pi or network"
                            if (reconnected) online = online + node.id
                        }
                        working = null
                    }
                }) { Text(if (reboot) "Reboot" else "Start countdown", color = Raspberry) } },
                dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Cancel") } }, containerColor = Panel)
        }
        keyFingerprint?.let { fingerprint ->
            AlertDialog(onDismissRequest = { finishHostKey(false); keyFingerprint = null },
                title = { Text("Verify SSH host key") },
                text = { Column {
                    Text("This device has not seen this node’s host key before. Compare this fingerprint with one obtained directly on the Pi before trusting it.")
                    Spacer(Modifier.height(12.dp)); Text(fingerprint, color = Green, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp)); Text("Trusting a changed key is never offered. A mismatch blocks the connection.", color = Muted, fontSize = 12.sp)
                } },
                confirmButton = { TextButton(enabled = fingerprint.startsWith("SHA256:"), onClick = { finishHostKey(true); keyFingerprint = null }) { Text("Trust this key", color = Green) } },
                dismissButton = { TextButton(onClick = { finishHostKey(false); keyFingerprint = null }) { Text("Cancel") } }, containerColor = Panel)
        }
    }

    private fun finishHostKey(trust: Boolean) {
        pendingAnswer?.set(trust); pendingLatch?.countDown(); pendingLatch = null; pendingAnswer = null
    }
}

@Composable private fun NodeCard(node: PiNode, isExpanded: Boolean, isOnline: Boolean, busy: Boolean,
                                 cancelCountdown: () -> Unit, canCancel: Boolean,
                                 onExpand: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
                                 onShutdown: () -> Unit, onReboot: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(Panel2, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Text("π", color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(node.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(3.dp)); Text("${node.username}@${node.host}:${node.port}", color = Muted, fontSize = 12.sp)
            }
            Box(Modifier.size(8.dp).background(if (isOnline) Green else Muted.copy(alpha = .5f), CircleShape))
            Spacer(Modifier.width(9.dp)); Text(if (busy) "Working" else if (isOnline) "Online" else "Ready", fontSize = 11.sp, color = if (isOnline) Green else Muted)
            Spacer(Modifier.width(5.dp)); Text(if (isExpanded) "⌃" else "⌄", color = Muted, fontSize = 20.sp)
        }
        if (isExpanded) {
            HorizontalDivider(color = Panel2, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canCancel) TextButton(onClick = cancelCountdown) { Text("Cancel", color = Raspberry) }
                TextButton(onClick = onEdit, enabled = !busy) { Text("Edit", color = Text) }
                TextButton(onClick = onDelete, enabled = !busy) { Text("Delete", color = Muted) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onReboot, enabled = !busy, shape = RoundedCornerShape(12.dp)) { Text("↻  Reboot", color = Text, fontSize = 12.sp) }
                Spacer(Modifier.width(7.dp))
                Button(onClick = onShutdown, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Raspberry), shape = RoundedCornerShape(12.dp)) { Text("⏻  Power off", fontSize = 12.sp) }
            }
        }
    }
}

@Composable private fun NodeEditor(existing: PiNode?, onDismiss: () -> Unit, onSave: (PiNode) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf(existing?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Add a node" else "Edit node") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Node name") }, singleLine = true, colors = PiFieldColors(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(host, { host = it }, label = { Text("Host or IP address") }, singleLine = true, colors = PiFieldColors(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("SSH port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, colors = PiFieldColors(), modifier = Modifier.weight(1f))
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, colors = PiFieldColors(), modifier = Modifier.weight(1.4f))
            }
            OutlinedTextField(password, { password = it }, label = { Text("SSH password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, colors = PiFieldColors(), modifier = Modifier.fillMaxWidth())
            Text("Credentials are encrypted with a key held in Android Keystore.", color = Muted, fontSize = 11.sp)
        } }, confirmButton = { TextButton(enabled = name.isNotBlank() && host.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && (port.toIntOrNull() ?: 0) in 1..65535,
            onClick = { onSave(PiNode(existing?.id ?: UUID.randomUUID().toString(), name.trim(), host.trim(), port.toInt(), username.trim(), password)) }) { Text("Save", color = Raspberry) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }, containerColor = Panel)
}

@Composable private fun PiFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Raspberry,
    focusedBorderColor = Raspberry,
    unfocusedBorderColor = Color(0xFF738397),
    focusedLabelColor = Raspberry,
    unfocusedLabelColor = Color(0xFFC0CAD5),
    focusedContainerColor = Color(0xFF222D38),
    unfocusedContainerColor = Color(0xFF222D38),
)

private fun securePrefs(): android.content.SharedPreferences = EncryptedSharedPreferences.create(App.instance, "pi_nodes_secure", MasterKey.Builder(App.instance).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

private fun loadNodes(prefs: android.content.SharedPreferences): List<PiNode> = try {
    val arr = JSONArray(prefs.getString("nodes", "[]"))
    (0 until arr.length()).map { i -> arr.getJSONObject(i).let { PiNode(it.getString("id"), it.getString("name"), it.getString("host"), it.getInt("port"), it.getString("username"), it.getString("password")) } }
} catch (_: Exception) { emptyList() }

private fun saveNodes(prefs: android.content.SharedPreferences, nodes: List<PiNode>) {
    val arr = JSONArray(); nodes.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port).put("username", it.username).put("password", it.password)) }
    prefs.edit().putString("nodes", arr.toString()).apply()
}

private fun sendPowerAction(node: PiNode, action: String, approve: (String) -> Boolean): String {
    val session = createSession(node, App.instance.filesDir, approve)
    try {
        session.connect(12000)
        val command = if (action == "reboot") "sudo -n reboot" else "sudo -n shutdown -h now"
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command); channel.setInputStream(null); channel.setErrStream(null)
        val output = channel.inputStream
        val errors = channel.errStream
        channel.connect(8000)
        val until = System.currentTimeMillis() + 5000
        val bytes = ByteArray(1024); val text = StringBuilder()
        while (System.currentTimeMillis() < until && !channel.isClosed) {
            while (output.available() > 0) { val n = output.read(bytes); if (n > 0) text.append(String(bytes, 0, n)) }
            while (errors.available() > 0) { val n = errors.read(bytes); if (n > 0) text.append(String(bytes, 0, n)) }
            Thread.sleep(100)
        }
        val code = channel.exitStatus
        channel.disconnect()
        val err = text.toString().trim()
        if (code == 0) return if (action == "reboot") "Reboot command sent; waiting for SSH to return" else "Shutdown command sent. The node may go offline shortly."
        if (err.contains("password is required", true) || err.contains("a terminal is required", true)) return "SSH connected, but sudo needs passwordless permission for shutdown/reboot."
        if (code < 0) return if (action == "reboot") "Reboot command may have been accepted; SSH disconnected before confirmation" else "Shutdown may have started, but SSH disconnected before confirmation. Check the Pi."
        return if (code >= 0) "Command failed (exit $code). Check sudo permissions on the Pi." else "Connection ended before the command was confirmed; the action may have started. Check the Pi."
    } catch (e: Exception) {
        val m = e.message.orEmpty()
        if (m.contains("unknown host key", true)) return "SSH host key was not approved. Verify it and try again."
        if (m.contains("auth fail", true)) return "SSH login failed. Check the username and password."
        if (m.contains("reject HostKey", true) || m.contains("HostKey has been changed", true)) return "SSH host key changed. Connection blocked; verify the Pi’s identity."
        return "SSH failed: ${m.take(120)}"
    } finally { session.disconnect() }
}

private fun canConnect(node: PiNode, files: File, activity: MainActivity): Boolean = try {
    val session = createSession(node, files) { fp -> activity.verifyUnknownHost(fp) }
    session.connect(6000); session.disconnect(); true
} catch (_: Exception) { false }

private fun createSession(node: PiNode, files: File, approve: (String) -> Boolean): Session {
    val jsch = JSch()
    val known = File(files, "ssh_known_hosts_${node.id}"); if (!known.exists()) known.createNewFile(); jsch.setKnownHosts(known.absolutePath)
    val session = jsch.getSession(node.username, node.host, node.port)
    session.setPassword(node.password)
    session.setConfig("StrictHostKeyChecking", "ask")
    session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
    session.userInfo = object : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = node.password
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean {
            if (message.orEmpty().contains("changed", true) || message.orEmpty().contains("REMOTE HOST IDENTIFICATION", true)) return false
            val key = try { session.hostKey } catch (_: Exception) { null }
            val fp = if (key == null) "Host key fingerprint unavailable" else "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key.key)))
            if (!fp.startsWith("SHA256:")) return false
            return approve(fp)
        }
        override fun showMessage(message: String?) { }
    }
    return session
}

class App : android.app.Application() { override fun onCreate() { super.onCreate(); instance = this }; companion object { lateinit var instance: App } }
