package net.ikvm.javarefplugin;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.util.Context;
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
                if (unit != null && processedUnits.add(unit)) {
                    stripper.strip(unit);
                }
            }
        });
    }
}
