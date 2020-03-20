
package de.fraunhofer.aisec.crymlin;

import de.fraunhofer.aisec.analysis.scp.SimpleConstantResolver;
import de.fraunhofer.aisec.analysis.structures.*;
import de.fraunhofer.aisec.analysis.utils.Utils;
import de.fraunhofer.aisec.cpg.graph.*;
import de.fraunhofer.aisec.crymlin.connectors.db.OverflowDatabase;
import de.fraunhofer.aisec.crymlin.connectors.db.TraversalConnection;
import de.fraunhofer.aisec.crymlin.dsl.CrymlinTraversalSource;
import de.fraunhofer.aisec.crymlin.dsl.__;
import de.fraunhofer.aisec.mark.markDsl.OpStatement;
import de.fraunhofer.aisec.mark.markDsl.Parameter;
import de.fraunhofer.aisec.markmodel.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.tinkerpop.gremlin.neo4j.process.traversal.LabelP;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.eclipse.emf.common.util.EList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.__;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.has;

public class CrymlinQueryWrapper {

	private static final Logger log = LoggerFactory.getLogger(CrymlinQueryWrapper.class);

	// do not instantiate
	private CrymlinQueryWrapper() {
	}

	/**
	 * Returns a set of Vertices representing the <code>ConstructExpression</code> to an initialized type.
	 * <p>
	 *
	 * @param crymlinTraversal
	 * @param fqnName fully qualified name
	 * @param parameters list of parameter types; must appear in order (i.e. index 0 = type of first parameter, etc.); currently, types must be precise (i.e. with
	 *        qualifiers, pointer, reference)
	 * @return
	 */
	@NonNull
	public static Set<Vertex> getCtors(
			@NonNull CrymlinTraversalSource crymlinTraversal,
			@NonNull String fqnName,
			EList<Parameter> parameters) {

		fqnName = Utils.unifyType(fqnName);

		// In case of constructors, "functionName" holds the name of the constructed type.
		Set<Vertex> ret = new HashSet<>(crymlinTraversal.ctor(fqnName).toSet());

		// now, ret contains possible candidates --> need to filter out calls where params don't match
		ret.removeIf(
			v -> {
				Iterator<Edge> referencedArguments = v.edges(Direction.OUT, "ARGUMENTS");

				// Collect all argument Vertices into a list
				List<Vertex> arguments = new ArrayList<>();
				while (referencedArguments.hasNext()) {
					Edge e = referencedArguments.next();
					arguments.add(e.inVertex());
				}

				return !argumentsMatchParameters(parameters, arguments);
			});

		return ret;
	}

	/**
	 * Returns a set of Vertices representing the <code>CallExpression</code> to a target function
	 * <p>
	 *
	 * @param crymlinTraversal
	 * @param fqnName fully qualified name
	 * @param parameters list of parameter types; must appear in order (i.e. index 0 = type of first parameter, etc.); currently, types must be precise (i.e. with
	 *        qualifiers, pointer, reference)
	 * @return
	 */
	@NonNull
	public static Set<Vertex> getCalls(
			@NonNull CrymlinTraversalSource crymlinTraversal,
			@NonNull String fqnName,
			EList<Parameter> parameters) {

		fqnName = Utils.unifyType(fqnName);

		// since fqn is a fully qualified name, this includes method calls on an instance, static calls, and functioncalls
		Set<Vertex> ret = new HashSet<>(crymlinTraversal.calls(fqnName).toSet());

		// now, ret contains possible candidates --> need to filter out calls where params don't match
		ret.removeIf(
			v -> {
				Iterator<Edge> referencedArguments = v.edges(Direction.OUT, "ARGUMENTS");

				// Collect all argument Vertices into a list
				List<Vertex> arguments = new ArrayList<>();
				while (referencedArguments.hasNext()) {
					Edge e = referencedArguments.next();
					arguments.add(e.inVertex());
				}

				return !argumentsMatchParameters(parameters, arguments);
			});

		return ret;
	}

