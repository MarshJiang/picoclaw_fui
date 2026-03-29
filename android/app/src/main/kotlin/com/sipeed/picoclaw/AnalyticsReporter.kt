package com.sipeed.picoclaw

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.umeng.analytics.MobclickAgent
import com.umeng.commonsdk.UMConfigure

object AnalyticsReporter {
    private const val DEVICE_REPORT_EVENT = "device_feedback_report"
    private var umengInitialized = false
    private var initError: String? = null

    private val provider: String
        get() = BuildConfig.PICOCLAW_ANALYTICS_PROVIDER.lowercase()

    private val umengAppKey: String
        get() = BuildConfig.PICOCLAW_UMENG_APP_KEY

    private val umengChannel: String
        get() = BuildConfig.PICOCLAW_UMENG_CHANNEL.ifBlank { "official" }

    private fun isUmengProviderEnabled(): Boolean {
        val enabled = provider == "umeng" && umengAppKey.isNotBlank()
        android.util.Log.d("AnalyticsReporter", "isUmengProviderEnabled: provider=$provider, appKeyEmpty=${umengAppKey.isBlank()}, enabled=$enabled")
        return enabled
    }

    private fun checkNetwork(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun preInit(context: Context) {
        if (!isUmengProviderEnabled()) {
            android.util.Log.d("AnalyticsReporter", "Umeng not enabled, skipping preInit")
            return
        }
        
        val networkAvailable = checkNetwork(context)
        android.util.Log.d("AnalyticsReporter", "Pre-initializing Umeng SDK with channel=$umengChannel")
        android.util.Log.d("AnalyticsReporter", "Network available: $networkAvailable")
        
        try {
            // PreInit must be called before init
            UMConfigure.preInit(context.applicationContext, umengAppKey, umengChannel)
            android.util.Log.d("AnalyticsReporter", "PreInit completed successfully")
            
            // Auto-initialize if not already initialized (for first-run scenarios)
            if (!umengInitialized) {
                android.util.Log.d("AnalyticsReporter", "Auto-initializing Umeng SDK for device feedback")
                
                val appContext = context.applicationContext
                
                // Enable debug mode to see detailed logs
                UMConfigure.setLogEnabled(true)
                android.util.Log.d("AnalyticsReporter", "Debug logging enabled")
                
                // Submit consent result (required by Umeng privacy policy)
                UMConfigure.submitPolicyGrantResult(appContext, true)
                android.util.Log.d("AnalyticsReporter", "Policy grant result submitted")
                
                // Initialize on main thread
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    performInit(appContext)
                } else {
                    Handler(Looper.getMainLooper()).post {
                        performInit(appContext)
                    }
                }
            } else {
                android.util.Log.d("AnalyticsReporter", "Umeng SDK already initialized")
            }
        } catch (e: Exception) {
            initError = e.message
            android.util.Log.e("AnalyticsReporter", "Failed to preInit Umeng SDK: ${e.message}", e)
        }
    }
    
    private fun performInit(context: Context) {
        try {
            android.util.Log.d("AnalyticsReporter", "Performing Umeng SDK init on main thread...")
            UMConfigure.init(
                context,
                umengAppKey,
                umengChannel,
                UMConfigure.DEVICE_TYPE_PHONE,
                null,
            )
            MobclickAgent.setPageCollectionMode(MobclickAgent.PageMode.AUTO)
            // 设置为实时发送模式，确保事件立即上报
            MobclickAgent.setCatchUncaughtExceptions(true)
            android.util.Log.d("AnalyticsReporter", "Set to real-time send mode")
            umengInitialized = true
            initError = null
            android.util.Log.d("AnalyticsReporter", "Umeng SDK initialized successfully")
        } catch (e: Exception) {
            initError = e.message
            android.util.Log.e("AnalyticsReporter", "Failed to initialize Umeng SDK: ${e.message}", e)
        }
    }

