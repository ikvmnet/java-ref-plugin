package org.ikvm.javarefplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        CompilationResult result = compile(
            Map.of(
                "example/Sample.java",
                """
                package example;

                public class Sample {
                    public static final String CONSTANT = "hello";

                    public Sample() {
                    }

                    public static String greeting() {
                        return CONSTANT;
                    }
                }
                """
            )
        );

        Class<?> sampleClass = result.loadClass("example.Sample");

        assertNotNull(sampleClass.getField("CONSTANT"));
        assertEquals("hello", sampleClass.getField("CONSTANT").get(null));

        InvocationTargetException staticFailure =
            assertThrows(InvocationTargetException.class, () -> sampleClass.getMethod("greeting").invoke(null));
        assertTrue(staticFailure.getCause() instanceof NoSuchMethodError);

        InvocationTargetException constructorFailure =
            assertThrows(InvocationTargetException.class, () -> sampleClass.getConstructor().newInstance());
        assertTrue(constructorFailure.getCause() instanceof NoSuchMethodError);
    }

    @Test
    void keepsExplicitConstructorChainingValid() throws Exception {
        CompilationResult result = compile(
            Map.of(
                "example/Base.java",
                """
                package example;

                public class Base {
                    public Base(String name) {
                    }
                }
                """,
                "example/Child.java",
                """
                package example;

                public class Child extends Base {
                    public Child() {
                        super("parent");
                    }
                }
                """
            )
        );

        Class<?> childClass = result.loadClass("example.Child");
        InvocationTargetException constructorFailure =
            assertThrows(InvocationTargetException.class, () -> childClass.getConstructor().newInstance());
        assertTrue(constructorFailure.getCause() instanceof NoSuchMethodError);
    }

    @Test
    void stripsInterfaceDefaultAndStaticMethods() throws Exception {
        CompilationResult result = compile(
            Map.of(
                "example/SampleInterface.java",
                """
                package example;

                public interface SampleInterface {
                    default String value() {
                        return "value";
                    }

                    static String helper() {
                        return "helper";
                    }
                }
                """
            )
        );

        Class<?> interfaceClass = result.loadClass("example.SampleInterface");

        InvocationTargetException staticFailure =
            assertThrows(InvocationTargetException.class, () -> interfaceClass.getMethod("helper").invoke(null));
        assertTrue(staticFailure.getCause() instanceof NoSuchMethodError);

        Object proxy = java.lang.reflect.Proxy.newProxyInstance(
            result.classLoader(),
            new Class<?>[] { interfaceClass },
            (instance, method, arguments) -> {
                throw new UnsupportedOperationException(method.getName());
            }
        );

        MethodHandle handle = MethodHandles.privateLookupIn(interfaceClass, MethodHandles.lookup())
            .findSpecial(interfaceClass, "value", MethodType.methodType(String.class), interfaceClass)
            .bindTo(proxy);

        NoSuchMethodError defaultFailure = assertThrows(NoSuchMethodError.class, handle::invokeWithArguments);
        assertEquals("Method body stripped by JavaRef plugin.", defaultFailure.getMessage());
    }

    @Test
    void stripsImplicitDefaultConstructors() throws Exception {
        CompilationResult result = compile(
            Map.of(
                "example/ImplicitConstructor.java",
                """
                package example;

                public class ImplicitConstructor {
                }
                """
            )
        );

        Class<?> type = result.loadClass("example.ImplicitConstructor");
        InvocationTargetException constructorFailure =
            assertThrows(InvocationTargetException.class, () -> type.getConstructor().newInstance());
        assertTrue(constructorFailure.getCause() instanceof NoSuchMethodError);
    }

    @Test
    void pluginClassesTargetJava8Bytecode() throws Exception {
        assertEquals(52, classFileMajorVersion(JavaRefPlugin.class));
        assertEquals(52, classFileMajorVersion(MethodBodyStripper.class));
    }

    private CompilationResult compile(Map<String, String> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path sourceDirectory = Files.createDirectories(tempDir.resolve("src"));
        Path classesDirectory = Files.createDirectories(tempDir.resolve("classes"));
        List<java.io.File> sourceFiles = new ArrayList<>();

        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = sourceDirectory.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            sourceFiles.add(file.toFile());
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(sourceFiles);
            List<String> options = List.of(
                "-proc:none",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classesDirectory.toString(),
                "-Xplugin:" + JavaRefPlugin.NAME
            );
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
        private final URLClassLoader classLoader;

        private CompilationResult(Path classesDirectory) throws IOException {
            URL url = classesDirectory.toUri().toURL();
            this.classLoader = new URLClassLoader(new URL[] { url }, JavaRefPluginTest.class.getClassLoader());
        }

        private Class<?> loadClass(String name) throws Exception {
            return Class.forName(name, true, classLoader);
        }

        private URLClassLoader classLoader() {
            return classLoader;
        }
    }
}

