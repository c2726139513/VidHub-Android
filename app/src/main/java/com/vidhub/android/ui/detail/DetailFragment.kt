package com.vidhub.android.ui.detail

import android.Manifest
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.viewModels
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DetailsOverviewRow
import androidx.leanback.widget.FullWidthDetailsOverviewRowPresenter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import com.vidhub.android.R
import com.vidhub.android.download.DownloadRepository
import com.vidhub.android.download.DownloadService
import com.vidhub.android.model.Episode
import com.vidhub.android.model.VideoItem
import com.vidhub.android.navigation.Router
import com.vidhub.android.ui.browse.TextCard
import com.vidhub.android.ui.browse.TextCardPresenter
import com.vidhub.android.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 视频详情页：简介 + 播放/收藏/下载操作 + 剧集列表。
 */
@AndroidEntryPoint
class DetailFragment : DetailsSupportFragment() {

    private val viewModel: DetailViewModel by viewModels()

    @Inject
    lateinit var downloadRepository: DownloadRepository

    // API33+ 通知权限请求：授权与否都不阻塞下载，仅决定通知可见性（T10 契约）
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> /* 结果不影响下载：入队与服务启动在请求后立即继续 */ }

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private var errorShown = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val selector = ClassPresenterSelector().apply {
            addClassPresenter(
                DetailsOverviewRow::class.java,
                FullWidthDetailsOverviewRowPresenter(DetailsDescriptionPresenter()),
            )
            addClassPresenter(ListRow::class.java, ListRowPresenter())
        }
        rowsAdapter = ArrayObjectAdapter(selector)
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Action -> when (item.id) {
                    ACTION_PLAY -> playFromHistory()
                    ACTION_FAVORITE -> viewModel.toggleFavorite()
                    ACTION_DOWNLOAD -> showDownloadDialog()
                }
                is TextCard -> (item.payload as? Episode)?.let { playAt(it.index) }
            }
        }

        val item = IntentCompat.getParcelableExtra(
            requireActivity().intent, Constants.EXTRA_VIDEO_ITEM, VideoItem::class.java,
        )
        if (item == null) {
            requireActivity().finish()
            return
        }
        viewModel.load(item)
        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    private fun render(state: DetailViewModel.DetailUiState) {
        val item = state.item ?: return

        rowsAdapter.clear()

        // 概览行：封面 + 标题/简介 + 操作按钮
        val overviewRow = DetailsOverviewRow(item)
        val actions = ArrayObjectAdapter()
        if (state.episodes.isNotEmpty() || state.loading) {
            actions.add(Action(ACTION_PLAY, state.playLabel))
        }
        actions.add(
            Action(
                ACTION_FAVORITE,
                getString(if (state.isFavorite) R.string.action_favorite_remove else R.string.action_favorite_add),
            )
        )
        if (state.episodes.isNotEmpty()) {
            actions.add(Action(ACTION_DOWNLOAD, getString(R.string.detail_action_download)))
        }
        overviewRow.actionsAdapter = actions
        rowsAdapter.add(overviewRow)
        loadCover(overviewRow, item.coverUrl)

        // 剧集行
        if (state.episodes.isNotEmpty()) {
            val episodesAdapter = ArrayObjectAdapter(TextCardPresenter())
            state.episodes.forEach { episode ->
                episodesAdapter.add(TextCard(id = "ep_${episode.index}", title = episode.name, payload = episode))
            }
            rowsAdapter.add(ListRow(HeaderItem(getString(R.string.row_episodes)), episodesAdapter))
        }

        // 错误提示（只弹一次）
        if (!state.loading && state.error != null && state.episodes.isEmpty() && !errorShown) {
            errorShown = true
            Toast.makeText(requireContext(), state.error, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadCover(row: DetailsOverviewRow, url: String?) {
        val context = requireContext()
        val placeholder = ContextCompat.getDrawable(context, R.drawable.poster_placeholder)
        if (url.isNullOrBlank()) {
            row.imageDrawable = placeholder
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val drawable = context.imageLoader.execute(
                ImageRequest.Builder(context).data(url).build()
            ).drawable
            row.imageDrawable = drawable ?: placeholder
        }
    }

    /** 主播放按钮：从历史进度续播 */
    private fun playFromHistory() {
        val state = viewModel.uiState.value
        val item = state.item ?: return
        if (state.episodes.isEmpty()) return
        val history = state.history
        val index = history?.episodeIndex?.coerceIn(0, state.episodes.size - 1) ?: 0
        val position = history?.positionMs ?: 0L
        Router.openPlayer(requireContext(), item, state.episodes.map { it.url }, index, position)
    }

    /** 点击某集：从头播放该集 */
    private fun playAt(index: Int) {
        val state = viewModel.uiState.value
        val item = state.item ?: return
        Router.openPlayer(requireContext(), item, state.episodes.map { it.url }, index, 0L)
    }

    /** 下载按钮：剧集多选弹窗（数据源=已加载 episodes，标题与选集行一致） */
    private fun showDownloadDialog() {
        val episodes = viewModel.uiState.value.episodes
        if (episodes.isEmpty()) return
        val titles = episodes.map { it.name }.toTypedArray()
        val checked = BooleanArray(episodes.size) // 预选为空
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.detail_download_dialog_title)
            .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.detail_action_download) { _, _ ->
                enqueueSelected(episodes, checked)
            }
            .setNegativeButton(R.string.detail_download_cancel, null)
            .show()
    }

    /** 确认下载：逐条入队（同 url 重复由仓库静默去重），启动前台服务并提示实际入队数 */
    private fun enqueueSelected(episodes: List<Episode>, checked: BooleanArray) {
        val selected = episodes.filterIndexed { index, _ -> checked[index] }
        if (selected.isEmpty()) return // 全不选：无副作用，不启动服务

        // 通知权限只门禁通知可见性：先发起请求，入队与服务启动不等结果
        if (!DownloadService.hasNotificationPermission(requireContext())) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // serverId 取详情条目所属服务器；旧条目缺省（空串）按 null 记账，不臆造
        val serverId = viewModel.uiState.value.item?.serverId?.takeIf { it.isNotBlank() }
        var queued = 0
        selected.forEach { episode ->
            if (downloadRepository.enqueue(serverId, episode.name, episode.url)) queued++
        }
        DownloadService.start(requireContext())
        Toast.makeText(
            requireContext(),
            getString(R.string.detail_download_toast, queued),
            Toast.LENGTH_SHORT,
        ).show()
    }

    companion object {
        private const val ACTION_PLAY = 1L
        private const val ACTION_FAVORITE = 2L
        private const val ACTION_DOWNLOAD = 3L
    }
}