    fun submitConsent(context: Context, granted: Boolean) {
        if (!isUmengProviderEnabled()) {
            return
        }
        val appContext = context.applicationContext
        UMConfigure.submitPolicyGrantResult(appContext, granted)
        if (granted && !umengInitialized) {
            UMConfigure.init(
                appContext,
                umengAppKey,
                umengChannel,
                UMConfigure.DEVICE_TYPE_PHONE,
                null,
            )
            MobclickAgent.setPageCollectionMode(MobclickAgent.PageMode.AUTO)
            umengInitialized = true
        }
    }

    fun uploadDeviceReport(context: Context, payload: Map<String, Any?>): Map<String, Any> {
        android.util.Log.d("AnalyticsReporter", "=== uploadDeviceReport START ===")
        android.util.Log.d("AnalyticsReporter", "umengInitialized=$umengInitialized, provider=$provider")
        
        if (!isUmengProviderEnabled()) {
            android.util.Log.e("AnalyticsReporter", "Umeng provider not enabled!")
            return mapOf(
                "success" to false,
                "message" to "Umeng provider is not enabled for this build.",
            )
        }
        if (!umengInitialized) {
            android.util.Log.e("AnalyticsReporter", "Umeng SDK not initialized")
            return mapOf(
                "success" to false,
                "message" to "Umeng SDK is not initialized. Error: $initError",
            )
        }

        // Check network status before sending
        val networkAvailable = checkNetwork(context)
        android.util.Log.d("AnalyticsReporter", "Network available: $networkAvailable")

        val eventPayload = linkedMapOf<String, Any>(
            "installId" to ((payload["installId"] as? String).orEmpty()),
            "platform" to ((payload["platform"] as? String).orEmpty()),
            "deviceModel" to ((payload["deviceModel"] as? String).orEmpty()),
            "systemVersion" to ((payload["systemVersion"] as? String).orEmpty()),
            "clientType" to ((payload["clientType"] as? String).orEmpty()),
            "updatedAt" to ((payload["updatedAt"] as? String).orEmpty()),
            "manufacturer" to Build.MANUFACTURER.orEmpty(),
            "sdkInt" to Build.VERSION.SDK_INT,
            "channel" to ((payload["channel"] as? String).orEmpty()),
        )
        android.util.Log.d("AnalyticsReporter", "Event payload prepared: $eventPayload")

        return try {
            android.util.Log.d("AnalyticsReporter", "[1/4] Preparing to send event '$DEVICE_REPORT_EVENT'")
            
            // Method 1: Counter event
            android.util.Log.d("AnalyticsReporter", "[2/4] Calling MobclickAgent.onEvent()...")
            MobclickAgent.onEvent(context.applicationContext, DEVICE_REPORT_EVENT, "report")
            android.util.Log.d("AnalyticsReporter", "[3/4] MobclickAgent.onEvent() returned successfully")
            
            // Method 2: Try to force sync
            android.util.Log.d("AnalyticsReporter", "[4/4] Attempting to flush data...")
            try {
                val method = MobclickAgent::class.java.getDeclaredMethod("flush", Context::class.java)
                method.invoke(null, context.applicationContext)
                android.util.Log.d("AnalyticsReporter", "Flush method called via reflection")
            } catch (e: Exception) {
                android.util.Log.w("AnalyticsReporter", "Reflection flush failed: ${e.message}, using fallback")
                MobclickAgent.onKillProcess(context.applicationContext)
                android.util.Log.d("AnalyticsReporter", "Fallback flush (onKillProcess) called")
            }
            
            android.util.Log.d("AnalyticsReporter", "=== uploadDeviceReport SUCCESS ===")
            mapOf(
                "success" to true,
                "message" to "Event sent successfully",
            )
        } catch (e: Exception) {
            android.util.Log.e("AnalyticsReporter", "=== uploadDeviceReport FAILED ===")
            android.util.Log.e("AnalyticsReporter", "Exception: ${e.message}", e)
            mapOf(
                "success" to false,
                "message" to "Upload failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }
}
