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

package com.tencent.tinker.loader;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;

import com.tencent.tinker.loader.app.TinkerApplication;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import dalvik.system.DelegateLastClassLoader;

/**
 * Created by tangyinsheng on 2019-10-31.
 */
final class NewClassLoaderInjector {
    private static final String TAG = "tinker.NewClassLoaderI";
    public static ClassLoader inject(Application app, ClassLoader oldClassLoader, List<File> patchedDexes) throws Throwable {
        Log.d(TAG, "inject() called with: app = [" + app + "], oldClassLoader = [" + oldClassLoader + "], patchedDexes = [" + patchedDexes + "]");
        final String[] patchedDexPaths = new String[patchedDexes.size()];
        for (int i = 0; i < patchedDexPaths.length; ++i) {
            patchedDexPaths[i] = patchedDexes.get(i).getAbsolutePath();
        }
        final ClassLoader newClassLoader = createNewClassLoader(app, oldClassLoader, patchedDexPaths);
        doInject(app, newClassLoader);
        return newClassLoader;
    }

    // Q&A  为什么是trigger？ 如果dex文件没有优化安装，makePathElements会触发dex2oat.
    public static void triggerDex2Oat(Context context, String... dexPaths) throws Throwable {
        Log.d(TAG, "triggerDex2Oat() called with: context = [" + context + "], dexPaths = [" + dexPaths + "]");
        // Suggestion from Huawei: Only PathClassLoader (Perhaps other ClassLoaders known by system
        // like DexClassLoader also works ?) can be used here to trigger dex2oat so that JIT
        // mechanism can participate in runtime Dex optimization.
        final ClassLoader appClassLoader = TinkerApplication.class.getClassLoader();
        final ClassLoader triggerClassLoader = createNewClassLoader(context, appClassLoader, dexPaths);
    }

    @SuppressWarnings("unchecked")
    private static ClassLoader createNewClassLoader(Context context, ClassLoader oldClassLoader,
                                                    String... patchDexPaths) throws Throwable {
        Log.d(TAG, "createNewClassLoader() called with: context = [" + context + "], oldClassLoader = [" + oldClassLoader + "], patchDexPaths = [" + patchDexPaths + "]");
        final Field pathListField = findField(
                Class.forName("dalvik.system.BaseDexClassLoader", false, oldClassLoader),
                "pathList");
        final Object oldPathList = pathListField.get(oldClassLoader);

        final StringBuilder dexPathBuilder = new StringBuilder();
        final boolean hasPatchDexPaths = patchDexPaths != null && patchDexPaths.length > 0;
        if (hasPatchDexPaths) {
            for (int i = 0; i < patchDexPaths.length; ++i) {
                if (i > 0) {
                    dexPathBuilder.append(File.pathSeparator);
                }
                dexPathBuilder.append(patchDexPaths[i]);
            }
        }

        final String combinedDexPath = dexPathBuilder.toString();

        // //这部分就是 libs 加载路径了，默认有 /vendor/lib  system/lib  data/app-lib/packageName
        final Field nativeLibraryDirectoriesField = findField(oldPathList.getClass(), "nativeLibraryDirectories");
        List<File> oldNativeLibraryDirectories = null;
        if (nativeLibraryDirectoriesField.getType().isArray()) {
            oldNativeLibraryDirectories = Arrays.asList((File[]) nativeLibraryDirectoriesField.get(oldPathList));
        } else {
            oldNativeLibraryDirectories = (List<File>) nativeLibraryDirectoriesField.get(oldPathList);
        }
        final StringBuilder libraryPathBuilder = new StringBuilder();
        boolean isFirstItem = true;
        for (File libDir : oldNativeLibraryDirectories) {
            Log.e(TAG, "createNewClassLoader: libDir:"+libDir);
            if (libDir == null) {
                continue;
            }
            if (isFirstItem) {
                isFirstItem = false;
            } else {
                libraryPathBuilder.append(File.pathSeparator);
            }
            libraryPathBuilder.append(libDir.getAbsolutePath());
        }

        final String combinedLibraryPath = libraryPathBuilder.toString();
        Log.e(TAG, "createNewClassLoader: combinedLibraryPath:"+combinedLibraryPath);

        ClassLoader result = null;
        if (Build.VERSION.SDK_INT >= 28) {
            // DelegateLastClassLoader继承自PathClassLoader是API27新增的类加载器，
            // DelegateLastClassLoader实行最后的查找策略。使用DelegateLastClassLoader来加载每个类和资源，使用的是以下顺序：
            //1   判断是否已经加载过该类
            //2   判断此类是否被BootClassLoader加载过
            //3.  搜索此类是否被当前类加载器是已经加载过
            //4.  搜索与此类加载器相关联的dexPath文件列表，并委托给父加载器。

            result = new DelegateLastClassLoader(combinedDexPath, combinedLibraryPath, null);
        } else {
            result = new TinkerDelegateLastClassLoader(combinedDexPath, combinedLibraryPath, null);
        }

        findField(ClassLoader.class, "parent").set(result, oldClassLoader);
        findField(oldPathList.getClass(), "definingContext").set(oldPathList, result);

        return result;
    }

    // 在全局Context中持有的LoadedApk的对象mPackageInfo的属性中,有一个ClassLoader类的对象mClassLoader.
    // 层层反射将mClassLoader的引用替换为上面创建出来的AndroidNClassLoader对象.
    // 同时将Thread中持有的ClassLoader也同步替换为AndroidNClassLoader.
    // 至此PathClassLoader的修改和替换都已经完成了,接下来就可以正常得加载补丁dex了.
    // android N 替换为自己的classloader,而引用原来classloader的地方有loadedApk,resource,mDrawableInflater这三个地方。所以都需要反射替换。
    private static void doInject(Application app, ClassLoader classLoader) throws Throwable {
        Log.d(TAG, "doInject() called with: app = [" + app + "], classLoader = [" + classLoader + "]");
        Thread.currentThread().setContextClassLoader(classLoader);

        final Context baseContext = (Context) findField(app.getClass(), "mBase").get(app);
        final Object basePackageInfo = findField(baseContext.getClass(), "mPackageInfo").get(baseContext);
        findField(basePackageInfo.getClass(), "mClassLoader").set(basePackageInfo, classLoader);

        if (Build.VERSION.SDK_INT < 27) {
            final Resources res = app.getResources();
            try {
                findField(res.getClass(), "mClassLoader").set(res, classLoader);

                final Object drawableInflater = findField(res.getClass(), "mDrawableInflater").get(res);
                if (drawableInflater != null) {
                    findField(drawableInflater.getClass(), "mClassLoader").set(drawableInflater, classLoader);
                }
            } catch (Throwable ignored) {
                // Ignored.
            }
        }
    }

    private static Field findField(Class<?> clazz, String name) throws Throwable {
        Class<?> currClazz = clazz;
        while (true) {
            try {
                final Field result = currClazz.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (Throwable ignored) {
                if (currClazz == Object.class) {
                    throw new NoSuchFieldException("Cannot find field "
                            + name + " in class " + clazz.getName() + " and its super classes.");
                } else {
                    currClazz = currClazz.getSuperclass();
                }
            }
        }
    }

    private NewClassLoaderInjector() {
        throw new UnsupportedOperationException();
    }
}