	/**
	 * Returns true if the given list of arguments (of a function or method or constructor call) matches the given list of parameters in MARK.
	 *
	 * @param markParameters        List of types.
	 * @param sourceArguments    List of Vertices. Each Vertex is expected to have an "argumentIndex" property.
	 * @return
	 */
	private static boolean argumentsMatchParameters(EList<Parameter> markParameters, @NonNull List<Vertex> sourceArguments) {
		int i = 0;

		while (i < markParameters.size() && i < sourceArguments.size()) {
			Parameter markParam = markParameters.get(i);

			// ELLIPSIS (...) means we do not care about any further arguments
			if (Constants.ELLIPSIS.equals(markParam.getVar())) {
				return true;
			}

			Set<Type> sourceArgs = new HashSet<>();
			/* We cannot assume that the position in sourceArgument corresponds with the actual order. Must rather check "argumentIndex" property. */
			for (Vertex vArg : sourceArguments) {
				long sourceArgPos = (long) vArg.property("argumentIndex")
						.orElse(-1);
				if (sourceArgPos == i) {
					String subTypeProperty = (String) vArg.property("possibleSubTypes").value();
					for (String subType : subTypeProperty.split(",")) {
						sourceArgs.add(Type.createFrom(subType));
					}
				}
			}

			if (sourceArgs.isEmpty()) {
				log.error("Cannot compare function arguments to MARK parameters. Unexpectedly null element or no argument types: {}",
					String.join(", ", MOp.paramsToString(markParameters)));
				return false;
			}

			// UNDERSCORE means we do not care about this specific argument at all
			if (Constants.UNDERSCORE.equals(markParam.getVar())) {
				i++;
				continue;
			}

			// ANY_TYPE means we have a MARK variable, but do not care about its type
			if (Constants.ANY_TYPE.equals(markParam.getVar())) {
				i++;
				continue;
			}

			if (!Utils.isSubTypeOf(sourceArgs, markParam)) {
				return false;
			}

			i++;
		}

		return i == markParameters.size() && i == sourceArguments.size();
	}

	public static List<Vertex> lhsVariableOfAssignment(CrymlinTraversalSource crymlin, long id) {
		return crymlin.byID(id)
				.in("RHS")
				.where(
					has(T.label, LabelP.of(BinaryOperator.class.getSimpleName())).and()
							.has("operatorCode", "="))
				.out("LHS")
				.hasLabel(
					VariableDeclaration.class.getSimpleName(),
					DeclaredReferenceExpression.class.getSimpleName())
				.toList();
	}

	/**
	 * Returns true if the given vertex represents a CallExpression or a subclass thereof.
	 *
	 * @param v
	 * @return
	 */
	public static boolean isCallExpression(Vertex v) {
		if (v.label()
				.equals(CallExpression.class.getSimpleName())) {
			return true;
		}

		for (String subClass : OverflowDatabase.getSubclasses(CallExpression.class)) {
			if (v.label()
					.equals(subClass)) {
				return true;
			}
		}
		return false;
	}

	public static List<Vertex> getNextStatements(CrymlinTraversalSource crymlin, long id) {
		// TODO Needs testing for branches
		return crymlin.byID(id)
				.repeat(__().out("EOG")
						.simplePath())
				.until(__().inE("STATEMENTS"))
				.toList();
	}

