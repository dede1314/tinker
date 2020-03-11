/*
 * Copyright (C) 2016 THL A29 Limited, a Tencent company.
 * Copyright (C) 2013 The Android Open Source Project
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

import android.annotation.SuppressLint;
import android.app.Application;
import android.os.Build;
import android.util.Log;

import com.tencent.tinker.loader.shareutil.ShareConstants;
import com.tencent.tinker.loader.shareutil.SharePatchFileUtil;
import com.tencent.tinker.loader.shareutil.ShareReflectUtil;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.zip.ZipFile;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;

/**
 * Created by zhangshaowen on 16/3/18.
 */
public class SystemClassLoaderAdder {
    public static final String CHECK_DEX_CLASS = "com.tencent.tinker.loader.TinkerTestDexLoad";
    public static final String CHECK_DEX_FIELD = "isPatch";
    private static final String TAG = "Tinker.ClassLoaderAdder";
    private static int sPatchDexCount = 0;

    @SuppressLint("NewApi")
    // 一个ClassLoader可以包含多个dex文件，每个dex文件是一个Element，多个dex文件排列成一个有序的数组dexElements，
    // 当找类的时候，会按顺序遍历dex文件，然后从当前遍历的dex文件中找类，如果找类则返回，如果找不到从下一个dex文件继续查找。(来自：安卓App热补丁动态修复技术介绍)

    // install的做法就是，先获取BaseDexClassLoader的dexPathList对象，然后通过dexPathList的makeDexElements函数将我们要安装的dex转化成Element[]对象，
    // 最后将其和dexPathList的dexElements对象进行合并，就是新的Element[]对象，因为我们添加的dex都被放在dexElement数组的最前面，
    // 所以当通过findClass来查找这个类时，就是使用的我们最新的dex里面的类。
    public static void installDexes(Application application, BaseDexClassLoader loader, File dexOptDir, List<File> files, boolean isProtectedApp)
        throws Throwable {
        Log.i(TAG, "installDexes dexOptDir: " + dexOptDir.getAbsolutePath() + ", dex size:" + files.size());

        if (!files.isEmpty()) {
            files = createSortedAdditionalPathEntries(files);
            ClassLoader classLoader = loader;
            if (Build.VERSION.SDK_INT >= 24 && !isProtectedApp) {
                classLoader = NewClassLoaderInjector.inject(application, loader, files);
            } else {
                //because in dalvik, if inner class is not the same classloader with it wrapper class.
                //it won't fail at dex2opt
                if (Build.VERSION.SDK_INT >= 23) {
                    V23.install(classLoader, files, dexOptDir);
                } else if (Build.VERSION.SDK_INT >= 19) {
                    V19.install(classLoader, files, dexOptDir);
                } else if (Build.VERSION.SDK_INT >= 14) {
                    V14.install(classLoader, files, dexOptDir);
                } else {
                    V4.install(classLoader, files, dexOptDir);
                }
            }
            //install done
            sPatchDexCount = files.size();
            Log.i(TAG, "after loaded classloader: " + classLoader + ", dex size:" + sPatchDexCount);

            // Q&A ：对patch加载是否成功进行判断，怎么变成true的
            // 如何查看特定文件是否打包进去apk
            // 在没有补丁加载的情况下都是返回 false 的, 在补丁中修改 isPatch 属性为 true 。
            // 所以只要反射拿到isPatch 的属性为 true 就说明补丁已经成功加载进来了。否则就调用 SystemClassLoaderAdder.uninstallPatchDex 执行卸载
            if (!checkDexInstall(classLoader)) {
                //reset patch dex
                SystemClassLoaderAdder.uninstallPatchDex(classLoader);
                throw new TinkerRuntimeException(ShareConstants.CHECK_DEX_INSTALL_FAIL);
            }
        }
    }

    @SuppressLint("NewApi")
    public static void installApk(PathClassLoader loader, List<File> files) throws Throwable {
        if (!files.isEmpty()) {
            files = createSortedAdditionalPathEntries(files);
            ClassLoader classLoader = loader;
            ArkHot.install(classLoader, files);
            sPatchDexCount = files.size();
            Log.i(TAG, "after loaded classloader: " + classLoader + ", dex size:" + sPatchDexCount);

            if (!checkDexInstall(classLoader)) {
                // reset patch dex
//                SystemClassLoaderAdder.uninstallPatchDex(classLoader);
//                throw new TinkerRuntimeException(ShareConstants.CHECK_DEX_INSTALL_FAIL);
            }
        }
    }

