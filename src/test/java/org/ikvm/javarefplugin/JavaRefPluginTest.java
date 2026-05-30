package org.ikvm.javarefplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaRefPluginTest {

    private static final String STRIPPED_MESSAGE = "Method body stripped from reference-only artifact.";

    @TempDir
    Path tempDir;

    @Test
    void stripsStaticMethodsAndConstructorsWhileKeepingFields() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
            "example/Sample.java",
            joinLines(
                "package example;",
                "",
                "public class Sample {",
                "    public static final String CONSTANT = \"hello\";",
                "",
                "    public Sample() {",
                "    }",
                "",
                "    public static String greeting() {",
                "        return CONSTANT;",
                "    }",
                "}"
            )
        );
        CompilationResult result = compile(
            sources
        );

        Class<?> sampleClass = result.loadClass("example.Sample");

        assertNotNull(sampleClass.getField("CONSTANT"));
        assertEquals("hello", sampleClass.getField("CONSTANT").get(null));

        InvocationTargetException staticFailure =
            assertThrows(InvocationTargetException.class, () -> sampleClass.getMethod("greeting").invoke(null));
        assertTrue(staticFailure.getCause() instanceof UnsupportedOperationException);
        assertEquals(STRIPPED_MESSAGE, staticFailure.getCause().getMessage());

        InvocationTargetException constructorFailure =
            assertThrows(InvocationTargetException.class, () -> sampleClass.getConstructor().newInstance());
        assertTrue(constructorFailure.getCause() instanceof UnsupportedOperationException);
        assertEquals(STRIPPED_MESSAGE, constructorFailure.getCause().getMessage());
    }

    @Test
    void keepsExplicitConstructorChainingValid() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
            "example/Base.java",
            joinLines(
                "package example;",
                "",
                "public class Base {",
                "    public Base(String name) {",
                "    }",
                "}"
            )
        );
        sources.put(
            "example/Child.java",
            joinLines(
                "package example;",
                "",
                "public class Child extends Base {",
                "    public Child() {",
                "        super(\"parent\");",
                "    }",
                "}"
            )
        );
        CompilationResult result = compile(
            sources
        );

        Class<?> childClass = result.loadClass("example.Child");
        InvocationTargetException constructorFailure =
            assertThrows(InvocationTargetException.class, () -> childClass.getConstructor().newInstance());
        assertTrue(constructorFailure.getCause() instanceof UnsupportedOperationException);
        assertEquals(STRIPPED_MESSAGE, constructorFailure.getCause().getMessage());
    }

    @Test
    void stripsInterfaceDefaultAndStaticMethods() throws Exception {
        // Compile interface WITH plugin → static and default method bodies stripped
        Map<String, String> interfaceSources = new LinkedHashMap<>();
        interfaceSources.put(
            "example/SampleInterface.java",
            joinLines(
                "package example;",
                "",
                "public interface SampleInterface {",
                "    default String value() {",
                "        return \"value\";",
                "    }",
                "",
                "    static String helper() {",
                "        return \"helper\";",
                "    }",
                "}"
            )
        );
        CompilationResult interfaceResult = compileImpl(interfaceSources, "", true);

        // Compile implementation WITHOUT plugin so its constructor is usable
        Map<String, String> implSources = new LinkedHashMap<>();
        implSources.put(
            "example/SampleInterfaceImpl.java",
            joinLines(
                "package example;",
                "",
                "public class SampleInterfaceImpl implements SampleInterface {",
                "}"
            )
        );
        CompilationResult implResult = compileImpl(implSources, interfaceResult.classesDirectory.toString(), false);

        // Load both sets of classes together
        URL[] urls = new URL[] {
            interfaceResult.classesDirectory.toUri().toURL(),
            implResult.classesDirectory.toUri().toURL()
        };
        URLClassLoader classLoader = new URLClassLoader(urls, JavaRefPluginTest.class.getClassLoader());

        Class<?> interfaceClass = Class.forName("example.SampleInterface", true, classLoader);
        Class<?> implementationClass = Class.forName("example.SampleInterfaceImpl", true, classLoader);

        // Static method should throw
        InvocationTargetException staticFailure =
            assertThrows(InvocationTargetException.class, () -> interfaceClass.getMethod("helper").invoke(null));
        assertTrue(staticFailure.getCause() instanceof UnsupportedOperationException);
        assertEquals(STRIPPED_MESSAGE, staticFailure.getCause().getMessage());

        // Default method should throw (dispatches to stripped default from the interface)
        Object instance = implementationClass.getConstructor().newInstance();
        Method defaultMethod = implementationClass.getMethod("value");
        InvocationTargetException defaultFailure =
            assertThrows(InvocationTargetException.class, () -> defaultMethod.invoke(instance));
        assertTrue(defaultFailure.getCause() instanceof UnsupportedOperationException);
        assertEquals(STRIPPED_MESSAGE, defaultFailure.getCause().getMessage());
    }

    @Test
    void stripsImplicitDefaultConstructors() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
            "example/ImplicitConstructor.java",
            joinLines(
                "package example;",
                "",
                "public class ImplicitConstructor {",
                "}"
            )
        );
        CompilationResult result = compile(
            sources
        );

        Class<?> type = result.loadClass("example.ImplicitConstructor");
        InvocationTargetException constructorFailure =
            assertThrows(InvocationTargetException.class, () -> type.getConstructor().newInstance());
        assertTrue(constructorFailure.getCause() instanceof UnsupportedOperationException);
        assertEquals(STRIPPED_MESSAGE, constructorFailure.getCause().getMessage());
    }

    @Test
    void pluginClassesTargetJava8Bytecode() throws Exception {
        assertEquals(52, classFileMajorVersion(JavaRefPlugin.class));
        assertEquals(52, classFileMajorVersion(MethodBodyStripper.class));
    }

    private CompilationResult compile(Map<String, String> sources) throws IOException {
        return compileImpl(sources, "", true);
    }

    private CompilationResult compileImpl(Map<String, String> sources, String extraClasspath, boolean withPlugin) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path sourceDirectory = Files.createDirectories(tempDir.resolve("src-" + System.nanoTime()));
        Path classesDirectory = Files.createDirectories(tempDir.resolve("classes-" + System.nanoTime()));
        List<java.io.File> sourceFiles = new ArrayList<>();

        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = sourceDirectory.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, source.getValue().getBytes(StandardCharsets.UTF_8));
            sourceFiles.add(file.toFile());
        }

        String classpath = System.getProperty("java.class.path");
        if (extraClasspath != null && !extraClasspath.isEmpty()) {
            classpath = classpath + File.pathSeparator + extraClasspath;
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(sourceFiles);
            List<String> options = new ArrayList<String>();
            options.add("-proc:none");
            options.add("-classpath");
            options.add(classpath);
            options.add("-d");
            options.add(classesDirectory.toString());
            if (withPlugin) {
                options.add("-Xplugin:" + JavaRefPlugin.NAME);
            }
            Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();

            assertTrue(Boolean.TRUE.equals(success), () -> diagnostics.getDiagnostics()
                .stream()
                .map(JavaRefPluginTest::formatDiagnostic)
                .collect(Collectors.joining(System.lineSeparator())));
        }

        return new CompilationResult(classesDirectory);
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        String source = diagnostic.getSource() == null ? "<unknown>" : diagnostic.getSource().getName();
        return source + ":" + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(null);
    }

    private static String joinLines(String... lines) {
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static int classFileMajorVersion(Class<?> type) throws IOException {
        try (InputStream input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(input);

            byte[] header = new byte[8];
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) {
                    throw new IOException("Unexpected end of class file for " + type.getName());
                }
                offset += read;
            }

            return ((header[6] & 0xff) << 8) | (header[7] & 0xff);
        }
    }

    private static final class CompilationResult {
        private final Path classesDirectory;
        private final URLClassLoader classLoader;

        private CompilationResult(Path classesDirectory) throws IOException {
            this.classesDirectory = classesDirectory;
            URL url = classesDirectory.toUri().toURL();
            this.classLoader = new URLClassLoader(new URL[] { url }, JavaRefPluginTest.class.getClassLoader());
        }

        private Class<?> loadClass(String name) throws Exception {
            return Class.forName(name, true, classLoader);
        }
    }
}

