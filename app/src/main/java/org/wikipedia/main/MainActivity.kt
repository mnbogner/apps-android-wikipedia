package org.wikipedia.main

import IEnvoyProxy.IEnvoyProxy
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.greatfire.envoy.*
import org.json.JSONObject
import org.wikipedia.BuildConfig
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
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class MainActivity : SingleFragmentActivity<MainFragment>(), MainFragment.Callback {

    private val TAG = "MainActivity"

    private lateinit var binding: ActivityMainBinding

    private var controlNavTabInFragment = false

    // initialize one or more string values containing the urls of available http/https proxies (include trailing slash)
    // -> now parsing urls from dnstt request
    // urls for additional proxy services, change if there are port conflicts (do not include trailing slash)
    private var ssUrlRemote = ""
    private var hysteriaUrlRemote = ""
    private var v2wsUrlRemote = ""
    private var v2srtpUrlRemote = ""
    private var v2wechatUrlRemote = ""
    private val baseUrlLocal = "socks5://127.0.0.1:"
    // add all string values to this list value
    private val defaultUrls = mutableListOf<String>()
    private val dnsttUrls = mutableListOf<String>()

    // TODO: revisit and refactor
    private var waitingForDnstt = false
    private var waitingForV2ray = false
    private var waitingForHysteria = false
    private var waitingForShadowsocks = false
    private var waitingForDefaultUrl = false
    private var waitingForDnsttUrl = false

    // TODO: copied from org.greatfire.envoy.NetworkIntentService, make public/static and import?
    private val UNMODIFIED_STRATEGY = 0
    private val ELEVATED_COUNT_STRATEGY = 1
    private val TRUNCATED_RESERVED_STRATEGY = 2
    private val MULTI_BYTE_STRATEGY = 3
    private val MULTI_BYTE_ELEVATED_COUNT_STRATEGY = 4
    private val COMPRESSED_STRATEGY = 5

    // TEMP
    private val CURRENT_STRATEGY = ELEVATED_COUNT_STRATEGY

    // this receiver should be triggered by a success or failure broadcast from either the
    // NetworkIntentService (indicating whether submitted urls were valid or invalid) or the
    // ShadowsocksService (indicating whether the service was successfully started or not
    private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && context != null) {
                if (intent.action == BROADCAST_URL_VALIDATION_SUCCEEDED) {
                    val validUrls = intent.getStringArrayListExtra(EXTENDED_DATA_VALID_URLS)
                    Log.d(TAG, "received " + validUrls?.size + " valid urls")
                    if (waitingForDefaultUrl || waitingForDnsttUrl) {
                        if (validUrls != null && !validUrls.isEmpty()) {
                            // if we get a valid url, it doesn't matter whether it's from defaults or dnstt
                            waitingForDefaultUrl = false
                            waitingForDnsttUrl = false
                            val envoyUrl = validUrls[0]
                            Log.d(TAG, "found a valid url: " + envoyUrl + ", start engine")
                            // select the fastest one (urls are ordered by latency), reInitializeIfNeeded set to false
                            CronetNetworking.initializeCronetEngine(context, envoyUrl)
                        } else {
                            Log.e(TAG, "received empty list of valid urls")
                        }
                    } else {
                        Log.d(TAG, "already found a valid url")
                    }
                } else if (intent.action == BROADCAST_URL_VALIDATION_FAILED) {
                    val invalidUrls = intent.getStringArrayListExtra(EXTENDED_DATA_INVALID_URLS)
                    Log.e(TAG, "received " + invalidUrls?.size + " invalid urls")
                    if (invalidUrls != null && !invalidUrls.isEmpty()) {
                        if (waitingForDefaultUrl && (invalidUrls.size >= defaultUrls.size)) {
                            Log.e(TAG, "no default urls left to try, fetch urls with dnstt")
                            waitingForDefaultUrl = false
                            waitingForDnsttUrl = true
                            // start asynchronous dnstt task to fetch proxy urls
                            lifecycleScope.launch(Dispatchers.IO) {
                                getDnsttUrls()
                            }
                        } else if (waitingForDnsttUrl && (invalidUrls.size >= dnsttUrls.size)) {
                            Log.e(TAG, "no dnstt urls left to try, cannot start envoy/cronet")
                            waitingForDnsttUrl = false
                        } else {
                            Log.e(TAG, "still trying urls: default - " + waitingForDefaultUrl + ", " + defaultUrls.size + " / dnstt - " + waitingForDnsttUrl + ", " + dnsttUrls.size)
                        }
                    } else {
                        Log.e(TAG, "received empty list of invalid urls")
                    }
                } else if (intent.action == ShadowsocksService.SHADOWSOCKS_SERVICE_BROADCAST) {
                    waitingForShadowsocks = false
                    var shadowsocksResult = intent.getIntExtra(ShadowsocksService.SHADOWSOCKS_SERVICE_RESULT, 0)
                    if (shadowsocksResult > 0) {
                        Log.d(TAG, "shadowsocks service started ok")
                    } else {
                        Log.e(TAG, "shadowsocks service failed to start")
                    }
                    // shadowsocks service was started if possible, submit list of urls to envoy for evaluation
                    if (waitingForHysteria || waitingForV2ray) {
                        Log.d(TAG, "submit urls after an additional delay for starting hysteria and/or v2ray")
                        lifecycleScope.launch(Dispatchers.IO) {
                            Log.d(TAG, "start delay")
                            delay(5000L) // wait 5 seconds
                            Log.d(TAG, "end delay")
                            waitingForHysteria = false
                            waitingForV2ray = false
                            if (waitingForDefaultUrl) {
                                NetworkIntentService.submit(this@MainActivity, defaultUrls, CURRENT_STRATEGY)
                            } else {
                                NetworkIntentService.submit(this@MainActivity, dnsttUrls, CURRENT_STRATEGY)
                            }
                        }
                    } else {
                        Log.d(TAG, "submit urls, no additional delay is needed")
                        if (waitingForDefaultUrl) {
                            NetworkIntentService.submit(this@MainActivity, defaultUrls, CURRENT_STRATEGY)
                        } else {
                            NetworkIntentService.submit(this@MainActivity, dnsttUrls, CURRENT_STRATEGY)
                        }
                    }
                } else {
                    Log.e(TAG, "received unexpected intent: " + intent.action)
                }
            } else {
                Log.e(TAG, "receiver triggered but context or intent was null")
            }
        }
    }

    override fun inflateAndSetContentView() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    fun getDefaultUrls() {

        var urlList = mutableListOf<String>()

        if (BuildConfig.DEF_PROXY.isNullOrEmpty()) {
            Log.w(TAG, "no default proxy urls were provided")
        } else {
            Log.d(TAG, "found default proxy urls: " + BuildConfig.DEF_PROXY)
            urlList.addAll(BuildConfig.DEF_PROXY.split(","))
        }

        handleUrls(urlList)
    }

    fun getDnsttUrls() {

        // check for dnstt project properties
        if (BuildConfig.DNSTT_SERVER.isNullOrEmpty() ||
            BuildConfig.DNSTT_KEY.isNullOrEmpty() ||
            BuildConfig.DNSTT_PATH.isNullOrEmpty() ||
            (BuildConfig.DOH_URL.isNullOrEmpty() && BuildConfig.DOT_ADDR.isNullOrEmpty())) {
            Log.e(TAG, "dnstt parameters are not defined, cannot fetch metadata with dnstt")
        } else {

            // set time limit for dnstt (dnstt allows a long timeout and retries, may never return)
            lifecycleScope.launch(Dispatchers.IO) {
                Log.d(TAG, "start timer")
                waitingForDnstt = true
                delay(10000L) // wait 10 seconds
                if (waitingForDnstt) {
                    Log.d(TAG, "stop timer, stop dnstt")
                    waitingForDnstt = false
                    IEnvoyProxy.stopDnstt()
                } else {
                    Log.d(TAG, "dnstt already complete")
                }
            }

            try {
                // provide either DOH or DOT address, and provide an empty string for the other
                Log.d(TAG, "start dnstt proxy: " + BuildConfig.DNSTT_SERVER + " / " + BuildConfig.DOH_URL + " / " + BuildConfig.DOT_ADDR + " / " + BuildConfig.DNSTT_KEY)
                val dnsttPort = IEnvoyProxy.startDnstt(
                    BuildConfig.DNSTT_SERVER,
                    BuildConfig.DOH_URL,
                    BuildConfig.DOT_ADDR,
                    BuildConfig.DNSTT_KEY
                )

                Log.d(TAG, "get list of possible urls")
                val url = URL("http://127.0.0.1:" + dnsttPort + BuildConfig.DNSTT_PATH)
                Log.d(TAG, "open connection: " + url)
                val connection = url.openConnection() as HttpURLConnection
                try {
                    Log.d(TAG, "set timeout")
                    connection.connectTimeout = 5000
                    Log.d(TAG, "connect")
                    connection.connect()
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "connection timeout when connecting: " + e.localizedMessage)
                } catch (e: ConnectException) {
                    Log.e(TAG, "connection error: " + e.localizedMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "unexpected error when connecting: " + e.localizedMessage)
                }

                try {
                    Log.d(TAG, "open input stream")
                    val input = connection.inputStream
                    if (input != null) {
                        Log.d(TAG, "parse json and extract possible urls")
                        val json = input.bufferedReader().use(BufferedReader::readText)
                        val envoyObject = JSONObject(json)
                        val envoyUrlArray = envoyObject.getJSONArray("envoyUrls")

                        var urlList = mutableListOf<String>()

                        for (i in 0 until envoyUrlArray!!.length()) {
                            if (defaultUrls.contains(envoyUrlArray.getString(i)) ||
                                    hysteriaUrlRemote.equals(envoyUrlArray.getString(i)) ||
                                    ssUrlRemote.equals(envoyUrlArray.getString(i))) {
                                Log.d(TAG, "dnstt url " + envoyUrlArray.getString(i) + " has aready been validated")
                            } else {
                                Log.d(TAG, "dnstt url " + envoyUrlArray.getString(i) + " has not been validated yet")
                                urlList.add(envoyUrlArray.getString(i))
                            }
                        }

                        hysteriaUrlRemote = ""
                        ssUrlRemote = ""

                        handleUrls(urlList)
                    } else {
                        Log.e(TAG, "response contained no json to parse")
                    }
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "connection timeout when getting input: " + e.localizedMessage)
                } catch (e: FileNotFoundException) {
                    Log.e(TAG, "config file error: " + e.localizedMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "unexpected error when reading file: " + e.localizedMessage)
                }
            } catch (e: Error) {
                Log.e(TAG, "dnstt error: " + e.localizedMessage)
            } catch (e: Exception) {
                Log.e(TAG, "unexpected error when starting dnstt: " + e.localizedMessage)
            }

            Log.d(TAG, "stop dnstt proxy")
            waitingForDnstt = false
            IEnvoyProxy.stopDnstt()
        }
    }

    fun handleUrls(envoyUrls: MutableList<String>) {

        // check url types
        for (url in envoyUrls) {
            if (url.startsWith("v2ws://")) {

                // TEMP: current v2ray host uses an ip not a url
                var shortV2wsUrl = url.replace("v2ws://", "")

                Log.d(TAG, "found v2ray url: " + shortV2wsUrl)
                v2wsUrlRemote = shortV2wsUrl
            } else if (url.startsWith("v2srtp://")) {

                // TEMP: current v2ray host uses an ip not a url
                var shortV2srtpUrl = url.replace("v2srtp://", "")

                Log.d(TAG, "found v2ray url: " + shortV2srtpUrl)
                v2srtpUrlRemote = shortV2srtpUrl
            } else if (url.startsWith("v2wechat://")) {

                // TEMP: current v2ray host uses an ip not a url
                var shortV2wechatUrl = url.replace("v2wechat://", "")

                Log.d(TAG, "found v2ray url: " + shortV2wechatUrl)
                v2wechatUrlRemote = shortV2wechatUrl
            } else if (url.startsWith("hysteria://")) {

                // TEMP: current hysteria host uses an ip not a url
                var shortHysteriaUrl = url.replace("hysteria://", "")

                Log.d(TAG, "found hysteria url: " + shortHysteriaUrl)
                hysteriaUrlRemote = shortHysteriaUrl
            } else if (url.startsWith("ss://")) {
                Log.d(TAG, "found ss url: " + url)
                ssUrlRemote = url
            } else {
                Log.d(TAG, "found url: " + url)
                if (waitingForDefaultUrl) {
                    defaultUrls.add(url)
                } else {
                    dnsttUrls.add(url)
                }
            }
        }

        // check for urls that require services

        if (v2wsUrlRemote.isNotEmpty()) {
            Log.d(TAG, "v2ray websocket service needed")
            // start v2ray websocket service
            val v2wsParts = v2wsUrlRemote.split(":")
            if (v2wsParts == null || v2wsParts.size < 4) {
                Log.e(TAG, "some arguments required for v2ray websocket service are missing")
            } else {
                val v2wsPort = IEnvoyProxy.startV2RayWs(v2wsParts[0], v2wsParts[1], v2wsParts[2], v2wsParts[3])

                Log.d(TAG, "v2ray websocket service started at " + baseUrlLocal + v2wsPort)

                // add url for v2ray service
                if (waitingForDefaultUrl) {
                    defaultUrls.add(baseUrlLocal + v2wsPort)
                } else {
                    dnsttUrls.add(baseUrlLocal + v2wsPort)
                }

                waitingForV2ray = true
            }
        }

        if (v2srtpUrlRemote.isNotEmpty()) {
            Log.d(TAG, "v2ray srtp service needed")
            // start v2ray srtp service
            val v2srtpParts = v2srtpUrlRemote.split(":")
            if (v2srtpParts == null || v2srtpParts.size < 3) {
                Log.e(TAG, "some arguments required for v2ray srtp service are missing")
            } else {
                val v2srtpPort = IEnvoyProxy.startV2raySrtp(v2srtpParts[0], v2srtpParts[1], v2srtpParts[2])

                Log.d(TAG, "v2ray srtp service started at " + baseUrlLocal + v2srtpPort)

                // add url for v2ray service
                if (waitingForDefaultUrl) {
                    defaultUrls.add(baseUrlLocal + v2srtpPort)
                } else {
                    dnsttUrls.add(baseUrlLocal + v2srtpPort)
                }

                waitingForV2ray = true
            }
        }

        if (v2wechatUrlRemote.isNotEmpty()) {
            Log.d(TAG, "v2ray wechat service needed")
            // start v2ray wechat service
            val v2wechatParts = v2wechatUrlRemote.split(":")
            if (v2wechatParts == null || v2wechatParts.size < 3) {
                Log.e(TAG, "some arguments required for v2ray wechat service are missing")
            } else {
                val v2wechatPort = IEnvoyProxy.startV2RayWechat(v2wechatParts[0], v2wechatParts[1], v2wechatParts[2])

                Log.d(TAG, "v2ray wechat service started at " + baseUrlLocal + v2wechatPort)

                // add url for v2ray service
                if (waitingForDefaultUrl) {
                    defaultUrls.add(baseUrlLocal + v2wechatPort)
                } else {
                    dnsttUrls.add(baseUrlLocal + v2wechatPort)
                }

                waitingForV2ray = true
            }
        }

        if (hysteriaUrlRemote.isNotEmpty()) {
            Log.d(TAG, "hysteria service needed")
            // start hysteria service
            val hysteriaPort = IEnvoyProxy.startHysteria(
                hysteriaUrlRemote, "uPa1gar4Guce5ooteyiuthie7soqu5Mu", """
            -----BEGIN CERTIFICATE-----
            MIIEzjCCAzagAwIBAgIRAIwE+m2D+1vvzPZaSLj/a7YwDQYJKoZIhvcNAQELBQAw
            fzEeMBwGA1UEChMVbWtjZXJ0IGRldmVsb3BtZW50IENBMSowKAYDVQQLDCFzY21A
            bTFwcm8ubG9jYWwgKFN0ZXZlbiBNY0RvbmFsZCkxMTAvBgNVBAMMKG1rY2VydCBz
            Y21AbTFwcm8ubG9jYWwgKFN0ZXZlbiBNY0RvbmFsZCkwHhcNMjIwMTI3MDE0NTQ5
            WhcNMzIwMTI3MDE0NTQ5WjB/MR4wHAYDVQQKExVta2NlcnQgZGV2ZWxvcG1lbnQg
            Q0ExKjAoBgNVBAsMIXNjbUBtMXByby5sb2NhbCAoU3RldmVuIE1jRG9uYWxkKTEx
            MC8GA1UEAwwobWtjZXJ0IHNjbUBtMXByby5sb2NhbCAoU3RldmVuIE1jRG9uYWxk
            KTCCAaIwDQYJKoZIhvcNAQEBBQADggGPADCCAYoCggGBANd+mMC9kQWwH+h++vmS
            Kkqv1xebHKncKT/JAAr6lBG/O9T6V0KEZTgMeVU4XG4C2CVPRzbceADSTN36u2k2
            +ToGeP6fEc/sz7SD1Uf/Xu6aZCrEuuK8aHchcn2+BgcV5heiKIpQGHVjFzCgez97
            wXdcNowerpWP42WK5yj2e3+VKBojHouvSBrTj3EaYAn5nQLiIpi7ZqHmq7NorOhS
            ldaCKO6tp8LRQX0X13FL0o8hNJb7gZuSYxt3NzoP0ZCeKfd9La7409u0ZBUuUrWl
            k01gPh+6SqrvsqSf3AnpxvlvUfpm1e9LfUZe0S/J1OYOkF2QdQ+wlzHZsYyxZ2uc
            kRWLYbqXkF93X3O2H0SkjYKB3PFKcWNeUdt3LJ4lNrisX+R+JTU+4XpGYznnIebF
            /Jt/U9aFkenkE3JHyfe9SDedAqUVO9j6XGRFSK5LuoZsXoEqrqY3DXbUZTsZbkZ2
            NVtmM+9/bcuBxDgBxUGnvPLRaHO9Y3rkjc+8Qb40iibW8QIDAQABo0UwQzAOBgNV
            HQ8BAf8EBAMCAgQwEgYDVR0TAQH/BAgwBgEB/wIBADAdBgNVHQ4EFgQUyaGG2QSl
            nr3VsOPd+7EwfxSIQ7UwDQYJKoZIhvcNAQELBQADggGBAA97ah3o5EUwy/LNSkSK
            MEYREtZfZp6oz4IjDMCKN9FKtKcqlpbyJVlz/ahIU9/QDqCKcaAJVLmR57fZ/qio
            HNQcm1yvA6TlprnwMHNtPO3cxsi1p0D7EofAy0oAcRp3NgTOWpX7zTpd2pNIuDy6
            lmP1iBkUxfXorAN+MR1SzEWYQn2k3hcHesrvTzqGZmcVyRDihLWd7bTeixGO5x8w
            fNNWTW+Sd6t1vPVR+qBwSLGUKMxoVeenaP8PXn6u5BDzNkwZKQMWQzFlt+DQL61z
            6t5OU73CYgJ7XIKvKN+eFOG9lvYglo8LyDJ74QbznVh/Hcwzps7t3QB/S7Q1imue
            7n3hINp1GwDgVmFkk0oIG8+s5z54hxCIABgWZsBr2vtGLvn3+xEDgFtRsY9N4PTO
            PRHq//BHvTjFt9pwZs5k+EBu9K3I0WZw2PBWhzLiLA7PdkDiDvPw5sJW80vOVo8w
            lTIm9+lxj2TaeiqcPaVRBUG7cmIx+iUFPnpttnp8SvRWlQ==
            -----END CERTIFICATE-----
        """.trimIndent()
            )

            Log.d(TAG, "hysteria service started at " + baseUrlLocal + hysteriaPort)

            // add url for hysteria service
            if (waitingForDefaultUrl) {
                defaultUrls.add(baseUrlLocal + hysteriaPort)
            } else {
                dnsttUrls.add(baseUrlLocal + hysteriaPort)
            }

            waitingForHysteria = true
        }

        if (ssUrlRemote.isNotEmpty()) {
            // Notification.Builder in ShadowsocksService.onStartCommand may require api > 7
            Log.d(TAG, "shadowsocks service needed")
            // start shadowsocks service
            val shadowsocksIntent = Intent(this, ShadowsocksService::class.java)
            // put shadowsocks proxy url here, should look like ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNz@127.0.0.1:1234 (base64 encode user/password)
            shadowsocksIntent.putExtra(
                "org.greatfire.envoy.START_SS_LOCAL",
                ssUrlRemote
            )

            Log.d(TAG, "shadowsocks service starting at " + baseUrlLocal + "1080")
            ContextCompat.startForegroundService(applicationContext, shadowsocksIntent)

            // add url for shadowsocks service
            if (waitingForDefaultUrl) {
                defaultUrls.add(baseUrlLocal + "1080")
            } else {
                dnsttUrls.add(baseUrlLocal + "1080")
            }

            waitingForShadowsocks = true
        }

        if (waitingForDefaultUrl && defaultUrls.isEmpty()) {
            Log.w(TAG, "no default urls to submit, get additional urls with dnstt")
            waitingForDefaultUrl = false
            waitingForDnsttUrl = true
            // start asynchronous dnstt task to fetch proxy urls
            lifecycleScope.launch(Dispatchers.IO) {
                getDnsttUrls()
            }
        } else if (waitingForDnsttUrl && dnsttUrls.isEmpty()) {
            waitingForDnsttUrl = false
            Log.w(TAG, "no dnstt urls to submit, cannot start envoy/cronet")
        } else if (waitingForShadowsocks) {
            Log.d(TAG, "submit urls after starting shadowsocks service")
        } else if (waitingForHysteria || waitingForV2ray) {
            Log.d(TAG, "submit urls after a short delay for starting hysteria and/or v2ray")
            lifecycleScope.launch(Dispatchers.IO) {
                Log.d(TAG, "start delay")
                delay(10000L) // wait 10 seconds
                Log.d(TAG, "end delay")
                // clear both flags
                waitingForHysteria = false
                waitingForV2ray = false
                if (waitingForDefaultUrl) {
                    NetworkIntentService.submit(this@MainActivity, defaultUrls, CURRENT_STRATEGY)
                } else {
                    NetworkIntentService.submit(this@MainActivity, dnsttUrls, CURRENT_STRATEGY)
                }
            }
        } else {
            // submit list of urls to envoy for evaluation
            Log.d(TAG, "no services needed, submit urls immediately")
            if (waitingForDefaultUrl) {
                NetworkIntentService.submit(this@MainActivity, defaultUrls, CURRENT_STRATEGY)
            } else {
                NetworkIntentService.submit(this@MainActivity, dnsttUrls, CURRENT_STRATEGY)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // register to receive test results
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, IntentFilter().apply {
            addAction(BROADCAST_URL_VALIDATION_SUCCEEDED)
            addAction(BROADCAST_URL_VALIDATION_FAILED)
            addAction(ShadowsocksService.SHADOWSOCKS_SERVICE_BROADCAST)
        })

        setImageZoomHelper()
        if (Prefs.isInitialOnboardingEnabled && savedInstanceState == null) {
            // Updating preference so the search multilingual tooltip
            // is not shown again for first time users
            Prefs.isMultilingualSearchTooltipShown = false

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

        // start cronet here to prevent exception from starting a service when out of focus
        if (CronetNetworking.cronetEngine() != null) {
            Log.d(TAG, "cronet already running, don't try to start again")
        } else if (waitingForDefaultUrl || waitingForDnsttUrl) {
            Log.d(TAG, "already processing urls, don't try to start again")
        } else {
            // run envoy setup (fetches and validate urls)
            Log.d(TAG, "begin processing urls to start cronet")
            waitingForDefaultUrl = true
            getDefaultUrls()
        }

        invalidateOptionsMenu()
    }

    override fun createFragment(): MainFragment {
        return MainFragment.newInstance()
    }

    override fun onTabChanged(tab: NavTab) {
        binding.mainToolbar.setTitle(tab.text())
        if (tab == NavTab.EXPLORE) {
            controlNavTabInFragment = false
        } else {
            if (tab == NavTab.SEARCH && Prefs.showSearchTabTooltip) {
                FeedbackUtil.showTooltip(this, fragment.binding.mainNavTabLayout.findViewById(NavTab.SEARCH.id()), getString(R.string.search_tab_tooltip), aboveOrBelow = true, autoDismiss = false)
                Prefs.showSearchTabTooltip = false
            }
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
