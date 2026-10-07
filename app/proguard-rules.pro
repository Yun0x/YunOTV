# Room and Retrofit ship consumer rules. Unified models are persisted with Gson.
-keep class com.yuno.tv.core.model.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

-keep class com.yuno.tv.core.network.HomeContent { *; }
-keep class com.yuno.tv.core.network.MediaSection { *; }
