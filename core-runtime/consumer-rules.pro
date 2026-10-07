# Binder methods and the narrow CatVod host ABI are externally invoked.
-keep class com.yuno.tv.core.runtime.IRuntime$Stub { *; }
-keep class com.yuno.tv.core.runtime.IRuntimeHost$Stub { *; }
-keep class com.github.catvod.crawler.Spider { public protected *; }
-keep class com.github.catvod.crawler.SpiderDebug { public *; }
-keepattributes InnerClasses,EnclosingMethod
# Rhino 1.7.15 VMBridge and lazy built-ins are looked up by literal class names.
# Retain only the reflection entry points, leaving interpreted code eligible for R8.
-keep,allowoptimization class org.mozilla.javascript.jdk18.VMBridge_jdk18 { public <init>(); protected *; }
-keep,allowoptimization class org.mozilla.javascript.Interpreter { public <init>(); }
-keepnames class org.mozilla.javascript.Context
-keepnames class org.mozilla.javascript.ContextFactory
-keepnames class org.mozilla.javascript.Function
-keepnames class org.mozilla.javascript.ScriptableObject
-keep,allowoptimization class org.mozilla.javascript.regexp.NativeRegExp { public static void init(...); }
-keep,allowoptimization class org.mozilla.javascript.regexp.RegExpImpl { public <init>(); }
-keep,allowoptimization class org.mozilla.javascript.NativeContinuation { public static void init(...); }
-keep,allowoptimization class org.mozilla.javascript.typedarrays.Native* { public static void init(...); }
-dontwarn javax.lang.model.**
-dontwarn javax.tools.**

-keepclassmembers class com.yuno.tv.core.runtime.RuntimeDiagnostic { <fields>; }
-keepclassmembers class com.yuno.tv.core.runtime.ArtifactRepository$Artifact { <fields>; }

# Desktop Java-bean conversion is unreachable: WrapFactory rejects every Java object.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
