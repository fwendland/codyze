
package de.fraunhofer.aisec.crymlin.connectors.db;

import com.google.common.base.CaseFormat;
import com.google.common.collect.Sets;
import de.fraunhofer.aisec.analysis.structures.ServerConfiguration;
import de.fraunhofer.aisec.cpg.graph.EdgeProperty;
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.helpers.Benchmark;
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.javatuples.Pair;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.annotation.Transient;
import org.neo4j.ogm.annotation.typeconversion.Convert;
import org.neo4j.ogm.typeconversion.AttributeConverter;
import org.neo4j.ogm.typeconversion.CompositeAttributeConverter;
import org.reflections.Reflections;
import org.reflections.scanners.ResourcesScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import overflowdb.EdgeFactory;
import overflowdb.EdgeLayoutInformation;
import overflowdb.NodeFactory;
import overflowdb.NodeLayoutInformation;
import overflowdb.NodeRef;
import overflowdb.OdbConfig;
import overflowdb.OdbEdge;
import overflowdb.OdbGraph;
import overflowdb.OdbNode;
import overflowdb.OdbNodeProperty;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * <code>Database</code> implementation for OverflowDB.
 *
 * <p>OverflowDB is Shiftleft's fork of Tinkergraph, which is a more efficient in-memory graph DB
 * overflowing to disk when heap is full.
 */
@SuppressWarnings("java:S3740")
public class OverflowDatabase<N> implements Database<N> {

	private static final Logger log = LoggerFactory.getLogger(OverflowDatabase.class);
	// persistable property types, taken from Neo4j
	private static final String PRIMITIVES = "char,byte,short,int,long,float,double,boolean,char[],byte[],short[],int[],long[],float[],double[],boolean[]";
	private static final String AUTOBOXERS = "java.lang.Object"
			+ "java.lang.Character"
			+ "java.lang.Byte"
			+ "java.lang.Short"
			+ "java.lang.Integer"
			+ "java.lang.Long"
			+ "java.lang.Float"
			+ "java.lang.Double"
			+ "java.lang.Boolean"
			+ "java.lang.String"
			+ "java.lang.Object[]"
			+ "java.lang.Character[]"
			+ "java.lang.Byte[]"
			+ "java.lang.Short[]"
			+ "java.lang.Integer[]"
			+ "java.lang.Long[]"
			+ "java.lang.Float[]"
			+ "java.lang.Double[]"
			+ "java.lang.Boolean[]"
			+ "java.lang.String[]";

	/**
	 * Package containing all CPG classes *
	 */
	private static final String CPG_PACKAGE = "de.fraunhofer.aisec.cpg.graph";

	private final ServerConfiguration config;

	private OdbGraph graph;
	private OdbConfig odbConfig;

	private static final Map<String, List<Field>> fieldsIncludingSuperclasses = new HashMap<>();
	private static final Map<String, Pair<List<EdgeLayoutInformation>, List<EdgeLayoutInformation>>> inAndOutFields = new HashMap<>();
	private static final Map<String, Map<String, Object>> edgeProperties = new HashMap<>();
	private static final Map<String, Boolean> mapsToRelationship = new HashMap<>();
	private static final Map<String, Boolean> mapsToProperty = new HashMap<>();
	private static final Map<String, NodeLayoutInformation> layoutInformation = new HashMap<>();
	private static final Map<String, String[]> subClasses = new HashMap<>();
	private static final Map<String, String[]> superClasses = new HashMap<>();

	// Scan all classes in package
	private static final Reflections reflections = new Reflections(
		new ConfigurationBuilder()
				.setScanners(
					new SubTypesScanner(false /* don't exclude Object.class */),
					new ResourcesScanner())
				.setUrls(ClasspathHelper.forPackage(CPG_PACKAGE))
				.filterInputsBy(new FilterBuilder().include(FilterBuilder.prefix(CPG_PACKAGE))));

	/**
	 * maps from vertex ID to edge targets (map label to IDs of target vertices)
	 */
	private Map<Object, Map<String, Set<Object>>> edgesCache = new HashMap<>();
	private final Map<N, Vertex> nodeToVertex = new IdentityHashMap<>(); // No cache.
	private final Map<Long, N> nodesCache = new HashMap<>(); // Key is actually v.id() (Long)
	private final Set<N> saved = new HashSet<>();

	public OverflowDatabase(ServerConfiguration config) {
		try {
			if (!config.disableOverflow) {
				// Delete overflow cache file. Otherwise, OverflowDB will try to initialize the DB from it.
				Files.deleteIfExists(new File("graph-cache-overflow.bin").toPath());
			}
		}
		catch (IOException e) {
			log.error("IO", e);
		}

		this.config = config;

		// This is how to create indices. Unused at the moment.
		// graph.createIndex("EOG", Vertex.class);
	}

	public boolean connect() {
		// Create factories for nodes and edges of CPG.
		Pair<List<NodeFactory<OdbNode>>, List<EdgeFactory<OdbEdge>>> factories = getFactories();
		List<NodeFactory<OdbNode>> nodeFactories = factories.getValue0();
		List<EdgeFactory<OdbEdge>> edgeFactories = factories.getValue1();

		odbConfig = OdbConfig.withDefaults();

		if (config.disableOverflow) {
			odbConfig.disableOverflow();
		} else {
			odbConfig.withStorageLocation("graph-cache-overflow.bin").withHeapPercentageThreshold(5);
		}

		graph = OdbGraph.open(
			odbConfig,
			Collections.unmodifiableList(nodeFactories),
			Collections.unmodifiableList(edgeFactories));

		return true;
	}

