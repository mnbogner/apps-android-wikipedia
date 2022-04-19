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
import androidx.core.content.ContextCompat
import org.greatfire.envoy.*

class MainActivity : SingleFragmentActivity<MainFragment>(), MainFragment.Callback {

    private val TAG = "MainActivity"

    private lateinit var binding: ActivityMainBinding

    private var controlNavTabInFragment = false

    // initialize one or more string values containing the urls of available http/https proxies (include trailing slash)
    private val httpUrl = "http://wiki.epochbelt.com/wikipedia/"
    private val httpsUrl = "https://wiki.epochbelt.com/wikipedia/"
    // urls for proxy services, change if there are port conflicts (do not include trailing slash)
    private val ssUrl = "socks5://127.0.0.1:1080"
    private val proxyUrl = "socks5://127.0.0.1:1081"
    // add all string values to this list value
    private val possibleUrls = listOf<String>(ssUrl, proxyUrl, httpUrl, httpsUrl)

    // TODO: replace with alternate method of waiting until services are started?
    private var waitingForShadowsocks = false
    private var waitingForProxy = false
    private var waitingForUrl = true

    // this receiver listens for the results from the NetworkIntentService started below
    // it should receive a result if no valid urls are found but not if the service throws an exception
    private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && context != null) {
                var proxyResult = intent.getIntExtra(ProxyService.PROXY_SERVICE_RESULT, 0)
                if (proxyResult != 0) {
                    Log.d(TAG, "GOT PROXY SERVICE RESULT: " + proxyResult)
                    waitingForProxy = false
                    if (proxyResult > 0) {
                        Log.d(TAG, "PROXY STARTED OK")
                    } else if (proxyResult == ProxyService.PROXY_RUNNING) {
                        Log.d(TAG, "PROXY ALREADY RUNNING")
                    } else if (proxyResult == ProxyService.PROXY_ERROR_PARAMETERS) {
                        Log.d(TAG, "PROXY ERROR: MISSING PARAMETERS")
                    } else if (proxyResult == ProxyService.PROXY_ERROR_RUN) {
                        Log.d(TAG, "PROXY ERROR: FAILED TO RUN")
                    } else if (proxyResult == ProxyService.PROXY_ERROR_EXE) {
                        Log.d(TAG, "PROXY ERROR: MISSING EXE")
                    } else {
                        Log.d(TAG, "UNKNOWN PROXY ERROR")
                    }

                    if (waitingForShadowsocks) {
                        Log.d(TAG, "STILL WAITING FOR SHADOWSOCKS SERVICE TO START")
                    } else {
                        Log.d(TAG, "SERVICE(S) STARTED, CHECK URLS")
                        NetworkIntentService.submit(
                            context,
                            possibleUrls
                        )
                    }
                }

                var shadowsocksResult = intent.getIntExtra(ShadowsocksService.SHADOWSOCKS_SERVICE_RESULT, 0)
                if (shadowsocksResult != 0) {
                    Log.d(TAG, "GOT SHADOWSOCKS SERVICE RESULT: " + shadowsocksResult)
                    waitingForShadowsocks = false
                    if (shadowsocksResult > 0) {
                        Log.d(TAG, "SHADOWSOCKS STARTED OK")
                    } else if (shadowsocksResult == ShadowsocksService.SHADOWSOCKS_ERROR) {
                        Log.d(TAG, "SHADOWSOCKS ERROR: ???")
                    } else {
                        Log.d(TAG, "UNKNOWN SHADOWSOCKS ERROR")
                    }

                    if (waitingForProxy) {
                        Log.d(TAG, "STILL WAITING FOR PROXY SERVICE TO START")
                    } else {
                        Log.d(TAG, "SERVICE(S) STARTED, CHECK URLS")
                        NetworkIntentService.submit(
                            context,
                            possibleUrls
                        )
                    }
                }

                val validUrls = intent.getStringArrayListExtra(EXTENDED_DATA_VALID_URLS)
                Log.i("BroadcastReceiver", "Received valid urls: " + validUrls?.let {
                    TextUtils.join(", ", it)
                })
                // if there are no valid urls, initializeCronetEngine will not be called
                // the app will start normally and connect to the internet directly if possible
                if (validUrls != null && !validUrls.isEmpty()) {
                    if (waitingForUrl) {
                        waitingForUrl = false
                        val envoyUrl = validUrls[0]
                        Log.d(TAG, "GOT VALID URL: " + envoyUrl)
                        // select the fastest one (urls are ordered by latency), reInitializeIfNeeded set to false
                        CronetNetworking.initializeCronetEngine(context, envoyUrl)
                    } else {
                        Log.d(TAG, "ALREADY GOT VALID URL")
                    }
                }
            } else {
                Log.d(TAG, "INTENT OR CONTEXT MISSING")
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
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, IntentFilter().apply {
            addAction(BROADCAST_VALID_URL_FOUND)
            addAction(BROADCAST_NO_URL_FOUND)
            addAction(ProxyService.PROXY_SERVICE_BROADCAST)
            addAction(ShadowsocksService.SHADOWSOCKS_SERVICE_BROADCAST)
        })

        if (possibleUrls.contains(ssUrl)) {
            Log.d(TAG, "LIST CONTAINS SHADOWSOCKS URL, START SERVICE")
            // start shadowsocks service
            val shadowsocksIntent = Intent(this, ShadowsocksService::class.java)
            // put shadowsocks proxy url here, should look like ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNz@127.0.0.1:1234 (base64 encode user/password)
            shadowsocksIntent.putExtra(
                "org.greatfire.envoy.START_SS_LOCAL",
                "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTppZXNvaHZvOHh1Nm9oWW9yaWUydGhhZWhvaFBoOFRoYQ==@172.104.163.54:8388"
            );
            waitingForShadowsocks = true
            ContextCompat.startForegroundService(applicationContext, shadowsocksIntent)
        }

        if (possibleUrls.contains(proxyUrl)) {
            Log.d(TAG, "LIST CONTAINS PROXY URL, START SERVICE")
            // start proxy service
            val proxyIntent = Intent(this, ProxyService::class.java)
            // put local proxy url here, can use socks5://127.0.0.1:1081 or any other available port
            proxyIntent.putExtra(ProxyService.LOCAL_URL, "socks5://127.0.0.1")
            proxyIntent.putExtra(ProxyService.LOCAL_PORT, 1081)
            // put socks or obfs4 url here. can include auth or certs
            proxyIntent.putExtra(
                ProxyService.PROXY_URL,
                "obfs4://<ip>:<port>/?cert=<encoded cert>&iat-mode=0"
            )
            waitingForProxy = true
            ContextCompat.startForegroundService(applicationContext, proxyIntent)
        }

        if (!possibleUrls.contains(ssUrl) && !possibleUrls.contains(proxyUrl)) {
            Log.d(TAG, "LIST CONTAINS NO SHADOWSOCKS OR PROXY URL, CHECK REMAINING URLS")
            // submit list of urls to envoy for evaluation
            NetworkIntentService.submit(this, possibleUrls)
        }

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

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
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
        fun newIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java)
        }
    }
}
