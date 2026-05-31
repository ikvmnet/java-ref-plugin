package org.ikvm.javarefplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
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
        assertTrue(staticFailure.getCause() instanceof NullPointerException);

        InvocationTargetException constructorFailure =
            assertThrows(InvocationTargetException.class, () -> sampleClass.getConstructor().newInstance());
        assertTrue(constructorFailure.getCause() instanceof NullPointerException);
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
        assertTrue(constructorFailure.getCause() instanceof NullPointerException);
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
        assertTrue(staticFailure.getCause() instanceof NullPointerException);

        // Default method should throw (dispatches to stripped default from the interface)
        Object instance = implementationClass.getConstructor().newInstance();
        Method defaultMethod = implementationClass.getMethod("value");
        InvocationTargetException defaultFailure =
            assertThrows(InvocationTargetException.class, () -> defaultMethod.invoke(instance));
        assertTrue(defaultFailure.getCause() instanceof NullPointerException);
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
        assertTrue(constructorFailure.getCause() instanceof NullPointerException);
    }

    @Test
    void ignoresConfiguredPackage() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
            "example/keep/Kept.java",
            joinLines(
                "package example.keep;",
                "",
                "public class Kept {",
                "    public static String value() {",
                "        return \"kept\";",
                "    }",
                "}"
            )
        );
        sources.put(
            "example/strip/Stripped.java",
            joinLines(
                "package example.strip;",
                "",
                "public class Stripped {",
                "    public static String value() {",
                "        return \"stripped\";",
                "    }",
                "}"
            )
        );

        CompilationResult result = compile(sources, "ignorePackage=example.keep");

        Class<?> keptClass = result.loadClass("example.keep.Kept");
        assertEquals("kept", keptClass.getMethod("value").invoke(null));

        Class<?> strippedClass = result.loadClass("example.strip.Stripped");
        InvocationTargetException strippedFailure =
            assertThrows(InvocationTargetException.class, () -> strippedClass.getMethod("value").invoke(null));
        assertTrue(strippedFailure.getCause() instanceof NullPointerException);
    }

    @Test
    void ignoresMultipleConfiguredPackages() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
            "example/keep/one/KeptOne.java",
            joinLines(
                "package example.keep.one;",
                "",
                "public class KeptOne {",
                "    public static String value() {",
                "        return \"one\";",
                "    }",
                "}"
            )
        );
        sources.put(
            "example/keep/two/KeptTwo.java",
            joinLines(
                "package example.keep.two;",
                "",
                "public class KeptTwo {",
                "    public static String value() {",
                "        return \"two\";",
                "    }",
                "}"
            )
        );
        sources.put(
            "example/strip/Stripped.java",
            joinLines(
                "package example.strip;",
                "",
                "public class Stripped {",
                "    public static String value() {",
                "        return \"strip\";",
                "    }",
                "}"
            )
        );

        CompilationResult result = compile(
            sources,
            "ignorePackage=example.keep.one",
            "ignorePackage=example.keep.two"
        );

        Class<?> keptOneClass = result.loadClass("example.keep.one.KeptOne");
        assertEquals("one", keptOneClass.getMethod("value").invoke(null));

        Class<?> keptTwoClass = result.loadClass("example.keep.two.KeptTwo");
        assertEquals("two", keptTwoClass.getMethod("value").invoke(null));

        Class<?> strippedClass = result.loadClass("example.strip.Stripped");
        InvocationTargetException strippedFailure =
            assertThrows(InvocationTargetException.class, () -> strippedClass.getMethod("value").invoke(null));
        assertTrue(strippedFailure.getCause() instanceof NullPointerException);
    }

    @Test
    void logsIgnoredClasses() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
            "example/keep/Kept.java",
            joinLines(
                "package example.keep;",
                "",
                "public class Kept {",
                "    public static String value() {",
                "        return \"kept\";",
                "    }",
                "}"
            )
        );

        CompilationInvocation invocation = compileCapturingOutput(sources, "ignorePackage=example.keep");

        assertTrue(invocation.output.contains("JavaRef: ignoring example.keep.Kept due to ignorePackage=example.keep"));

        Class<?> keptClass = invocation.result.loadClass("example.keep.Kept");
        assertEquals("kept", keptClass.getMethod("value").invoke(null));
    }

    @Test
    void pluginClassesTargetJava8Bytecode() throws Exception {
        assertEquals(52, classFileMajorVersion(JavaRefPlugin.class));
        assertEquals(52, classFileMajorVersion(MethodBodyStripper.class));
    }

    private CompilationResult compile(Map<String, String> sources) throws IOException {
        return compileImpl(sources, "", true);
    }

    private CompilationResult compile(Map<String, String> sources, String... pluginArgs) throws IOException {
        return compileImpl(sources, "", true, pluginArgs);
    }

    private CompilationInvocation compileCapturingOutput(Map<String, String> sources, String... pluginArgs) throws IOException {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8.name()));
            CompilationResult result = compileImpl(sources, "", true, pluginArgs);
            return new CompilationInvocation(result, capturedErr.toString(StandardCharsets.UTF_8.name()));
        } finally {
            System.setErr(originalErr);
        }
    }

    private CompilationResult compileImpl(Map<String, String> sources, String extraClasspath, boolean withPlugin, String... pluginArgs) throws IOException {
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
                options.add(pluginOption(pluginArgs));
            }
            Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();

            assertTrue(Boolean.TRUE.equals(success), () -> diagnostics.getDiagnostics()
                .stream()
                .map(JavaRefPluginTest::formatDiagnostic)
                .collect(Collectors.joining(System.lineSeparator())));
        }

        return new CompilationResult(classesDirectory);
    }

    private static String pluginOption(String... pluginArgs) {
        if (pluginArgs == null || pluginArgs.length == 0) {
            return "-Xplugin:" + JavaRefPlugin.NAME;
        }

        return "-Xplugin:" + JavaRefPlugin.NAME + " " + String.join(" ", pluginArgs);
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

    private static final class CompilationInvocation {
        private final CompilationResult result;
        private final String output;

        private CompilationInvocation(CompilationResult result, String output) {
            this.result = result;
            this.output = output;
        }
    }

    @Test
    void staticInitializerOptimizationHandlesAllFieldTypes() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
            "example/StaticFieldTypes.java",
            joinLines(
                "package example;",
                "",
                "public class StaticFieldTypes {",
                "    static final long CONSTANT = 42L;",
                "    static final String uninit;",
                "    static int mutable;",
                "    static {",
                "        uninit = \"initialized\";",
                "        mutable = 100;",
                "    }",
                "    public static long getConstant() { return CONSTANT; }",
                "    public static String getUninit() { return uninit; }",
                "    public static int getMutable() { return mutable; }",
                "}"
            )
        );

        // Just verify the plugin can compile this without errors
        // (the key test is that final fields with initializers don't get reassigned)
        CompilationResult result = compile(sources);
        Class<?> type = result.loadClass("example.StaticFieldTypes");

        // All methods should be stripped and throw NullPointerException
        InvocationTargetException constantFailure =
            assertThrows(InvocationTargetException.class, () -> type.getMethod("getConstant").invoke(null));
        assertTrue(constantFailure.getCause() instanceof NullPointerException);

        InvocationTargetException uninitFailure =
            assertThrows(InvocationTargetException.class, () -> type.getMethod("getUninit").invoke(null));
        assertTrue(uninitFailure.getCause() instanceof NullPointerException);

        InvocationTargetException mutableFailure =
            assertThrows(InvocationTargetException.class, () -> type.getMethod("getMutable").invoke(null));
        assertTrue(mutableFailure.getCause() instanceof NullPointerException);
    }

    @Test
    void staticFieldsAllCombinations() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
            "example/AllStaticFields.java",
            joinLines(
                "package example;",
                "",
                "public class AllStaticFields {",
                "    // static final WITH initializer - should NOT be reassigned",
                "    static final long WITH_INIT_FINAL = 42L;",
                "",
                "    // static final WITHOUT initializer - MUST be assigned",
                "    static final String WITHOUT_INIT_FINAL;",
                "",
                "    // static non-final WITH initializer - should NOT be reassigned?",
                "    static int WITH_INIT_MUTABLE = 100;",
                "",
                "    // static non-final WITHOUT initializer - should be assigned to 0",
                "    static int WITHOUT_INIT_MUTABLE;",
                "",
                "    static {",
                "        WITHOUT_INIT_FINAL = \"initialized\";",
                "        WITHOUT_INIT_MUTABLE = 200;",
                "    }",
                "",
                "    public static void dummy() { }",
                "}"
            )
        );

        // Just verify the plugin can compile this without errors
        CompilationResult result = compile(sources);
        assertNotNull(result.loadClass("example.AllStaticFields"));
    }

    @Test
    void staticBooleanFieldsCombinationsAreHandled() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
            "example/StaticBooleanFields.java",
            joinLines(
                "package example;",
                "",
                "public class StaticBooleanFields {",
                "    static final boolean WITH_INIT_FINAL = true;",
                "    static final boolean WITHOUT_INIT_FINAL;",
                "    static boolean WITH_INIT_MUTABLE = true;",
                "    static boolean WITHOUT_INIT_MUTABLE;",
                "",
                "    static {",
                "        WITHOUT_INIT_FINAL = true;",
                "        WITHOUT_INIT_MUTABLE = true;",
                "    }",
                "}"
            )
        );

        CompilationResult result = compile(sources);
        Class<?> type = result.loadClass("example.StaticBooleanFields");

        assertTrue(getStaticBoolean(type, "WITH_INIT_FINAL"));
        assertFalse(getStaticBoolean(type, "WITHOUT_INIT_FINAL"));
        assertTrue(getStaticBoolean(type, "WITH_INIT_MUTABLE"));
        assertFalse(getStaticBoolean(type, "WITHOUT_INIT_MUTABLE"));
    }

    private static boolean getStaticBoolean(Class<?> type, String fieldName) throws Exception {
        java.lang.reflect.Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(null);
    }

}