	public boolean isConnected() {
		return this.graph != null;
	}

	public <T extends N> T find(Class<T> clazz, Long id) {
		GraphTraversal<Vertex, Vertex> v = graph.traversal().V(id);
		if (v.hasNext()) {
			return (T) vertexToNode(v.next());
		} else {
			return null;
		}
	}

	public void saveAll(Collection<? extends N> list) {
		Benchmark bench = new Benchmark(OverflowDatabase.class, "save all");
		for (N node : list) {
			save(node);
		}
		bench.stop();

		// Clear some caches. They are only needed during saving.
		inAndOutFields.clear();
		mapsToProperty.clear();
		mapsToRelationship.clear();
		nodesCache.clear();

		// Note: Do NOT clear "layoutInformation". They will be needed for queries.
	}

	/**
	 * Saves a single Node in OverflowDB.
	 */
	private void save(N node) {
		if (saved.contains(node)) {
			// Has been seen before. Skip
			return;
		}

		// Store node
		createVertex(node);
		saved.add(node);

		if (!Node.class.isAssignableFrom(node.getClass())) {
			throw new RuntimeException("Cannot apply SubgraphWalker to " + node.getClass() + ".");
		}

		for (Node child : SubgraphWalker.getAstChildren((Node) node)) {
			save((N) child);
		}
	}

	/**
	 * Returns a map of all properties of a Vertex. This is a copy of the actual map stored in the
	 * vertex and can thus be safely modified.
	 *
	 * <p>Note that the map will not contain the id() and label() of the Vertex. If it contains
	 * properties with key "id" or "label", their values might or might not equal the results of id()
	 * and label(). Always use the latter functions to get IDs and labels.
	 */
	private <K, V> Map<K, V> getAllProperties(Vertex v) {
		try {
			Field node = NodeRef.class.getDeclaredField("node");
			node.setAccessible(true);
			Object n = node.get(v);
			Field propertyValues = n.getClass().getDeclaredField("propertyValues");
			propertyValues.setAccessible(true);
			return new HashMap<>((Map<K, V>) propertyValues.get(n));
		}
		catch (NoSuchFieldException e) {
			log.error("Vertex has no field called propertyValues!");
		}
		catch (IllegalAccessException e) {
			log.error("IllegalAccess ", e);
		}
		return Collections.emptyMap();
	}

	/**
	 * Constructs a native Node object from a given Vertex or returns a cached Node object.
	 *
	 * @return Null, if the Vertex could not be converted into a native object.
	 */
	@Nullable
	public N vertexToNode(Vertex v) {
		// avoid loops
		if (nodesCache.containsKey((Long) v.id())) {
			return nodesCache.get((Long) v.id());
		}

		Class<?> targetClass;
		String nodeType = (String) v.property("nodeType").value();
		try {
			targetClass = Class.forName(nodeType);
		}
		catch (ClassNotFoundException e) {
			log.error("Class not found (node type): {}", nodeType);
			return null;
		}

		try {
			Constructor<?> defaultConstructor = targetClass.getDeclaredConstructor();
			defaultConstructor.setAccessible(true);
			N node = (N) defaultConstructor.newInstance();
			nodesCache.put((Long) v.id(), node);

			for (Field f : getFieldsIncludingSuperclasses(targetClass)) {
				f.setAccessible(true);
				if (hasAnnotation(f, Id.class)) {
					/* Retain the original vertex ID via this dedicated ID field */
					f.set(node, v.id());
				} else if (hasAnnotation(f, Convert.class)) {
					/* Need to first handle attributes which need a special treatment (annotated with AttributeConverter or CompositeConverter) */
					Object value = convertToNodeProperty(v, f);
					f.set(node, value);
				} else if (mapsToProperty(f) && v.property(f.getName()).isPresent()) {
					/* Handle "normal" properties */
					Object value = restoreProblematicProperty(v, f.getName());
					f.set(node, value);
				} else if (mapsToRelationship(f)) {
					/* Handle properties which should be treated as relationships */
					Direction direction = getRelationshipDirection(f);
					List<N> targets = IteratorUtils.stream(v.vertices(direction, getRelationshipLabel(f)))
							.filter(distinctByKey(Vertex::id))
							.map(this::vertexToNode)
							.collect(Collectors.toList());
					if (isCollection(f.getType())) {
						/*
						 * we don't know for sure that the relationships are stored as a list. Might as well be any other collection. Thus we'll create it using
						 * reflection
						 */
						Class<?> collectionType;
						String className = "";
						try {
							className = (String) v.property(f.getName() + "_type").value();
							collectionType = Class.forName(className);
						}
						catch (ClassNotFoundException e) {
							log.error("Class not found: {}", className);
							continue;
						}
						catch (IllegalStateException e) {
							log.error(
								"Unable to instantiate collection property {} for node, no information about actual element type",
								f);
							continue;
						}
						assert Collection.class.isAssignableFrom(collectionType);
						handleCollections(node, f, targets, collectionType);
					} else if (f.getType().isArray()) {
						Object targetArray = Array.newInstance(f.getType(), targets.size());
						for (int i = 0; i < targets.size(); i++) {
							Array.set(targetArray, i, targets.get(i));
						}
						f.set(node, targetArray);
					} else {
						// single edge
						if (!targets.isEmpty() && !Modifier.isFinal(f.getModifiers())) {
							f.set(node, targets.get(0));
						}
					}
				}
			}
			return node;
		}
		catch (Exception e) {
			log.error("Error creating new {} node", targetClass.getName(), e);
		}
		return null;
	}

