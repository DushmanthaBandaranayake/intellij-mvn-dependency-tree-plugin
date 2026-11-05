package com.dush.deptree;

import org.jetbrains.idea.maven.model.MavenArtifact;
import org.jetbrains.idea.maven.model.MavenArtifactNode;
import org.jetbrains.idea.maven.project.MavenProject;

import java.io.File;
import java.util.List;

/**
 * Converts Maven's resolved dependency tree to JSON (hand-built to avoid a Gson dependency).
 *
 * @author Dushmantha Bandaranayake
 */
final class TreeJson {
    private TreeJson() {}

    static String build(MavenProject project) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":").append(str(project.getDisplayName()))
                .append(",\"scope\":\"root\",\"state\":\"ADDED\",\"nav\":null,\"children\":[");
        appendNodes(sb, project.getDependencyTree(), null, null);
        sb.append("]}");
        return sb.toString();
    }

    /**
     * nav encoding (read by PomNavigator):
     *   direct:      D|group:artifact
     *   transitive:  T|group:artifact|<declaring artifact's .pom path>|<top-level direct group:artifact>
     *
     * @param topKey   group:artifact of the direct dependency this subtree hangs under (null at the top)
     * @param declarer the artifact whose POM declares these nodes (null at the top)
     */
    private static void appendNodes(StringBuilder sb, List<MavenArtifactNode> nodes,
                                    String topKey, MavenArtifact declarer) {
        boolean first = true;
        for (MavenArtifactNode n : nodes) {
            if (!first) sb.append(',');
            first = false;

            MavenArtifact a = n.getArtifact();
            String key = a.getGroupId() + ":" + a.getArtifactId();
            String top = topKey != null ? topKey : key;
            boolean transitive = declarer != null;

            String nav = transitive ? "T|" + key + "|" + pomPath(declarer) + "|" + top : "D|" + key;
            String by = transitive
                    ? declarer.getGroupId() + ":" + declarer.getArtifactId() + ":" + declarer.getVersion()
                    : "";

            sb.append("{\"name\":").append(str(key + ":" + a.getVersion()))
                    .append(",\"scope\":").append(str(String.valueOf(a.getScope())))
                    .append(",\"state\":").append(str(String.valueOf(n.getState())))
                    .append(",\"transitive\":").append(transitive)
                    .append(",\"by\":").append(str(by))
                    .append(",\"nav\":").append(str(nav))
                    .append(",\"children\":[");
            appendNodes(sb, n.getDependencies(), top, a);
            sb.append("]}");
        }
    }

    /** The .pom sits next to the artifact's jar in the local repository. */
    private static String pomPath(MavenArtifact a) {
        File f = a.getFile();
        if (f == null || f.getParentFile() == null) return "";
        return new File(f.getParentFile(), a.getArtifactId() + "-" + a.getVersion() + ".pom").getPath();
    }

    private static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '<': b.append("\\u003c"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}