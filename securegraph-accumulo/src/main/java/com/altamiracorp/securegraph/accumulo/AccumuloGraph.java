package com.altamiracorp.securegraph.accumulo;

import com.altamiracorp.securegraph.*;
import com.altamiracorp.securegraph.accumulo.iterator.ElementVisibilityRowFilter;
import com.altamiracorp.securegraph.accumulo.serializer.ValueSerializer;
import com.altamiracorp.securegraph.id.IdGenerator;
import com.altamiracorp.securegraph.property.PropertyBase;
import com.altamiracorp.securegraph.property.StreamingPropertyValue;
import com.altamiracorp.securegraph.search.SearchIndex;
import com.altamiracorp.securegraph.util.LimitOutputStream;
import com.altamiracorp.securegraph.util.LookAheadIterable;
import com.altamiracorp.securegraph.util.StreamUtils;
import org.apache.accumulo.core.client.*;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;

import static com.altamiracorp.securegraph.util.Preconditions.checkNotNull;

public class AccumuloGraph extends GraphBase {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccumuloGraph.class);
    private static final Text EMPTY_TEXT = new Text("");
    private static final Value EMPTY_VALUE = new Value(new byte[0]);
    public static final String DATA_ROW_KEY_PREFIX = "D";
    public static final String VALUE_SEPERATOR = "\u001f";
    private final Connector connector;
    private final ValueSerializer valueSerializer;
    private BatchWriter writer;
    private final Object writerLock = new Object();
    private long maxStreamingPropertyValueTableDataSize;
    private final FileSystem fileSystem;
    private String dataDir;

    protected AccumuloGraph(AccumuloGraphConfiguration config, IdGenerator idGenerator, SearchIndex searchIndex, Connector connector, FileSystem fileSystem, ValueSerializer valueSerializer) {
        super(config, idGenerator, searchIndex);
        this.connector = connector;
        this.fileSystem = fileSystem;
        this.valueSerializer = valueSerializer;
        this.maxStreamingPropertyValueTableDataSize = config.getMaxStreamingPropertyValueTableDataSize();
        this.dataDir = config.getDataDir();
    }

    public static AccumuloGraph create(AccumuloGraphConfiguration config) throws AccumuloSecurityException, AccumuloException, SecureGraphException, InterruptedException, IOException, URISyntaxException {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        Connector connector = config.createConnector();
        FileSystem fs = config.createFileSystem();
        ValueSerializer valueSerializer = config.createValueSerializer();
        SearchIndex searchIndex = config.createSearchIndex();
        IdGenerator idGenerator = config.createIdGenerator();
        ensureTableExists(connector, config.getTableName());
        return new AccumuloGraph(config, idGenerator, searchIndex, connector, fs, valueSerializer);
    }

    private static void ensureTableExists(Connector connector, String tableName) {
        try {
            if (!connector.tableOperations().exists(tableName)) {
                connector.tableOperations().create(tableName);
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to create table " + tableName);
        }
    }

    public static AccumuloGraph create(Map config) throws AccumuloSecurityException, AccumuloException, SecureGraphException, InterruptedException, IOException, URISyntaxException {
        return create(new AccumuloGraphConfiguration(config));
    }

    @Override
    public Vertex addVertex(Object vertexId, Visibility vertexVisibility, Property... properties) {
        if (vertexId == null) {
            vertexId = getIdGenerator().nextId();
        }

        AccumuloVertex vertex = new AccumuloVertex(this, vertexId, vertexVisibility, properties);

        String vertexRowKey = AccumuloVertex.ROW_KEY_PREFIX + vertex.getId();
        Mutation m = new Mutation(vertexRowKey);
        m.put(AccumuloVertex.CF_SIGNAL, EMPTY_TEXT, new ColumnVisibility(vertexVisibility.getVisibilityString()), EMPTY_VALUE);
        for (Property property : vertex.getProperties()) {
            addPropertyToMutation(m, vertexRowKey, property);
        }
        addMutations(m);

        getSearchIndex().addElement(this, vertex);

        return vertex;
    }

    void saveProperties(AccumuloElement element, Property[] properties) {
        String rowPrefix = getRowPrefixForElement(element);

        String elementRowKey = rowPrefix + element.getId();
        Mutation m = new Mutation(elementRowKey);
        for (Property property : properties) {
            addPropertyToMutation(m, elementRowKey, property);
        }
        addMutations(m);

        getSearchIndex().addElement(this, element);
    }

    void removeProperty(AccumuloElement element, Property property) {
        String rowPrefix = getRowPrefixForElement(element);

        Mutation m = new Mutation(rowPrefix + element.getId());
        addPropertyRemoveToMutation(m, property);
        addMutations(m);

        getSearchIndex().addElement(this, element);
    }

    private String getRowPrefixForElement(AccumuloElement element) {
        if (element instanceof Vertex) {
            return AccumuloVertex.ROW_KEY_PREFIX;
        }
        if (element instanceof Edge) {
            return AccumuloEdge.ROW_KEY_PREFIX;
        }
        throw new SecureGraphException("Unexpected element type: " + element.getClass().getName());
    }

    private void addPropertyToMutation(Mutation m, String rowKey, Property property) {
        Text columnQualifier = new Text(property.getName() + VALUE_SEPERATOR + property.getId());
        ColumnVisibility columnVisibility = new ColumnVisibility(property.getVisibility().getVisibilityString());
        Object propertyValue = property.getValue();
        if (propertyValue instanceof StreamingPropertyValue) {
            StreamingPropertyValueRef streamingPropertyValueRef = saveStreamingPropertyValue(rowKey, property, (StreamingPropertyValue) propertyValue, columnVisibility);
            ((PropertyBase) property).setValue(streamingPropertyValueRef.toStreamingPropertyValue(this));
            propertyValue = streamingPropertyValueRef;
        }
        Value value = new Value(getValueSerializer().objectToValue(propertyValue));
        m.put(AccumuloElement.CF_PROPERTY, columnQualifier, columnVisibility, value);
        if (property.getMetadata() != null) {
            Value metadataValue = new Value(getValueSerializer().objectToValue(property.getMetadata()));
            m.put(AccumuloElement.CF_PROPERTY_METADATA, columnQualifier, columnVisibility, metadataValue);
        }
    }

    private StreamingPropertyValueRef saveStreamingPropertyValue(String rowKey, Property property, StreamingPropertyValue propertyValue, ColumnVisibility columnVisibility) {
        try {
            HdfsLargeDataStore largeDataStore = new HdfsLargeDataStore(this.fileSystem);
            LimitOutputStream out = new LimitOutputStream(largeDataStore, maxStreamingPropertyValueTableDataSize);
            try {
                StreamUtils.copy(propertyValue.getInputStream(null), out);
            } finally {
                out.close();
            }

            if (out.hasExceededSizeLimit()) {
                return saveStreamingPropertyValueLarge(rowKey, property, largeDataStore, propertyValue.getValueType());
            } else {
                return saveStreamingPropertyValueSmall(rowKey, property, out.getSmall(), propertyValue.getValueType(), columnVisibility);
            }
        } catch (IOException ex) {
            throw new SecureGraphException(ex);
        }
    }

    private StreamingPropertyValueRef saveStreamingPropertyValueLarge(String rowKey, Property property, HdfsLargeDataStore largeDataStore, Class valueType) throws IOException {
        Path dir = new Path(dataDir, rowKey);
        fileSystem.mkdirs(dir);
        Path path = new Path(dir, property.getName() + "_" + property.getId());
        if (fileSystem.exists(path)) {
            fileSystem.delete(path, true);
        }
        fileSystem.rename(largeDataStore.getFileName(), path);
        return new StreamingPropertyValueHdfsRef(path, valueType);
    }

    private StreamingPropertyValueRef saveStreamingPropertyValueSmall(String rowKey, Property property, byte[] data, Class valueType, ColumnVisibility columnVisibility) {
        String dataRowKey = createTableDataRowKey(rowKey, property);
        Mutation dataMutation = new Mutation(dataRowKey);
        dataMutation.put(EMPTY_TEXT, EMPTY_TEXT, columnVisibility, new Value(data));
        addMutations(dataMutation);
        return new StreamingPropertyValueTableRef(dataRowKey, valueType);
    }

    private String createTableDataRowKey(String rowKey, Property property) {
        return DATA_ROW_KEY_PREFIX + rowKey + VALUE_SEPERATOR + property.getName() + VALUE_SEPERATOR + property.getId();
    }

    private void addPropertyRemoveToMutation(Mutation m, Property property) {
        Text columnQualifier = new Text(property.getName() + VALUE_SEPERATOR + property.getId());
        ColumnVisibility columnVisibility = new ColumnVisibility(property.getVisibility().getVisibilityString());
        m.putDelete(AccumuloElement.CF_PROPERTY, columnQualifier, columnVisibility);
        m.putDelete(AccumuloElement.CF_PROPERTY_METADATA, columnQualifier, columnVisibility);
    }

    private void addMutations(Collection<Mutation> mutations) {
        addMutations(mutations.toArray(new Mutation[mutations.size()]));
    }

    private void addMutations(Mutation... mutations) {
        try {
            BatchWriter writer = getWriter();
            synchronized (this.writerLock) {
                for (Mutation m : mutations) {
                    writer.addMutation(m);
                }
                if (getConfiguration().isAutoFlush()) {
                    flush();
                }
            }
        } catch (MutationsRejectedException ex) {
            throw new RuntimeException("Could not add mutation", ex);
        }
    }

    protected synchronized BatchWriter getWriter() {
        try {
            if (this.writer != null) {
                return this.writer;
            }
            BatchWriterConfig writerConfig = new BatchWriterConfig();
            this.writer = this.connector.createBatchWriter(getConfiguration().getTableName(), writerConfig);
            return this.writer;
        } catch (TableNotFoundException ex) {
            throw new RuntimeException("Could not create batch writer", ex);
        }
    }

    @Override
    public Iterable<Vertex> getVertices(Authorizations authorizations) throws SecureGraphException {
        return getVerticesInRange(null, null, authorizations);
    }

    @Override
    public void removeVertex(Vertex vertex, Authorizations authorizations) {
        if (vertex == null) {
            throw new IllegalArgumentException("vertex cannot be null");
        }

        List<Mutation> mutations = new ArrayList<Mutation>();

        getSearchIndex().removeElement(this, vertex);

        // Remove all edges that this vertex participates.
        for (Edge edge : vertex.getEdges(Direction.BOTH, authorizations)) {
            removeEdge(mutations, edge, authorizations);
        }

        addDeleteRowToMutations(mutations, AccumuloVertex.ROW_KEY_PREFIX + vertex.getId(), authorizations);

        addMutations(mutations);
    }

    @Override
    public Edge addEdge(Object edgeId, Vertex outVertex, Vertex inVertex, String label, Visibility edgeVisibility, Property... properties) {
        if (outVertex == null) {
            throw new IllegalArgumentException("outVertex is required");
        }
        if (inVertex == null) {
            throw new IllegalArgumentException("inVertex is required");
        }
        if (edgeId == null) {
            edgeId = getIdGenerator().nextId();
        }

        AccumuloEdge edge = new AccumuloEdge(this, edgeId, outVertex.getId(), inVertex.getId(), label, edgeVisibility, properties);

        String edgeRowKey = AccumuloEdge.ROW_KEY_PREFIX + edge.getId();
        Mutation addEdgeMutation = new Mutation(edgeRowKey);
        ColumnVisibility edgeColumnVisibility = new ColumnVisibility(edgeVisibility.getVisibilityString());
        addEdgeMutation.put(AccumuloEdge.CF_SIGNAL, new Text(label), edgeColumnVisibility, EMPTY_VALUE);
        addEdgeMutation.put(AccumuloEdge.CF_OUT_VERTEX, new Text(outVertex.getId().toString()), edgeColumnVisibility, EMPTY_VALUE);
        addEdgeMutation.put(AccumuloEdge.CF_IN_VERTEX, new Text(inVertex.getId().toString()), edgeColumnVisibility, EMPTY_VALUE);
        for (Property property : edge.getProperties()) {
            addPropertyToMutation(addEdgeMutation, edgeRowKey, property);
        }

        Value edgeLabelValue = new Value(label.getBytes());

        // Update out vertex.
        Mutation addEdgeToOutMutation = new Mutation(AccumuloVertex.ROW_KEY_PREFIX + outVertex.getId());
        addEdgeToOutMutation.put(AccumuloVertex.CF_OUT_EDGE, new Text(edge.getId().toString()), edgeColumnVisibility, edgeLabelValue);
        addEdgeToOutMutation.put(AccumuloVertex.CF_OUT_VERTEX, new Text(inVertex.getId().toString()), edgeColumnVisibility, edgeLabelValue);

        // Update in vertex.
        Mutation addEdgeToInMutation = new Mutation(AccumuloVertex.ROW_KEY_PREFIX + inVertex.getId());
        addEdgeToInMutation.put(AccumuloVertex.CF_IN_EDGE, new Text(edge.getId().toString()), edgeColumnVisibility, edgeLabelValue);
        addEdgeToInMutation.put(AccumuloVertex.CF_IN_VERTEX, new Text(outVertex.getId().toString()), edgeColumnVisibility, edgeLabelValue);

        addMutations(addEdgeMutation, addEdgeToOutMutation, addEdgeToInMutation);

        if (outVertex instanceof AccumuloVertex) {
            ((AccumuloVertex) outVertex).addOutEdge(edge);
        }
        if (inVertex instanceof AccumuloVertex) {
            ((AccumuloVertex) inVertex).addInEdge(edge);
        }

        getSearchIndex().addElement(this, edge);

        return edge;
    }

    @Override
    public Iterable<Edge> getEdges(Authorizations authorizations) {
        return getEdgesInRange(null, null, authorizations);
    }

    @Override
    public void removeEdge(Edge edge, Authorizations authorizations) {
        List<Mutation> mutations = new ArrayList<Mutation>();
        removeEdge(mutations, edge, authorizations);
        addMutations(mutations);
    }

    @Override
    public void flush() {
        if (this.writer != null) {
            try {
                this.writer.flush();
            } catch (MutationsRejectedException e) {
                throw new SecureGraphException("Could not flush", e);
            }
        }
    }

    @Override
    public void shutdown() {
        try {
            flush();
        } catch (Exception ex) {
            throw new SecureGraphException(ex);
        }
    }

    private void removeEdge(List<Mutation> mutations, Edge edge, Authorizations authorizations) {
        checkNotNull(edge);

        getSearchIndex().removeElement(this, edge);

        Vertex out = edge.getVertex(Direction.OUT, authorizations);
        checkNotNull(out, "Unable to delete edge %s, can't find out vertex", edge.getId());
        Vertex in = edge.getVertex(Direction.IN, authorizations);
        checkNotNull(in, "Unable to delete edge %s, can't find in vertex", edge.getId());

        ColumnVisibility visibility = visibilityToAccumuloVisibility(edge.getVisibility());

        Mutation outMutation = new Mutation(AccumuloVertex.ROW_KEY_PREFIX + out.getId());
        outMutation.putDelete(AccumuloVertex.CF_OUT_EDGE, new Text(edge.getId().toString()), visibility);
        outMutation.putDelete(AccumuloVertex.CF_OUT_VERTEX, new Text(in.getId().toString()), visibility);
        mutations.add(outMutation);

        Mutation inMutation = new Mutation(AccumuloVertex.ROW_KEY_PREFIX + in.getId());
        inMutation.putDelete(AccumuloVertex.CF_IN_EDGE, new Text(edge.getId().toString()), visibility);
        inMutation.putDelete(AccumuloVertex.CF_IN_VERTEX, new Text(out.getId().toString()), visibility);
        mutations.add(inMutation);

        // Remove everything else related to edge.
        addDeleteRowToMutations(mutations, AccumuloEdge.ROW_KEY_PREFIX + edge.getId(), authorizations);
        addMutations(mutations);

        if (out instanceof AccumuloVertex) {
            ((AccumuloVertex) out).removeOutEdge(edge);
        }
        if (in instanceof AccumuloVertex) {
            ((AccumuloVertex) out).removeInEdge(edge);
        }
    }

    private void addDeleteRowToMutations(List<Mutation> mutations, String rowKey, Authorizations authorizations) {
        // TODO: How do we delete rows if a user can't see all properties?
        try {
            Scanner scanner = connector.createScanner(getConfiguration().getTableName(), toAccumuloAuthorizations(authorizations));
            scanner.setRange(new Range(rowKey));
            Mutation m = new Mutation(rowKey);
            for (Map.Entry<Key, Value> col : scanner) {
                m.putDelete(col.getKey().getColumnFamily(), col.getKey().getColumnQualifier(), new ColumnVisibility(col.getKey().getColumnVisibility()));
            }
            mutations.add(m);
        } catch (TableNotFoundException ex) {
            throw new SecureGraphException(ex);
        }
    }

    public ValueSerializer getValueSerializer() {
        return valueSerializer;
    }

    @Override
    public AccumuloGraphConfiguration getConfiguration() {
        return (AccumuloGraphConfiguration) super.getConfiguration();
    }

    @Override
    public Property createProperty(Object id, String name, Object value, Map<String, Object> metadata, Visibility visibility) {
        return new AccumuloProperty(id, name, value, metadata, visibility);
    }

    @Override
    public Vertex getVertex(Object vertexId, Authorizations authorizations) throws SecureGraphException {
        Iterator<Vertex> vertices = getVerticesInRange(vertexId, vertexId, authorizations).iterator();
        if (vertices.hasNext()) {
            return vertices.next();
        }
        return null;
    }

    private Iterable<Vertex> getVerticesInRange(Object startId, Object endId, final Authorizations authorizations) throws SecureGraphException {
        final Scanner scanner = createVertexScanner(authorizations);
        final AccumuloGraph graph = this;

        Key startKey;
        if (startId == null) {
            startKey = new Key(AccumuloVertex.ROW_KEY_PREFIX);
        } else {
            startKey = new Key(AccumuloVertex.ROW_KEY_PREFIX + startId);
        }

        Key endKey;
        if (endId == null) {
            endKey = new Key(AccumuloVertex.AFTER_ROW_KEY_PREFIX);
        } else {
            endKey = new Key(AccumuloVertex.ROW_KEY_PREFIX + endId + "~");
        }

        scanner.setRange(new Range(startKey, endKey));
        scanner.clearColumns();

        return new LookAheadIterable<Iterator<Map.Entry<Key, Value>>, Vertex>() {

            @Override
            protected boolean isIncluded(Iterator<Map.Entry<Key, Value>> src, Vertex dest) {
                return dest != null;
            }

            @Override
            protected Vertex convert(Iterator<Map.Entry<Key, Value>> next) {
                VertexMaker maker = new VertexMaker(graph, next);
                return maker.make();
            }

            @Override
            protected Iterator<Iterator<Map.Entry<Key, Value>>> createIterator() {
                return new RowIterator(scanner.iterator());
            }
        };
    }

    private Scanner createVertexScanner(Authorizations authorizations) throws SecureGraphException {
        return createElementVisibilityScanner(authorizations, ElementVisibilityRowFilter.OPT_FILTER_VERTICES);
    }

    private Scanner createEdgeScanner(Authorizations authorizations) throws SecureGraphException {
        return createElementVisibilityScanner(authorizations, ElementVisibilityRowFilter.OPT_FILTER_EDGES);
    }

    private Scanner createElementVisibilityScanner(Authorizations authorizations, String elementMode) throws SecureGraphException {
        try {
            Scanner scanner = connector.createScanner(getConfiguration().getTableName(), toAccumuloAuthorizations(authorizations));
            // TODO !!! when using a remote accumulo this doesn't work
//            IteratorSetting iteratorSetting = new IteratorSetting(
//                    100,
//                    ElementVisibilityRowFilter.class.getSimpleName(),
//                    ElementVisibilityRowFilter.class
//            );
//            iteratorSetting.addOption(elementMode, Boolean.TRUE.toString());
//            scanner.addScanIterator(iteratorSetting);
            return scanner;
        } catch (TableNotFoundException e) {
            throw new SecureGraphException(e);
        }
    }

    private org.apache.accumulo.core.security.Authorizations toAccumuloAuthorizations(Authorizations authorizations) {
        return new org.apache.accumulo.core.security.Authorizations(authorizations.getAuthorizations());
    }

    @Override
    public Edge getEdge(Object edgeId, Authorizations authorizations) {
        Iterator<Edge> edges = getEdgesInRange(edgeId, edgeId, authorizations).iterator();
        if (edges.hasNext()) {
            return edges.next();
        }
        return null;
    }

    private Iterable<Edge> getEdgesInRange(Object startId, Object endId, final Authorizations authorizations) throws SecureGraphException {
        final Scanner scanner = createEdgeScanner(authorizations);
        final AccumuloGraph graph = this;

        Key startKey;
        if (startId == null) {
            startKey = new Key(AccumuloEdge.ROW_KEY_PREFIX);
        } else {
            startKey = new Key(AccumuloEdge.ROW_KEY_PREFIX + startId);
        }

        Key endKey;
        if (endId == null) {
            endKey = new Key(AccumuloEdge.AFTER_ROW_KEY_PREFIX);
        } else {
            endKey = new Key(AccumuloEdge.ROW_KEY_PREFIX + endId + "~");
        }

        scanner.setRange(new Range(startKey, endKey));
        scanner.clearColumns();

        return new LookAheadIterable<Iterator<Map.Entry<Key, Value>>, Edge>() {

            @Override
            protected boolean isIncluded(Iterator<Map.Entry<Key, Value>> src, Edge dest) {
                return dest != null;
            }

            @Override
            protected Edge convert(Iterator<Map.Entry<Key, Value>> next) {
                EdgeMaker maker = new EdgeMaker(graph, next);
                return maker.make();
            }

            @Override
            protected Iterator<Iterator<Map.Entry<Key, Value>>> createIterator() {
                return new RowIterator(scanner.iterator());
            }
        };
    }

    private void printTable(Authorizations authorizations) {
        try {
            Scanner scanner = connector.createScanner(getConfiguration().getTableName(), toAccumuloAuthorizations(authorizations));
            RowIterator it = new RowIterator(scanner.iterator());
            while (it.hasNext()) {
                boolean first = true;
                Text lastColumnFamily = null;
                Iterator<Map.Entry<Key, Value>> row = it.next();
                while (row.hasNext()) {
                    Map.Entry<Key, Value> col = row.next();
                    if (first) {
                        System.out.println("\"" + col.getKey().getRow() + "\"");
                        first = false;
                    }
                    if (!col.getKey().getColumnFamily().equals(lastColumnFamily)) {
                        System.out.println("  \"" + col.getKey().getColumnFamily() + "\"");
                        lastColumnFamily = col.getKey().getColumnFamily();
                    }
                    System.out.println("    \"" + col.getKey().getColumnQualifier() + "\"(" + col.getKey().getColumnVisibility() + ")=\"" + col.getValue() + "\"");
                }
            }
            System.out.flush();
        } catch (TableNotFoundException e) {
            throw new SecureGraphException(e);
        }
    }

    public byte[] streamingPropertyValueTableData(String dataRowKey, Authorizations authorizations) {
        try {
            Scanner scanner = connector.createScanner(getConfiguration().getTableName(), toAccumuloAuthorizations(authorizations));
            scanner.setRange(new Range(dataRowKey));
            Iterator<Map.Entry<Key, Value>> it = scanner.iterator();
            if (it.hasNext()) {
                Map.Entry<Key, Value> col = it.next();
                return col.getValue().get();
            }
        } catch (Exception ex) {
            throw new SecureGraphException(ex);
        }
        throw new SecureGraphException("Unexpected end of row: " + dataRowKey);
    }

    public FileSystem getFileSystem() {
        return fileSystem;
    }

    private ColumnVisibility visibilityToAccumuloVisibility(Visibility visibility) {
        return new ColumnVisibility(visibility.getVisibilityString());
    }
}
