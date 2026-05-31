# java-ref-plugin

`java-ref-plugin` is a `javac` plugin that rewrites concrete method bodies into failing stubs for reference-only API artifacts.

## Maven coordinates

```text
org.ikvm:java-ref-plugin
```

## Plugin identity

- Plugin name: `JavaRef`
- Plugin class: `org.ikvm.javarefplugin.JavaRefPlugin`
- Automatic module name: `org.ikvm.javarefplugin`

## Plugin behavior

The plugin rewrites method bodies to throw `NullPointerException` on invocation:

- **Instance methods**: replaced with `throw null`.
- **Constructors**: preserved `this()/super()` chaining, then `throw null`.
- **Static initializers**: preserved intact.
- **Abstract/native methods**: bodies left unchanged (they have no bodies).
- **Synthetic bridge methods**: not specifically handled; the plugin rewrites the concrete method bodies it sees during its `ENTER`-time traversal.

### Plugin arguments

- `ignorePackage=<packageName>`: skips rewriting for classes in the package and its subpackages. Repeat the argument to ignore multiple package roots. Ignored classes are logged as `javac` notices during compilation.

## Building

```bash
./gradlew build
```

## JVM setup

- Gradle daemon: Java 17+
- Java compilation target: Java 8 (toolchain)
- Test runtime: Java 8 (toolchain)

If your shell defaults to Java 8, point `JAVA_HOME` at a Java 17 JDK before running Gradle.

## Publishing

The project publishes the Maven artifact `org.ikvm:java-ref-plugin`.

## Source layout

- Main sources: `src/main/java/org/ikvm/javarefplugin`
- Tests: `src/test/java/org/ikvm/javarefplugin`

