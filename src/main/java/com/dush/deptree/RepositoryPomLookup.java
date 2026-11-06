package com.dush.deptree;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.idea.maven.model.MavenArtifactNode;
import org.jetbrains.idea.maven.model.MavenArtifactState;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.io.File;
import java.util.List;

/**
 * Finds a POM from the local Maven repository in the dependency trees of the imported projects.
 * Reusing those trees keeps the versions identical to what the project really uses,
 * which re-resolving the POM on its own would not guarantee.
 *
 * @author Dushmantha Bandaranayake
 */
final class RepositoryPomLookup {
    private RepositoryPomLookup() {}

    static final class Match {
        final MavenProject project;
        final MavenArtifactNode node;

        Match(MavenProject project, MavenArtifactNode node) {
            this.project = project;
            this.node = node;
        }

        /** False when Maven left this version out, so it has no resolved dependencies to show. */
        boolean isResolved() {
            return node.getState() == MavenArtifactState.ADDED;
        }
    }

    /** Prefers an occurrence Maven kept; returns an omitted one only if there is nothing better, or null if none. */
    static Match find(Project project, VirtualFile pom) {
        File file = new File(pom.getPath());
        Match omitted = null;
        for (MavenProject candidate : MavenProjectsManager.getInstance(project).getProjects()) {
            MavenArtifactNode node = find(candidate.getDependencyTree(), file);
            if (node == null) continue;
            Match match = new Match(candidate, node);
            if (match.isResolved()) return match;
            if (omitted == null) omitted = match;
        }
        return omitted;
    }

    private static MavenArtifactNode find(List<MavenArtifactNode> nodes, File pom) {
        MavenArtifactNode omitted = null;
        for (MavenArtifactNode n : nodes) {
            if (pom.equals(TreeJson.pomFile(n.getArtifact()))) {
                if (n.getState() == MavenArtifactState.ADDED) return n;
                if (omitted == null) omitted = n;
            }
            MavenArtifactNode below = find(n.getDependencies(), pom);
            if (below != null) {
                if (below.getState() == MavenArtifactState.ADDED) return below;
                if (omitted == null) omitted = below;
            }
        }
        return omitted;
    }
}
