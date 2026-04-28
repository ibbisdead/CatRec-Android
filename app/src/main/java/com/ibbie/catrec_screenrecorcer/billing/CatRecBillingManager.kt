package com.ibbie.catrec_screenrecorcer.billing

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import com.ibbie.catrec_screenrecorcer.data.SettingsRepository
import com.ibbie.catrec_screenrecorcer.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed interface BillingUiEvent {
    /** Consumable [BillingProductIds.SUPPORT_ME] was consumed successfully — show thanks. */
    data object SupportMeConsumed : BillingUiEvent

    /** remove_ads is awaiting payment / Play confirmation. */
    data object RemoveAdsPending : BillingUiEvent
}

/**
 * Google Play Billing: restore [BillingProductIds.REMOVE_ADS] on connect, persist via [SettingsRepository],
 * consumable [BillingProductIds.SUPPORT_ME] with consume + repeat purchases.
 *
 * Promo and standard purchases both surface the same product id in [Purchase.getProducts] — see
 * [BillingProductIds.REMOVE_ADS] (`"remove_ads"`).
 */
class CatRecBillingManager(
    private val application: Application,
) {
    private val repository = SettingsRepository(application.applicationContext)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _uiEvents =
        MutableSharedFlow<BillingUiEvent>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val uiEvents: SharedFlow<BillingUiEvent> = _uiEvents.asSharedFlow()

    private var billingClient: BillingClient? = null

    @Volatile
    private var removeAdsProductDetails: ProductDetails? = null

    @Volatile
    private var supportMeProductDetails: ProductDetails? = null

    /** True only after [onBillingSetupFinished] with [BillingClient.BillingResponseCode.OK]. */
    @Volatile
    private var billingSetupFinishedOk = false

    /**
     * User asked to refresh/restore while billing was not ready yet; cleared after the next successful
     * [onBillingSetupFinished] runs [syncInAppPurchases] (tied to connection lifecycle, no fixed retry count).
     */
    @Volatile
    private var pendingRefreshAfterSetup = false

    private val purchasesUpdatedListener =
        PurchasesUpdatedListener { billingResult, purchases ->
            Log.d(
                TAG,
                "onPurchasesUpdated code=${billingResult.responseCode} " +
                    "count=${purchases?.size ?: 0} setupOk=$billingSetupFinishedOk",
            )
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    if (purchases.isNullOrEmpty()) {
                        syncInAppPurchases("on_purchases_updated_ok_empty")
                    } else {
                        purchases.forEach { handlePurchase(it) }
                    }
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> {
                    Log.d(TAG, "Purchase canceled by user")
                }
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    Log.d(TAG, "ITEM_ALREADY_OWNED — syncing entitlements")
                    syncInAppPurchases("on_purchases_updated_item_already_owned")
                }
                else -> {
                    AppLogger.w(TAG, "onPurchasesUpdated error: ${billingResult.responseCode} ${billingResult.debugMessage}")
                }
            }
        }

    private val connectionListener =
        object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                val hadPending = pendingRefreshAfterSetup
                logBillingState("onBillingSetupFinished(before_ok)", billingResult.responseCode)
                Log.d(
                    TAG,
                    "onBillingSetupFinished code=${billingResult.responseCode} " +
                        "msg=${billingResult.debugMessage} pendingRefreshBefore=$hadPending",
                )
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    billingSetupFinishedOk = true
                    loadProductDetails("billing_setup_ok")
                    // Initial sync + fulfils any [pendingRefreshAfterSetup] from restore/resume before connect.
                    val trigger =
                        if (hadPending) {
                            "billing_setup_ok_pending_refresh_fulfilled"
                        } else {
                            "billing_setup_ok_initial"
                        }
                    syncInAppPurchases(trigger)
                    pendingRefreshAfterSetup = false
                    logBillingState("onBillingSetupFinished(after_ok)", BillingClient.BillingResponseCode.OK)
                } else {
                    billingSetupFinishedOk = false
                    AppLogger.w(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                    scheduleReconnectAfterSetupFailure()
                    logBillingState("onBillingSetupFinished(failed)", billingResult.responseCode)
                }
            }

            override fun onBillingServiceDisconnected() {
                billingSetupFinishedOk = false
                Log.d(TAG, "onBillingServiceDisconnected — scheduling reconnect pendingRefresh=$pendingRefreshAfterSetup")
                logBillingState("onBillingServiceDisconnected", null)
                mainHandler.postDelayed({
                    try {
                        val c = billingClient
                        if (c != null && !c.isReady) {
                            c.startConnection(this)
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Billing reconnect failed: ${e.message}")
                    }
                }, RECONNECT_DELAY_MS)
            }
        }

    fun start() {
        if (billingClient != null) return
        val client =
            BillingClient
                .newBuilder(application)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
                ).build()
        billingClient = client
        Log.d(TAG, "startConnection requested")
        client.startConnection(connectionListener)
    }

    private fun logBillingState(
        where: String,
        lastResponseCode: Int?,
    ) {
        val c = billingClient
        Log.d(
            TAG,
            "billingState[$where] setupOk=$billingSetupFinishedOk clientNull=${c == null} " +
                "isReady=${c?.isReady} pendingRefresh=$pendingRefreshAfterSetup " +
                "lastResponseCode=${lastResponseCode ?: "n/a"}",
        )
    }

    /**
     * Call from [Activity.onResume] to pick up grants completed outside the app
     * (e.g. promo redemption in Play Store), or when the user explicitly restores purchases.
     * If billing is not ready, sets [pendingRefreshAfterSetup]; the next [onBillingSetupFinished](OK) runs sync.
     *
     * @return true if sync ran immediately; false if deferred until connection is ready.
     */
    fun refreshPurchasesIfConnected(): Boolean {
        val c = billingClient
        if (c == null) {
            Log.w(TAG, "refreshPurchases requested: billingClient null")
            return false
        }
        Log.d(TAG, "refreshPurchases requested setupOk=$billingSetupFinishedOk ready=${c.isReady}")
        if (!billingSetupFinishedOk || !c.isReady) {
            pendingRefreshAfterSetup = true
            Log.d(
                TAG,
                "refreshPurchases deferred: pendingRefreshAfterSetup=true " +
                    "(will sync on next onBillingSetupFinished OK)",
            )
            logBillingState("refreshPurchases(deferred)", null)
            return false
        }
        pendingRefreshAfterSetup = false
        loadProductDetails("refresh_explicit")
        syncInAppPurchases("refresh_explicit")
        Log.d(TAG, "refreshPurchases ran immediately")
        return true
    }

    fun launchRemoveAdsPurchase(activity: Activity): Boolean {
        val client = billingClient
        val details = removeAdsProductDetails
        if (client == null || !client.isReady || details == null) {
            AppLogger.w(TAG, "launchRemoveAdsPurchase blocked ready=${client?.isReady} details=$details")
            return false
        }
        val params =
            BillingFlowParams.ProductDetailsParams
                .newBuilder()
                .setProductDetails(details)
                .build()
        val result =
            client.launchBillingFlow(
                activity,
                BillingFlowParams
                    .newBuilder()
                    .setProductDetailsParamsList(listOf(params))
                    .build(),
            )
        return when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> true
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                syncInAppPurchases("launch_remove_ads_item_already_owned")
                true
            }
            else -> {
                AppLogger.w(TAG, "launchBillingFlow remove_ads: ${result.debugMessage}")
                false
            }
        }
    }

    fun launchSupportMePurchase(activity: Activity): Boolean {
        val client = billingClient
        val details = supportMeProductDetails
        if (client == null || !client.isReady || details == null) {
            AppLogger.w(TAG, "launchSupportMePurchase blocked ready=${client?.isReady} details=$details")
            return false
        }
        val params =
            BillingFlowParams.ProductDetailsParams
                .newBuilder()
                .setProductDetails(details)
                .build()
        val result =
            client.launchBillingFlow(
                activity,
                BillingFlowParams
                    .newBuilder()
                    .setProductDetailsParamsList(listOf(params))
                    .build(),
            )
        return when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> true
            else -> {
                AppLogger.w(TAG, "launchBillingFlow support_me: ${result.debugMessage}")
                false
            }
        }
    }

    private fun scheduleReconnectAfterSetupFailure() {
        Log.d(TAG, "scheduleReconnectAfterSetupFailure in ${RECONNECT_DELAY_MS}ms")
        mainHandler.postDelayed({
            try {
                val c = billingClient
                if (c != null && !c.isReady) {
                    c.startConnection(connectionListener)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Billing reconnect after setup failure: ${e.message}")
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun loadProductDetails(trigger: String) {
        val client = billingClient ?: return
        if (!billingSetupFinishedOk || !client.isReady) {
            Log.d(TAG, "loadProductDetails skipped trigger=$trigger setupOk=$billingSetupFinishedOk ready=${client.isReady}")
            return
        }
        Log.d(TAG, "loadProductDetails start trigger=$trigger ids=${BillingProductIds.REMOVE_ADS},${BillingProductIds.SUPPORT_ME}")
        val productList =
            listOf(
                QueryProductDetailsParams.Product
                    .newBuilder()
                    .setProductId(BillingProductIds.REMOVE_ADS)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build(),
                QueryProductDetailsParams.Product
                    .newBuilder()
                    .setProductId(BillingProductIds.SUPPORT_ME)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build(),
            )
        val params =
            QueryProductDetailsParams
                .newBuilder()
                .setProductList(productList)
                .build()
        client.queryProductDetailsAsync(params) { billingResult, detailsResult: QueryProductDetailsResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                AppLogger.w(TAG, "queryProductDetails failed trigger=$trigger: ${billingResult.debugMessage}")
                return@queryProductDetailsAsync
            }
            val loadedIds = detailsResult.productDetailsList.map { it.productId }
            Log.d(
                TAG,
                "queryProductDetails success trigger=$trigger productDetailsCount=${detailsResult.productDetailsList.size} " +
                    "productIdsLoaded=$loadedIds",
            )
            for (pd in detailsResult.productDetailsList) {
                when (pd.productId) {
                    BillingProductIds.REMOVE_ADS -> removeAdsProductDetails = pd
                    BillingProductIds.SUPPORT_ME -> supportMeProductDetails = pd
                }
                Log.d(TAG, "productDetails loaded productId=${pd.productId}")
            }
            for (unfetched in detailsResult.unfetchedProductList) {
                AppLogger.w(TAG, "Product not fetched: ${unfetched.productId}")
            }
        }
    }

    private fun syncInAppPurchases(trigger: String) {
        val client = billingClient ?: run {
            Log.w(TAG, "syncInAppPurchases skipped trigger=$trigger: no client")
            return
        }
        if (!billingSetupFinishedOk || !client.isReady) {
            Log.d(
                TAG,
                "syncInAppPurchases skipped trigger=$trigger setupOk=$billingSetupFinishedOk " +
                    "ready=${client.isReady}",
            )
            return
        }
        Log.d(TAG, "syncInAppPurchases start trigger=$trigger expectedRemoveAdsId=\"${BillingProductIds.REMOVE_ADS}\"")
        client.queryPurchasesAsync(
            QueryPurchasesParams
                .newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        ) { billingResult, purchases ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                AppLogger.w(TAG, "queryPurchases failed trigger=$trigger: ${billingResult.debugMessage}")
                return@queryPurchasesAsync
            }
            Log.d(
                TAG,
                "queryPurchases result trigger=$trigger purchaseListSize=${purchases.size} " +
                    "responseCode=${billingResult.responseCode}",
            )
            var removeAdsOwned = false
            for (purchase in purchases) {
                val products = purchase.products
                val containsRemoveAds = products.contains(BillingProductIds.REMOVE_ADS)
                Log.d(
                    TAG,
                    "purchase row state=${purchase.purchaseState} products=$products " +
                        "containsRemoveAds=$containsRemoveAds " +
                        "(match id \"${BillingProductIds.REMOVE_ADS}\") tokenPrefix=" +
                        "${purchase.purchaseToken.take(12)}…",
                )
                when {
                    products.contains(BillingProductIds.SUPPORT_ME) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED -> {
                        Log.d(TAG, "Found unconsumed support_me — consuming")
                        consumeSupportMePurchase(purchase)
                    }
                    containsRemoveAds -> {
                        when (purchase.purchaseState) {
                            Purchase.PurchaseState.PENDING -> {
                                Log.d(TAG, "remove_ads PENDING (awaiting completion)")
                            }
                            Purchase.PurchaseState.PURCHASED -> {
                                removeAdsOwned = true
                                acknowledgeRemoveAdsIfNeeded(purchase)
                            }
                            else -> { }
                        }
                    }
                }
            }
            appScope.launch {
                repository.setAdsDisabled(removeAdsOwned)
            }
            Log.d(
                TAG,
                "syncInAppPurchases done trigger=$trigger removeAdsOwned=$removeAdsOwned " +
                    "purchaseListSize=${purchases.size}",
            )
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        Log.d(TAG, "handlePurchase state=${purchase.purchaseState} products=${purchase.products}")
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> {
                if (purchase.products.contains(BillingProductIds.REMOVE_ADS)) {
                    Log.d(TAG, "remove_ads pending — awaiting completion")
                    appScope.launch { _uiEvents.emit(BillingUiEvent.RemoveAdsPending) }
                }
            }
            Purchase.PurchaseState.PURCHASED -> {
                if (purchase.products.contains(BillingProductIds.REMOVE_ADS)) {
                    appScope.launch { repository.setAdsDisabled(true) }
                    acknowledgeRemoveAdsIfNeeded(purchase)
                }
                if (purchase.products.contains(BillingProductIds.SUPPORT_ME)) {
                    consumeSupportMePurchase(purchase)
                }
            }
            else -> {
                AppLogger.w(TAG, "Unhandled purchase state ${purchase.purchaseState}")
            }
        }
    }

    private fun acknowledgeRemoveAdsIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val client = billingClient ?: return
        val ackParams =
            AcknowledgePurchaseParams
                .newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        client.acknowledgePurchase(ackParams) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                AppLogger.w(TAG, "acknowledge remove_ads failed: ${result.debugMessage}")
            } else {
                Log.d(TAG, "remove_ads acknowledged")
            }
        }
    }

    private fun consumeSupportMePurchase(purchase: Purchase) {
        val client = billingClient ?: return
        val consumeParams =
            ConsumeParams
                .newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        client.consumeAsync(consumeParams) { result, token ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "support_me consumed token=$token")
                appScope.launch { _uiEvents.emit(BillingUiEvent.SupportMeConsumed) }
            } else {
                AppLogger.w(TAG, "consume support_me failed: ${result.debugMessage}")
            }
        }
    }

    companion object {
        private const val TAG = "CatRecBilling"
        private const val RECONNECT_DELAY_MS = 2000L
    }
}
