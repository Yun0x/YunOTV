package com.yuno.tv

import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Raw Binder client uses SDK types only, so it also tests the actual R8-optimized release APK. */
@RunWith(AndroidJUnit4::class)
class RuntimeReleaseSmokeTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val connections=mutableListOf<ServiceConnection>()
    private fun call(service:String,file:java.io.File,operation:String):String {
        val ready=CountDownLatch(1);var endpoint:IBinder?=null
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder){endpoint=binder;ready.countDown()}
            override fun onServiceDisconnected(name:ComponentName){}
        }
        assertTrue(context.bindService(Intent().setComponent(ComponentName(context.packageName,"com.yuno.tv.core.runtime.$service")),connection,Context.BIND_AUTO_CREATE))
        try {
            assertTrue(ready.await(8,TimeUnit.SECONDS))
            val host=object:Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                if(code==INTERFACE_TRANSACTION){reply?.writeString("com.yuno.tv.core.runtime.IRuntimeHost");return true}
                data.enforceInterface("com.yuno.tv.core.runtime.IRuntimeHost");reply?.writeNoException();reply?.writeString(if(code==1)"{\"code\":200,\"content\":\"\",\"headers\":{}}" else "");return true
            }}
            val spec=JSONObject().put("sourceId","release-fixture").put("api","csp_YunoFixture").put("ext","http://10.0.2.2:18765/clip.mp4").put("operation",operation).put("args",JSONArray().put("legal").put("opaque"))
            val request=Parcel.obtain();val reply=Parcel.obtain()
            try {
                request.writeInterfaceToken("com.yuno.tv.core.runtime.IRuntime")
                ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> request.writeInt(1);descriptor.writeToParcel(request,0);request.writeString(spec.toString());request.writeStrongBinder(host);assertTrue(endpoint!!.transact(IBinder.FIRST_CALL_TRANSACTION,request,reply,0)) }
                reply.readException();val result=JSONObject(reply.readString()!!);assertFalse(result.toString(),result.has("error"));return result.getString("result")
            }finally{request.recycle();reply.recycle()}
        }finally{connections+=connection}
    }
    @Test fun releaseRhinoAndDexExecuteWithR8() {
        val directory=context.filesDir.resolve("qa").apply{mkdirs()}
        val js=directory.resolve("release-script.json").apply{writeText(JSONObject().put("engine","export default{home:function(){return {list:[{vod_id:'1',vod_name:'Original Release'}]};}};").put("rule","").toString())}
        try {
            assertTrue(call("DrpyRuntimeService",js,"home").contains("Original Release"))
            assertTrue(call("SpiderRuntimeService",directory.resolve("fixture.jar"),"home").contains("Original"))
            val hold=InstrumentationRegistry.getArguments().getString("holdMs")?.toLongOrNull()?.coerceIn(0,20_000) ?: 0
            Thread.sleep(hold)
        }finally{connections.forEach { context.unbindService(it) };connections.clear()}
    }
}
