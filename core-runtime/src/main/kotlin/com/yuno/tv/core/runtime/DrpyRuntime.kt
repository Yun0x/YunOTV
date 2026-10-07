package com.yuno.tv.core.runtime

import com.google.gson.*

/** Executes the actual DRPY exported API, rather than translating rule fields into a fabricated protocol. */
class DrpyRuntime(private val host: RuntimeHost) : AutoCloseable {
    private var engine: JsEngine? = null
    private var version = ""
    fun execute(bundle: JsonObject, operation: String, arguments: JsonArray, timeoutMs: Long): String {
        val identity = RuntimePolicy.hash(bundle.toString().toByteArray())
        if (identity != version) {
            close(); engine = JsEngine(DrpyBridge(host), timeoutMs)
            val js = engine!!
            js.evaluate(BOOTSTRAP)
            bundle.obj("modules").entrySet().forEach { (url, body) ->
                val name = url.substringAfterLast('/').substringBefore('?')
                if (name == "模板.js") js.evaluate(module(body.asString, "模板"))
            }
            val source = bundle.text("engine")
            if (source.trimStart().startsWith("<")) throw RuntimeFailure("INVALID_SCRIPT")
            js.evaluate(module(source, "__drpy"))
            val rule = bundle.text("rule")
            if (rule.isNotBlank()) js.evaluate("__drpy.init(${Gson().toJson(rule)});")
            version = identity
        }
        val js = engine ?: throw RuntimeFailure("INVALID_SCRIPT")
        js.resetDeadline(timeoutMs)
        if(operation=="inspect")return js.evaluate("JSON.stringify({initialized:true});")
        require(operation in setOf("home", "homeVod", "category", "search", "detail", "play"))
        val result = js.evaluate("JSON.stringify(__drpy.$operation.apply(__drpy,${arguments}));")
        RuntimePolicy.requireSize(result.toByteArray(), RuntimePolicy.RESULT_BYTES)
        return if (result.startsWith('"')) JsonParser.parseString(result).asString else result
    }
    private fun module(source: String, name: String): String {
        // Only explicit, host-provided helper modules can be imported. No Java/npm/file resolver exists.
        val withoutImports = source.replace(Regex("(?m)\\bimport\\s*(?:[^;\"']*?\\bfrom\\s*)?[\"'][^\"']+[\"']\\s*;?"), "")
        val exports = Regex("export\\s+default\\b\\s*")
        return if (exports.containsMatchIn(withoutImports)) withoutImports.replace(exports, "var $name = ")
        else "$withoutImports\nvar $name = typeof DRPY === 'function' ? DRPY() : {home:home,homeVod:homeVod,category:category,search:search,detail:detail,play:play,init:init};"
    }
    override fun close() { engine?.close(); engine = null; version = "" }
    companion object {
        // All facade values are JavaScript primitives and objects; logs intentionally discard plugin content.
        val BOOTSTRAP = """
            var console={log:function(){},error:function(){},warn:function(){}}; var print=function(){}; var log=print;
            function req(u,o){return JSON.parse(__bridge('http',String(u),JSON.stringify(o||{})));}
            function fetch(u,o){return req(u,o).content;} function post(u,o){o=o||{};o.method='POST';return fetch(u,o);}
            function getItem(k,d){var v=__bridge('get',String(k),'');return v||d||'';}
            function setItem(k,v){return __bridge('set',String(k),String(v));} function clearItem(k){return __bridge('remove',String(k),'');}
            var local={get:function(s,k){return getItem(s+':'+k);},set:function(s,k,v){return setItem(s+':'+k,v);},delete:function(s,k){return clearItem(s+':'+k);}};
            function base64Encode(s){return __bridge('base64Encode',String(s));} function base64Decode(s){return __bridge('base64Decode',String(s));}
            function md5(s){return __bridge('MD5',String(s));} function urljoin(u,p){return __bridge('urljoin',String(u),String(p));}
            function pdfh(h,s){return __bridge('pdfh',String(h),String(s));} function pdfa(h,s){return JSON.parse(__bridge('pdfa',String(h),String(s)));}
            function pd(h,s,u){return __bridge('pd',String(h),String(s),String(u||''));}
            var gbkTool={encode:function(s){throw Error("UNSUPPORTED_ENCODING");},decode:function(s){throw Error("UNSUPPORTED_ENCODING");}};
            var JSON5={parse:JSON.parse,stringify:JSON.stringify};var 模板={getMubans:function(){return {};}};
            function word(s){return {toString:function(){return String(s);}};}
            var CryptoJS={MD5:function(s){return word(md5(s));},SHA1:function(s){return word(__bridge('SHA-1',String(s)));},SHA256:function(s){return word(__bridge('SHA-256',String(s)));},enc:{Utf8:{parse:word},Base64:{parse:function(s){return word(base64Decode(s));},stringify:function(s){return base64Encode(String(s));}},Hex:{}},mode:{CBC:'CBC',ECB:'ECB'},pad:{Pkcs7:'Pkcs7'},AES:{encrypt:function(v,k,o){return word(__bridge('aes',String(v),String(k),String(o&&o.iv||''),'encrypt',String(o&&o.mode||'CBC')));},decrypt:function(v,k,o){return word(__bridge('aes',String(v),String(k),String(o&&o.iv||''),'decrypt',String(o&&o.mode||'CBC')));}}};
            function dom(h){var item={ele:h,rr:function(){return item;},toString:function(){return h;},text:function(){return pdfh(h,'&&Text');},html:function(){return pdfh(h,'&&Html');},attr:function(a){return pdfh(h,'&&'+a);},find:function(s){return selection(pdfa(h,s));},eq:function(n){return n===0?item:dom('');},first:function(){return item;}};return item;}
            function selection(items){var a={length:items.length,toArray:function(){return items;},each:function(fn){items.forEach(function(h,i){fn.call(dom(h),i,h);});return a;},eq:function(n){return dom(items[n]||'');},first:function(){return a.eq(0);},find:function(s){return selection(items.reduce(function(r,h){return r.concat(pdfa(h,s));},[]));},text:function(){return items.map(function(h){return dom(h).text();}).join('');},html:function(){return a.first().html();},attr:function(k){return a.first().attr(k);}};items.forEach(function(h,i){a[i]=h;});return a;}
            var cheerio={load:function(h){var f=function(s){return typeof s==='string'?selection(pdfa(h,s)):dom(String(s));};f.html=function(n){return n?String(n):h;};f.root=function(){return dom(h);};return f;}};
            var jinja={render:function(s,o){return s.replace(/\{\{\s*(\w+)\s*\}\}/g,function(m,k){return o[k]===undefined?'':o[k];});}};cheerio.jinja2=jinja;
        """.trimIndent()
    }
}
