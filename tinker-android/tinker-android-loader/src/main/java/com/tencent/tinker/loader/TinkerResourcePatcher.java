/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.tinker.loader;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.ArrayMap;
import android.util.Log;

import com.tencent.tinker.loader.shareutil.ShareConstants;
import com.tencent.tinker.loader.shareutil.SharePatchFileUtil;
import com.tencent.tinker.loader.shareutil.ShareReflectUtil;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.KITKAT;
import static com.tencent.tinker.loader.shareutil.ShareReflectUtil.findConstructor;
import static com.tencent.tinker.loader.shareutil.ShareReflectUtil.findField;
import static com.tencent.tinker.loader.shareutil.ShareReflectUtil.findMethod;

/**
 * Created by zhangshaowen on 16/9/21.
 * Thanks for Android Fragmentation
 */
class TinkerResourcePatcher {
    private static final String TAG = "Tinker.ResourcePatcher";
    private static final String TEST_ASSETS_VALUE = "only_use_to_test_tinker_resource.txt";

    // original object
    private static Collection<WeakReference<Resources>> references = null;
    private static Object currentActivityThread = null;
    private static AssetManager newAssetManager = null;

    // method
    private static Method addAssetPathMethod = null;
    private static Method ensureStringBlocksMethod = null;

    // field
    private static Field assetsFiled = null;
    private static Field resourcesImplFiled = null;
    private static Field resDir = null;
    private static Field packagesFiled = null;
    private static Field resourcePackagesFiled = null;
    private static Field publicSourceDirField = null;
    private static Field stringBlocksField = null;

    @SuppressWarnings("unchecked")
    //通过 context 来检查当前环境是否支持加载资源补丁。方法里面做的事就是通过反射来获取各种系统的属性和方法。简单地举例以下几种：
    //ActivityThread : 当前的 ActivityThread 实例，app主线程的入口。利用 ActivityThread 可以获取到 LoadedApk 对象；
    //LoadedApk : 通过 LoadedApk 可以获取 mResDir 属性；
    //mResDir : 这个值很关键，就是资源文件的路径。在后面会被 hook 成资源补丁的路径；
    //addAssetPath : 通过 addAssetPath 方法将资源补丁文件加载进新的 AssetManager 中；
    //mActiveResources : ResourcesManager 的 Resources 容器。里面会存储着每个 apk 对应的 Resources 对象。
    // mActiveResources 是 ArrayMap 类型的，不同的 apk 都有一个不同的 key 来获取对应的 apk 的 Resource 对象；
    //mAssets : 即 Resources 类中的 mAssets 属性，其实就是一个 AssetManager 对象。
    // 在资源打补丁的时候，Resources 中原来的 mAssets 对象会被替换成新的 AssetManager 对象。
    // 这里就不详细讲了，总结起来就一句话：获取 Android 系统中与资源有关的一些属性和方法，为接下来的加载资源补丁做准备。
    // 如果在 isResourceCanPatch 方法中报出异常了，就认为当前环境不能加载资源补丁了。

