package org.securegraph.query;

import org.securegraph.*;

public class DefaultVertexQuery extends VertexQueryBase implements VertexQuery {
    public DefaultVertexQuery(Graph graph, Vertex sourceVertex, String queryString, Authorizations authorizations) {
        super(graph, sourceVertex, queryString, authorizations);
    }

    @Override
    public Iterable<Vertex> vertices() {
        return new DefaultGraphQueryIterable<Vertex>(getParameters(), getSourceVertex().getVertices(Direction.BOTH, getParameters().getAuthorizations()), true);
    }

    @Override
    public Iterable<Edge> edges() {
        return new DefaultGraphQueryIterable<Edge>(getParameters(), getSourceVertex().getEdges(Direction.BOTH, getParameters().getAuthorizations()), true);
    }


}