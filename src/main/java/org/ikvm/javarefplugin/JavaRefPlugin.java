package org.ikvm.javarefplugin;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

public final class JavaRefPlugin implements Plugin {

    public static final String NAME = "JavaRef";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        Set<String> ignoredPackages = parseIgnoredPackages(args);
        Context context = ((BasicJavacTask) task).getContext();
        Log log = Log.instance(context);
        MethodBodyStripper stripper = new MethodBodyStripper(context);
        Set<Object> processedUnits = Collections.newSetFromMap(new IdentityHashMap<>());

        task.addTaskListener(new TaskListener() {
            @Override
            public void started(TaskEvent event) {
            }

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() != TaskEvent.Kind.ENTER) {
                    return;
                }

                Object unit = event.getCompilationUnit();
                if (unit == null || !processedUnits.add(unit)) {
                    return;
                }

                String ignoredPackage = findIgnoredPackage(unit, ignoredPackages);
                if (ignoredPackage != null) {
                    logIgnoredUnit(log, unit, ignoredPackage);
                    return;
                }

                stripper.strip(unit);
            }
        });
    }

    private static Set<String> parseIgnoredPackages(String... args) {
        Set<String> ignoredPackages = new LinkedHashSet<>();
        for (String arg : args) {
            if (arg.startsWith("ignorePackage=")) {
                String value = arg.substring("ignorePackage=".length()).trim();
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("ignorePackage argument must not be empty.");
                }

                ignoredPackages.add(value);
            }
        }

        return ignoredPackages;
    }

    private static String findIgnoredPackage(Object unit, Set<String> ignoredPackages) {
        if (ignoredPackages.isEmpty() || !(unit instanceof JCTree.JCCompilationUnit)) {
            return null;
        }

        JCTree.JCCompilationUnit compilationUnit = (JCTree.JCCompilationUnit) unit;
        String packageName = packageName(compilationUnit);
        for (String ignoredPackage : ignoredPackages) {
            if (packageName.equals(ignoredPackage) || packageName.startsWith(ignoredPackage + ".")) {
                return ignoredPackage;
            }
        }

        return null;
    }

    private static void logIgnoredUnit(Log log, Object unit, String ignoredPackage) {
        if (!(unit instanceof JCTree.JCCompilationUnit)) {
            return;
        }

        JCTree.JCCompilationUnit compilationUnit = (JCTree.JCCompilationUnit) unit;
        String packageName = packageName(compilationUnit);
        boolean loggedType = false;
        for (JCTree definition : compilationUnit.defs) {
            if (definition instanceof JCTree.JCClassDecl) {
                JCTree.JCClassDecl classDecl = (JCTree.JCClassDecl) definition;
                log.printRawLines(
                    Log.WriterKind.NOTICE,
                    NAME + ": ignoring " + qualifiedName(packageName, classDecl.name.toString())
                        + " due to ignorePackage=" + ignoredPackage
                );
                loggedType = true;
            }
        }

        if (!loggedType) {
            String sourceName = compilationUnit.getSourceFile() == null ? "<unknown>" : compilationUnit.getSourceFile().getName();
            log.printRawLines(
                Log.WriterKind.NOTICE,
                NAME + ": ignoring compilation unit " + sourceName + " due to ignorePackage=" + ignoredPackage
            );
        }
    }

    private static String packageName(JCTree.JCCompilationUnit compilationUnit) {
        JCTree.JCExpression packageNameExpression = compilationUnit.getPackageName();
        return packageNameExpression == null ? "" : packageNameExpression.toString();
    }

    private static String qualifiedName(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }
}

