package com.replaymod.gradle.remap

import com.replaymod.gradle.remap.legacy.LegacyMapping
import org.cadixdev.lorenz.MappingSet
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.ContentRoot
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.com.intellij.codeInsight.CustomExceptionHandler
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.PathUtil
import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.LinkedBlockingQueue
import kotlin.system.exitProcess

private class TransformerWorker(
    private val map: MappingSet,
    private val classpath: Array<String>?,
    private val remappedClasspath: Array<String>?,
    private val jdkHome: File?,
    private val remappedJdkHome: File? ,
    private val patternAnnotation: String?,
    private val manageImports: Boolean,
    private val enableMessageCollector: Boolean,
    private val verboseCompilerMessages: Boolean,

    private val sources: Map<String, String>,
    private val processedSources: Map<String, String>,
    private val tmpDir: Path,
    private val processedTmpDir: Path,
) {
    private val disposable = Disposer.newDisposable()
    private lateinit var psiManager: PsiManager
    private lateinit var vfs: CoreLocalFileSystem
    private var patterns: PsiPatterns? = null
    private lateinit var analysis: AnalysisResult
    private var remappedEnv: KotlinCoreEnvironment? = null
    private var autoImports: AutoImports? = null

    fun init() {
        val config = CompilerConfiguration()
        config.put(CommonConfigurationKeys.MODULE_NAME, "main")
        jdkHome?.let {config.setupJdk(it) }
        config.add<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, JavaSourceRoot(tmpDir.toFile(), ""))
        val kotlinSourceRoot = try {
            kotlinSourceRoot1521(tmpDir.toAbsolutePath().toString(), false)
        } catch (e: NoSuchMethodError) {
            kotlinSourceRoot190(tmpDir.toAbsolutePath().toString(), false)
        }
        config.add<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, kotlinSourceRoot)
        config.addAll<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, classpath!!.map { JvmClasspathRoot(File(it)) })
        config.put<MessageCollector>(
            CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
            if (enableMessageCollector) PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, verboseCompilerMessages)
            else MessageCollector.NONE
        )

        // Our PsiMapper only works with the PSI tree elements, not with the faster (but kotlin-specific) classes
        config.put(JVMConfigurationKeys.USE_PSI_CLASS_FILES_READING, true)

        // Mark Registry as loaded, otherwise RegistryKey will (provided a sufficiently complex project) log
        // messages about it being accessed before it is loaded (and it won't ever be loaded naturally).
        val loadedField = try {
            Registry::class.java.getDeclaredField("myLoaded")
        } catch (_: NoSuchFieldException) {
            Registry::class.java.getDeclaredField("isLoaded")
        }
        loadedField.isAccessible = true
        loadedField.set(Registry.getInstance(), true)

        val environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            config,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
        @Suppress("DEPRECATION")
        val rootArea = Extensions.getRootArea()
        synchronized(rootArea) {
            if (!rootArea.hasExtensionPoint(CustomExceptionHandler.KEY)) {
                rootArea.registerExtensionPoint(CustomExceptionHandler.KEY.name, CustomExceptionHandler::class.java.name, ExtensionPoint.Kind.INTERFACE)
            }
        }

        val project = environment.project as MockProject
        psiManager = PsiManager.getInstance(project)
        vfs = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL) as CoreLocalFileSystem
        val virtualFiles = sources.mapValues { vfs.findFileByIoFile(tmpDir.resolve(it.key).toFile())!! }
        val psiFiles = virtualFiles.mapValues { psiManager.findFile(it.value)!! }
        val ktFiles = psiFiles.values.filterIsInstance<KtFile>()

        analysis = try {
            analyze1521(environment, ktFiles)
        } catch (e: NoSuchMethodError) {
            try {
                analyze1620(environment, ktFiles)
            } catch (e: NoSuchMethodError) {
                analyze200(environment, ktFiles)
            }
        }

        remappedEnv = remappedClasspath?.let {
            setupRemappedProject(disposable, it, processedTmpDir)
        }

        patterns = patternAnnotation?.let { annotationFQN ->
            val patterns = PsiPatterns(annotationFQN)
            val annotationName = annotationFQN.substring(annotationFQN.lastIndexOf('.') + 1)
            for ((unitName, source) in sources) {
                if (!source.contains(annotationName)) continue
                try {
                    val patternFile = vfs.findFileByIoFile(tmpDir.resolve(unitName).toFile())!!
                    val patternPsiFile = psiManager.findFile(patternFile)!!
                    patterns.read(patternPsiFile, processedSources[unitName]!!)
                } catch (e: Exception) {
                    throw RuntimeException("Failed to read patterns from file \"$unitName\".", e)
                }
            }
            patterns
        }

        autoImports = if (manageImports && remappedEnv != null) {
            AutoImports(remappedEnv!!)
        } else {
            null
        }
    }

    fun shutdown() {
        Disposer.dispose(disposable)
    }

    private fun CompilerConfiguration.setupJdk(jdkHome: File) {
        put(JVMConfigurationKeys.JDK_HOME, jdkHome)

        if (!CoreJrtFileSystem.isModularJdk(jdkHome)) {
            val roots = PathUtil.getJdkClassesRoots(jdkHome).map { JvmClasspathRoot(it, true) }
            addAll(CLIConfigurationKeys.CONTENT_ROOTS, 0, roots)
        }
    }

    private fun setupRemappedProject(disposable: Disposable, classpath: Array<String>, sourceRoot: Path): KotlinCoreEnvironment {
        val config = CompilerConfiguration()
        (remappedJdkHome ?: jdkHome)?.let { config.setupJdk(it) }
        config.put(CommonConfigurationKeys.MODULE_NAME, "main")
        config.addAll(CLIConfigurationKeys.CONTENT_ROOTS, classpath.map { JvmClasspathRoot(File(it)) })
        if (manageImports) {
            config.add(CLIConfigurationKeys.CONTENT_ROOTS, JavaSourceRoot(sourceRoot.toFile(), ""))
        }
        config.put(
            CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
            if (enableMessageCollector) PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, verboseCompilerMessages)
            else MessageCollector.NONE
        )

        val environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            config,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
        try {
            analyze1521(environment, emptyList())
        } catch (e: NoSuchMethodError) {
            try {
                analyze1620(environment, emptyList())
            } catch (e: NoSuchMethodError) {
                analyze200(environment, emptyList())
            }
        }
        return environment
    }

    fun work(name: String): Pair<String, List<Pair<Int, String>>> {
        val file = vfs.findFileByIoFile(tmpDir.resolve(name).toFile())!!
        val psiFile = psiManager.findFile(file)!!

        var (text, errors) = try {
            PsiMapper(map, remappedEnv?.project, psiFile, analysis.bindingContext, patterns).remapFile()
        } catch (e: Exception) {
            throw RuntimeException("Failed to map file \"${name}\".", e)
        }
        if (autoImports != null && "/* remap: no-manage-imports */" !in text) {
            val processedText = processedSources[name] ?: text
            text = autoImports!!.apply(psiFile, text, processedText)
        }

        return text to errors
    }
}

