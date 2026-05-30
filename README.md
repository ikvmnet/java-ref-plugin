# java-ref-plugin

`java-ref-plugin` is a `javac` plugin that rewrites concrete method bodies into `NoSuchMethodError` throws for reference-only API artifacts.

## Maven coordinates

```text
org.ikvm:java-ref-plugin
```

## Plugin identity

- Plugin name: `JavaRef`
- Plugin class: `org.ikvm.javarefplugin.JavaRefPlugin`
- Automatic module name: `org.ikvm.javarefplugin`

## Building

```bash
./gradlew build
```

## Publishing

The project publishes the Maven artifact `org.ikvm:java-ref-plugin`.

## Source layout

- Main sources: `src/main/java/org/ikvm/javarefplugin`
- Tests: `src/test/java/org/ikvm/javarefplugin`