	/**
	 * Returns a List of Vertices with label "DeclaredReferenceExpression" that correspond to a given MARK variable in a given rule.
	 *
	 * @param markVar     The MARK variable.
	 * @param rule        The MARK rule using the MARK variable.
	 * @param markModel   The current MARK model.
	 * @param crymlin     A CrymlinTraversalSource for querying the CPG.
	 * @return            List of Vertex objects whose label is "DeclaredReferenceExpression".
	 */
	public static List<CPGVertexWithValue> getMatchingVertices(@NonNull String markVar, @NonNull MRule rule, Mark markModel, @NonNull CrymlinTraversalSource crymlin) {
		final List<CPGVertexWithValue> matchingVertices = new ArrayList<>();
		// Split MARK variable "myInstance.attribute" into "myInstance" and "attribute".
		final String[] markVarParts = markVar.split("\\.");
		String instance = markVarParts[0];
		String attribute = markVarParts[1];

		// Get the MARK entity corresponding to the MARK instance variable.
		Pair<String, MEntity> ref = rule.getEntityReferences().get(instance);
		if (ref == null || ref.getValue1() == null) {
			log.warn("Unexpected: rule {} without referenced entity for instance {}", rule.getName(), instance);
			return matchingVertices;
		}
		MEntity referencedEntity = ref.getValue1();

		if (StringUtils.countMatches(markVar, ".") > 1) {
			log.info("{} References an entity inside an entity", markVar);
			for (int i = 1; i < markVarParts.length - 1; i++) {
				instance += "." + markVarParts[i];
				attribute = markVarParts[i + 1];

				// sanity-checking the references entity
				MVar match = null;
				for (MVar var : referencedEntity.getVars()) {
					if (var.getName().equals(markVarParts[i])) {
						match = var;
						break;
					}
				}
				if (match == null) {
					log.warn("Entity does not contain var {}", markVarParts[i]);
					return matchingVertices;
				}
				referencedEntity = markModel.getEntity(match.getType());
				if (referencedEntity == null) {
					log.warn("No Entity with name {} found", match.getType());
					return matchingVertices;
				}
			}
		}
		String finalAttribute = attribute;

		List<Pair<MOp, Set<OpStatement>>> usesAsVar = new ArrayList<>();
		List<Pair<MOp, Set<OpStatement>>> usesAsFunctionArgs = new ArrayList<>();

		// Collect *variables* assigned in Ops of this entity and *arguments* used in Ops.
		for (MOp operation : referencedEntity.getOps()) {
			Set<OpStatement> vars = new HashSet<>();
			Set<OpStatement> args = new HashSet<>();

			// Iterate over all statements of that op
			for (OpStatement opStmt : operation.getStatements()) {
				// simple assignment, i.e. "var = something()"
				if (attribute.equals(opStmt.getVar())) {
					vars.add(opStmt);
				}
				// Function parameter, i.e. "something(..., var, ...)"
				if (opStmt.getCall()
						.getParams()
						.stream()
						.anyMatch(p -> p.getVar().equals(finalAttribute))) {
					args.add(opStmt);
				}
			}

			if (!vars.isEmpty()) {
				usesAsVar.add(new Pair<>(operation, vars));
			}

			if (!args.isEmpty()) {
				usesAsFunctionArgs.add(new Pair<>(operation, args));
			}
		}

		// get vertices for all usesAsVar (i.e., simple assignment, i.e. "var = something()")
		for (Pair<MOp, Set<OpStatement>> p : usesAsVar) {
			if (p.getValue1() == null) {
				log.warn("Unexpected: Null value for usesAsFunctionArg {}", p.getValue0());
				continue;
			}
			for (OpStatement opstmt : p.getValue1()) {

				String fqFunctionName = opstmt.getCall().getName();

				Set<Vertex> vertices = CrymlinQueryWrapper.getCalls(crymlin, fqFunctionName, opstmt.getCall().getParams());
				vertices.addAll(CrymlinQueryWrapper.getCtors(crymlin, fqFunctionName, opstmt.getCall().getParams()));

				for (Vertex v : vertices) {
					// precalculate base
					Optional<Vertex> baseOfCallExpression = CrymlinQueryWrapper.getBaseOfCallExpression(v);

					boolean foundTargetVertex = false;

					// check if there was an assignment (i.e., i = call(foo);)
					List<Vertex> varDeclarations = CrymlinQueryWrapper.lhsVariableOfAssignment(crymlin, (long) v.id());
					if (!varDeclarations.isEmpty()) {
						foundTargetVertex = true;
						log.info("found assignment: {}", varDeclarations);
						varDeclarations.forEach(vertex -> {
							CPGVertexWithValue cpgVertexWithValue = new CPGVertexWithValue(vertex, ConstantValue.newUninitialized());
							cpgVertexWithValue.setBase(baseOfCallExpression.orElse(null));
							matchingVertices.add(cpgVertexWithValue);
						});
					}

					// check if there was a direct initialization (i.e., int i = call(foo);)
					varDeclarations = crymlin.byID((long) v.id())
							.initializerVariable()
							.toList();
					if (!varDeclarations.isEmpty()) {
						foundTargetVertex = true;
						log.info("found direct initialization: {}", varDeclarations);
						varDeclarations.forEach(vertex -> {
							CPGVertexWithValue cpgVertexWithValue = new CPGVertexWithValue(vertex, ConstantValue.newUninitialized());
							cpgVertexWithValue.setBase(baseOfCallExpression.orElse(null));
							matchingVertices.add(cpgVertexWithValue);
						});
					}

					if (!foundTargetVertex) { // this can be a directly used return value from a call
						CPGVertexWithValue cpgVertexWithValue = new CPGVertexWithValue(v, ConstantValue.newUninitialized());
						cpgVertexWithValue.setBase(baseOfCallExpression.orElse(null));
						matchingVertices.add(cpgVertexWithValue);
					}
				}
			}
		}

		// get vertices for all usesAsFunctionArgs (i.e., Function parameter, i.e. "something(..., var, ...)")
		for (Pair<MOp, Set<OpStatement>> p : usesAsFunctionArgs) {
			if (p.getValue1() == null) {
				log.warn("Unexpected: Null value for usesAsFunctionArg {}", p.getValue0());
				continue;
			}
			for (OpStatement opstmt : p.getValue1()) { // opstatement is one possible method call/ctor inside an op

				String fqFunctionName = opstmt.getCall().getName();

				EList<Parameter> params = opstmt.getCall().getParams();
				IntStream intStream = IntStream.range(0, params.size()).filter(i -> finalAttribute.equals(params.get(i).getVar()));
				if (intStream.count() > 1) {
					log.warn("Invalid op signature: MarkVar is referenced more than once. Only the first one will be used.");
				}
				OptionalInt argumentIndexOptional = intStream.findFirst();
				if (argumentIndexOptional.isEmpty()) {
					log.error("argument not found in parameters. This should not happen");
					continue;
				}
				int argumentIndex = argumentIndexOptional.getAsInt();

				log.debug("Checking for call/ctor. fqname: {} - markParams: {}", fqFunctionName, String.join(", ", MOp.paramsToString(params)));

				for (Vertex v : CrymlinQueryWrapper.getCalls(crymlin, fqFunctionName, params)) {
					List<Vertex> argumentVertices = crymlin.byID((long) v.id())
							.argument(argumentIndex)
							.toList();

					if (argumentVertices.size() == 1) {
						// get base of call expression
						Optional<Vertex> baseOfCallExpression;
						if (v.property("nodeType").isPresent() && v.value("nodeType").equals("de.fraunhofer.aisec.cpg.graph.StaticCallExpression")) {
							baseOfCallExpression = CrymlinQueryWrapper.getDFGTarget(v);
						} else {
							baseOfCallExpression = CrymlinQueryWrapper.getBaseOfCallExpression(v);
							if (baseOfCallExpression.isEmpty()) { // if we did not find a base the "easy way", try to find a base using the simple-DFG
								baseOfCallExpression = CrymlinQueryWrapper.getDFGTarget(v);
							}
						}
						CPGVertexWithValue cpgVertexWithValue = new CPGVertexWithValue(argumentVertices.get(0), ConstantValue.newUninitialized());
						cpgVertexWithValue.setBase(baseOfCallExpression.orElse(null));
						matchingVertices.add(cpgVertexWithValue);
					} else {
						log.warn("multiple arguments for function {} have the same argument_id. Invalid cpg.", fqFunctionName);
					}
				}

				for (Vertex v : CrymlinQueryWrapper.getCtors(crymlin, fqFunctionName, params)) {
					List<Vertex> argumentVertices = crymlin.byID((long) v.id())
							.argument(argumentIndex)
							.toList();

					if (argumentVertices.size() == 1) {
						// get base of initializer for ctor
						Optional<Vertex> baseOfCallExpression = CrymlinQueryWrapper.getBaseOfInitializerArgument(argumentVertices.get(0));
						CPGVertexWithValue cpgVertexWithValue = new CPGVertexWithValue(argumentVertices.get(0), ConstantValue.newUninitialized());
						cpgVertexWithValue.setBase(baseOfCallExpression.orElse(null));
						matchingVertices.add(cpgVertexWithValue);
					} else {
						log.warn("multiple arguments for function {} have the same argument_id. Invalid cpg.", fqFunctionName);
					}
				}
			}
		}
		log.debug("GETMATCHINGVERTICES returns {}",
			matchingVertices.stream()
					.map(v -> v.getArgumentVertex().label() + ": " + v.getArgumentVertex().property("code").value())
					.collect(Collectors.joining(", ")));
		return matchingVertices;
	}

