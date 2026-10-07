package com.yuno.tv.core.runtime;
interface IRuntimeHost {
    String http(String url, String options);
    String storage(String operation, String key, String value);
}
