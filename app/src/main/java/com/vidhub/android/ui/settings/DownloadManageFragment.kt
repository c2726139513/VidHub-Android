package com.vidhub.android.ui.settings

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vidhub.android.R
import com.vidhub.android.download.DownloadRepository
import com.vidhub.android.download.DownloadTask
import com.vidhub.android.download.DownloadTaskStatus
import com.vidhub.android.ui.browse.TextCard
import com.vidhub.android.ui.browse.TextCardPresenter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 下载任务管理（设置页「下载管理」入口）：
 * 行 = 任务名 + 状态标签 + 百分比（[DownloadRepository.tasks] StateFlow 实时刷新）；
 * 点行 → AlertDialog 按状态给动作（取消 / 继续 / 重试 / 删除），完成项附保存路径文本（不跳转打开）。
 */
@AndroidEntryPoint
class DownloadManageFragment : RowsSupportFragment() {

    @Inject
    lateinit var downloadRepository: DownloadRepository

    private lateinit var rowsAdapter: ArrayObjectAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 与 SettingsFragment 同款：外层 ListRowPresenter，行内 TextCardPresenter
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (val card = item as? TextCard) {
                null -> Unit
                else -> when (card.payload) {
                    is DownloadTask -> showTaskActions(card.payload)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadRepository.tasks.collect { render(it) }
            }
        }
    }

    private fun render(tasks: List<DownloadTask>) {
        rowsAdapter.clear()
        val adapter = ArrayObjectAdapter(TextCardPresenter())
        if (tasks.isEmpty()) {
            // 空态：不可聚焦纯文本卡（无可操作项）
            adapter.add(
                TextCard(
                    id = "download_empty",
                    title = getString(R.string.download_manage_empty),
                    flat = true,
                )
            )
        } else {
            tasks.forEach { task ->
                adapter.add(
                    TextCard(
                        id = task.taskId,
                        title = task.title,
                        subtitle = subtitleOf(task),
                        payload = task,
                    )
                )
            }
        }
        rowsAdapter.add(ListRow(HeaderItem(getString(R.string.download_manage_title)), adapter))
    }

    /** 副标题 = 状态标签 · 百分比（total>0 且未完成时）· 失败原因（failed 时） */
    private fun subtitleOf(task: DownloadTask): String {
        val parts = mutableListOf(statusLabel(task.status))
        if (task.progressTotal > 0 && task.status != DownloadTaskStatus.COMPLETED) {
            val percent = task.progressCompleted * 100 / task.progressTotal
            parts += getString(R.string.download_manage_progress, percent)
        }
        if (task.status == DownloadTaskStatus.FAILED) {
            task.error?.takeIf { it.isNotBlank() }?.let { parts += it }
        }
        return parts.joinToString(" · ")
    }

    private fun statusLabel(status: String): String = when (status) {
        DownloadTaskStatus.PENDING -> getString(R.string.download_status_pending)
        DownloadTaskStatus.RUNNING -> getString(R.string.download_status_running)
        DownloadTaskStatus.PAUSED -> getString(R.string.download_status_paused)
        DownloadTaskStatus.FAILED -> getString(R.string.download_status_failed)
        DownloadTaskStatus.COMPLETED -> getString(R.string.download_status_completed)
        DownloadTaskStatus.CANCELLED -> getString(R.string.download_status_cancelled)
        else -> status
    }

    /** 点行 → 按状态给动作；完成项标题附保存路径文本（仅展示，不做 ACTION_VIEW） */
    private fun showTaskActions(task: DownloadTask) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        when (task.status) {
            DownloadTaskStatus.PENDING, DownloadTaskStatus.RUNNING -> {
                labels += getString(R.string.download_manage_action_cancel)
                actions += { downloadRepository.cancel(task.taskId) }
            }
            DownloadTaskStatus.PAUSED -> {
                labels += getString(R.string.download_manage_action_resume)
                actions += { downloadRepository.resume(task.taskId) }
                labels += getString(R.string.download_manage_action_cancel)
                actions += { downloadRepository.cancel(task.taskId) }
            }
            DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED -> {
                labels += getString(R.string.download_manage_action_retry)
                actions += { downloadRepository.retry(task.taskId) }
                labels += getString(R.string.download_manage_action_delete)
                actions += { downloadRepository.delete(task.taskId) }
            }
            DownloadTaskStatus.COMPLETED -> {
                labels += getString(R.string.download_manage_action_delete)
                actions += { downloadRepository.delete(task.taskId) }
            }
            else -> return
        }

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(task.title)
        if (task.status == DownloadTaskStatus.COMPLETED) {
            builder.setMessage(
                getString(R.string.download_manage_output_path, readablePath(task.outputUri))
            )
        }
        builder
            .setItems(labels.toTypedArray()) { dialog, which ->
                actions[which]()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.download_manage_close)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /** outputUri → 可读路径（best-effort）：file:// 取 path；content:// 查 DATA 列，失败回退原串 */
    private fun readablePath(outputUri: String?): String {
        if (outputUri.isNullOrBlank()) return getString(R.string.download_manage_path_unknown)
        return try {
            val uri = Uri.parse(outputUri)
            when (uri.scheme) {
                "file" -> uri.path ?: outputUri
                "content" -> queryMediaPath(uri) ?: outputUri
                else -> outputUri
            }
        } catch (e: Exception) {
            outputUri
        }
    }

    private fun queryMediaPath(uri: Uri): String? = try {
        requireContext().contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    } catch (e: Exception) {
        null
    }
}
