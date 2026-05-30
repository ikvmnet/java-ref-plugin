package org.ikvm.javarefplugin;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

final class MethodBodyStripper extends TreeTranslator {

    private static final String STRIPPED_MESSAGE = "Method body stripped by JavaRef plugin.";

    private final TreeMaker maker;
    private final Names names;
    private boolean inAnnotationType;

    MethodBodyStripper(Context context) {
        this.maker = TreeMaker.instance(context);
        this.names = Names.instance(context);
    }

    void strip(Object compilationUnit) {
        ((JCTree) compilationUnit).accept(this);
    }

    @Override
    public void visitClassDef(JCTree.JCClassDecl tree) {
        boolean previousInAnnotationType = inAnnotationType;
        inAnnotationType = (tree.mods.flags & Flags.ANNOTATION) != 0;
        super.visitClassDef(tree);
        inAnnotationType = previousInAnnotationType;
        result = tree;
    }

    @Override
    public void visitMethodDef(JCTree.JCMethodDecl tree) {
        super.visitMethodDef(tree);

        if (tree.body == null || inAnnotationType || shouldKeepBody(tree)) {
            result = tree;
            return;
        }

        tree.body = maker.Block(0, replacementStatements(tree));
        result = tree;
    }

    private boolean shouldKeepBody(JCTree.JCMethodDecl tree) {
        long flags = tree.mods.flags;
        if ((flags & (Flags.ABSTRACT | Flags.NATIVE)) != 0) {
            return true;
        }

        return (flags & Flags.SYNTHETIC) != 0 && !isConstructor(tree);
    }

    private boolean isConstructor(JCTree.JCMethodDecl tree) {
        return tree.name == names.init;
    }

    private List<JCTree.JCStatement> replacementStatements(JCTree.JCMethodDecl tree) {
        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<>();

        if (isConstructor(tree)) {
            JCTree.JCStatement constructorInvocation = findConstructorInvocation(tree.body.stats);
            if (constructorInvocation != null) {
                statements.append(constructorInvocation);
            }
        }

        statements.append(
            maker.Throw(
                maker.NewClass(
                    null,
                    List.nil(),
                    memberAccess("java.lang.NoSuchMethodError"),
                    List.of(maker.Literal(STRIPPED_MESSAGE)),
                    null
                )
            )
        );

        return statements.toList();
    }

    private JCTree.JCStatement findConstructorInvocation(List<JCTree.JCStatement> statements) {
        if (statements.isEmpty()) {
            return null;
        }

        JCTree.JCStatement first = statements.head;
        if (first instanceof JCTree.JCExpressionStatement) {
            JCTree.JCExpressionStatement expressionStatement = (JCTree.JCExpressionStatement) first;
            if (!(expressionStatement.expr instanceof JCTree.JCMethodInvocation)) {
                return null;
            }

            JCTree.JCMethodInvocation invocation = (JCTree.JCMethodInvocation) expressionStatement.expr;
            Name invokedName = null;
            if (invocation.meth instanceof JCTree.JCIdent) {
                JCTree.JCIdent ident = (JCTree.JCIdent) invocation.meth;
                invokedName = ident.name;
            } else if (invocation.meth instanceof JCTree.JCFieldAccess) {
                JCTree.JCFieldAccess fieldAccess = (JCTree.JCFieldAccess) invocation.meth;
                invokedName = fieldAccess.name;
            }

            if (invokedName == names._this || invokedName == names._super) {
                return first;
            }
        }

        return null;
    }

    private JCTree.JCExpression memberAccess(String qualifiedName) {
        String[] elements = qualifiedName.split("\\.");
        JCTree.JCExpression expression = maker.Ident(names.fromString(elements[0]));
        for (int i = 1; i < elements.length; i++) {
            expression = maker.Select(expression, names.fromString(elements[i]));
        }
        return expression;
    }
}

