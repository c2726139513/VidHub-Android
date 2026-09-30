package com.vidhub.android.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
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
import com.vidhub.android.model.ServerConfig
import com.vidhub.android.navigation.Router
import com.vidhub.android.ui.browse.TextCard
import com.vidhub.android.ui.browse.TextCardPresenter
import com.vidhub.android.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 设置页：服务器列表管理（添加 / 编辑 / 删除 / 切换 / 刷新数据源 / 自定义数据源）。
 */
@AndroidEntryPoint
class SettingsFragment : RowsSupportFragment() {

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var rowsAdapter: ArrayObjectAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 外层适配器装的是 ListRow 对象，必须用 ListRowPresenter；
        // 行内条目（TextCard）由各行自己的 ArrayObjectAdapter(TextCardPresenter()) 呈现。
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (val card = item as? TextCard) {
                null -> Unit
                else -> when (card.payload) {
                    is ServerConfig -> showServerActions(card.payload)
                    ACTION_ADD -> Router.openServerEdit(requireContext(), null)
                    ACTION_DOWNLOAD_MANAGE -> openDownloadManage()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is SettingsViewModel.Event.Message ->
                                Toast.makeText(requireContext(), event.text, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun render(state: SettingsViewModel.SettingsState) {
        rowsAdapter.clear()

        state.servers.forEach { server ->
            val isActive = server.id == state.activeServerId
            val adapter = ArrayObjectAdapter(TextCardPresenter())
            adapter.add(
                TextCard(
                    id = "server_${server.id}",
                    title = (if (isActive) "✓ " else "") + server.name,
                    subtitle = server.url,
                    payload = server,
                )
            )
            val header = if (isActive) {
                "${server.name}（${getString(R.string.settings_active)}）"
            } else {
                server.name
            }
            rowsAdapter.add(ListRow(HeaderItem(header), adapter))
        }

        val addAdapter = ArrayObjectAdapter(TextCardPresenter())
        addAdapter.add(
            TextCard(
                id = "add",
                title = "+ ${getString(R.string.settings_add_server)}",
                subtitle = getString(R.string.server_field_url_hint),
                payload = ACTION_ADD,
            )
        )
        addAdapter.add(
            TextCard(
                id = "download_manage",
                title = getString(R.string.settings_download_manage),
                subtitle = getString(R.string.download_manage_entry_subtitle),
                payload = ACTION_DOWNLOAD_MANAGE,
            )
        )
        rowsAdapter.add(ListRow(HeaderItem(getString(R.string.settings_title)), addAdapter))
    }

    /** 服务器操作菜单 */
    private fun showServerActions(server: ServerConfig) {
        val labels = arrayOf(
            getString(R.string.settings_action_set_active),
            getString(R.string.settings_action_edit),
            getString(R.string.settings_action_refresh_sources),
            getString(R.string.settings_action_custom_sources),
            getString(R.string.settings_action_delete),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(server.name)
            .setItems(labels) { dialog, which ->
                when (which) {
                    0 -> viewModel.setActive(server)
                    1 -> Router.openServerEdit(requireContext(), server.id)
                    2 -> viewModel.refreshSources(server)
                    3 -> openCustomSources(server)
                    4 -> confirmDelete(server)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun confirmDelete(server: ServerConfig) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_action_delete))
            .setMessage("确定删除「${server.name}」吗？该操作不可撤销。")
            .setPositiveButton(getString(R.string.settings_action_delete)) { dialog, _ ->
                viewModel.delete(server)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.server_cancel)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun openCustomSources(server: ServerConfig) {
        startActivity(
            Intent(requireContext(), CustomSourcesActivity::class.java)
                .putExtra(Constants.EXTRA_SERVER_ID, server.id)
        )
    }

    /** 打开下载任务管理页（与 openCustomSources 同款显式 Intent 导航） */
    private fun openDownloadManage() {
        startActivity(Intent(requireContext(), DownloadManageActivity::class.java))
    }

    companion object {
        private val ACTION_ADD = Any()
        private val ACTION_DOWNLOAD_MANAGE = Any()
    }
}
