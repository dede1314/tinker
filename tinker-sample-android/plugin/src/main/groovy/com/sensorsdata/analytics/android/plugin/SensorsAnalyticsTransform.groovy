package com.sensorsdata.analytics.android.plugin

import com.android.build.api.transform.Context
import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.Format
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.tools.r8.org.objectweb.asm.ClassReader
import com.android.tools.r8.org.objectweb.asm.ClassWriter
import groovy.io.FileType
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Project

class SensorsAnalyticsTransform extends Transform {
    private static Project project
    private SensorsAnalyticsExtension sensorsAnalyticsExtension

    SensorsAnalyticsTransform(Project project, SensorsAnalyticsExtension sensorsAnalyticsExtension) {
        this.project = project
        this.sensorsAnalyticsExtension = sensorsAnalyticsExtension
    }

    @Override
    String getName() {
        return "sensorsAnalytics"
    }

    /**
     * 需要处理的数据类型，有两种枚举类型
     * CLASSES 代表处理的 java 的 class 文件，RESOURCES 代表要处理 java 的资源
     * @return
     */
    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    /**
     * 指 Transform 要操作内容的范围，官方文档 Scope 有 7 种类型：
     * 1. EXTERNAL_LIBRARIES        只有外部库
     * 2. PROJECT                   只有项目内容
     * 3. PROJECT_LOCAL_DEPS        只有项目的本地依赖(本地jar)
     * 4. PROVIDED_ONLY             只提供本地或远程依赖项
     * 5. SUB_PROJECTS              只有子项目。
     * 6. SUB_PROJECTS_LOCAL_DEPS   只有子项目的本地依赖项(本地jar)。
     * 7. TESTED_CODE               由当前变量(包括依赖项)测试的代码
     * @return
     */
    @Override
    Set<QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
//        _transform(transformInvocation.context, transformInvocation.inputs, transformInvocation.outputProvider, transformInvocation.incremental)

        println("===== ASM Transform =====")
        println("${transformInvocation.inputs}")
        println("${transformInvocation.referencedInputs}")
        println("${transformInvocation.outputProvider}")
        println("${transformInvocation.incremental}")
        //当前是否是增量编译
        boolean isIncremental = transformInvocation.isIncremental()
        //消费型输入，可以从中获取jar包和class文件夹路径。需要输出给下一个任务
        Collection<TransformInput> inputs = transformInvocation.getInputs()
        //引用型输入，无需输出。
        Collection<TransformInput> referencedInputs = transformInvocation.getReferencedInputs()
        //OutputProvider管理输出路径，如果消费型输入为空，你会发现OutputProvider == null
        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider()
        for (TransformInput input : inputs) {
            for (JarInput jarInput : input.getJarInputs()) {
                File dest = outputProvider.getContentLocation(
                        jarInput.getFile().getAbsolutePath(),
                        jarInput.getContentTypes(),
                        jarInput.getScopes(),
                        Format.JAR)
                //将修改过的字节码copy到dest，就可以实现编译期间干预字节码的目的了
                transformJar(jarInput.getFile(), dest)
            }
            for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                println("== DI = " + directoryInput.file.listFiles().toArrayString())
                File dest = outputProvider.getContentLocation(directoryInput.getName(),
                        directoryInput.getContentTypes(), directoryInput.getScopes(),
                        Format.DIRECTORY)
                //将修改过的字节码copy到dest，就可以实现编译期间干预字节码的目的了
                //FileUtils.copyDirectory(directoryInput.getFile(), dest)
                transformDir(directoryInput.getFile(), dest)
            }
        }
    }


    private static void transformJar(File input, File dest) {
        println("=== transformJar ===")
        FileUtils.copyFile(input, dest)
    }

    private static void transformDir(File input, File dest) {
        if (dest.exists()) {
            FileUtils.forceDelete(dest)
        }
        FileUtils.forceMkdir(dest)
        String srcDirPath = input.getAbsolutePath()
        String destDirPath = dest.getAbsolutePath()
        println("=== transform dir = " + srcDirPath + ", " + destDirPath)
        for (File file : input.listFiles()) {
            String destFilePath = file.absolutePath.replace(srcDirPath, destDirPath)
            File destFile = new File(destFilePath)
            if (file.isDirectory()) {
                transformDir(file, destFile)
            } else if (file.isFile()) {
                FileUtils.touch(destFile)
                transformSingleFile(file, destFile)
            }
        }
    }

    private static void transformSingleFile(File input, File dest) {
        println("=== transformSingleFile ===")
        weave(input.getAbsolutePath(), dest.getAbsolutePath())
    }

    private static void weave(String inputPath, String outputPath) {
        print("start weave")
        try {
            FileInputStream is = new FileInputStream(inputPath)
            ClassReader cr = new ClassReader(is)
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
            TestMethodClassAdapter adapter = new TestMethodClassAdapter(cw)
            cr.accept(adapter, 0)
            FileOutputStream fos = new FileOutputStream(outputPath)
            fos.write(cw.toByteArray())
            fos.close()
        } catch (IOException e) {
            e.printStackTrace()
        }
    }

