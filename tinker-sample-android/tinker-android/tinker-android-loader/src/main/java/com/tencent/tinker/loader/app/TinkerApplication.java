/*
 * Tencent is pleased to support the open source community by making Tinker available.
 *
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.tinker.loader.app;

import android.annotation.TargetApi;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.tencent.tinker.loader.TinkerLoader;
import com.tencent.tinker.loader.TinkerRuntimeException;
import com.tencent.tinker.loader.TinkerUncaughtHandler;
import com.tencent.tinker.loader.shareutil.ShareConstants;
import com.tencent.tinker.loader.shareutil.ShareIntentUtil;
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Created by zhangshaowen on 16/3/8.
 */
public abstract class TinkerApplication extends Application {
    private static final String INTENT_PATCH_EXCEPTION = ShareIntentUtil.INTENT_PATCH_EXCEPTION;
    private static final String TINKER_LOADER_METHOD = "tryLoad";

    /**
     * tinkerFlags, which types is supported
     * dex only, library only, all support
     * default: TINKER_ENABLE_ALL
     */
    private final int tinkerFlags;

    /**
     * whether verify md5 when we load dex or lib
     * they store at data/data/package, and we had verity them at the :patch process.
     * so we don't have to verity them every time for quicker!
     * default:false
     */
    // Q&A 1   :patch process 如何检查的md5, 如果检查没问题，为何还要单独加参数做成可以配置的？
    private final boolean tinkerLoadVerifyFlag;
    private final String delegateClassName;
    private final String loaderClassName;

    /**
     * if we have load patch, we should use safe mode
     */
    private boolean useSafeMode;
    private Intent tinkerResultIntent;

    private ClassLoader mCurrentClassLoader = null;
    private Handler mInlineFence = null;

    // Q&A 2 代理模式？  不改造原有application，直接加注解？
    protected TinkerApplication(int tinkerFlags) {
        this(tinkerFlags, "com.tencent.tinker.entry.DefaultApplicationLike",
                TinkerLoader.class.getName(), false);
    }

    protected TinkerApplication(int tinkerFlags, String delegateClassName,
                                String loaderClassName, boolean tinkerLoadVerifyFlag) {
        this.tinkerFlags = tinkerFlags;
        this.delegateClassName = delegateClassName;
        this.loaderClassName = loaderClassName;
        this.tinkerLoadVerifyFlag = tinkerLoadVerifyFlag;
    }

    protected TinkerApplication(int tinkerFlags, String delegateClassName) {
        this(tinkerFlags, delegateClassName, TinkerLoader.class.getName(), false);
    }

    private void loadTinker() {
        try {
            Log.e("tinker test ", "loadTinker: ");
            //reflect tinker loader, because loaderClass may be define by user!
            Class<?> tinkerLoadClass = Class.forName(loaderClassName, false, TinkerApplication.class.getClassLoader());
            Method loadMethod = tinkerLoadClass.getMethod(TINKER_LOADER_METHOD, TinkerApplication.class);
            Constructor<?> constructor = tinkerLoadClass.getConstructor();
            tinkerResultIntent = (Intent) loadMethod.invoke(constructor.newInstance(), this);
        } catch (Throwable e) {
            //has exception, put exception error code
            tinkerResultIntent = new Intent();
            ShareIntentUtil.setIntentReturnCode(tinkerResultIntent, ShareConstants.ERROR_LOAD_PATCH_UNKNOWN_EXCEPTION);
            tinkerResultIntent.putExtra(INTENT_PATCH_EXCEPTION, e);
        }
    }

