package org.ikvm.javarefplugin;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.IdentityHashMap;
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
                if (unit != null && processedUnits.add(unit) && !shouldIgnoreUnit(unit, ignoredPackages)) {
                    stripper.strip(unit);
                }
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

    private static boolean shouldIgnoreUnit(Object unit, Set<String> ignoredPackages) {
        if (ignoredPackages.isEmpty() || !(unit instanceof JCTree.JCCompilationUnit)) {
            return false;
        }

        JCTree.JCCompilationUnit compilationUnit = (JCTree.JCCompilationUnit) unit;
        JCTree.JCExpression packageNameExpression = compilationUnit.getPackageName();
        String packageName = packageNameExpression == null ? "" : packageNameExpression.toString();
        for (String ignoredPackage : ignoredPackages) {
            if (packageName.equals(ignoredPackage) || packageName.startsWith(ignoredPackage + ".")) {
                return true;
            }
        }

        return false;
    }
}

