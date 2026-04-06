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
 */
class CatRecBillingManager(private val application: Application) {

    private val repository = SettingsRepository(application.applicationContext)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _uiEvents = MutableSharedFlow<BillingUiEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val uiEvents: SharedFlow<BillingUiEvent> = _uiEvents.asSharedFlow()

    private var billingClient: BillingClient? = null
    @Volatile
    private var removeAdsProductDetails: ProductDetails? = null
    @Volatile
    private var supportMeProductDetails: ProductDetails? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        Log.d(TAG, "onPurchasesUpdated code=${billingResult.responseCode} count=${purchases?.size ?: 0}")
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) {
                    syncInAppPurchases()
                } else {
                    purchases.forEach { handlePurchase(it) }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "Purchase canceled by user")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.d(TAG, "ITEM_ALREADY_OWNED — syncing entitlements")
                syncInAppPurchases()
            }
            else -> {
                AppLogger.w(TAG, "onPurchasesUpdated error: ${billingResult.responseCode} ${billingResult.debugMessage}")
            }
        }
    }

    private val connectionListener = object : BillingClientStateListener {
        override fun onBillingSetupFinished(billingResult: BillingResult) {
            Log.d(TAG, "onBillingSetupFinished code=${billingResult.responseCode} msg=${billingResult.debugMessage}")
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                loadProductDetails()
                syncInAppPurchases()
            } else {
                AppLogger.w(TAG, "Billing setup failed: ${billingResult.debugMessage}")
            }
        }

        override fun onBillingServiceDisconnected() {
            Log.d(TAG, "onBillingServiceDisconnected — scheduling reconnect")
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
        val client = BillingClient.newBuilder(application)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()
        billingClient = client
        client.startConnection(connectionListener)
    }

    /** Call from [Activity.onResume] to pick up grants completed outside the app. */
    fun refreshPurchasesIfConnected() {
        val c = billingClient ?: return
        if (c.isReady) {
            loadProductDetails()
            syncInAppPurchases()
        }
    }

    fun launchRemoveAdsPurchase(activity: Activity): Boolean {
        val client = billingClient
        val details = removeAdsProductDetails
        if (client == null || !client.isReady || details == null) {
            AppLogger.w(TAG, "launchRemoveAdsPurchase blocked ready=${client?.isReady} details=$details")
            return false
        }
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val result = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(params))
                .build(),
        )
        return when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> true
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                syncInAppPurchases()
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
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val result = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder()
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

    private fun loadProductDetails() {
        val client = billingClient ?: return
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(BillingProductIds.REMOVE_ADS)
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(BillingProductIds.SUPPORT_ME)
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
        client.queryProductDetailsAsync(params) { billingResult, detailsResult: QueryProductDetailsResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                AppLogger.w(TAG, "queryProductDetails failed: ${billingResult.debugMessage}")
                return@queryProductDetailsAsync
            }
            for (pd in detailsResult.productDetailsList) {
                when (pd.productId) {
                    BillingProductIds.REMOVE_ADS -> removeAdsProductDetails = pd
                    BillingProductIds.SUPPORT_ME -> supportMeProductDetails = pd
                }
                Log.d(TAG, "Product loaded: ${pd.productId}")
            }
            for (unfetched in detailsResult.unfetchedProductList) {
                AppLogger.w(TAG, "Product not fetched: ${unfetched.productId}")
            }
        }
    }

    private fun syncInAppPurchases() {
        val client = billingClient ?: return
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        ) { billingResult, purchases ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                AppLogger.w(TAG, "queryPurchases failed: ${billingResult.debugMessage}")
                return@queryPurchasesAsync
            }
            var removeAdsOwned = false
            for (purchase in purchases) {
                when {
                    purchase.products.contains(BillingProductIds.SUPPORT_ME) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED -> {
                        Log.d(TAG, "Found unconsumed support_me — consuming")
                        consumeSupportMePurchase(purchase)
                    }
                    purchase.products.contains(BillingProductIds.REMOVE_ADS) -> {
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
            Log.d(TAG, "syncInAppPurchases removeAdsOwned=$removeAdsOwned count=${purchases.size}")
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
        val ackParams = AcknowledgePurchaseParams.newBuilder()
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
        val consumeParams = ConsumeParams.newBuilder()
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