class Transformer(private val map: MappingSet) {
    var classpath: Array<String>? = null
    var remappedClasspath: Array<String>? = null
    var jdkHome: File? = null
    var remappedJdkHome: File? = null
    var patternAnnotation: String? = null
    var manageImports = false
    var enableMessageCollector = true
    var verboseCompilerMessages = false

    // added in fallen's fork
    var concurrency = 1

    @Throws(IOException::class)
    fun remap(sources: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> =
            remap(sources, emptyMap())

    @Throws(IOException::class)
    fun remap(sources: Map<String, String>, processedSources: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> {
        val startMs = System.currentTimeMillis()
        println("remap start")
        val tmpDir = Files.createTempDirectory("remap")
        val processedTmpDir = Files.createTempDirectory("remap-processed")
        try {
            for ((unitName, source) in sources) {
                val path = tmpDir.resolve(unitName)
                Files.createDirectories(path.parent)
                Files.write(path, source.toByteArray(StandardCharsets.UTF_8), StandardOpenOption.CREATE)

                val processedSource = processedSources[unitName] ?: source
                val processedPath = processedTmpDir.resolve(unitName)
                Files.createDirectories(processedPath.parent)
                Files.write(processedPath, processedSource.toByteArray(), StandardOpenOption.CREATE)
            }

            val workerNum = if (concurrency <= 0) {
                Runtime.getRuntime().availableProcessors() + concurrency
            } else {
                concurrency
            }.coerceIn(1, sources.size.coerceAtLeast(1))
            println("workerNum: $workerNum")

            data class Task(val name: String, val source: String, val processedSource: String)
            data class Result(val name: String, val output: Pair<String, List<Pair<Int, String>>>?, val e: Exception?)

            val sentinel = Task("", "", "")
            val taskQueue = LinkedBlockingQueue<Task>()
            val resultChannel = LinkedBlockingQueue<Result>()

            val workers = mutableListOf<Thread>()
            repeat(workerNum) { index ->
                val workerName = "TransformerWorker-$index"
                val thread = Thread({
                    val workerStartMs = System.currentTimeMillis()
                    println("worker $workerName start")
                    val worker = TransformerWorker(
                        map, classpath, remappedClasspath, jdkHome, remappedJdkHome, patternAnnotation, manageImports,
                        enableMessageCollector, verboseCompilerMessages,
                        sources, processedSources, tmpDir, processedTmpDir,
                    )
                    var workCnt = 0
                    try {
                        worker.init()
                        while (true) {
                            val task = taskQueue.take()
                            if (task === sentinel) {
                                taskQueue.put(task)
                                break
                            }
                            workCnt++
                            try {
                                val output = worker.work(task.name)
                                resultChannel.put(Result(task.name, output, null))
                            } catch (e: Exception) {
                                resultChannel.put(Result(task.name, null, e))
                            }
                        }
                    } finally {
                        worker.shutdown()
                    }
                    println("$workerName workCnt: $workCnt, cost ${System.currentTimeMillis() - workerStartMs}ms")
                }, workerName)
                thread.start()
                workers.add(thread)
            }

            for ((name, source) in sources) {
                val processedSource = processedSources[name] ?: source
                taskQueue.put(Task(name, source, processedSource))
            }
            taskQueue.put(sentinel)

            workers.forEach { it.join() }

            val results = mutableMapOf<String, Pair<String, List<Pair<Int, String>>>>()
            resultChannel.forEach { result ->
                result.e?.let { throw it }
                results[result.name] = result.output!!
            }
            return results
        } finally {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            Files.walk(processedTmpDir).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            println("remap end, cost ${System.currentTimeMillis() - startMs}ms")
        }
    }

    companion object {

        @Throws(IOException::class)
        @JvmStatic
        fun main(args: Array<String>) {
            val mappings: MappingSet = if (args[0].isEmpty()) {
                MappingSet.create()
            } else {
                LegacyMapping.readMappingSet(File(args[0]).toPath(), args[1] == "true")
            }
            val transformer = Transformer(mappings)

            val reader = BufferedReader(InputStreamReader(System.`in`))

            transformer.classpath = (1..Integer.parseInt(args[2])).map { reader.readLine() }.toTypedArray()

            val sources = mutableMapOf<String, String>()
            while (true) {
                val name = reader.readLine()
                if (name == null || name.isEmpty()) {
                    break
                }

                val lines = arrayOfNulls<String>(Integer.parseInt(reader.readLine()))
                for (i in lines.indices) {
                    lines[i] = reader.readLine()
                }
                val source = lines.joinToString("\n")

                sources[name] = source
            }

            val results = transformer.remap(sources)

            for (name in sources.keys) {
                println(name)
                val lines = results.getValue(name).first.split("\n").dropLastWhile { it.isEmpty() }.toTypedArray()
                println(lines.size)
                for (line in lines) {
                    println(line)
                }
            }

            if (results.any { it.value.second.isNotEmpty() }) {
                exitProcess(1)
            }
        }

        init {
            // fallen's fork: Fix "WARN: Failed to initialize native filesystem for Windows" warnings
            setIdeaIoUseFallback()

            // fallen's fork: Mute intellij platform logger for those "WARN: The registry key 'xxx' accessed, but not loaded yet" warnings
            org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger.setFactory {
                org.jetbrains.kotlin.utils.PrintingLogger(PrintStream(object : OutputStream() {
                    override fun write(b: Int) {}
                }))
            }
        }
    }

}