	private void handleCollections(N node, Field f, List<N> targets, Class<?> collectionType)
			throws InstantiationException, IllegalAccessException, InvocationTargetException,
			NoSuchMethodException, ClassNotFoundException {
		Collection targetCollection;
		Class<?> clazz = Class.forName("java.util.ImmutableCollections");

		if (collectionType.getEnclosingClass() != null
				&& clazz.isAssignableFrom(collectionType.getEnclosingClass())) {
			// immutable collections have size 1 and 2 as special cases
			Constructor<?> constructor;
			switch (targets.size()) {
				case 0:
					targetCollection = (Collection) collectionType.getDeclaredConstructor().newInstance();
					break;
				case 1:
					constructor = collectionType.getDeclaredConstructor(Object.class);
					constructor.setAccessible(true);
					targetCollection = (Collection) constructor.newInstance(targets.get(0));
					break;
				case 2:
					// not all immutable collections might have a special constructor for 2 elements
					try {
						constructor = collectionType.getDeclaredConstructor(Object.class, Object.class);
						constructor.setAccessible(true);
						targetCollection = (Collection) constructor.newInstance(targets.get(0), targets.get(1));
						break;
					}
					catch (NoSuchMethodException e) {
						// fall through
					}
				default:
					constructor = collectionType.getDeclaredConstructor(Object[].class);
					constructor.setAccessible(true);
					targetCollection = (Collection) constructor.newInstance(targets.toArray());
					break;
			}
		} else {
			targetCollection = (Collection) collectionType.getDeclaredConstructor().newInstance();
			targetCollection.addAll(targets);
		}
		f.set(node, targetCollection);
	}

