package org.ikvm.javarefplugin;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

final class MethodBodyStripper extends TreeTranslator {

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
        return (flags & (Flags.ABSTRACT | Flags.NATIVE)) != 0;
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
            maker.Throw(maker.Literal(TypeTag.BOT, null))
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

}
