package com.yuno.tv.core.network
import com.yuno.tv.core.model.UnifiedVod
import java.security.MessageDigest
object MediaFingerprint {
    fun of(vod:UnifiedVod):String {
        val title=java.text.Normalizer.normalize(vod.name,java.text.Normalizer.Form.NFKC).trim().lowercase().replace(Regex("\\s+"),"")
        val season=Regex("(?i)(?:第([一二三四五六七八九十0-9]+)季|season\\s*(\\d+)|s(\\d{1,2})(?:e\\d+)?)").find(title)?.value.orEmpty()
        val text=listOf(title,vod.year.trim(),season,vod.type.trim().lowercase(),vod.area.trim()).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
