package com.dush.deptree;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.UpdateInBackground;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.jetbrains.idea.maven.model.MavenArtifact;

import java.util.function.Supplier;

/**
 * Context-menu action that opens the dependency tree of a POM in the tool window:
 * an imported project's pom.xml, or a dependency's .pom file from the local repository.
 *
 * @author Dushmantha Bandaranayake
 */
public class ShowDependencyTreeAction extends AnAction implements DumbAware, UpdateInBackground {

    static final String TOOL_WINDOW_ID = "Dependency Tree";

    // UpdateInBackground runs update() on a background thread, which newer IDEs require for reading the
    // selected file. It's deprecated, but it exists in every supported IDE. getActionUpdateThread(), its
    // replacement, can't be used yet because ActionUpdateThread only exists since 2022.3.
    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean isPom = e.getProject() != null && file != null
                && ("pom.xml".equals(file.getName()) || file.getName().endsWith(".pom"));   // .pom: local repository
        e.getPresentation().setEnabledAndVisible(isPom);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile pom = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || pom == null) return;

        MavenProject mavenProject = MavenProjectsManager.getInstance(project).findProject(pom);
        if (mavenProject != null) {
            if (mavenProject.getDependencyTree().isEmpty() && offerReload(project, mavenProject)) return;
            show(project, mavenProject, mavenProject.getDisplayName(), () -> {
                MavenProject current = MavenProjectsManager.getInstance(project).findProject(pom);
                return current == null ? null : TreeJson.build(current);
            });
            return;
        }

        RepositoryPomLookup.Match match = RepositoryPomLookup.find(project, pom);
        if (match == null) {
            Messages.showInfoMessage(project,
                    pom.getName() + " isn't in the dependency tree of any imported Maven project.\n"
                    + "Parent POMs, BOMs and versions your projects don't use can't be shown.\n"
                    + "If this is your own project, right-click it and choose \"Add as Maven Project\" first.",
                    "Dependency Tree");
            return;
        }
        MavenArtifact artifact = match.node.getArtifact();
        if (!match.isResolved()) {
            MavenArtifact used = match.node.getRelatedArtifact();
            Messages.showInfoMessage(project,
                    artifact.getArtifactId() + " " + artifact.getVersion() + " was omitted from "
                    + match.project.getDisplayName() + (used == null ? "" : ", which uses version " + used.getVersion() + " instead")
                    + ".\nMaven didn't resolve its dependencies, so there's no tree to show.",
                    "Dependency Tree");
            return;
        }
        show(project, match.project, artifact.getArtifactId() + ":" + artifact.getVersion(), () -> {
            RepositoryPomLookup.Match current = RepositoryPomLookup.find(project, pom);
            return current != null && current.isResolved() ? TreeJson.build(current.node, current.project) : null;
        });
    }

    /**
     * An empty tree usually means Maven hasn't resolved the project in this session, not that it has no
     * dependencies. Returns true if a reload was started, in which case the tree should be opened again after it.
     */
    private static boolean offerReload(Project project, MavenProject mavenProject) {
        int choice = Messages.showYesNoDialog(project,
                "Maven has no resolved dependencies for " + mavenProject.getDisplayName() + ".\n"
                + "If its POM declares dependencies, reload the Maven projects, wait for the sync to finish, "
                + "and open the tree again.",
                "Dependency Tree", "Reload Maven Projects", "Show Anyway", Messages.getInformationIcon());
        if (choice != Messages.YES) return false;
        MavenProjectsManager.getInstance(project).forceUpdateAllProjectsOrFindAllAvailablePomFiles();
        return true;
    }

    private static void show(Project project, MavenProject mavenProject, String title, Supplier<String> treeSource) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) return;

        DependencyTreePanel panel;
        try {
            panel = new DependencyTreePanel(project, mavenProject, treeSource, title);
        } catch (NoClassDefFoundError missingBrowser) {
            Messages.showErrorDialog(project,
                    "The graph needs IntelliJ's embedded browser, which isn't available.\n"
                    + "In Settings > Plugins, enable or install \"Web Browser (JCEF)\", then restart the IDE.",
                    "Dependency Tree");
            return;
        }
        // Service lookup rather than ContentFactory.getInstance(), which is missing from older platforms
        Content content = ApplicationManager.getApplication().getService(ContentFactory.class)
                .createContent(panel.getComponent(), title, false);
        content.setDisposer(panel);
        content.setCloseable(true);

        toolWindow.getContentManager().addContent(content);
        toolWindow.getContentManager().setSelectedContent(content);
        toolWindow.activate(null);
    }
}