	private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
		Set<Object> seen = ConcurrentHashMap.newKeySet();
		return t -> seen.add(keyExtractor.apply(t));
	}

	/**
	 * Creates a new Vertex from a given native Node or returns a Vertex from cache.
	 */
	public Vertex createVertex(N n) {
		if (nodeToVertex.containsKey(n)) {
			return nodeToVertex.get(n);
		}

		HashMap<Object, Object> properties = new HashMap<>();

		// Set node label (from its class)
		properties.put(T.label, n.getClass().getSimpleName());

		// Set node properties (from field values which are not relationships)
		List<Field> fields = getFieldsIncludingSuperclasses(n.getClass());
		for (Field f : fields) {
			if (!mapsToRelationship(f) && mapsToProperty(f)) {
				try {
					f.setAccessible(true);
					Object x = f.get(n);
					if (x == null) {
						continue;
					}
					if (hasAnnotation(f, Convert.class)) {
						properties.putAll(convertToVertexProperties(f, x));
					} else if (mapsToProperty(f)) {
						properties.put(f.getName(), x);
					} else {
						log.info("Not a property");
					}
				}
				catch (IllegalAccessException e) {
					log.error("IllegalAccessException", e);
				}
			}
		}

		convertProblematicProperties(properties);

		// Add types of nodes (names of superclasses) to properties
		List<String> superclasses = Arrays.asList(getSuperclasses(n.getClass()));
		properties.put("labels", superclasses);

		// Add hashCode of object so we can easily retrieve a vertex from graph given the node object
		properties.put("hashCode", n.hashCode());

		// Add current class needed for translating it back to a node object
		properties.put("nodeType", n.getClass().getName());

		List<Object> props = linearize(properties);

		/* Create a new vertex. Note that this will auto-generate a new id() for the vertex and thus this method should only be called once per Node. */
		Vertex result = graph.addVertex(props.toArray());
		nodeToVertex.put(n, result);

		createEdges(result, n);
		return result;
	}

	private List<Object> linearize(Map<?, ?> properties) {
		List<Object> props = new ArrayList<>(properties.size() * 2);
		for (Map.Entry p : properties.entrySet()) {
			props.add(p.getKey());
			props.add(p.getValue());
		}
		return props;
	}

	/**
	 * OverflowDB has problems when trying to persist things like String arrays. To ensure that
	 * overflowing to disk works as intended, this method ensures that such properties are converted
	 * to a persistable format.
	 */
	private void convertProblematicProperties(HashMap<Object, Object> properties) {
		for (Object key : new HashSet<>(properties.keySet())) {
			Object value = properties.get(key);
			if (value instanceof Integer) {
				// mimic neo4j-ogm behaviour: ints are stored as longs
				properties.put(key, Long.valueOf((Integer) value));
				properties.put(key.toString() + "_original", value);
			} else if (value instanceof Character) {
				// related: https://github.com/ShiftLeftSecurity/overflowdb/issues/42
			} else if (value instanceof String[]) {
				properties.put(key, String.join(", ", (String[]) value));
				properties.put(key + "_converted-from", "String[]");
			}
		}
	}

	/**
	 * Inverse of <code>convertProblematicProperties</code> in the sense that a single property value
	 * is retrieved from a <code>Vertex</code> and converted back into its intended format (if
	 * applicable). See <code>restoreProblematicProperties</code> for conversion of all node
	 * properties.
	 */
	private Object restoreProblematicProperty(Vertex v, String key) {
		// Check whether this value has been converted before being persisted (e.g. String[])
		if (v.property(key + "_converted-from").isPresent()) {
			String type = (String) v.property(key + "_converted-from").value();
			switch (type) {
				case "String[]":
					return ((String) v.property(key).value()).split(", ");
				case "Character":
					return ((String) v.property(key).value()).charAt(0);
				default:
					log.error("Unknown converter type: {}", type);
					return null;
			}
		} else {
			// If available, take the "_original" version, which might be present because of an
			// int->long conversion
			return v.property(key + "_original").orElse(v.property(key).value());
		}
	}

	/**
	 * Applies <code>restoreProblematicProperty</code> on all properties of a given <code>Vertex
	 * </code>
	 */
	private Map<String, Object> restoreProblematicProperties(Vertex v) {
		Map<String, Object> properties = getAllProperties(v);
		for (String key : properties.keySet()) {
			Object value = restoreProblematicProperty(v, key);
			properties.put(key, value);
		}
		return properties;
	}

	/**
	 * Applies AttributeConverter or CompositeAttributeConverter to flatten a complex field into a map
	 * of properties.
	 */
	private Map<Object, Object> convertToVertexProperties(Field f, Object content) {
		try {
			Object converter = f.getAnnotation(Convert.class).value().getDeclaredConstructor().newInstance();
			if (converter instanceof AttributeConverter) {
				// Single attribute will be provided
				return Map.of(f.getName(), ((AttributeConverter) converter).toGraphProperty(content));
			} else if (converter instanceof CompositeAttributeConverter) {
				// Yields a map of properties
				return ((CompositeAttributeConverter) converter).toGraphProperties(content);
			}
		}
		catch (NoSuchMethodException e) {
			log.error("A converter needs to have an empty constructor", e);
		}
		catch (Exception e) {
			log.error("Error creating new converter instance", e);
		}

		return Collections.emptyMap();
	}

	/**
	 * Converts a subset of a vertices' <code>v</code> properties into a value for a complex field
	 * <code>f</code>.
	 *
	 * <p>Inverse of <code>convertToVertexProperties</code>.
	 */
	private Object convertToNodeProperty(Vertex v, Field f) {
		try {
			Object converter = f.getAnnotation(Convert.class).value().getDeclaredConstructor().newInstance();
			// check whether any property value has been altered. If so, restore its original version
			Map<String, Object> properties = restoreProblematicProperties(v);
			if (converter instanceof AttributeConverter) {
				// Single attribute will be provided
				return ((AttributeConverter) converter).toEntityAttribute(properties.get(f.getName()));
			} else if (converter instanceof CompositeAttributeConverter) {
				return ((CompositeAttributeConverter) converter).toEntityAttribute(properties);
			}
		}
		catch (NoSuchMethodException e) {
			log.error("A converter needs to have an empty constructor", e);
		}
		catch (Exception e) {
			log.error("Error when trying to convert", e);
		}

		return null;
	}

	private void createEdges(Vertex v, N n) {
		for (Field f : getFieldsIncludingSuperclasses(n.getClass())) {
			if (mapsToRelationship(f)) {

				Direction direction = getRelationshipDirection(f);
				String relName = getRelationshipLabel(f);
				Map<String, Object> edgePropertiesForField = getEdgeProperties(f);

				try {
					f.setAccessible(true);
					Object x = f.get(n);
					if (x == null) {
						continue;
					}

					// provide a type hint for later re-translation into a field
					v.property(f.getName() + "_type", x.getClass().getName());

					// Create an edge from a field value
					if (isCollection(x.getClass())) {
						// Add multiple edges for collections
						connectAll(
							v, relName, edgePropertiesForField, (Collection) x, direction.equals(Direction.IN));
					} else if (Node[].class.isAssignableFrom(x.getClass())) {
						connectAll(
							v,
							relName,
							edgePropertiesForField,
							Collections.singletonList(x),
							direction.equals(Direction.IN));
					} else {
						// Add single edge for non-collections
						Vertex target = connect(
							v, relName, edgePropertiesForField, (Node) x, direction.equals(Direction.IN));
						assert target.property("hashCode").value().equals(x.hashCode());
					}
				}
				catch (IllegalAccessException e) {
					log.error("IllegalAccessException", e);
				}
			}
		}
	}

	private Vertex connect(
			Vertex sourceVertex,
			String label,
			Map<String, Object> edgeProperties,
			Node targetNode,
			boolean reverse) {
		Vertex targetVertex = null;
		Vertex targetId = nodeToVertex.get(targetNode);
		if (targetId != null) {
			Iterator<Vertex> vIt = graph.vertices(targetId);
			if (vIt.hasNext()) {
				targetVertex = vIt.next();
			}
		}
		if (targetVertex == null) {
			targetVertex = createVertex((N) targetNode);
		}

		// determine the actual source and target for this edge (depending on the edge direction)
		Vertex actualSource = reverse ? targetVertex : sourceVertex;
		Vertex actualTarget = reverse ? sourceVertex : targetVertex;

		// prepare the edge cache
		edgesCache.putIfAbsent(actualSource.id(), new HashMap<>());
		Map<String, Set<Object>> currOutgoingEdges = edgesCache.get(actualSource.id());
		currOutgoingEdges.putIfAbsent(label, new HashSet<>());
		// only add edge if this exact one has not been added before
		if (currOutgoingEdges.get(label).add(actualTarget)) {
			actualSource.addEdge(label, actualTarget, linearize(edgeProperties).toArray());
		}

		return targetVertex;
	}

	private void connectAll(
			Vertex sourceVertex,
			String label,
			Map<String, Object> edgeTypes,
			Collection<?> targetNodes,
			boolean reverse) {
		for (Object entry : targetNodes) {
			if (Node.class.isAssignableFrom(entry.getClass())) {
				Vertex target = connect(sourceVertex, label, edgeTypes, (Node) entry, reverse);
				assert target.property("hashCode").value().equals(entry.hashCode());
			} else {
				log.info("Found non-Node class in collection for label \"{}\"", label);
			}
		}
	}

	/**
	 * Reproduced Neo4J-OGM behavior for mapping fields to relationships (or properties otherwise).
	 */
	private boolean mapsToRelationship(Field f) {
		// Using cache. This method is called from several places and does heavyweight reflection
		String key = f.getDeclaringClass().getName() + "." + f.getName();
		if (mapsToRelationship.containsKey(key)) {
			return mapsToRelationship.get(key);
		}

		boolean result = hasAnnotation(f, Relationship.class) || Node.class.isAssignableFrom(getContainedType(f));
		mapsToRelationship.putIfAbsent(key, result);
		return result;
	}

	private Class<?> getContainedType(Field f) {
		if (Collection.class.isAssignableFrom(f.getType())) {
			// Check whether the elements in this collection are nodes
			assert f.getGenericType() instanceof ParameterizedType;
			Type[] elementTypes = ((ParameterizedType) f.getGenericType()).getActualTypeArguments();
			assert elementTypes.length == 1;
			return (Class<?>) elementTypes[0];
		} else if (f.getType().isArray()) {
			return f.getType().getComponentType();
		} else {
			return f.getType();
		}
	}

	private boolean mapsToProperty(Field f) {
		// Check cache first to reduce heavy reflection
		String key = f.getDeclaringClass().getName() + "." + f.getName();
		if (mapsToProperty.containsKey(key)) {
			return mapsToProperty.get(key);
		}

		// Transient fields are not supposed to be persisted
		if (Modifier.isTransient(f.getModifiers()) || hasAnnotation(f, Transient.class)) {
			mapsToProperty.putIfAbsent(key, false);
			return false;
		}

		// constant values are not considered properties
		if (Modifier.isFinal(f.getModifiers())) {
			mapsToProperty.putIfAbsent(key, false);
			return false;
		}

		// check if we have a converter for this
		if (f.getAnnotation(Convert.class) != null) {
			mapsToProperty.putIfAbsent(key, true);
			return true;
		}

		// check whe ther this is some kind of primitive datatype that seems likely to be a property
		String type = getContainedType(f).getTypeName();

		boolean result = PRIMITIVES.contains(type) || AUTOBOXERS.contains(type);
		mapsToProperty.putIfAbsent(key, result);
		return result;
	}

	private boolean isCollection(Class<?> aClass) {
		return Collection.class.isAssignableFrom(aClass);
	}

	/**
	 * Returns all classes implementing a given class c.
	 *
	 * <p>The result does not include c itself.
	 */
	public static String[] getSubclasses(Class<?> c) {
		if (subClasses.containsKey(c.getName())) {
			return subClasses.get(c.getName());
		}

		Set<String> subclasses = new HashSet<>();
		subclasses.add(c.getSimpleName());
		subclasses.addAll(
			reflections.getSubTypesOf(c)
					.stream()
					.map(Class::getSimpleName)
					.collect(Collectors.toSet()));
		String[] result = subclasses.toArray(new String[0]);
		subClasses.put(c.getName(), result);
		return result;
	}

	private static String[] getSuperclasses(Class<?> c) {
		if (superClasses.containsKey(c.getName())) {
			return superClasses.get(c.getName());
		}

		List<String> labels = new ArrayList<>();
		while (!c.equals(Object.class)) {
			labels.add(c.getSimpleName());
			c = c.getSuperclass();
		}

		String[] result = labels.toArray(new String[0]);
		superClasses.put(c.getName(), result);
		return result;
	}

	public void clearDatabase() {
		/* The way to fully delete an OverflowDB is to simply close the graph
		and connect again */
		if (isConnected()) {
			close();
			connect();
		}
	}

	public void close() {
		if (this.odbConfig != null) {
			// do not save database on close
			this.odbConfig.withStorageLocation(null);
		}

		// Clear saved nodes.
		this.saved.clear();

		// Close graph
		try {
			this.graph.traversal().V().drop();
			this.graph.traversal().E().drop();
			this.graph.close();
		}
		catch (Exception e) {
			log.error("Closing graph", e);
		}

		this.nodeToVertex.clear();
	}

	public void destroy() {
		close();
	}

	public long getNumNodes() {
		return graph.traversal().V().count().next();
	}

	/**
	 * Generate the Node and Edge factories that are required by OverflowDB.
	 */
	private Pair<List<NodeFactory<OdbNode>>, List<EdgeFactory<OdbEdge>>> getFactories() {
		Set<Class<? extends Node>> allClasses = reflections.getSubTypesOf(Node.class);
		allClasses.add(Node.class);

		// Make sure to first call createEdgeFactories, which will collect some IN fields needed for
		// createNodeFactories
		Pair<List<EdgeFactory<OdbEdge>>, Map<Class, Set<MutableEdgeLayout>>> edgeFactories = createEdgeFactories(allClasses);
		List<NodeFactory<OdbNode>> nodeFactories = createNodeFactories(allClasses, edgeFactories.getValue1());

		return new Pair<>(nodeFactories, edgeFactories.getValue0());
	}

	/**
	 * Edge factories for all fields with @Relationship annotation
	 *
	 * @param allClasses Set of classes to create edge factories for.
	 * @return a Pair of edge factories and a pre-computed map of classes/IN-Fields.
	 */
	private Pair<List<EdgeFactory<OdbEdge>>, Map<Class, Set<MutableEdgeLayout>>> createEdgeFactories(
			@NonNull Set<Class<? extends Node>> allClasses) {
		final HashMap<Class, Set<Class>> subclassCache = new HashMap<>();
		Map<Class, Set<MutableEdgeLayout>> inEdgeLayouts = new HashMap<>();
		List<EdgeFactory<OdbEdge>> edgeFactories = new ArrayList<>();
		for (Class c : allClasses) {
			for (Field field : getFieldsIncludingSuperclasses(c)) {
				if (!mapsToRelationship(field)) {
					continue;
				}

				/*
				 * Handle situation where class A has a field f to class B:
				 *
				 * <p>B and all of its subclasses need to accept INCOMING edges labeled "f". Additionally,
				 * the INCOMING edges need to accept edge properties that may come up.
				 */
				if (getRelationshipDirection(field).equals(Direction.OUT)) {
					List<Class> classesWithIncomingEdge = new ArrayList<>();
					classesWithIncomingEdge.add(getContainedType(field));
					for (int i = 0; i < classesWithIncomingEdge.size(); i++) {
						Class subclass = classesWithIncomingEdge.get(i);
						String relName = getRelationshipLabel(field);
						if (!inEdgeLayouts.containsKey(subclass)) {
							inEdgeLayouts.put(subclass, new HashSet<>());
						}

						// Make sure that incoming edges accept the union of all possible edge properties
						Optional<MutableEdgeLayout> currRelLayout = inEdgeLayouts.get(subclass)
								.stream()
								.filter(e -> e.getLabel().equals(relName))
								.findFirst();
						Set<String> propertyKeys = getEdgeProperties(field).keySet();
						if (currRelLayout.isPresent()) {
							currRelLayout.get().getPropertyKeys().addAll(propertyKeys);
						} else {
							MutableEdgeLayout newLayout = new MutableEdgeLayout(relName, propertyKeys);
							inEdgeLayouts.get(subclass).add(newLayout);
						}

						Set<Class> targets = subclassCache.get(subclass);
						if (targets == null) {
							targets = reflections.getSubTypesOf(subclass);
							subclassCache.put(subclass, targets);
						}
						classesWithIncomingEdge.addAll(targets);
					}
				}

				EdgeFactory<OdbEdge> edgeFactory = new EdgeFactory<>() {
					@Override
					public String forLabel() {
						return getRelationshipLabel(field);
					}

					@Override
					public OdbEdge createEdge(OdbGraph graph, NodeRef outNode, NodeRef inNode) {
						return new OdbEdge(
							graph, getRelationshipLabel(field), outNode, inNode, Sets.newHashSet()) {
						};
					}
				};
				edgeFactories.add(edgeFactory);
			}
		}
		return new Pair<>(edgeFactories, inEdgeLayouts);
	}

	private List<Field> getFieldsIncludingSuperclasses(Class c) {
		// Try cache first. There are only few (<50) different inputs c, but many calls to this method.
		if (fieldsIncludingSuperclasses.containsKey(c.getName())) {
			return fieldsIncludingSuperclasses.get(c.getName());
		}

		List<Field> fields = new ArrayList<>();
		var parent = c;
		while (parent != Object.class) {
			fields.addAll(Arrays.asList(parent.getDeclaredFields()));
			parent = parent.getSuperclass();
		}
		fieldsIncludingSuperclasses.putIfAbsent(c.getName(), fields);

		return fields;
	}

	/**
	 * Reproduces Neo4j-OGM's behavior of creating edge labels.
	 *
	 * <p>Values set by the <code>@Relationship</code> annotation take precedence and determine the
	 * edge label. If no annotation is given or if the annotation does not contain a value, the label
	 * is created from the field name in uppercase underscore notation.
	 *
	 * <p>A field name of <code>myField</code> thus becomes a label <code>MY_FIELD</code>.
	 */
	private String getRelationshipLabel(Field f) {
		String relName = f.getName();
		if (hasAnnotation(f, Relationship.class)) {
			Relationship rel = (Relationship) Arrays.stream(f.getAnnotations())
					.filter(a -> a.annotationType().equals(Relationship.class))
					.findFirst()
					.orElse(null);
			return (rel == null || rel.value().trim().isEmpty()) ? f.getName() : rel.value();
		}

		return CaseFormat.UPPER_CAMEL.converterTo(CaseFormat.UPPER_UNDERSCORE).convert(relName);
	}

	private Map<String, Object> getEdgeProperties(Field f) {
		String fieldFqn = f.getDeclaringClass().getName() + "." + f.getName();
		if (edgeProperties.containsKey(fieldFqn)) {
			return edgeProperties.get(fieldFqn);
		}

		Map<String, Object> properties = Arrays.stream(f.getAnnotations())
				.filter(a -> a.annotationType().getAnnotation(EdgeProperty.class) != null)
				.collect(
					Collectors.toMap(
						a -> a.annotationType().getAnnotation(EdgeProperty.class).key(),
						a -> {
							try {
								Method valueMethod = a.getClass().getDeclaredMethod("value");
								Object value = valueMethod.invoke(a);
								String result;
								if (value.getClass().isArray()) {
									String[] strings = new String[Array.getLength(value)];
									for (int i = 0; i < Array.getLength(value); i++) {
										strings[i] = Array.get(value, i).toString();
									}
									result = String.join(", ", strings);
								} else {
									result = value.toString();
								}
								return result;
							}
							catch (NoSuchMethodException
									| IllegalAccessException
									| InvocationTargetException e) {
								log.error(
									"Edge property annotation {} does not provide a 'value' method of type String",
									a.getClass().getName(),
									e);
								return "UNKNOWN_PROPERTY";
							}
						}));

		edgeProperties.put(fieldFqn, properties);
		return properties;
	}

	/**
	 * For each class that should become a node in the graph, we must register a NodeFactory and for
	 * each edge we must register an EdgeFactory. The factories provide labels and properties,
	 * according to the field names and/or their annotations.
	 *
	 * @param allClasses    classes to create node factories for.
	 * @param inEdgeLayouts Map from class names to IN edge layouts which must be supported by that
	 *                      class. Will be collected by <code>createEdgeFactories</code>
	 */
	private List<NodeFactory<OdbNode>> createNodeFactories(
			@NonNull Set<Class<? extends Node>> allClasses,
			@NonNull Map<Class, Set<MutableEdgeLayout>> inEdgeLayouts) {
		List<NodeFactory<OdbNode>> nodeFactories = new ArrayList<>();
		for (Class<? extends Node> c : allClasses) {
			nodeFactories.add(createNodeFactory(c, inEdgeLayouts));
		}
		return nodeFactories;
	}

	private NodeFactory<OdbNode> createNodeFactory(
			@NonNull Class<? extends Node> c, @NonNull Map<Class, Set<MutableEdgeLayout>> inEdgeLayouts) {
		return new NodeFactory<>() {
			@Override
			public String forLabel() {
				return c.getSimpleName();
			}

			@Override
			public int forLabelId() {
				return c.getSimpleName().hashCode();
			}

			@Override
			public OdbNode createNode(NodeRef<OdbNode> ref) {
				return new OdbNode(ref) {
					private Map<String, Object> propertyValues = new HashMap<>();

					/**
					 * All fields annotated with <code></code>@Relationship</code> will become edges.
					 *
					 * <p>Note that this method MUST be fast, as it will also be called during queries
					 * iterating over edges.
					 */
					@Override
					public NodeLayoutInformation layoutInformation() {
						if (layoutInformation.containsKey(c.getSimpleName())) {
							return layoutInformation.get(c.getSimpleName());
						}
						if (((long) ref.id()) % 100 == 0) {
							log.info("Cache miss for layoutInformation for {}", c.getSimpleName());
						}

						Pair<List<EdgeLayoutInformation>, List<EdgeLayoutInformation>> inAndOut = getInAndOutFields(c);

						List<EdgeLayoutInformation> out = inAndOut.getValue1();
						List<EdgeLayoutInformation> in = inAndOut.getValue0();
						in.addAll(
							inEdgeLayouts.getOrDefault(c, new HashSet<>())
									.stream()
									.filter(e -> e.label != null)
									.map(MutableEdgeLayout::makeImmutable)
									.collect(Collectors.toList()));

						out = deduplicateEdges(out);
						in = deduplicateEdges(in);

						Set<String> properties = new HashSet<>();
						for (Field f : getFieldsIncludingSuperclasses(c)) {
							if (mapsToProperty(f)) {
								properties.add(f.getName());
								if (isCollection(f.getType())) {
									// type hints for exact collection type
									properties.add(f.getName() + "_type");
								} else if (Character.class.isAssignableFrom(f.getType())
										|| String[].class.isAssignableFrom(f.getType())) {
									properties.add(f.getName() + "_converted-from");
								} else if (Integer.class.isAssignableFrom(f.getType())) {
									properties.add(f.getName() + "_original");
								}
							}
						}

						NodeLayoutInformation result = new NodeLayoutInformation(forLabelId(), properties, out, in);
						layoutInformation.putIfAbsent(c.getSimpleName(), result);
						return result;
					}

					@Override
					@SuppressWarnings("java:S125")
					protected <V> Iterator<VertexProperty<V>> specificProperties(String key) {
						/*
						 * We filter out null property values here. GraphMLWriter cannot handle these and will die with NPE. Gremlin assumes that property values are
						 * non-null.
						 */
						Object values = this.propertyValues.get(key);
						if (values == null) {
							// the following empty collection filter breaks vertexToNode, but might be needed
							// for GraphMLWriter. Leaving this in for future reference
							//                || (Collection.class.isAssignableFrom(values.getClass())
							//                    && ((Collection) values).isEmpty())) {
							return new ArrayList<VertexProperty<V>>(0).iterator();
						}
						return IteratorUtils.<VertexProperty<V>> of(
							new OdbNodeProperty(this, key, this.propertyValues.get(key)));
					}

					@Override
					protected Object specificProperty2(String key) {
						return this.propertyValues.get(key);
					}

					@Override
					public Map<String, Object> valueMap() {
						return propertyValues;
					}

					@Override
					protected <V> VertexProperty<V> updateSpecificProperty(
							VertexProperty.Cardinality cardinality, String key, V value) {
						this.propertyValues.put(key, value);
						return new OdbNodeProperty<>(this, key, value);
					}

					@Override
					protected void removeSpecificProperty(String key) {
						propertyValues.remove(key);
					}
				};
			}

			@Override
			public NodeRef createNodeRef(OdbGraph graph, long id) {
				return new NodeRef(graph, id) {
					@Override
					public String label() {
						return c.getSimpleName();
					}
				};
			}
		};
	}

	private List<EdgeLayoutInformation> deduplicateEdges(List<EdgeLayoutInformation> edges) {
		Set<EdgeLayoutInformation> deduplicated = new TreeSet<>(Comparator.comparing(e -> e.label));
		deduplicated.addAll(edges);
		return new ArrayList<>(deduplicated);
	}

	private Pair<List<EdgeLayoutInformation>, List<EdgeLayoutInformation>> getInAndOutFields(
			Class c) {
		if (inAndOutFields.containsKey(c.getName())) {
			return inAndOutFields.get(c.getName());
		}
		List<EdgeLayoutInformation> inFields = new ArrayList<>();
		List<EdgeLayoutInformation> outFields = new ArrayList<>();

		for (Field f : getFieldsIncludingSuperclasses(c)) {
			if (mapsToRelationship(f)) {
				String relName = getRelationshipLabel(f);
				Direction dir = getRelationshipDirection(f);
				Set<String> propertyKeys = getEdgeProperties(f).keySet()
						.stream()
						.map(String.class::cast)
						.collect(Collectors.toSet());
				if (dir.equals(Direction.IN)) {
					inFields.add(new EdgeLayoutInformation(relName, propertyKeys));
				} else if (dir.equals(Direction.OUT) || dir.equals(Direction.BOTH)) {
					outFields.add(new EdgeLayoutInformation(relName, propertyKeys));
					// Note that each target of an OUT field must also be registered as an IN field
				}
			}
		}

		Pair<List<EdgeLayoutInformation>, List<EdgeLayoutInformation>> result = new Pair<>(inFields, outFields);
		inAndOutFields.putIfAbsent(c.getName(), result);
		return result;
	}

	private boolean hasAnnotation(Field f, Class annotationClass) {
		return Arrays.stream(f.getAnnotations())
				.anyMatch(a -> a.annotationType().equals(annotationClass));
	}

	public Graph getGraph() {
		return this.graph;
	}

	private Direction getRelationshipDirection(Field f) {
		Direction direction = Direction.OUT;
		if (hasAnnotation(f, Relationship.class)) {
			Relationship rel = (Relationship) Arrays.stream(f.getAnnotations())
					.filter(a -> a.annotationType().equals(Relationship.class))
					.findFirst()
					.orElse(null);
			if (rel == null) {
				log.error("Relation direction is null");
				return Direction.BOTH;
			}
			switch (rel.direction()) {
				case Relationship.INCOMING:
					direction = Direction.IN;
					break;
				case Relationship.UNDIRECTED:
					direction = Direction.BOTH;
					break;
				default:
					direction = Direction.OUT;
			}
		}
		return direction;
	}

	private static class MutableEdgeLayout {
		private String label;
		private Set<String> propertyKeys;

		public String getLabel() {
			return label;
		}

		public void setLabel(String label) {
			this.label = label;
		}

		public Set<String> getPropertyKeys() {
			return propertyKeys;
		}

		public void setPropertyKeys(Set<String> propertyKeys) {
			this.propertyKeys = propertyKeys;
		}

		public MutableEdgeLayout(String label, Set<String> propertyKeys) {
			this.label = label;
			// make sure that we can mutate the set
			this.propertyKeys = new HashSet<>(propertyKeys);
		}

		public EdgeLayoutInformation makeImmutable() {
			return new EdgeLayoutInformation(label, propertyKeys);
		}

		@Override
		public String toString() {
			return "MutableEdgeLayout{"
					+ "label='"
					+ label
					+ '\''
					+ ", propertyKeys="
					+ propertyKeys
					+ '}';
		}
	}
}
