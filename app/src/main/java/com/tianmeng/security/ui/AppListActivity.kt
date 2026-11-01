package com.tianmeng.security.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.tianmeng.security.R
import com.tianmeng.security.adapter.AppListAdapter
import com.tianmeng.security.data.AppInfo
import com.tianmeng.security.data.FreezeMode
import com.tianmeng.security.data.Prefs
import com.tianmeng.security.manager.FreezeManager
import com.tianmeng.security.manager.ShellManager
import com.tianmeng.security.manager.VirusManager
import com.tianmeng.security.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用列表界面：病毒扫描结果 / 应用冻结 / 已冻结列表
 */
class AppListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_VIRUS = "virus"
        const val MODE_FREEZE = "freeze"
        const val MODE_FROZEN = "frozen"
        const val MODE_WHITELIST = "whitelist"
    }

    private lateinit var mode: String
    private lateinit var adapter: AppListAdapter
    private lateinit var emptyView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FREEZE

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = when (mode) {
            MODE_VIRUS -> getString(R.string.virus_scan)
            MODE_FREEZE -> getString(R.string.app_freeze)
            MODE_FROZEN -> getString(R.string.frozen_list)
            MODE_WHITELIST -> getString(R.string.whitelist)
            else -> getString(R.string.app_name)
        }

        val rvApps = findViewById<RecyclerView>(R.id.rvApps)
        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
        emptyView = findViewById(R.id.emptyView)

        val adapterMode = when (mode) {
            MODE_VIRUS -> AppListAdapter.Mode.VIRUS
            MODE_FREEZE -> AppListAdapter.Mode.FREEZE
            MODE_FROZEN -> AppListAdapter.Mode.FROZEN
            MODE_WHITELIST -> AppListAdapter.Mode.WHITELIST
            else -> AppListAdapter.Mode.FREEZE
        }

        adapter = AppListAdapter(adapterMode) { app ->
            handleAction(app)
        }

        rvApps.layoutManager = GridLayoutManager(this, tabletSpan())
        rvApps.adapter = adapter

        swipeRefresh.setOnRefreshListener { loadData(swipeRefresh) }
        loadData(swipeRefresh)
    }

    private fun tabletSpan(): Int {
        return when {
            resources.configuration.screenWidthDp >= 900 -> 3
            resources.configuration.screenWidthDp >= 600 -> 2
            else -> 1
        }
    }

    private fun loadData(swipeRefresh: SwipeRefreshLayout) {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                when (mode) {
                    MODE_VIRUS -> VirusManager.scanForViruses(this@AppListActivity)
                    MODE_FREEZE -> AppUtils.getInstalledApps(this@AppListActivity)
                    MODE_FROZEN -> AppUtils.getFrozenApps(this@AppListActivity)
                    MODE_WHITELIST -> AppUtils.getInstalledApps(this@AppListActivity)
                    else -> emptyList()
                }
            }
            adapter.submitList(apps)
            swipeRefresh.isRefreshing = false

            val emptyMsg = when (mode) {
                MODE_VIRUS -> getString(R.string.no_virus)
                MODE_FROZEN -> getString(R.string.no_frozen_apps)
                MODE_WHITELIST -> getString(R.string.whitelist_empty)
                else -> "暂无应用"
            }
            val tvEmpty = findViewById<TextView>(R.id.tvEmpty)
            tvEmpty.text = emptyMsg
            emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun handleAction(app: AppInfo) {
        when (mode) {
            MODE_VIRUS -> confirmDeleteVirus(app)
            MODE_FREEZE -> {
                if (app.isFrozen) {
                    unfreezeApp(app)
                } else {
                    // 只保留强制冻结，不再提供停用冻结
                    freezeApp(app, FreezeMode.NORMAL)
                }
            }
            MODE_FROZEN -> unfreezeApp(app)
            MODE_WHITELIST -> toggleWhitelist(app)
        }
    }

    private fun toggleWhitelist(app: AppInfo) {
        val whitelisted = Prefs.getWhitelist().contains(app.packageName)
        if (whitelisted) {
            Prefs.removeFromWhitelist(app.packageName)
        } else {
            Prefs.addToWhitelist(app.packageName)
        }
        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
        loadData(swipeRefresh)
    }

    private fun confirmDeleteVirus(app: AppInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(getString(R.string.confirm_delete_msg, app.appName))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        VirusManager.removeVirus(this@AppListActivity, app.packageName)
                    }
                    // 刷新列表
                    val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
                    loadData(swipeRefresh)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun freezeApp(app: AppInfo, mode: FreezeMode) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                FreezeManager.freezeApp(this@AppListActivity, app.packageName, mode)
            }
            val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
            loadData(swipeRefresh)
        }
    }

    private fun unfreezeApp(app: AppInfo) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                FreezeManager.unfreezeApp(this@AppListActivity, app.packageName)
            }
            val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
            loadData(swipeRefresh)
        }
    }
}