	/**
	 * Given a MARK variable and a list of vertices, attempts to find constant values that would be assigned to these variables at runtime.
	 *
	 * The precision of this resolution depends on the implementation of the ConstantResolver.
	 *
	 * @param vertices
	 * @param markVar
	 * @return
	 */
	public static List<CPGVertexWithValue> resolveValuesForVertices(List<CPGVertexWithValue> vertices, @NonNull String markVar) {

		List<CPGVertexWithValue> ret = new ArrayList<>();

		for (CPGVertexWithValue v : vertices) {
			if (Utils.hasLabel(v.getArgumentVertex(), Literal.class)) {
				// The vertices may already be constants ("Literal"). In that case, immediately add the value.
				CPGVertexWithValue add = CPGVertexWithValue.of(v);
				add.setValue(ConstantValue.of(v.getArgumentVertex().property("value").value()));
				ret.add(add);
			} else if (Utils.hasLabel(v.getArgumentVertex(), DeclaredReferenceExpression.class)) {
				// Otherwise we use ConstantResolver to find concrete values of a DeclaredReferenceExpression.
				ConstantResolver cResolver = new SimpleConstantResolver(TraversalConnection.Type.OVERFLOWDB);
				DeclaredReferenceExpression declExpr = (DeclaredReferenceExpression) OverflowDatabase.getInstance()
						.vertexToNode(v.getArgumentVertex());
				if (declExpr == null) {
					continue;
				}

				Set<ConstantValue> constantValue = cResolver.resolveConstantValues(declExpr);

				if (!constantValue.isEmpty()) {
					constantValue.forEach(cv -> {
						CPGVertexWithValue add = CPGVertexWithValue.of(v);
						add.setValue(cv);
						ret.add(add);
					});
				} else {
					CPGVertexWithValue add = CPGVertexWithValue.of(v);
					v.setValue(ErrorValue.newErrorValue(String.format("could not resolve %s", markVar)));
					ret.add(add);
				}
			} else {
				log.info("Cannot resolve concrete value of a node that is not a DeclaredReferenceExpression or a Literal: {} Returning NULL",
					v.getArgumentVertex().label());
				CPGVertexWithValue add = CPGVertexWithValue.of(v);
				v.setValue(ErrorValue.newErrorValue(String.format("could not resolve %s", markVar)));
				ret.add(add);
			}
		}
		return ret;
	}

