package com.github.catvod.crawler;
/** Plugin messages can contain credentials; only the host emits diagnostic event codes. */
public final class SpiderDebug {
    public static void log(String message) {}
    public static void log(Throwable error) {}
}
