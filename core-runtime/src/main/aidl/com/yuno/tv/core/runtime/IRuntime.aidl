package com.yuno.tv.core.runtime;
import android.os.ParcelFileDescriptor;
import com.yuno.tv.core.runtime.IRuntimeHost;
interface IRuntime {
    String execute(in ParcelFileDescriptor artifact, String specification, IRuntimeHost host);
    oneway void abort();
}
