package org.wikipedia.analytics

import android.util.Log

class EnvoyAnalytics {

    companion object {

        val TAG = "ENVOY_ANALYTICS_EVENT"

        var enabled = false

        fun enableAnalytics() {
            enabled = true
        }

        fun disableAnalytics() {
            enabled = false
        }

        fun logDirectUrl(directUrl: String) {
            val eventMessage = "connected directly to url: " + directUrl
            logEvent(eventMessage)
        }

        fun logSelectedUrl(selectedUrl: String) {
            val eventMessage = "selected envoy url: " + selectedUrl
            logEvent(eventMessage)
        }

        fun logValidUrl(validUrl: String) {
            val eventMessage = "envoy url " + validUrl + " is valid"
            logEvent(eventMessage)
        }

        fun logInvalidUrl(invalidUrl: String) {
            val eventMessage = "envoy url " + invalidUrl + " is invalid"
            logEvent(eventMessage)
        }

        private fun logEvent(eventMessage: String) {
            if (enabled) {
                // TODO: replace with firebase integration or other actual analytics
                Log.d(TAG, eventMessage)
            } else {
                Log.d(TAG, "local logging -> " + eventMessage)
            }
        }
    }
}