package org.wikipedia.dataclient.okhttp

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import org.wikipedia.WikipediaApp
import org.wikipedia.dataclient.RestService
import org.wikipedia.settings.Prefs.isEventLoggingEnabled
import java.io.IOException

internal class CommonHeaderRequestInterceptor : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val app = WikipediaApp.instance
        Log.d("ENVOY_LOG", "event logging is currently enabled:" + isEventLoggingEnabled + ", configure necessary headers")
        val builder = chain.request().newBuilder()
                .header("User-Agent", app.userAgent)
                .header(if (isEventLoggingEnabled) "X-WMF-UUID" else "DNT",
                        if (isEventLoggingEnabled) app.appInstallID else "1")
        if (chain.request().url.encodedPath.contains(RestService.PAGE_HTML_ENDPOINT)) {
            builder.header("Accept", RestService.ACCEPT_HEADER_MOBILE_HTML)
        }
        return chain.proceed(builder.build())
    }
}