    private Handler createInlineFence(Application app,
                                      int tinkerFlags,
                                      String delegateClassName,
                                      boolean tinkerLoadVerifyFlag,
                                      long applicationStartElapsedTime,
                                      long applicationStartMillisTime,
                                      Intent resultIntent) {
        try {
            // Q&A 4 看注释意思是：通过反射创建代理，所以不需要访问primary dex。那么问题来来，如果不需要去primary dex，那是在哪个dex中？
            // 如果不用反射，还可以使用什么办法？
            // 避免出现Class ref in pre-verified class问题，通过反射将tinker和applicationlike类解耦开。反射最直接的目的也是为了隔离开这两个类
            // 通过反射，将Tinker组建和App隔离开，并且先后顺序是先Tinker后App，这样可以防止App中的代码提前加载，确保App中所有的代码都可以具有被热修复的能力包括ApplicationLike。
            // Use reflection to create the delegate so it doesn't need to go into the primary dex.
            // And we can also patch it
            final Class<?> delegateClass = Class.forName(delegateClassName, false, mCurrentClassLoader);
            final Constructor<?> constructor = delegateClass.getConstructor(Application.class, int.class, boolean.class,
                    long.class, long.class, Intent.class);
            final Object appLike = constructor.newInstance(app, tinkerFlags, tinkerLoadVerifyFlag,
                    applicationStartElapsedTime, applicationStartMillisTime, resultIntent);
            // Q&A 5 此处为何通过反射的方式创建，此时知道完全限定名。
            final Class<?> inlineFenceClass = Class.forName(
                    "com.tencent.tinker.entry.TinkerApplicationInlineFence", false, mCurrentClassLoader);
            final Class<?> appLikeClass = Class.forName(
                    "com.tencent.tinker.entry.ApplicationLike", false, mCurrentClassLoader);
            final Constructor<?> inlineFenceCtor = inlineFenceClass.getConstructor(appLikeClass);
            inlineFenceCtor.setAccessible(true);
            // Q&A 4   内联？？？？
            return (Handler) inlineFenceCtor.newInstance(appLike);
        } catch (Throwable thr) {
            thr.printStackTrace();
            throw new TinkerRuntimeException("createInlineFence failed", thr);
        }
    }

    private void onBaseContextAttached(Context base) {
        Log.d("tinker test", "onBaseContextAttached() called with: base = [" + base + "]");
        try {
            final long applicationStartElapsedTime = SystemClock.elapsedRealtime();
            final long applicationStartMillisTime = System.currentTimeMillis();
            loadTinker();
            mCurrentClassLoader = base.getClassLoader();
            mInlineFence = createInlineFence(this, tinkerFlags, delegateClassName,
                    tinkerLoadVerifyFlag, applicationStartElapsedTime, applicationStartMillisTime,
                    tinkerResultIntent);
            TinkerInlineFenceAction.callOnBaseContextAttached(mInlineFence, base);
            //reset save mode
            // Q&A 3 安全模式如何生效   不是影响下一次启动，useSafeMode这个变量存在于内存中
            if (useSafeMode) {
                ShareTinkerInternals.setSafeModeCount(this, 0);
            }
        } catch (TinkerRuntimeException e) {
            throw e;
        } catch (Throwable thr) {
            throw new TinkerRuntimeException(thr.getMessage(), thr);
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Log.d("tinker test", "attachBaseContext() called with: base = [" + base + "]");
        Thread.setDefaultUncaughtExceptionHandler(new TinkerUncaughtHandler(this));
        onBaseContextAttached(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (mInlineFence == null) {
            return;
        }
        TinkerInlineFenceAction.callOnCreate(mInlineFence);
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        if (mInlineFence == null) {
            return;
        }
        TinkerInlineFenceAction.callOnTerminate(mInlineFence);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mInlineFence == null) {
            return;
        }
        TinkerInlineFenceAction.callOnLowMemory(mInlineFence);
    }

    @TargetApi(14)
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (mInlineFence == null) {
            return;
        }
        TinkerInlineFenceAction.callOnTrimMemory(mInlineFence, level);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mInlineFence == null) {
            return;
        }
        TinkerInlineFenceAction.callOnConfigurationChanged(mInlineFence, newConfig);
    }

    @Override
    public Resources getResources() {
        final Resources resources = super.getResources();
        if (mInlineFence == null) {
            return resources;
        }
        return TinkerInlineFenceAction.callGetResources(mInlineFence, resources);
    }

    @Override
    public ClassLoader getClassLoader() {
        final ClassLoader classLoader = super.getClassLoader();
        if (mInlineFence == null) {
            return classLoader;
        }
        return TinkerInlineFenceAction.callGetClassLoader(mInlineFence, classLoader);
    }

    @Override
    public AssetManager getAssets() {
        final AssetManager assets = super.getAssets();
        if (mInlineFence == null) {
            return assets;
        }
        return TinkerInlineFenceAction.callGetAssets(mInlineFence, assets);
    }

    @Override
    public Object getSystemService(String name) {
        final Object service = super.getSystemService(name);
        if (mInlineFence == null) {
            return service;
        }
        return TinkerInlineFenceAction.callGetSystemService(mInlineFence, name, service);
    }

    @Override
    public Context getBaseContext() {
        final Context base = super.getBaseContext();
        if (mInlineFence == null) {
            return base;
        }
        return TinkerInlineFenceAction.callGetBaseContext(mInlineFence, base);
    }

    public void setUseSafeMode(boolean useSafeMode) {
        this.useSafeMode = useSafeMode;
    }

    public boolean isTinkerLoadVerifyFlag() {
        return tinkerLoadVerifyFlag;
    }

    public int getTinkerFlags() {
        return tinkerFlags;
    }
}
