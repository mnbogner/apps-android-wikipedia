package org.wikipedia.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import org.wikipedia.Constants
import org.wikipedia.R
import org.wikipedia.activity.SingleFragmentActivity
import org.wikipedia.databinding.ActivityMainBinding
import org.wikipedia.navtab.NavTab
import org.wikipedia.onboarding.InitialOnboardingActivity
import org.wikipedia.settings.Prefs
import org.wikipedia.util.DimenUtil
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.util.ResourceUtil
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.text.TextUtils
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.greatfire.envoy.CronetNetworking
import org.greatfire.envoy.NetworkIntentService
import org.greatfire.envoy.EXTENDED_DATA_VALID_URLS
import org.greatfire.envoy.BROADCAST_VALID_URL_FOUND

class MainActivity : SingleFragmentActivity<MainFragment>(), MainFragment.Callback {
    private lateinit var binding: ActivityMainBinding

    private var controlNavTabInFragment = false

    private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && context != null) {
                val validUrls = intent.getStringArrayListExtra(EXTENDED_DATA_VALID_URLS)
                Log.i("BroadcastReceiver", "Received valid urls: " + validUrls?.let {
                    TextUtils.join(", ", it)
                })
                if (validUrls != null && !validUrls.isEmpty()) {
                    val envoyUrl = validUrls[0]
                    // select the fastest one (urls are ordered by latency), reInitializeIfNeeded set to false
                    CronetNetworking.initializeCronetEngine(context, envoyUrl)
                }
            }
        }
    }

    override fun inflateAndSetContentView() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // register to receive test results
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, IntentFilter(BROADCAST_VALID_URL_FOUND))

        // TEMP - shadowsocks is not supported in the current build
        /*
        val shadowsocksIntent = Intent(this, ShadowsocksService::class.java)
        // put shadowsocks proxy url here, should look like ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNz@127.0.0.1:1234
        shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL", "ss://foo");
        ContextCompat.startForegroundService(applicationContext, shadowsocksIntent)
        */

        // TEMP - submitting local shadowsocks url with no active service causes an exception
        // val ssUrl = "socks5://127.0.0.1:1080";  // local shadowsocks url, keep this if no port conflicts
        val envoyUrl = "https://foo"  // put envoy url here
        // val possibleUrls = listOf<String>(ssUrl, envoyUrl)  // build collection of urls
        val possibleUrls = listOf<String>(envoyUrl)  // build collection of urls
        NetworkIntentService.submit(this, possibleUrls)  // submit urls to envoy for evaluation

        setImageZoomHelper()
        if (Prefs.isInitialOnboardingEnabled && savedInstanceState == null) {
            // Updating preference so the search multilingual tooltip
            // is not shown again for first time users
            Prefs.isMultilingualSearchTutorialEnabled = false

            // Use startActivityForResult to avoid preload the Feed contents before finishing the initial onboarding.
            // The ACTIVITY_REQUEST_INITIAL_ONBOARDING has not been used in any onActivityResult
            startActivityForResult(InitialOnboardingActivity.newIntent(this), Constants.ACTIVITY_REQUEST_INITIAL_ONBOARDING)
        }
        setNavigationBarColor(ResourceUtil.getThemedColor(this, R.attr.nav_tab_background_color))
        setSupportActionBar(binding.mainToolbar)
        supportActionBar?.title = ""
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        binding.mainToolbar.navigationIcon = null
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
    }

    override fun createFragment(): MainFragment {
        return MainFragment.newInstance()
    }

    override fun onTabChanged(tab: NavTab) {
        if (tab == NavTab.EXPLORE) {
            binding.mainToolbarWordmark.visibility = View.VISIBLE
            binding.mainToolbar.title = ""
            controlNavTabInFragment = false
        } else {
            if (tab == NavTab.SEARCH && Prefs.showSearchTabTooltip) {
                FeedbackUtil.showTooltip(this, fragment.binding.mainNavTabLayout.findViewById(NavTab.SEARCH.id()), getString(R.string.search_tab_tooltip), aboveOrBelow = true, autoDismiss = false)
                Prefs.showSearchTabTooltip = false
            }
            binding.mainToolbarWordmark.visibility = View.GONE
            binding.mainToolbar.setTitle(tab.text())
            controlNavTabInFragment = true
        }
        fragment.requestUpdateToolbarElevation()
    }

    override fun onSupportActionModeStarted(mode: ActionMode) {
        super.onSupportActionModeStarted(mode)
        if (!controlNavTabInFragment) {
            fragment.setBottomNavVisible(false)
        }
    }

    override fun onSupportActionModeFinished(mode: ActionMode) {
        super.onSupportActionModeFinished(mode)
        fragment.setBottomNavVisible(true)
    }

    override fun updateToolbarElevation(elevate: Boolean) {
        if (elevate) {
            setToolbarElevationDefault()
        } else {
            clearToolbarElevation()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fragment.handleIntent(intent)
    }

    override fun onGoOffline() {
        fragment.onGoOffline()
    }

    override fun onGoOnline() {
        fragment.onGoOnline()
    }

    override fun onBackPressed() {
        if (fragment.onBackPressed()) {
            return
        }
        super.onBackPressed()
    }

    fun isCurrentFragmentSelected(f: Fragment): Boolean {
        return fragment.currentFragment === f
    }

    fun getToolbar(): Toolbar {
        return binding.mainToolbar
    }

    override fun onUnreadNotification() {
        fragment.updateNotificationDot(true)
    }

    private fun setToolbarElevationDefault() {
        binding.mainToolbar.elevation = DimenUtil.dpToPx(DimenUtil.getDimension(R.dimen.toolbar_default_elevation))
    }

    private fun clearToolbarElevation() {
        binding.mainToolbar.elevation = 0f
    }

    companion object {
        @JvmStatic
        fun newIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java)
        }
    }
}