	public static Set<Vertex> getVerticesForFunctionDeclaration(
			de.fraunhofer.aisec.mark.markDsl.FunctionDeclaration functionDeclaration,
			MEntity ent,
			CrymlinTraversalSource crymlinTraversal) {

		// resolve parameters which have a corresponding var part in the entity
		Set<Vertex> callsAndInitializers = new HashSet<>(CrymlinQueryWrapper.getCalls(crymlinTraversal, functionDeclaration.getName(), functionDeclaration.getParams()));
		callsAndInitializers.addAll(CrymlinQueryWrapper.getCtors(crymlinTraversal, functionDeclaration.getName(), functionDeclaration.getParams()));

		// fix for Java. In java, a ctor is always accompanied with a newexpression
		callsAndInitializers.removeIf(c -> c.label().contains("NewExpression"));

		return callsAndInitializers;
	}

	/**
	 * Given a Vertex v, try to find the function or method in which v is contained.
	 * <p>
	 * The resulting Vertex will be of type FunctionDeclaration or MethodDeclaration.
	 * <p>
	 * If v is not contained in a function, this method returns an empty Optional.
	 *
	 * @param v
	 * @param crymlinTraversal
	 * @return
	 */
	public static Optional<Vertex> getContainingFunction(Vertex v, CrymlinTraversalSource crymlinTraversal) {
		return crymlinTraversal.byID((long) v.id())
				.repeat(__.__()
						.inE()
						.has("sub-graph", "AST")
						.outV())
				.until(__.__()
						.or(
							__.__()
									.hasLabel(FunctionDeclaration.class.getSimpleName()),
							__.__()
									.hasLabel(MethodDeclaration.class.getSimpleName())))
				.tryNext();
	}