    // 卸载补丁可以说是加载补丁的逆向操作，具体操作可以分成 v4 和 v14 两个版本
    // 具体的内容就是把 dexElements 中的头部 element 去除了。
    public static void uninstallPatchDex(ClassLoader classLoader) throws Throwable {
        if (sPatchDexCount <= 0) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 14) {
            Field pathListField = ShareReflectUtil.findField(classLoader, "pathList");
            Object dexPathList = pathListField.get(classLoader);
            ShareReflectUtil.reduceFieldArray(dexPathList, "dexElements", sPatchDexCount);
        } else {
            ShareReflectUtil.reduceFieldArray(classLoader, "mPaths", sPatchDexCount);
            ShareReflectUtil.reduceFieldArray(classLoader, "mFiles", sPatchDexCount);
            ShareReflectUtil.reduceFieldArray(classLoader, "mZips", sPatchDexCount);
            try {
                ShareReflectUtil.reduceFieldArray(classLoader, "mDexs", sPatchDexCount);
            } catch (Exception e) {
                // Ignored.
            }
        }
    }

    private static boolean checkDexInstall(ClassLoader classLoader) throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
        Class<?> clazz = Class.forName(CHECK_DEX_CLASS, true, classLoader);
        Field filed = ShareReflectUtil.findField(clazz, CHECK_DEX_FIELD);
        boolean isPatch = (boolean) filed.get(null);
        Log.w(TAG, "checkDexInstall result:" + isPatch);
        return isPatch;
    }

    private static List<File> createSortedAdditionalPathEntries(List<File> additionalPathEntries) {
        final List<File> result = new ArrayList<>(additionalPathEntries);

        final Map<String, Boolean> matchesClassNPatternMemo = new HashMap<>();
        for (File file : result) {
            final String name = file.getName();
            matchesClassNPatternMemo.put(name, ShareConstants.CLASS_N_PATTERN.matcher(name).matches());
        }
        Collections.sort(result, new Comparator<File>() {
            @Override
            public int compare(File lhs, File rhs) {
                if (lhs == null && rhs == null) {
                    return 0;
                }
                if (lhs == null) {
                    return -1;
                }
                if (rhs == null) {
                    return 1;
                }

                final String lhsName = lhs.getName();
                final String rhsName = rhs.getName();
                if (lhsName.equals(rhsName)) {
                    return 0;
                }

                final String testDexSuffix = ShareConstants.TEST_DEX_NAME;
                // test.dex should always be at tail.
                if (lhsName.startsWith(testDexSuffix)) {
                    return 1;
                }
                if (rhsName.startsWith(testDexSuffix)) {
                    return -1;
                }

                final boolean isLhsNameMatchClassN = matchesClassNPatternMemo.get(lhsName);
                final boolean isRhsNameMatchClassN = matchesClassNPatternMemo.get(rhsName);
                if (isLhsNameMatchClassN && isRhsNameMatchClassN) {
                    final int lhsDotPos = lhsName.indexOf('.');
                    final int rhsDotPos = rhsName.indexOf('.');
                    final int lhsId = (lhsDotPos > 7 ? Integer.parseInt(lhsName.substring(7, lhsDotPos)) : 1);
                    final int rhsId = (rhsDotPos > 7 ? Integer.parseInt(rhsName.substring(7, rhsDotPos)) : 1);
                    return (lhsId == rhsId ? 0 : (lhsId < rhsId ? -1 : 1));
                } else if (isLhsNameMatchClassN) {
                    // Dex name that matches class N rules should always be at first.
                    return -1;
                } else if (isRhsNameMatchClassN) {
                    return 1;
                }
                return lhsName.compareTo(rhsName);
            }
        });

        return result;
    }

    /**
     * Installer for platform huawei ark
     */
    private static final class ArkHot {
        private static void install(ClassLoader loader, List<File> additionalClassPathEntries)
                throws IllegalArgumentException, IllegalAccessException, NoSuchMethodException,
                InvocationTargetException, IOException, ClassNotFoundException, SecurityException {
            Class<?>  extendedClassLoaderHelper = ClassLoader.getSystemClassLoader()
                    .getParent().loadClass("com.huawei.ark.classloader.ExtendedClassLoaderHelper");

            for (File file : additionalClassPathEntries) {
                String path = file.getCanonicalPath();
                Method applyPatchMethod = extendedClassLoaderHelper.getDeclaredMethod(
                        "applyPatch", ClassLoader.class, String.class);
                applyPatchMethod.setAccessible(true);
                applyPatchMethod.invoke(null, loader, path);
                Log.i(TAG, "ArkHot install path = " + path);
            }
        }
    }

    /**
     * Installer for platform versions 23.
     */
    private static final class V23 {

        private static void install(ClassLoader loader, List<File> additionalClassPathEntries,
                                    File optimizedDirectory)
            throws IllegalArgumentException, IllegalAccessException,
            NoSuchFieldException, InvocationTargetException, NoSuchMethodException, IOException {
            /* The patched class loader is expected to be a descendant of
             * dalvik.system.BaseDexClassLoader. We modify its
             * dalvik.system.DexPathList pathList field to append additional DEX
             * file entries.
             */
            Field pathListField = ShareReflectUtil.findField(loader, "pathList");
            Object dexPathList = pathListField.get(loader);
            ArrayList<IOException> suppressedExceptions = new ArrayList<IOException>();
            ShareReflectUtil.expandFieldArray(dexPathList, "dexElements", makePathElements(dexPathList,
                new ArrayList<File>(additionalClassPathEntries), optimizedDirectory,
                suppressedExceptions));
            if (suppressedExceptions.size() > 0) {
                for (IOException e : suppressedExceptions) {
                    Log.w(TAG, "Exception in makePathElement", e);
                    throw e;
                }

            }
        }

        /**
         * A wrapper around
         * {@code private static final dalvik.system.DexPathList#makePathElements}.
         */
        private static Object[] makePathElements(
            Object dexPathList, ArrayList<File> files, File optimizedDirectory,
            ArrayList<IOException> suppressedExceptions)
            throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {

            Method makePathElements;
            try {
                makePathElements = ShareReflectUtil.findMethod(dexPathList, "makePathElements", List.class, File.class,
                    List.class);
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "NoSuchMethodException: makePathElements(List,File,List) failure");
                try {
                    makePathElements = ShareReflectUtil.findMethod(dexPathList, "makePathElements", ArrayList.class, File.class, ArrayList.class);
                } catch (NoSuchMethodException e1) {
                    Log.e(TAG, "NoSuchMethodException: makeDexElements(ArrayList,File,ArrayList) failure");
                    try {
                        Log.e(TAG, "NoSuchMethodException: try use v19 instead");
                        return V19.makeDexElements(dexPathList, files, optimizedDirectory, suppressedExceptions);
                    } catch (NoSuchMethodException e2) {
                        Log.e(TAG, "NoSuchMethodException: makeDexElements(List,File,List) failure");
                        throw e2;
                    }
                }
            }

            return (Object[]) makePathElements.invoke(dexPathList, files, optimizedDirectory, suppressedExceptions);
        }
    }

    /**
     * Installer for platform versions 19.
     */
    // 首先反射拿到反射得到 PathClassLoader 中的 pathList 对象,再将补丁文件通过反射调用makeDexElements 得到补丁文件的 Element[] ,
    // 再将补丁包的 Element[] 数组插入到 dexElements 中
    private static final class V19 {
        // 在该版本系统区间中,加载补丁涉及到的修改只是增加了一个exElementsSuppressedExceptions异常数组的维护.
        // 所以在加载补丁的时候就跟V14差不多了.既然只是多了一个异常的管理,
        private static void install(ClassLoader loader, List<File> additionalClassPathEntries,
                                    File optimizedDirectory)
            throws IllegalArgumentException, IllegalAccessException,
            NoSuchFieldException, InvocationTargetException, NoSuchMethodException, IOException {
            /* The patched class loader is expected to be a descendant of
             * dalvik.system.BaseDexClassLoader. We modify its
             * dalvik.system.DexPathList pathList field to append additional DEX
             * file entries.
             */
            Field pathListField = ShareReflectUtil.findField(loader, "pathList");
            Object dexPathList = pathListField.get(loader);
            ArrayList<IOException> suppressedExceptions = new ArrayList<IOException>();
            // note 如果dex文件没有优化安装，makePathElements会触发dex2oat.
            ShareReflectUtil.expandFieldArray(dexPathList, "dexElements", makeDexElements(dexPathList,
                new ArrayList<File>(additionalClassPathEntries), optimizedDirectory,
                suppressedExceptions));
            if (suppressedExceptions.size() > 0) {
                for (IOException e : suppressedExceptions) {
                    Log.w(TAG, "Exception in makeDexElement", e);
                    throw e;
                }
            }
        }

        /**
         * A wrapper around
         * {@code private static final dalvik.system.DexPathList#makeDexElements}.
         */
        private static Object[] makeDexElements(
            Object dexPathList, ArrayList<File> files, File optimizedDirectory,
            ArrayList<IOException> suppressedExceptions)
            throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {

            Method makeDexElements = null;
            try {
                makeDexElements = ShareReflectUtil.findMethod(dexPathList, "makeDexElements", ArrayList.class, File.class,
                    ArrayList.class);
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "NoSuchMethodException: makeDexElements(ArrayList,File,ArrayList) failure");
                try {
                    makeDexElements = ShareReflectUtil.findMethod(dexPathList, "makeDexElements", List.class, File.class, List.class);
                } catch (NoSuchMethodException e1) {
                    Log.e(TAG, "NoSuchMethodException: makeDexElements(List,File,List) failure");
                    throw e1;
                }
            }

            return (Object[]) makeDexElements.invoke(dexPathList, files, optimizedDirectory, suppressedExceptions);
        }
    }

    /**
     * Installer for platform versions 14, 15, 16, 17 and 18.
     */
    // 在这个Android版本的区间内不再像老版本的那样要维护四个数组,源码从中抽离出了一个类DexPathList.java,
    // 加载dex的关键数组也变成了dexElements,并且dexElements是根据makeDexElements方法生成的.
    // 对比过源码其实就可以发现dexElements其实就是老版本中mFiles, mZips和mDexs的封装,
    // makeDexElements方法就是老版本DexClassLoader.java构造方法中对数组初始化的动作.
    private static final class V14 {

        // 系统既然自己做了封装,那么我们反射调用起来也会更方便.首先反射拿到反射得到PathClassLoader中的pathList对象,
        // 再将补丁文件通过反射调用makeDexElements得到补丁文件的Element[],再将补丁包的Element数组插入到dexElements中,方法如V4.完成补丁加载.

        private static void install(ClassLoader loader, List<File> additionalClassPathEntries,
                                    File optimizedDirectory)
            throws IllegalArgumentException, IllegalAccessException,
            NoSuchFieldException, InvocationTargetException, NoSuchMethodException {
            /* The patched class loader is expected to be a descendant of
             * dalvik.system.BaseDexClassLoader. We modify its
             * dalvik.system.DexPathList pathList field to append additional DEX
             * file entries.
             */
            Field pathListField = ShareReflectUtil.findField(loader, "pathList");
            Object dexPathList = pathListField.get(loader);
            //通过反射调用makeDexElements方法生成补丁包的dex数组,再将其插入到dexElements的头部
            ShareReflectUtil.expandFieldArray(dexPathList, "dexElements", makeDexElements(dexPathList,
                new ArrayList<File>(additionalClassPathEntries), optimizedDirectory));
        }

        /**
         * A wrapper around
         * {@code private static final dalvik.system.DexPathList#makeDexElements}.
         */
        private static Object[] makeDexElements(
            Object dexPathList, ArrayList<File> files, File optimizedDirectory)
            throws IllegalAccessException, InvocationTargetException,
            NoSuchMethodException {
            Method makeDexElements =
                ShareReflectUtil.findMethod(dexPathList, "makeDexElements", ArrayList.class, File.class);

            // 反射调用makeDexElements方法根据files得到新dexElements数组
            return (Object[]) makeDexElements.invoke(dexPathList, files, optimizedDirectory);
        }
    }

    /**
     * Installer for platform versions 4 to 13.
     */
    // 在Android SDK4到14之间PathClassLoader.java的实现是直接继承自ClassLoader,
    // findClass时是根据mFiles数组来遍历mDexs数组(类似于dexElements).从mDexs数组中的dex根据类名来加载Class,
    // 规则也是按照遍历的顺序加载,只要有加载出来的Class就直接return掉.
    private static final class V4 {
        private static void install(ClassLoader loader, List<File> additionalClassPathEntries, File optimizedDirectory)
            throws IllegalArgumentException, IllegalAccessException,
            NoSuchFieldException, IOException {
            /* The patched class loader is expected to be a descendant of
             * dalvik.system.DexClassLoader. We modify its
             * fields mPaths, mFiles, mZips and mDexs to append additional DEX
             * file entries.
             */
            int extraSize = additionalClassPathEntries.size();

            Field pathField = ShareReflectUtil.findField(loader, "path");

            StringBuilder path = new StringBuilder((String) pathField.get(loader));
            String[] extraPaths = new String[extraSize];
            File[] extraFiles = new File[extraSize];
            ZipFile[] extraZips = new ZipFile[extraSize];
            DexFile[] extraDexs = new DexFile[extraSize];
            for (ListIterator<File> iterator = additionalClassPathEntries.listIterator();
                 iterator.hasNext();) {
                File additionalEntry = iterator.next();
                String entryPath = additionalEntry.getAbsolutePath();
                path.append(':').append(entryPath);
                int index = iterator.previousIndex();
                extraPaths[index] = entryPath;
                extraFiles[index] = additionalEntry;
                extraZips[index] = new ZipFile(additionalEntry);
                //edit by zhangshaowen
                String outputPathName = SharePatchFileUtil.optimizedPathFor(additionalEntry, optimizedDirectory);
                //for below 4.0, we must input jar or zip
                extraDexs[index] = DexFile.loadDex(entryPath, outputPathName, 0);
            }

            pathField.set(loader, path.toString());
            ShareReflectUtil.expandFieldArray(loader, "mPaths", extraPaths);
            ShareReflectUtil.expandFieldArray(loader, "mFiles", extraFiles);
            ShareReflectUtil.expandFieldArray(loader, "mZips", extraZips);
            try {
                ShareReflectUtil.expandFieldArray(loader, "mDexs", extraDexs);
            } catch (Exception e) {
                // Ignored.
            }
        }
    }

}
