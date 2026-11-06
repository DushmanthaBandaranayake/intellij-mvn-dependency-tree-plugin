package com.dush.deptree;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import javax.swing.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.function.Supplier;

/**
 * Hosts the HTML/SVG tree in an embedded browser and bridges node clicks back to the IDE.
 *
 * @author Dushmantha Bandaranayake
 */
final class DependencyTreePanel implements Disposable {

    private final JComponent component;
    private final StringBuilder payload = new StringBuilder();
    private String payloadKind = "";
    private final Supplier<String> treeSource;
    private JBCefBrowser browser;
    private String pageTemplate;
    private String treeJson;
    private boolean reloading;
    private volatile boolean disposed;

    /** @param treeSource reads the current tree as JSON, or returns null if the POM is no longer in a resolved tree */
    DependencyTreePanel(@NotNull Project project, @NotNull MavenProject mavenProject,
                        @NotNull Supplier<String> treeSource, @NotNull String title) {
        this.treeSource = treeSource;
        if (!JBCefApp.isSupported()) {
            component = new JLabel("Embedded browser (JCEF) is not supported by this IDE runtime.", SwingConstants.CENTER);
            return;
        }

        browser = new JBCefBrowser();
        // The cast binds to the create(JBCefBrowserBase) overload, the one to rely on across versions
        JBCefJSQuery query = JBCefJSQuery.create((JBCefBrowserBase) browser);
        Disposer.register(this, browser);
        Disposer.register(this, query);

        query.addHandler(key -> {
            ApplicationManager.getApplication().executeOnPooledThread(
                    () -> PomNavigator.navigateTo(project, mavenProject, key));
            return null;
        });

        JBCefJSQuery hostQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        Disposer.register(this, hostQuery);
        hostQuery.addHandler(message -> {
            receive(project, mavenProject, title, message);
            return null;
        });

        pageTemplate = loadResource("/web/tree.html")
                .replace("/*NAV*/", "window.nav = function(k){ " + query.inject("k") + " };"
                        + " window.host = function(k){ " + hostQuery.inject("k") + " };");
        treeJson = treeSource.get();
        showTree();
        component = browser.getComponent();
    }

    private void showTree() {
        browser.loadHTML(pageTemplate
                .replace("/*DARK*/false", String.valueOf(!JBColor.isBright()))
                .replace("/*DATA*/null", treeJson));
    }

    /** Saves open edits, because Maven reads POMs from disk, then syncs the project and redraws when that finishes. */
    private void reload(Project project, MavenProject mavenProject) {
        if (reloading) return;
        reloading = true;
        FileDocumentManager.getInstance().saveAllDocuments();
        MavenProjectsManager.getInstance(project).forceUpdateProjects(Collections.singletonList(mavenProject))
                .onProcessed(ignored -> ApplicationManager.getApplication().invokeLater(() -> {
                    reloading = false;
                    if (disposed || project.isDisposed()) return;
                    String updated = treeSource.get();
                    if (updated != null) treeJson = updated;
                    else notify(project, "This POM is no longer in a resolved dependency tree, so the graph wasn't updated.",
                            NotificationType.WARNING);
                    showTree();
                }));
    }

    JComponent getComponent() {
        return component;
    }

    @Override
    public void dispose() {
        disposed = true;
    }

    /**
     * "R" asks for a reload. Large payloads come in pieces: "B" + kind starts one, "C" + data adds to it, "E" finishes it.
     * Kinds are "text" (copy to the clipboard) and "image" (a Base64 PNG to save to a file).
     */
    private synchronized void receive(Project project, MavenProject mavenProject, String title, String message) {
        if (message.isEmpty()) return;
        String body = message.substring(1);
        switch (message.charAt(0)) {
            case 'R':
                ApplicationManager.getApplication().invokeLater(() -> reload(project, mavenProject));
                break;
            case 'B':
                payloadKind = body;
                payload.setLength(0);
                break;
            case 'C':
                payload.append(body);
                break;
            case 'E':
                String kind = payloadKind, data = payload.toString();
                payload.setLength(0);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if ("text".equals(kind)) CopyPasteManager.getInstance().setContents(new StringSelection(data));
                    else if ("image".equals(kind)) saveImage(project, title, data);
                });
                break;
            default:
                break;
        }
    }

    private static void saveImage(Project project, String title, String base64) {
        // The String[] keeps this bound to the varargs constructor, the only one that exists in every supported IDE
        FileSaverDescriptor descriptor = new FileSaverDescriptor("Export Dependency Tree",
                "Save the dependency tree as a PNG image", new String[]{"png"});
        String fileName = title.replaceAll("[^\\w.-]", "_") + "-dependencies.png";
        VirtualFileWrapper target = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project).save((Path) null, fileName);
        if (target == null) return;

        File file = target.getFile();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Files.write(file.toPath(), Base64.getDecoder().decode(base64));
                notify(project, "Dependency tree saved to " + file.getPath(), NotificationType.INFORMATION);
            } catch (IOException | IllegalArgumentException ex) {
                notify(project, "Could not save the image: " + ex.getMessage(), NotificationType.ERROR);
            }
        });
    }

    private static void notify(Project project, String message, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("Dependency Tree")
                .createNotification(message, type)
                .notify(project);
    }

    private static String loadResource(String path) {
        try (InputStream in = DependencyTreePanel.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
