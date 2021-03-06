package com.codebrig.phenomenon.kythe.build

import com.codebrig.phenomenon.kythe.KytheIndexObserver
import com.codebrig.phenomenon.kythe.model.KytheIndex
import groovy.util.logging.Slf4j
import org.zeroturnaround.exec.ProcessExecutor

/**
 * todo: description
 *
 * @version 0.1
 * @since 0.1
 * @author <a href="mailto:brandon@srcpl.us">Brandon Fergerson</a>
 */
@Slf4j
class KytheIndexBuilder {

    static final String javacWrapperLocation = "extractors/javac-wrapper.sh"
    static final String javacExtractorLocation = "extractors/javac_extractor.jar"
    static final String javaIndexerLocation = "indexers/java_indexer.jar"
    static final String dedupStreamToolLocation = "tools/dedup_stream"
    static final String triplesToolLocation = "tools/triples"
    static final String runExtractorLocation = "tools/runextractor"
    static final String javac9Location = "javac-9+181-r4173-1.jar"
    private final File repositoryDirectory
    private File kytheDirectory = new File("opt/kythe-v0.0.49")
    private File kytheOutputDirectory = new File((System.getProperty("os.name").toLowerCase().startsWith("mac"))
            ? "/tmp" : System.getProperty("java.io.tmpdir"), "kythe-phenomenon")
    private List<KytheIndexObserver> indexObservers = new ArrayList<>()
    private KytheIndex kytheIndex

    KytheIndexBuilder(File repositoryDirectory) {
        this.repositoryDirectory = Objects.requireNonNull(repositoryDirectory)
    }

    KytheIndexBuilder setKytheDirectory(File kytheDirectory) {
        this.kytheDirectory = Objects.requireNonNull(kytheDirectory)
        return this
    }

    File getKytheDirectory() {
        return kytheDirectory
    }

    KytheIndexBuilder setKytheOutputDirectory(File kytheOutputDirectory) {
        this.kytheOutputDirectory = Objects.requireNonNull(kytheOutputDirectory)
        return this
    }

    File getKytheOutputDirectory() {
        return kytheOutputDirectory
    }

    void addKytheIndexObserver(KytheIndexObserver observer) {
        indexObservers.add(Objects.requireNonNull(observer))
    }

    KytheIndex getIndex() {
        if (kytheIndex == null) {
            build()
        }
        return kytheIndex
    }

    void build() throws KytheIndexException {
        kytheOutputDirectory.mkdirs()
        String jdk8Location = System.getenv("JDK_LOCATION") ?: "/usr/lib/jvm/java-8-openjdk-amd64"
        String javac8Location = "$jdk8Location/bin/javac"
        if (!new File(javac8Location).exists()) {
            throw new KytheIndexException("Failed to find JDK 1.8 at $jdk8Location")
        }

        def mvnEnvironment = [
                JAVA_HOME                 : jdk8Location,
                REAL_JAVAC                : javac8Location,
                KYTHE_CORPUS              : "kythe",
                KYTHE_ROOT_DIRECTORY      : repositoryDirectory.absolutePath,
                KYTHE_OUTPUT_DIRECTORY    : kytheOutputDirectory.absolutePath,
                JAVAC_EXTRACTOR_JAR       : new File(kytheDirectory, javacExtractorLocation).absolutePath,
                KYTHE_JAVA_RUNTIME_OPTIONS: "-Xbootclasspath/p:" + new File(kytheDirectory, javac9Location).absolutePath,
                KYTHE_OUTPUT_FILE         : new File(kytheOutputDirectory, "kythe_done.kzip").absolutePath
        ]
        def mvnCommand = [
                "/bin/sh", "-c",
                new File(kytheDirectory, runExtractorLocation).absolutePath +
                        " maven -javac_wrapper " + new File(kytheDirectory, javacWrapperLocation).absolutePath
        ]

        log.info "Executing runextractor"
        def result = new ProcessExecutor()
                .redirectOutput(System.out)
                .redirectError(System.err)
                .environment(mvnEnvironment)
                .directory(repositoryDirectory)
                .command(mvnCommand).execute()
        if (result.getExitValue() != 0) {
            throw new KytheIndexException() //todo: fill in exception
        }
        log.info "Finished runextractor"

        String jdk11Location = System.getenv("JDK_11_LOCATION") ?: "/usr/lib/jvm/java-11-openjdk-amd64"
        String java11Location = "$jdk11Location/bin/java"
        if (!new File(java11Location).exists()) {
            throw new KytheIndexException("Failed to find JDK 11 at $java11Location")
        }
        def command2 = ["/bin/sh", "-c",
                        "find " + kytheOutputDirectory.absolutePath +
                                " -name '*.kzip' | xargs -L1 " + java11Location +
                                " -jar " +
                                new File(kytheDirectory, javaIndexerLocation).absolutePath + " | " +
                                new File(kytheDirectory, dedupStreamToolLocation).absolutePath + " | " +
                                new File(kytheDirectory, triplesToolLocation).absolutePath + " >> " +
                                new File(kytheOutputDirectory, "kythe_phenomenon_triples").absolutePath
        ]

        log.info "Processing KZIP"
        result = new ProcessExecutor()
                .redirectOutput(System.out)
                .redirectError(System.err)
                .directory(repositoryDirectory)
                .command(command2).execute()
        if (result.getExitValue() != 0) {
            throw new KytheIndexException() //todo: fill in exception
        }
        log.info "Finished processing KZIP"

        kytheIndex = new KytheIndexExtractor(kytheDirectory, indexObservers)
                .processIndexFile(new File(kytheOutputDirectory, "kythe_phenomenon_triples"))
    }
}