	/**
	 * Given a vertex that represents a <code>CallExpression</code>, return the base(s) that this call expression uses.
	 *
	 * The result will be either an Optional.empty() in case of static method calls or function calls, or contain a single element.
	 *
	 * @param callExpression
	 * @return
	 */
	@NonNull
	public static Optional<Vertex> getBaseOfCallExpression(@NonNull Vertex callExpression) {
		Optional<Vertex> base = Optional.empty();
		Iterator<Edge> it = callExpression.edges(Direction.OUT, "BASE");
		if (it.hasNext()) {
			Vertex baseVertex = it.next().inVertex();
			Iterator<Edge> refIterator = baseVertex.edges(Direction.OUT, "REFERS_TO");
			if (refIterator.hasNext()) {
				// if the node refers to another node, return the node it refers to
				Vertex ref = refIterator.next().inVertex();
				base = Optional.of(ref);
			} else {
				base = Optional.of(baseVertex);
			}
		}
		return base;
	}

	public static Optional<Vertex> getBaseOfInitializerArgument(@NonNull Vertex expr) {
		Optional<Vertex> base = Optional.empty();
		Iterator<Edge> refIterator = expr.edges(Direction.IN, "ARGUMENTS");
		if (refIterator.hasNext()) {
			Iterator<Edge> it = refIterator.next().outVertex().edges(Direction.IN, "INITIALIZER");
			if (it.hasNext()) {
				Vertex baseVertex = it.next().outVertex();
				// for java, an initializer is contained in another
				it = baseVertex.edges(Direction.IN, "INITIALIZER");
				if (it.hasNext()) {
					baseVertex = it.next().outVertex();
				}
				refIterator = baseVertex.edges(Direction.OUT, "REFERS_TO");
				if (refIterator.hasNext()) {
					// if the node refers to another node, return the node it refers to
					base = Optional.of(refIterator.next().inVertex());
				} else {
					base = Optional.of(baseVertex);
				}
			}
		}
		return base;
	}

	public static Optional<Vertex> getBaseOfCallOfArgumentExpression(@NonNull Vertex expr) {
		Optional<Vertex> base = Optional.empty();
		Iterator<Edge> refIterator = expr.edges(Direction.IN, "ARGUMENTS");
		if (refIterator.hasNext()) {
			base = getBaseOfCallExpression(refIterator.next().outVertex());
		}
		return base;
	}

