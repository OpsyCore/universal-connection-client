package io.ucc.app.ads

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds

/**
 * DEBUG variant: Google Mobile Ads with Google's public **test** IDs, for previewing banner placement on a
 * device. Test ads only — they earn nothing and must never ship; the release source set replaces this object
 * with a no-op and the SDK is `debugImplementation`, so release artifacts contain no advertising code.
 *
 * Known interaction: the "Block ads" routing switch rejects ad domains, so with it on (and the tunnel up)
 * test banners will not load. That is the feature working, not a bug.
 */
object Ads {
    const val ENABLED: Boolean = true
    private const val TEST_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

    fun init(context: Context) {
        MobileAds.initialize(context.applicationContext) {}
    }

    @Composable
    fun Banner(modifier: Modifier = Modifier) {
        AndroidView(
            modifier = modifier.fillMaxWidth(),
            factory = { ctx ->
                AdView(ctx).apply {
                    setAdSize(AdSize.BANNER)
                    adUnitId = TEST_BANNER_UNIT_ID
                    loadAd(AdRequest.Builder().build())
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
