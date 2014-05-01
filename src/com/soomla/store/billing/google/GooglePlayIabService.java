/*
 * Copyright (C) 2012 Soomla Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soomla.store.billing.google;

import android.app.Activity;
import android.content.Intent;

import com.soomla.store.StoreUtils;
import com.soomla.store.billing.IIabService;
import com.soomla.store.billing.IabCallbacks;
import com.soomla.store.billing.IabException;
import com.soomla.store.billing.IabResult;
import com.soomla.store.billing.IabInventory;
import com.soomla.store.billing.IabPurchase;
import com.soomla.store.billing.IabSkuDetails;

import java.util.ArrayList;
import java.util.List;

public class GooglePlayIabService implements IIabService {


    public void initializeBillingService(final IabCallbacks.IabInitListener iabListener) {

        // Set up helper for the first time, querying and synchronizing inventory
        startIabHelper(new OnIabSetupFinishedListener(iabListener));
    }

    public void stopBillingService(IabCallbacks.IabInitListener iabListener) {
        stopIabHelper(iabListener);
    }

    public void startIabServiceInBg(IabCallbacks.IabInitListener iabListener) {
        keepIabServiceOpen = true;
        startIabHelper(new OnIabSetupFinishedListener(iabListener));
    }

    public void stopIabServiceInBg(IabCallbacks.IabInitListener iabListener) {
        keepIabServiceOpen = false;
        stopIabHelper(iabListener);
    }

    /**
     *  A wrapper to access IabHelper.handleActivityResult from outside
     */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        return isIabServiceInitialized() && mHelper.handleActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void queryInventoryAsync(boolean querySkuDetails,
                                    List<String> moreSkus,
                                    IabCallbacks.OnQueryInventoryListener queryInventoryListener) {

        mHelper.queryInventoryAsync(querySkuDetails, moreSkus, new QueryInventoryFinishedListener(queryInventoryListener));
    }


    @Override
    public boolean isIabServiceInitialized() {
        return mHelper != null;
    }

    @Override
    public void consume(IabPurchase purchase) throws IabException {
        mHelper.consume(purchase);
    }

    @Override
    public void consumeAsync(IabPurchase purchase, final IabCallbacks.OnConsumeListener consumeListener) {
        mHelper.consumeAsync(purchase, new IabHelper.OnConsumeFinishedListener() {
            @Override
            public void onConsumeFinished(IabPurchase purchase, IabResult result) {
                if(result.isSuccess()) {
                    
                    consumeListener.success(purchase);
                } else {
                    
                    consumeListener.fail(result.getMessage());
                }
            }
        });
    }
    
    @Override
    public void launchPurchaseFlow(Activity act,
                                   String sku,
                                   final IabCallbacks.OnPurchaseListener purchaseListener,
                                   String extraData) {
        mHelper.launchPurchaseFlow(act, sku, Consts.RC_REQUEST, new IabHelper.OnIabPurchaseFinishedListener() {
            @Override
            public void onIabPurchaseFinished(IabResult result, IabPurchase purchase) {

                /**
                 * Wait to see if the purchase succeeded, then start the consumption process.
                 */
                StoreUtils.LogDebug(TAG, "IabPurchase finished: " + result + ", purchase: " + purchase);
                if (result.getResponse() == IabResult.BILLING_RESPONSE_RESULT_OK) {

                    purchaseListener.success(purchase);
                } else if (result.getResponse() == IabResult.BILLING_RESPONSE_RESULT_USER_CANCELED) {

                    purchaseListener.cancelled(purchase);
                } else if (result.getResponse() == IabResult.BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED) {

                    purchaseListener.alreadyOwned(purchase);
                } else {

                    purchaseListener.fail(result.getMessage());
                }

                stopIabHelper(null);
            }
        }, extraData);
    }



    /*====================   Private Utility Methods   ====================*/

    /**
     * Create a new IAB helper and set it up.
     *
     * @param onIabSetupFinishedListener is a callback that lets users to add their own implementation for when the Iab is started
     */
    private synchronized void startIabHelper(OnIabSetupFinishedListener onIabSetupFinishedListener) {
        if (isIabServiceInitialized())
        {
            StoreUtils.LogDebug(TAG, "The helper is started. Just running the post start function.");

            if (onIabSetupFinishedListener != null && onIabSetupFinishedListener.getIabInitListener() != null) {
                onIabSetupFinishedListener.getIabInitListener().success(true);
            }
            return;
        }

        StoreUtils.LogDebug(TAG, "Creating IAB helper.");
        mHelper = new IabHelper();

        StoreUtils.LogDebug(TAG, "IAB helper Starting setup.");
        mHelper.startSetup(onIabSetupFinishedListener);
    }

    /**
     * Dispose of the helper to prevent memory leaks
     */
    private synchronized void stopIabHelper(IabCallbacks.IabInitListener iabInitListener) {
        if (keepIabServiceOpen) {
            String msg = "Not stopping Google Service b/c the user run 'startIabServiceInBg'. Keeping it open.";
            if (iabInitListener != null) {
                iabInitListener.fail(msg);
            } else {
                StoreUtils.LogDebug(TAG, msg);
            }
            return;
        }

        if (mHelper == null) {
            String msg = "Tried to stop Google Service when it was null.";
            if (iabInitListener != null) {
                iabInitListener.fail(msg);
            } else {
                StoreUtils.LogDebug(TAG, msg);
            }
            return;
        }

        if (!mHelper.isAsyncInProgress())
        {
            StoreUtils.LogDebug(TAG, "Stopping Google Service");
            mHelper.dispose();
            mHelper = null;
            if (iabInitListener != null) {
                iabInitListener.success(true);
            }
        }
        else
        {
            String msg = "Cannot stop Google Service during async process. Will be stopped when async operation is finished.";
            if (iabInitListener != null) {
                iabInitListener.fail(msg);
            } else {
                StoreUtils.LogDebug(TAG, msg);
            }
        }
    }


    /**
     * Handle incomplete purchase and refund after initialization
     */
    private class QueryInventoryFinishedListener implements IabHelper.QueryInventoryFinishedListener {


        private IabCallbacks.OnQueryInventoryListener mQueryInventoryListener;

        public QueryInventoryFinishedListener(IabCallbacks.OnQueryInventoryListener queryInventoryListener) {
            this.mQueryInventoryListener            = queryInventoryListener;
        }

        public void onQueryInventoryFinished(IabResult result, IabInventory inventory) {
            StoreUtils.LogDebug(TAG, "Query inventory succeeded");
            if (result.getResponse() == IabResult.BILLING_RESPONSE_RESULT_OK && mQueryInventoryListener != null) {
                // fetching owned items
                List<String> itemSkus = inventory.getAllOwnedSkus(IabHelper.ITEM_TYPE_INAPP);
                List<IabPurchase> purchases = new ArrayList<IabPurchase>();
                for (String sku : itemSkus) {
                    IabPurchase purchase = inventory.getPurchase(sku);
                    purchases.add(purchase);
                }

                // fetching sku details for ALL product ids
                // @lassic (May 1st): actually, HERE it only makes sense to get the details
                // of the SKUs we already queried for. This also enables a weaker dependency
                // from this module back to Soomla APIs (StoreInfo)
                List<String> skuList = inventory.getAllQueriedSkus(false);
                List<IabSkuDetails> skuDetails = new ArrayList<IabSkuDetails>();
                for (String sku : skuList) {
                    IabSkuDetails skuDetail = inventory.getSkuDetails(sku);
                    if (skuDetail != null) {
                        skuDetails.add(skuDetail);
                    }
                }

                this.mQueryInventoryListener.success(purchases, skuDetails);
            } else {
                StoreUtils.LogError(TAG, "Wither mQueryInventoryListener==null OR Query inventory error: " + result.getMessage());
                if (this.mQueryInventoryListener != null) this.mQueryInventoryListener.fail(result.getMessage());
            }

            stopIabHelper(null);
        }
    }


    private class OnIabSetupFinishedListener implements IabHelper.OnIabSetupFinishedListener {

        private IabCallbacks.IabInitListener mIabInitListener;

        public IabCallbacks.IabInitListener getIabInitListener() {
            return mIabInitListener;
        }

        public OnIabSetupFinishedListener(IabCallbacks.IabInitListener iabListener) {
            this.mIabInitListener = iabListener;
        }

        @Override
        public void onIabSetupFinished(IabResult result) {

            StoreUtils.LogDebug(TAG, "IAB helper Setup finished.");
            if (result.isFailure()) {
                if (mIabInitListener != null) mIabInitListener.fail(result.getMessage());
                return;
            }
            if (mIabInitListener != null) mIabInitListener.success(false);
        }
    }

    /* Private Members */
    private static final String TAG = "SOOMLA GooglePlayIabService";
    private IabHelper mHelper;
    private boolean keepIabServiceOpen = false;
}
