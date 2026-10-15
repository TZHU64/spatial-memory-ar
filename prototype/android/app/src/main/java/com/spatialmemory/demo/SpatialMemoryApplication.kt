package com.spatialmemory.demo
import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK
class SpatialMemoryApplication : Application() {
 override fun onCreate() { super.onCreate(); MercurySDK.init(this) }
}