    // 做完上述的操作之后,如果在过程中没有主动或被动抛异常出来就说明当前的系统环境是可以做资源的更新,
    // 并且将更新资源要用到的Field和Method保存起来方便加载补丁时的使用.
    // https://blog.csdn.net/l2show/article/details/53454933
    public static void isResourceCanPatch(Context context) throws Throwable {
        //   - Replace mResDir to point to the external resource file instead of the .apk. This is
        //     used as the asset path for new Resources objects.
        //   - Set Application#mLoadedApk to the found LoadedApk instance

        // Find the ActivityThread instance for the current thread
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        currentActivityThread = ShareReflectUtil.getActivityThread(context, activityThread);

        // API version 8 has PackageInfo, 10 has LoadedApk. 9, I don't know.
        Class<?> loadedApkClass;
        try {
            loadedApkClass = Class.forName("android.app.LoadedApk");
        } catch (ClassNotFoundException e) {
            loadedApkClass = Class.forName("android.app.ActivityThread$PackageInfo");
        }

        resDir = findField(loadedApkClass, "mResDir");
        packagesFiled = findField(activityThread, "mPackages");
        if (Build.VERSION.SDK_INT < 27) {
            resourcePackagesFiled = findField(activityThread, "mResourcePackages");
        }

        // Create a new AssetManager instance and point it to the resources
        final AssetManager assets = context.getAssets();
        addAssetPathMethod = findMethod(assets, "addAssetPath", String.class);

        // Kitkat needs this method call, Lollipop doesn't. However, it doesn't seem to cause any harm
        // in L, so we do it unconditionally.
        try {
            stringBlocksField = findField(assets, "mStringBlocks");
            ensureStringBlocksMethod = findMethod(assets, "ensureStringBlocks");
        } catch (Throwable ignored) {
            // Ignored.
        }

        // Use class fetched from instance to avoid some ROMs that use customized AssetManager
        // class. (e.g. Baidu OS)
        newAssetManager = (AssetManager) findConstructor(assets).newInstance();

        // Iterate over all known Resources objects
        if (SDK_INT >= KITKAT) {
            //pre-N
            // 在Android SDK >= 24时ResourcesManager持有的Resources容器属性名是mResourceReferences而Android SDK在(24, 19]之间时属性名是mActiveResources要再区分开两种实现.最终拿到所有Resources对象的引用.
            //————————————————
            //版权声明：本文为CSDN博主「Jesse-csdn」的原创文章，遵循 CC 4.0 BY-SA 版权协议，转载请附上原文出处链接及本声明。
            //原文链接：https://blog.csdn.net/l2show/article/details/53454933
            // Find the singleton instance of ResourcesManager
            final Class<?> resourcesManagerClass = Class.forName("android.app.ResourcesManager");
            final Method mGetInstance = findMethod(resourcesManagerClass, "getInstance");
            final Object resourcesManager = mGetInstance.invoke(null);
            try {
                Field fMActiveResources = findField(resourcesManagerClass, "mActiveResources");
                final ArrayMap<?, WeakReference<Resources>> activeResources19 =
                        (ArrayMap<?, WeakReference<Resources>>) fMActiveResources.get(resourcesManager);
                references = activeResources19.values();
            } catch (NoSuchFieldException ignore) {
                // N moved the resources to mResourceReferences
                final Field mResourceReferences = findField(resourcesManagerClass, "mResourceReferences");
                references = (Collection<WeakReference<Resources>>) mResourceReferences.get(resourcesManager);
            }
        } else {
            // 该Android SDK区间内Resources对象集合的引用是在ActivityThread对象的mActiveResources对象持有的.
            // 反射拿到之后也将所有的Resources引用保存起来.
            final Field fMActiveResources = findField(activityThread, "mActiveResources");
            final HashMap<?, WeakReference<Resources>> activeResources7 =
                    (HashMap<?, WeakReference<Resources>>) fMActiveResources.get(currentActivityThread);
            references = activeResources7.values();
        }
        // check resource
        if (references == null) {
            throw new IllegalStateException("resource references is null");
        }

        final Resources resources = context.getResources();

        // fix jianGuo pro has private field 'mAssets' with Resource
        // try use mResourcesImpl first
        if (SDK_INT >= 24) {
            try {
                // N moved the mAssets inside an mResourcesImpl field
                resourcesImplFiled = findField(resources, "mResourcesImpl");
            } catch (Throwable ignore) {
                // for safety
                assetsFiled = findField(resources, "mAssets");
            }
        } else {
            assetsFiled = findField(resources, "mAssets");
        }

        try {
            publicSourceDirField = findField(ApplicationInfo.class, "publicSourceDir");
        } catch (NoSuchFieldException ignore) {
            // Ignored.
        }
    }

