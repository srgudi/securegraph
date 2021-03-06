package org.securegraph.event;

import org.securegraph.Graph;
import org.securegraph.Vertex;

public class MarkVisibleVertexEvent extends GraphEvent {
    private final Vertex vertex;

    public MarkVisibleVertexEvent(Graph graph, Vertex vertex) {
        super(graph);
        this.vertex = vertex;
    }

    public Vertex getVertex() {
        return vertex;
    }

    @Override
    public String toString() {
        return "MarkVisibleVertexEvent{vertex=" + vertex + '}';
    }

    @Override
    public int hashCode() {
        return getVertex().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof MarkVisibleVertexEvent)) {
            return false;
        }

        MarkVisibleVertexEvent other = (MarkVisibleVertexEvent) obj;
        return getVertex().equals(other.getVertex()) && super.equals(obj);
    }
}