	public static Optional<Vertex> getAssigneeOfConstructExpression(Vertex vertex) {
		Iterator<Edge> it = vertex.edges(Direction.IN, "INITIALIZER");
		if (it.hasNext()) {
			Vertex variableDeclaration = it.next().outVertex();
			it = variableDeclaration.edges(Direction.IN, "INITIALIZER");
			if (it.hasNext()) {
				variableDeclaration = it.next().outVertex();
			}
			Iterator<Edge> refIterator = variableDeclaration.edges(Direction.OUT, "REFERS_TO");
			if (refIterator.hasNext()) {
				// if the node refers to another node, return the node it refers to
				variableDeclaration = refIterator.next().inVertex();
			}

			if (!VariableDeclaration.class.getSimpleName().equals(variableDeclaration.label())) {
				log.warn("Unexpected: Source of INITIALIZER edge to ConstructExpression is not a VariableDeclaration. Trying to continue anyway");
			}
			return Optional.of(variableDeclaration);
		}
		return Optional.empty();
	}

	public static Map<Integer, List<CPGVertexWithValue>> resolveOperand(MarkContextHolder context, @NonNull String markVar, @NonNull MRule rule,
			Mark markModel, @NonNull CrymlinTraversalSource crymlin) {

		HashMap<Integer, List<CPGVertexWithValue>> verticesPerContext = new HashMap<>();

		// first get all vertices for the operand
		List<CPGVertexWithValue> matchingVertices = CrymlinQueryWrapper.getMatchingVertices(markVar, rule, markModel, crymlin);

		if (matchingVertices.isEmpty()) {
			log.warn("Did not find matching vertices for {}", markVar);
			return verticesPerContext;
		}

		// Use Constant resolver to resolve assignments to arguments
		List<CPGVertexWithValue> vertices = new ArrayList<>(CrymlinQueryWrapper.resolveValuesForVertices(matchingVertices, markVar));

		// now split them up to belong to each instance (t) or markvar (t.foo)
		final String instance = markVar.substring(0, markVar.lastIndexOf('.'));

		// precompute a list mapping
		// from a nodeID (representing the varabledecl for the instance)
		// to a List of contexts where this base is referenced
		HashMap<Long, List<Integer>> nodeIDToContextIDs = new HashMap<>();
		if (StringUtils.countMatches(instance, '.') >= 1) {
			// if the instance itself is a markvar,
			// precalculate the variabledecl where each of the corresponding instance points to
			// i.e., precalculate the variabledecl for t.foo for each instance

			for (Map.Entry<Integer, MarkContext> entry : context.getAllContexts().entrySet()) {
				CPGVertexWithValue opInstance = entry.getValue().getOperand(instance);
				if (opInstance == null) {
					log.warn("Instance not found in context");
				} else if (opInstance.getArgumentVertex() == null) {
					log.warn("MARK variable {} does not correspond to a vertex", markVar);
				} else {
					Vertex vertex = opInstance.getArgumentVertex();
					// if available, get the variabledeclaration, this declaredreference refers_to
					Iterator<Edge> refers_to = opInstance.getArgumentVertex().edges(Direction.OUT, "REFERS_TO");
					if (refers_to.hasNext()) {
						Edge next = refers_to.next();
						vertex = next.inVertex();
					}
					List<Integer> contextIDs = nodeIDToContextIDs.computeIfAbsent((Long) vertex.id(), x -> new ArrayList<>());
					contextIDs.add(entry.getKey());
				}
			}
		} else {
			// if the instance is entity referenced in the op, precalculate the variabledecl for each used op

			for (Map.Entry<Integer, MarkContext> entry : context.getAllContexts().entrySet()) {
				if (!entry.getValue().getInstanceContext().containsInstance(instance)) {
					log.warn("Instance not found in context");
				} else {
					Vertex opInstance = entry.getValue().getInstanceContext().getVertex(instance);
					Long id = -1L;
					if (opInstance != null) {
						id = (Long) opInstance.id();
					}
					List<Integer> contextIDs = nodeIDToContextIDs.computeIfAbsent(id, x -> new ArrayList<>());
					contextIDs.add(entry.getKey());
				}
			}
		}

		// now calculate a list of contextID to matching vertices which fill the base we are looking for

		for (CPGVertexWithValue vertexWithValue : vertices) {
			Long id = -1L; // -1 = null
			if (vertexWithValue.getBase() != null) {
				id = (Long) vertexWithValue.getBase().id();
			}
			List<Integer> contextIDs = nodeIDToContextIDs.get(id);
			if (contextIDs == null) {
				log.warn("Base not found in any context. Following expressionevaluation will be incomplete");
			} else {
				for (Integer c : contextIDs) {
					List<CPGVertexWithValue> verts = verticesPerContext.computeIfAbsent(c, x -> new ArrayList<>());
					verts.add(vertexWithValue);
				}
			}
		}

		return verticesPerContext;
	}

