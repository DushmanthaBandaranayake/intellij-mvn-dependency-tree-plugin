package com.dush.deptree;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
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

/**
 * Context-menu action that opens the dependency tree of a pom.xml in the tool window.
 *
 * @author Dushmantha Bandaranayake
 */
public class ShowDependencyTreeAction extends AnAction implements DumbAware {

    static final String TOOL_WINDOW_ID = "Dependency Tree";

    // Do not override getActionUpdateThread(): ActionUpdateThread only exists since 2022.3.
    // The platform's default runs update() on the EDT, which is fine for this cheap check.
    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean isPom = e.getProject() != null && file != null && "pom.xml".equals(file.getName());
        e.getPresentation().setEnabledAndVisible(isPom);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile pom = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || pom == null) return;

        MavenProject mavenProject = MavenProjectsManager.getInstance(project).findProject(pom);
        if (mavenProject == null) {
            Messages.showInfoMessage(project,
                    "This pom.xml is not imported as a Maven project.\n" +
                    "Right-click it and choose \"Add as Maven Project\" first.",
                    "Dependency Tree");
            return;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) return;

        DependencyTreePanel panel = new DependencyTreePanel(project, mavenProject);
        // Service lookup rather than ContentFactory.getInstance(), which is missing from older platforms
        Content content = ApplicationManager.getApplication().getService(ContentFactory.class)
                .createContent(panel.getComponent(), mavenProject.getDisplayName(), false);
        content.setDisposer(panel);
        content.setCloseable(true);

        toolWindow.getContentManager().addContent(content);
        toolWindow.getContentManager().setSelectedContent(content);
        toolWindow.activate(null);
    }
}
