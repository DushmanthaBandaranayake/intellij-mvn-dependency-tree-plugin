package com.dush.deptree;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.idea.maven.model.MavenId;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.io.File;

/**
 * Opens the POM block where a dependency is declared. Must be called on a background thread, because
 * finding the POM reads files and indexes, which the IDE doesn't allow on the EDT. The editor opens on the EDT.
 *
 * @author Dushmantha Bandaranayake
 */
final class PomNavigator {
    private PomNavigator() {}

    static void navigateTo(Project project, MavenProject start, String nav) {
        String[] p = nav.split("\\|", 4);

        if (p.length >= 2 && "D".equals(p[0])) {
            navigateToDirect(project, start, p[1]);
            return;
        }

        if (p.length == 4 && "T".equals(p[0])) {
            String key = p[1], pomPath = p[2], directKey = p[3];
            if (!pomPath.isEmpty() && openInRepositoryPom(project, new File(pomPath), key)) return;

            notify(project, "The POM that declares " + key + " was not found in the local Maven repository"
                    + " (run a Maven reload / download sources & artifacts). Showing the direct dependency instead.");
            navigateToDirect(project, start, directKey);
        }
    }

    // ---- direct dependency in the project's own POM (and its parent POMs in the workspace) ----

    private static void navigateToDirect(Project project, MavenProject start, String key) {
        String[] ga = key.split(":", 2);
        if (ga.length != 2) return;

        MavenProjectsManager manager = MavenProjectsManager.getInstance(project);
        MavenProject current = start;
        for (int guard = 0; current != null && guard < 20; guard++) {
            if (openDependency(project, current.getFile(), ga[0], ga[1])) return;
            MavenId parentId = current.getParentId();
            current = parentId == null ? null : manager.findProject(parentId);
        }
        notify(project, "Could not find a declaration of " + key + " in the POM hierarchy "
                + "(it may come from a BOM, an import-scope POM or a plugin).");
    }

    // ---- transitive dependency: POM of the declaring artifact in ~/.m2, then its parents ----

    private static boolean openInRepositoryPom(Project project, File pom, String key) {
        String[] ga = key.split(":", 2);
        if (ga.length != 2) return false;

        File current = pom;
        for (int guard = 0; current != null && guard < 10; guard++) {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(current);
            if (vf == null) return false;

            if (openDependency(project, vf, ga[0], ga[1])) return true;

            File pomFile = current;
            current = ReadAction.compute(() -> parentPomOf(project, vf, pomFile));   // declared in an inherited parent POM?
        }
        return false;
    }

    /** Resolves the {@code <parent>} of a repository POM to its file in the same local repository. */
    private static File parentPomOf(Project project, VirtualFile vf, File current) {
        PsiFile psi = PsiManager.getInstance(project).findFile(vf);
        if (!(psi instanceof XmlFile)) return null;

        XmlTag root = ((XmlFile) psi).getRootTag();
        if (root == null) return null;

        XmlTag parent = root.findFirstSubTag("parent");
        if (parent == null) return null;

        String pg = text(parent, "groupId"), pa = text(parent, "artifactId"), pv = text(parent, "version");
        if (pg == null || pa == null || pv == null) return null;

        String ownGroup = text(root, "groupId");
        if (ownGroup == null) ownGroup = pg;

        // current = <repo>/<group dirs>/<artifact>/<version>/<file>.pom -> climb back to <repo>
        File dir = current.getParentFile();
        int ups = ownGroup.split("\\.").length + 2;
        for (int i = 0; i < ups && dir != null; i++) dir = dir.getParentFile();
        if (dir == null) return null;

        return new File(dir, pg.replace('.', File.separatorChar) + File.separator + pa
                + File.separator + pv + File.separator + pa + "-" + pv + ".pom");
    }

    // ---- shared helpers ----

    private static XmlTag findDependencyTag(Project project, VirtualFile pom, String groupId, String artifactId) {
        PsiFile psi = PsiManager.getInstance(project).findFile(pom);
        if (!(psi instanceof XmlFile)) return null;

        XmlTag fallback = null; // a match inside <dependencyManagement> if there is no direct one
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(psi, XmlTag.class)) {
            if (!"dependency".equals(tag.getName())) continue;
            if (tag.getParentTag() == null || !"dependencies".equals(tag.getParentTag().getName())) continue;
            XmlTag owner = tag.getParentTag().getParentTag();
            if (owner != null && "plugin".equals(owner.getName())) continue;

            String a = text(tag, "artifactId");
            String g = text(tag, "groupId");
            if (!artifactId.equals(a)) continue;
            if (g != null && !g.contains("${") && !g.equals(groupId)) continue;

            boolean managed = owner != null && "dependencyManagement".equals(owner.getName());
            if (!managed) return tag;
            if (fallback == null) fallback = tag;
        }
        return fallback;
    }

    /** Finds the dependency under a read lock, then opens it on the EDT. Returns false if the POM doesn't declare it. */
    private static boolean openDependency(Project project, VirtualFile pom, String groupId, String artifactId) {
        OpenFileDescriptor target = ReadAction.compute(() -> {
            XmlTag tag = findDependencyTag(project, pom, groupId, artifactId);
            return tag == null ? null : new OpenFileDescriptor(project, tag.getContainingFile().getVirtualFile(),
                    tag.getTextRange().getStartOffset());
        });
        if (target == null) return false;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) target.navigate(true);
        });
        return true;
    }

    private static String text(XmlTag parent, String child) {
        XmlTag t = parent.findFirstSubTag(child);
        return t == null ? null : t.getValue().getTrimmedText();
    }

    private static void notify(Project project, String message) {
        NotificationGroupManager.getInstance().getNotificationGroup("Dependency Tree")
                .createNotification(message, NotificationType.INFORMATION)
                .notify(project);
    }
}