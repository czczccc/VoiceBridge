package com.voicebridge.android.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicebridge.android.BuildConfig

@Composable
fun UpdatePanel(model: UpdateViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var installError by remember { mutableStateOf(false) }

    Text("测试版本 ${BuildConfig.VERSION_NAME}")
    when (val current = state) {
        UpdateState.Checking -> Text("正在检查更新…")
        UpdateState.Current -> {
            Text("暂未发现新版本")
            Button(onClick = model::check) { Text("检查更新") }
        }
        is UpdateState.Available -> {
            Text("发现新版本 ${current.manifest.versionName}")
            Button(onClick = { model.download(current.manifest) }) { Text("下载更新") }
        }
        UpdateState.Downloading -> Text("正在下载并校验更新…")
        is UpdateState.Ready -> {
            Text("更新已准备好；如需授权，请允许安装后返回，再点安装。")
            Button(onClick = {
                installError = false
                try {
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        context.startActivity(Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        ))
                    } else {
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.updates", current.file,
                        )
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }
                } catch (error: ActivityNotFoundException) {
                    installError = true
                } catch (error: SecurityException) {
                    installError = true
                }
            }) { Text("安装更新") }
        }
        UpdateState.Failed -> {
            Text("更新检查或下载失败，请检查网络后重试。现有版本仍可使用。")
            Button(onClick = model::check) { Text("重试") }
        }
    }
    if (installError) Text("无法打开安装界面，请检查系统安装权限。")
}
