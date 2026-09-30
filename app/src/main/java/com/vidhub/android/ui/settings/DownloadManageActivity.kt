package com.vidhub.android.ui.settings

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.vidhub.android.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * 下载任务管理页：承载 [DownloadManageFragment]（设置页「下载管理」入口）。
 * 结构与 SettingsActivity 一致（FragmentActivity + 帧容器装载 Leanback Fragment）。
 */
@AndroidEntryPoint
class DownloadManageActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_manage)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.download_manage_container, DownloadManageFragment())
                .commit()
        }
    }
}
