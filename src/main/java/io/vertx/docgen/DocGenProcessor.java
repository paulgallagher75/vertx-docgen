package io.vertx.docgen;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.DocTreeVisitor;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.doctree.UnknownInlineTagTree;
import com.sun.source.util.DocTreeScanner;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import io.vertx.codegen.annotations.GenModule;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
@SupportedAnnotationTypes({
    "io.vertx.codegen.annotations.GenModule"
})
@javax.annotation.processing.SupportedSourceVersion(javax.lang.model.SourceVersion.RELEASE_8)
public class DocGenProcessor extends AbstractProcessor {

  private DocTrees docTrees;
  Map<String, String> results = new HashMap<>();
  Map<String, String> failures = new HashMap<>();

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    docTrees = DocTrees.instance(processingEnv);
  }

  public String getDoc(String name) {
    return results.get(name);
  }

  private String render(List<? extends DocTree> trees) {
    StringBuilder buffer = new StringBuilder();
    DocTreeVisitor<Void, Void> visitor = new DocTreeScanner<Void, Void>() {
      @Override
      public Void visitText(TextTree node, Void aVoid) {
        buffer.append(node.getBody());
        return super.visitText(node, aVoid);
      }
    };
    trees.forEach(tree -> tree.accept(visitor, null));
    return buffer.toString();
  }


  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (failures.isEmpty()) {
      roundEnv.getElementsAnnotatedWith(GenModule.class).forEach(elt -> {
        StringBuilder buffer = new StringBuilder();
        PackageElement pkgElt = (PackageElement) elt;
        try {
          process(buffer, pkgElt);
        } catch (Exception e) {
          Element reportedElt = (e instanceof DocGenException) ? ((DocGenException) e).getElement() : elt;
          processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage(), reportedElt);
          if (reportedElt instanceof PackageElement) {
            failures.put(((PackageElement) reportedElt).getQualifiedName().toString(), e.getMessage());
          } else {
            throw new UnsupportedOperationException("not implemented");
          }
        }
        results.put(pkgElt.getQualifiedName().toString(), buffer.toString());
      });
    }
    return false;
  }

  protected String resolveLinkTypeDoc(TypeElement elt) {
    return "abc";
  }

  protected String resolveLinkMethodDoc(ExecutableElement elt) {
    return "def";
  }

  private void process(StringBuilder buffer, PackageElement pkgElt) {
    TreePath tp = docTrees.getPath(pkgElt);
    DocCommentTree doc = docTrees.getDocCommentTree(tp);
    DocTreeVisitor<Void, Void> visitor = new DocTreeScanner<Void, Void>() {

      @Override
      public Void visitText(TextTree node, Void v) {
        String body = node.getBody();
        buffer.append(body);
        return super.visitText(node, v);
      }

      @Override
      public Void visitLink(LinkTree node, Void v) {
        String signature = node.getReference().getSignature();
        Element resolvedElt = resolveLink(signature);
        String link;
        String name;
        if (resolvedElt instanceof TypeElement) {
          link = resolveLinkTypeDoc((TypeElement) resolvedElt);
          name = resolvedElt.getSimpleName().toString();
        } else {
          link = resolveLinkMethodDoc((ExecutableElement) resolvedElt);
          name = resolvedElt.getSimpleName().toString();
        }
        buffer.append(link).append("[`").append(name).append("`]");
        return super.visitLink(node, v);
      }

      private Element resolveLink(String signature) {
        int hash = signature.indexOf('#');
        String elementName;
        if (hash >= 0) {
          elementName = signature.substring(0, hash);
          String after = signature.substring(hash + 1);
          TypeElement targetElt = processingEnv.getElementUtils().getTypeElement(elementName);
          for (Element memberElt : processingEnv.getElementUtils().getAllMembers(targetElt)) {
            switch (memberElt.getKind()) {
              case METHOD:
                ExecutableElement methodElt = (ExecutableElement) memberElt;
                if (methodElt.getSimpleName().toString().equals(after)) {
                  return methodElt;
                }
                break;
            }
          }
          return null;
        } else {
          return processingEnv.getElementUtils().getTypeElement(signature);
        }
      }

      @Override
      public Void visitUnknownInlineTag(UnknownInlineTagTree node, Void v) {
        switch (node.getTagName()) {
          case "include":
            List<? extends DocTree> content = node.getContent();
            String target = render(content);
            PackageElement includedElt = processingEnv.getElementUtils().getPackageElement(pkgElt.getQualifiedName() + "." + target);
            if (includedElt == null) {
              throw new DocGenException(pkgElt, "handle me ");
            }
            process(buffer, includedElt);
            break;
        }
        return super.visitUnknownInlineTag(node, v);
      }
    };
    doc.accept(visitor, null);
  }
}