    /**
     * @param context
     * @param externalResourceFile
     * @throws Throwable
     */
    public static void monkeyPatchExistingResources(Context context, String externalResourceFile) throws Throwable {
        if (externalResourceFile == null) {
            return;
        }

        final ApplicationInfo appInfo = context.getApplicationInfo();

        final Field[] packagesFields;
        // 准备之前反射好的 packagesFiled 和 resourcePackagesFiled 字段
        // 利用 packagesFiled 和 resourcePackagesFiled 可以获取 LoadedApk 对象
        if (Build.VERSION.SDK_INT < 27) {
            packagesFields = new Field[]{packagesFiled, resourcePackagesFiled};
        } else {
            packagesFields = new Field[]{packagesFiled};
        }
        // 遍历 packagesFields ，获取对应的值
        for (Field field : packagesFields) {
            // 获取 ActivityThread 中 packagesFiled 或 resourcePackagesFiled
            // value 其实为 Map<String, WeakReference<LoadedApk>> 类型
            final Object value = field.get(currentActivityThread);

            // 再对 value 进行遍历，获取 LoadedApk 对象
            for (Map.Entry<String, WeakReference<?>> entry
                    : ((Map<String, WeakReference<?>>) value).entrySet()) {
                final Object loadedApk = entry.getValue().get();
                if (loadedApk == null) {
                    continue;
                }
                // 从 LoadedApk 对象中获取 mResDir 属性
                final String resDirPath = (String) resDir.get(loadedApk);
                if (appInfo.sourceDir.equals(resDirPath)) {
                    // 将 mResDir 的值 hook 成资源补丁 apk 的路径
                    resDir.set(loadedApk, externalResourceFile);
                }
            }
        }

        // Create a new AssetManager instance and point it to the resources installed under sdcard
        // 通过反射调用AssetManager的addAssetPath添加资源路径
        if (((Integer) addAssetPathMethod.invoke(newAssetManager, externalResourceFile)) == 0) {
            throw new IllegalStateException("Could not create new AssetManager");
        }

        // Kitkat needs this method call, Lollipop doesn't. However, it doesn't seem to cause any harm
        // in L, so we do it unconditionally.
        // 创建出 AssetManager 后，调用 ensureStringBlocks 来确保资源的字符串索引创建出来
        // 上面分析源码资源加载流程中在构造Resources的时候Resources会调用一次ensureStringBlocks确保资源的字符串索引创建出来.
        // 所以我们在创建出新的AssetManager之后, 主动调用一次该方法.
        if (stringBlocksField != null && ensureStringBlocksMethod != null) {
            stringBlocksField.set(newAssetManager, null);
            ensureStringBlocksMethod.invoke(newAssetManager);
        }

        for (WeakReference<Resources> wr : references) {
            final Resources resources = wr.get();
            if (resources == null) {
                continue;
            }
            // Set the AssetManager of the Resources instance to our brand new one
            try {
                //pre-N
                // Android N 之前的方案
                // 把原来 resources 的 mAssets 属性替换成新的 AssetManager 对象
                assetsFiled.set(resources, newAssetManager);
            } catch (Throwable ignore) {
                // N
                // Android N 之后， mAssets 属性被放在了 ResourcesImpl 中
                // 所以需要先获取 ResourcesImpl 对象再进行替换
                // 从Android N开始 Resources和AssetManager之间变成了间接引用Resources -> ResourcesImpl -> AssetManager
                final Object resourceImpl = resourcesImplFiled.get(resources);
                // for Huawei HwResourcesImpl
                final Field implAssets = findField(resourceImpl, "mAssets");
                implAssets.set(resourceImpl, newAssetManager);
            }

            // 在 Resource 中会维护一个 mTypedArrayPool 资源池
            // 来减少频繁访问 AssetManager ，所以需要去释放这个资源池，否则取到的都是缓存
            clearPreloadTypedArrayIssue(resources);

                // 最后调用 updateConfiguration 方法来确保资源更新了
            resources.updateConfiguration(resources.getConfiguration(), resources.getDisplayMetrics());
        }

        // Handle issues caused by WebView on Android N.
        // Issue: On Android N, if an activity contains a webview, when screen rotates
        // our resource patch may lost effects.
        // for 5.x/6.x, we found Couldn't expand RemoteView for StatusBarNotification Exception
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                if (publicSourceDirField != null) {
                    publicSourceDirField.set(context.getApplicationInfo(), externalResourceFile);
                }
            } catch (Throwable ignore) {
                // Ignored.
            }
        }

        // 就是来确认一下资源补丁是否已经加载成功了。具体的方法就是在资源补丁Apk的 assets 中有一个 Tinker 的测试资源，
        // 名字叫 only_use_to_test_tinker_resource.txt ，如果可以正确读取到并且没报错的话，就证明资源补丁加载成功了。
        // 否则就抛出异常，会执行 dex 补丁卸载的流程。
        if (!checkResUpdate(context)) {
            throw new TinkerRuntimeException(ShareConstants.CHECK_RES_INSTALL_FAIL);
        }
    }

    /**
     * Why must I do these?
     * Resource has mTypedArrayPool field, which just like Message Poll to reduce gc
     * MiuiResource change TypedArray to MiuiTypedArray, but it get string block from offset instead of assetManager
     */
    private static void clearPreloadTypedArrayIssue(Resources resources) {
        // Perform this trick not only in Miui system since we can't predict if any other
        // manufacturer would do the same modification to Android.
        // if (!isMiuiSystem) {
        //     return;
        // }
        Log.w(TAG, "try to clear typedArray cache!");
        // Clear typedArray cache.
        try {
            final Field typedArrayPoolField = findField(Resources.class, "mTypedArrayPool");
            final Object origTypedArrayPool = typedArrayPoolField.get(resources);
            final Method acquireMethod = findMethod(origTypedArrayPool, "acquire");
            while (true) {
                if (acquireMethod.invoke(origTypedArrayPool) == null) {
                    break;
                }
            }
        } catch (Throwable ignored) {
            Log.e(TAG, "clearPreloadTypedArrayIssue failed, ignore error: " + ignored);
        }
    }

    private static boolean checkResUpdate(Context context) {
        InputStream is = null;
        try {
            is = context.getAssets().open(TEST_ASSETS_VALUE);
        } catch (Throwable e) {
            Log.e(TAG, "checkResUpdate failed, can't find test resource assets file " + TEST_ASSETS_VALUE + " e:" + e.getMessage());
            return false;
        } finally {
            SharePatchFileUtil.closeQuietly(is);
        }
        Log.i(TAG, "checkResUpdate success, found test resource assets file " + TEST_ASSETS_VALUE);
        return true;
    }
}
