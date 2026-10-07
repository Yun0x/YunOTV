package com.github.catvod.crawler;
import android.content.Context;
import java.util.List;
import java.util.HashMap;
/** Narrow host ABI documented by CatVod; init receives null in the isolated runtime. */
public class Spider {
    public String siteKey = "";
    public void init(Context context) throws Exception {}
    public void init(Context context, String extend) throws Exception { init(context); }
    public String homeContent(boolean filter) throws Exception { return "{}"; }
    public String homeVideoContent() throws Exception { return "{}"; }
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String,String> extend) throws Exception { return "{}"; }
    public String detailContent(List<String> ids) throws Exception { return "{}"; }
    public String searchContent(String key, boolean quick) throws Exception { return "{}"; }
    public String searchContent(String key, boolean quick, String pg) throws Exception { return searchContent(key, quick); }
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception { return "{}"; }
    public void destroy() {}
    public static Object[] proxyLocal(java.util.Map<String,String> params) throws Exception { return null; }
    public boolean isVideoFormat(String url) { return false; }
    public boolean manualVideoCheck() { return false; }
}