//    作者：有风度开荒队
//    链接：https://juejin.im/post/5cc3db486fb9a03202222154
//    来源：掘金
//    著作权归作者所有。商业转载请联系作者获得授权，非商业转载请注明出处。


    void _transform(Context context, Collection<TransformInput> inputs, TransformOutputProvider outputProvider, boolean isIncremental) throws IOException, TransformException, InterruptedException {
        if (!incremental) {
            outputProvider.deleteAll()
        }

        /**Transform 的 inputs 有两种类型，一种是目录，一种是 jar 包，要分开遍历 */
        inputs.each { TransformInput input ->
            /**遍历目录*/
            input.directoryInputs.each { DirectoryInput directoryInput ->
                /**当前这个 Transform 输出目录*/
                File dest = outputProvider.getContentLocation(directoryInput.name, directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY)
                File dir = directoryInput.file

                if (dir) {
                    HashMap<String, File> modifyMap = new HashMap<>()
                    /**遍历以某一扩展名结尾的文件*/
                    dir.traverse(type: FileType.FILES, nameFilter: ~/.*\.class/) {
                        File classFile ->
                            if (SensorsAnalyticsClassModifier.isShouldModify(classFile.name)) {
                                File modified = null
                                if (!sensorsAnalyticsExtension.disableAppClick) {
                                    modified = SensorsAnalyticsClassModifier.modifyClassFile(dir, classFile, context.getTemporaryDir())
                                }
                                if (modified != null) {
                                    /**key 为包名 + 类名，如：/cn/sensorsdata/autotrack/android/app/MainActivity.class*/
                                    println("modified class "+ke)
                                    String ke = classFile.absolutePath.replace(dir.absolutePath, "")
                                    modifyMap.put(ke, modified)
                                }
                            }
                    }
                    FileUtils.copyDirectory(directoryInput.file, dest)
                    modifyMap.entrySet().each {
                        Map.Entry<String, File> en ->
                            File target = new File(dest.absolutePath + en.getKey())
                            if (target.exists()) {
                                target.delete()
                            }
                            FileUtils.copyFile(en.getValue(), target)
                            en.getValue().delete()
                    }
                }
            }

            /**遍历 jar*/
            input.jarInputs.each { JarInput jarInput ->
                String destName = jarInput.file.name

                /**截取文件路径的 md5 值重命名输出文件,因为可能同名,会覆盖*/
                def hexName = DigestUtils.md5Hex(jarInput.file.absolutePath).substring(0, 8)
                /** 获取 jar 名字*/
                if (destName.endsWith(".jar")) {
                    destName = destName.substring(0, destName.length() - 4)
                }

                /** 获得输出文件*/
                File dest = outputProvider.getContentLocation(destName + "_" + hexName, jarInput.contentTypes, jarInput.scopes, Format.JAR)
                def modifiedJar = null;
                if (!sensorsAnalyticsExtension.disableAppClick) {
                    modifiedJar = SensorsAnalyticsClassModifier.modifyJar(jarInput.file, context.getTemporaryDir(), true)
                }
                if (modifiedJar == null) {
                    modifiedJar = jarInput.file
                }
                FileUtils.copyFile(modifiedJar, dest)
            }
        }
    }
}