	/**
	 * Returns a set of ValueDeclarations where the variable/field/argument given by <code>delRefExpr</code> is declared.
	 *
	 * @param declRefExpr
	 * @return
	 */
	public static Set<ValueDeclaration> getDeclarationSites(@NonNull DeclaredReferenceExpression declRefExpr) {
		Set<Vertex> refersTo = OverflowDatabase.getInstance().getGraph().traversal().V(declRefExpr.getId()).outE("REFERS_TO").inV().toBulkSet();
		Set<ValueDeclaration> varDecls = new HashSet<>();
		for (Vertex v : refersTo) {
			ValueDeclaration varDecl = (ValueDeclaration) OverflowDatabase.getInstance().vertexToNode(v);
			varDecls.add(varDecl);
		}
		return varDecls;
	}

	public static String getFileLocation(Vertex v) {
		return v.value("file");
	}

	public static boolean eogConnection(Vertex source, Vertex sink, boolean branchesAllowed) {

		if (Objects.equals(source, sink)) {
			return true;
		}

		HashSet<Vertex> workList = new HashSet<>();
		HashSet<Vertex> seen = new HashSet<>();
		workList.add(source);

		while (!workList.isEmpty()) {
			HashSet<Vertex> newWorkList = new HashSet<>();
			for (Vertex v : workList) {
				seen.add(v);
				Iterator<Edge> eog = v.edges(Direction.OUT, "EOG");
				int numEdges = 0;
				while (eog.hasNext()) {
					numEdges++;
					if (numEdges > 1 && !branchesAllowed) {
						return false;
					}
					Vertex next = eog.next().inVertex();
					if (next.equals(sink)) {
						return true;
					}
					if (!seen.contains(next)) {
						newWorkList.add(next);
					}
				}
			}
			workList = newWorkList;
		}

		return false;
	}

	public static Optional<Vertex> getDFGTarget(Vertex vertex) {
		Iterator<Edge> it = vertex.edges(Direction.OUT, "DFG");
		if (it.hasNext()) {
			return Optional.of(it.next().inVertex());
		}
		return Optional.empty();
	}

	public static Set<Vertex> getDFGSources(Vertex vertex) {
		Set<Vertex> result = new HashSet<>();
		Iterator<Edge> it = vertex.edges(Direction.IN, "DFG");
		while (it.hasNext()) {
			result.add(it.next().outVertex());
		}
		return result;
	}

	public static Optional<Vertex> refersTo(Vertex vertex) {
		Iterator<Edge> it = vertex.edges(Direction.IN, "REFERS_TO");
		if (it.hasNext()) {
			return Optional.of(it.next().outVertex());
		}
		return Optional.empty();
	}

	public static Optional<Vertex> getInitializerFor(Vertex vertex) {
		// we first go back to the declaredreference (if any)
		Iterator<Edge> it = vertex.edges(Direction.IN, "REFERS_TO");
		if (it.hasNext()) {
			vertex = it.next().outVertex();
		}

		// then we go forward to all referenced vars
		Iterator<Edge> refers_to = vertex.edges(Direction.OUT, "REFERS_TO");
		while (refers_to.hasNext()) {
			Vertex referenced = refers_to.next().inVertex();
			Iterator<Edge> initializer = referenced.edges(Direction.OUT, "INITIALIZER");
			if (initializer.hasNext()) {
				return Optional.of(initializer.next().inVertex());
			}
		}
		return Optional.empty();
	}
}
