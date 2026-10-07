package com.github.catvod.spider;
import com.github.catvod.crawler.Spider;
import android.content.Context;
import java.util.List;
import java.util.HashMap;
/** Original fixture: metadata only, media URL supplied by the QA harness. */
public final class YunoFixture extends Spider {
    private String media;
    public void init(Context ignored, String extend) { media=extend; }
    private String list() { return "{\"list\":[{\"vod_id\":\"original\",\"vod_name\":\"Yuno Runtime Fixture\",\"vod_play_from\":\"Direct\",\"vod_play_url\":\"Episode$opaque\"}]}"; }
    public String homeContent(boolean filter) { return "{\"class\":[{\"type_id\":\"1\",\"type_name\":\"Original\"}]}"; }
    public String homeVideoContent() { return list(); }
    public String searchContent(String key, boolean quick) { if(key.equals("infinite"))while(true){}; if(key.equals("crash"))throw new IllegalStateException("fixture"); return list(); }
    public String categoryContent(String tid,String pg,boolean filter,HashMap<String,String> extend){return list();}
    public String detailContent(List<String> ids){return list();}
    public String playerContent(String flag,String id,List<String> vipFlags){return "{\"parse\":0,\"url\":\""+media+"\"}";}
}
