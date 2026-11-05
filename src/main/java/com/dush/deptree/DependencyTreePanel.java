package com.dush.deptree;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.project.MavenProject;

import javax.swing.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hosts the HTML/SVG tree in an embedded browser and bridges node clicks back to the IDE.
 *
 * @author Dushmantha Bandaranayake
 */
final class DependencyTreePanel implements Disposable {

    private final JComponent component;

    DependencyTreePanel(@NotNull Project project, @NotNull MavenProject mavenProject) {
        if (!JBCefApp.isSupported()) {
            component = new JLabel("Embedded browser (JCEF) is not supported by this IDE runtime.", SwingConstants.CENTER);
            return;
        }

        JBCefBrowser browser = new JBCefBrowser();
        // The cast binds to the create(JBCefBrowserBase) overload, the one to rely on across versions
        JBCefJSQuery query = JBCefJSQuery.create((JBCefBrowserBase) browser);
        Disposer.register(this, browser);
        Disposer.register(this, query);

        query.addHandler(key -> {
            ApplicationManager.getApplication().invokeLater(
                    () -> PomNavigator.navigateTo(project, mavenProject, key));
            return null;
        });

        String html = loadResource("/web/tree.html")
                .replace("/*NAV*/", "window.nav = function(k){ " + query.inject("k") + " };")
                .replace("/*DARK*/false", String.valueOf(!JBColor.isBright()))
                .replace("/*DATA*/null", TreeJson.build(mavenProject));
        browser.loadHTML(html);
        component = browser.getComponent();
    }

    JComponent getComponent() {
        return component;
    }

    @Override
    public void dispose() {
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
