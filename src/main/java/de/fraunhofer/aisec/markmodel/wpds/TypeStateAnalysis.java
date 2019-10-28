
package de.fraunhofer.aisec.markmodel.wpds;

import de.breakpoint.pushdown.IllegalTransitionException;
import de.breakpoint.pushdown.de.breakpoint.pushdown.fsm.Transition;
import de.breakpoint.pushdown.de.breakpoint.pushdown.fsm.WeightedAutomaton;
import de.breakpoint.pushdown.rules.NormalRule;
import de.breakpoint.pushdown.rules.PopRule;
import de.breakpoint.pushdown.rules.PushRule;
import de.breakpoint.pushdown.rules.Rule;
import de.fraunhofer.aisec.cpg.graph.*;
import de.fraunhofer.aisec.crymlin.connectors.db.OverflowDatabase;
import de.fraunhofer.aisec.crymlin.dsl.CrymlinTraversalSource;
import de.fraunhofer.aisec.crymlin.server.AnalysisContext;
import de.fraunhofer.aisec.crymlin.structures.Finding;
import de.fraunhofer.aisec.crymlin.utils.CrymlinQueryWrapper;
import de.fraunhofer.aisec.crymlin.utils.Pair;
import de.fraunhofer.aisec.mark.markDsl.OrderExpression;
import de.fraunhofer.aisec.markmodel.MEntity;
import de.fraunhofer.aisec.markmodel.MOp;
import de.fraunhofer.aisec.markmodel.MRule;
import de.fraunhofer.aisec.markmodel.fsm.Node;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static de.fraunhofer.aisec.crymlin.dsl.__.*;
import static de.fraunhofer.aisec.crymlin.utils.CrymlinQueryWrapper.isCallExpression;

/**
 * Implementation of a WPDS-based typestate analysis using the code property graph.
 * <p>
 * Legal typestates are given by a regular expression as of the MARK "order" construct. This class will convert this regular expression into a "typestate NFA". The
 * transitions of the typestate NFA are then represented as "weights" for a weighted pushdown system (WPDS). The WPDS is an abstraction of the data flows in the program
 * (currently there is one WPDS per function, but we can easily extend this to inter-procedural WPDS'es).
 * <p>
 * Given an "initial configuration" in form of a "weighted automaton" P, the "post-*" algorithm [1] will then "saturate" this weighted automaton P into Ps. The saturated
 * weighted automaton Ps is a representation of all type states reachable from the initial configuration, given the underlying program abstraction in form of the WPDS.
 * <p>
 * Thus when inspecting the weights (which are effectively type state transitions) of the saturated weighted automaton Ps, we can check if all operations on an object
 * (and its aliases) refer to legal type state transitions, if there is an execution path in the program which reaches the end of the typestate, or if any operation leads
 * to an illegal typestate (= empty weight in a transition of Ps).
 * <p>
 * <p>
 * [1] Reps T., Lal A., Kidd N. (2007) Program Analysis Using Weighted Pushdown Systems. In: Arvind V., Prasad S. (eds) FSTTCS 2007: Foundations of Software Technology
 * and Theoretical Computer Science. FSTTCS 2007. Lecture Notes in Computer Science, vol 4855. Springer, Berlin, Heidelberg
 */
public class TypeStateAnalysis {
	private static final Logger log = LoggerFactory.getLogger(TypeStateAnalysis.class);

	public void analyze(AnalysisContext ctx, CrymlinTraversalSource crymlinTraversal, MRule rule) throws IllegalTransitionException {
		log.info("Typestate analysis starting for " + ctx + " and " + crymlinTraversal);

		HashMap<MOp, Vertex> verticeMap = getVerticesOfRule(rule);

		for (Map.Entry<MOp, Vertex> vertices : verticeMap.entrySet()) {
			System.out.println("Vertex " + vertices.getValue().property("code").value() + " for " + vertices.getKey().getName());
		}

		// Create FSM from MARK expression
		OrderExpression inner = (OrderExpression) rule.getStatement().getEnsure().getExp();

		// Creating a WPDS from CPG, starting at seeds. Note that this will neglect alias which have been defined before the seed.
		HashSet<Node> seedExpression = null; // TODO Seeds must be vertices with calls which MAY be followed by a typestate violation

		/* Create typestate NFA, representing the regular expression of a MARK typestate rule. */
		NFA tsNFA = NFA.of(inner.getExp());
		System.out.println("Initial typestate NFA:\n" + tsNFA.toString());

		// Create a weighted pushdown system
		CpgWpds wpds = createWpds(seedExpression, verticeMap, crymlinTraversal, tsNFA);

		// Create a weighted automaton (= a weighted NFA) that describes the initial configurations
		// TODO Initial configuration is still hardcoded. Should be first Op(s) of order expression.
		String stmt = "11 : ok1";
		String variable = "EPSILON";
		String method = "ok1";
		WeightedAutomaton<Stmt, Val, Weight> wnfa = createInitialConfiguration(stmt, variable, method, tsNFA);

		// For debugging only: Print WPDS rules
		for (Rule r : wpds.getAllRules()) {
			System.out.println(r.toString());
		}

		// For debugging only: Print the non-saturated NFA
		log.info("Non saturated NFA", wnfa.toString());
		System.out.println(wnfa.toDotString());

		// Saturate the NFA from the WPDS, using the post-* algorithm.
		wpds.poststar(wnfa);

		// For debugging only: Print the post-*-saturated NFA
		System.out.println(wnfa.toString());
		System.out.println(wnfa.toDotString());

		// Evaluate saturated WNFA for any MARK violations
		Set<Finding> findings = getFindingsFromWpds(wnfa, tsNFA, rule);
		ctx.getFindings().addAll(findings);

	}

	/**
	 * Evaluates a saturated WNFA.
	 * <p>
	 * This method receives a post-*-saturated WNFA and creates Findings if any violations of the given MARK rule are found.
	 * <p>
	 * 1) Transitions in WNFA with *empty weights* or weights into an ERROR type state indicate an error. Type state requirements are violated at this point. 2) If there
	 * is a path through the automaton leading to the END state, the type state specification is completely covered by this path 3) If all transitions have proper type
	 * state weights but none of them leads to END, the type state is correct but incomplete.
	 *
	 * @param wnfa
	 * @param tsNFA
	 * @param rule
	 * @return
	 */
	@NonNull
	private Set<Finding> getFindingsFromWpds(@NonNull WeightedAutomaton<Stmt, Val, Weight> wnfa, NFA tsNFA, MRule rule) {
		Set<Finding> findings = new HashSet<>();

		System.out.println("--------------------------");

		// TODO reachableTypestates and didMove are currently not used.
		Set<Node> reachableTypestates = new HashSet<>();

		Set<Val> startStates = new HashSet<>();
		for (Transition<Stmt, Val> transition : wnfa.getTransitions()) {
			Weight w = wnfa.getWeightFor(transition);
			if (w.value() instanceof Set) {
				Set<NFATransition> weight = (Set<NFATransition>) w.value();
				for (NFATransition<Node> t : weight) {
					if (t.getSource().getName().equals("START.START")) {
						startStates.add(transition.getStart());
					}
				}
			}
		}
		if (startStates.isEmpty()) {
			System.out.println("No starting state found");
			return findings;
		}

		for (Val startState : startStates) {
			Collection<Transition<Stmt, Val>> ts = wnfa.getTransitionsOutOf(startState);
			for (Transition<Stmt, Val> t : ts) {
				Weight w = wnfa.getWeightFor(t);
				if (w.value() instanceof Set && !((Set) w.value()).isEmpty()) {
					Set<NFATransition> transitions = (Set<NFATransition>) w.value();
					boolean didMove = false;
					for (NFATransition<Node> tran : transitions) {
						didMove = tsNFA.handleEvent(tran.getLabel());
						Node typestate = tran.getTarget();
						reachableTypestates.add(typestate);
						System.out.println(" Reached " + typestate + " by " + t.getLabel().toString());
					}
				} else if (!w.equals(Weight.one()) && t.getTarget().getVariable().equals("ACCEPT") && t.getTarget().getCurrentScope().equals("ACCEPT")) {
					String name = "Invalid typestate of variable " + t.getStart() + " at statement: " + t.getLabel() + " . Violates order of " + rule.getName();

					// TODO get line/column start/end once we are not working with strings anymore, but with Vertices.
					long startLine = 0;
					long endLine = 0;
					long startColumn = 0;
					long endColumn = 0;
					Finding f = new Finding(name, startLine, endLine, startColumn, endColumn);
					findings.add(f);
				}
			}
		}

		System.out.println("Final config: " + String.join(", " + tsNFA.getCurrentConfiguration().stream().map(n->n.getName()).collect(Collectors.toList())));

		System.out.println("--------------------------");

		return findings;
	}

	/**
	 * Creates a weighted pushdown system (WPDS), linked to a typestate NFA.
	 * <p>
	 * When populating the WPDS using post-* algorithm, the result will be an automaton capturing the reachable type states.
	 *
	 * @param seedExpressions
	 * @param verticeMap
	 * @param crymlinTraversal
	 * @param nfa
	 * @return
	 * @throws IllegalTransitionException
	 */
	private CpgWpds createWpds(@Nullable HashSet<Node> seedExpressions, HashMap<MOp, Vertex> verticeMap, CrymlinTraversalSource crymlinTraversal, NFA nfa) {
		log.debug("-----  Creating WPDS ----------");

		/**
		 * We need OverflowDB to convert vertices back to CPG nodes.
		 */
		OverflowDatabase<de.fraunhofer.aisec.cpg.graph.Node> odb = OverflowDatabase.getInstance();

		/* Create empty WPDS */
		CpgWpds wpds = new CpgWpds();

		// TODO WPDS should be "seeded" for a relevant statements. Currently we transform whole functions into a WPDS

		// Alias analysis for base of seed: Create set of objects which are aliases of seed
		// TODO Alias analysis before seed

		/**
		 * For each function, create a WPDS
		 *
		 * The (normal, push, pop) rules of the WPDS reflect the data flow, similar to a static taint analysis.
		 *
		 */
		for (Vertex functionDeclaration : crymlinTraversal.functiondeclarations().toList()) {
			// Work list of following EOG nodes. Not all EOG nodes will result in a WPDS rule, though.
			String currentFunctionName = (String) functionDeclaration.property("name").value();
			System.out.println("Processing function " + currentFunctionName);
			ArrayDeque<Vertex> worklist = new ArrayDeque<>();
			worklist.add(functionDeclaration);
			Stmt previousStmt = new Stmt(functionDeclaration.property("startLine").value() + " : " + functionDeclaration.property("name").value());
			Map<Stmt, Val> skipTheseValsAtStmt = new HashMap<>();

			Set<Val> valsInScope = new HashSet<>();

			// Make sure we track all parameters inside this function
			List<ParamVariableDeclaration> params = ((FunctionDeclaration) Objects.requireNonNull(odb.vertexToNode(functionDeclaration))).getParameters();
			for (ParamVariableDeclaration p : params) {
				valsInScope.add(new Val(p.getName(), currentFunctionName));
			}
			while (!worklist.isEmpty()) {
				Vertex v = worklist.pop();
				System.out.println("TYPE: " + v.property("labels").value());
				String code = (String) v.property("code").value();
				System.out.println("CODE: " + code);

				// We consider only "Statements" and CallExpressions in the EOG
				if (isCallExpression(v) || v.edges(Direction.IN, "STATEMENTS").hasNext()) {

					Stmt currentStmt = new Stmt(v.property("startLine").value() + " : " + v.property("code").value().toString());
					if (isCallExpression(v)) {

						CallExpression mce = (CallExpression) odb.vertexToNode(v);
						if (mce != null) {
							if (!isPhantom(mce)) {
								/*
								 * For calls to functions whose body is known, we create push/pop rule pairs. All arguments flow into the parameters of the function. The
								 * "return site" is the statement to which flow returns after the function call.
								 */
								Set<PushRule<Stmt, Val, Weight>> pushRules = createPushRules(mce, crymlinTraversal, currentFunctionName, nfa, previousStmt, currentStmt,
										v);
								for (PushRule<Stmt, Val, Weight> pushRule : pushRules) {
									System.out.println("  Adding push rule: " + pushRule.toString());
									wpds.addRule(pushRule);

									// Remember that arguments flow only into callee and do not bypass it.
									skipTheseValsAtStmt.put(pushRule.getCallSite(), pushRule.getS1());
								}
							}

							/* For calls to external functions whose body is not known, we create a normal rule */
							Set<NormalRule<Stmt, Val, Weight>> normalRules = createNormalRules(nfa, mce, previousStmt, currentStmt, valsInScope);
							for (NormalRule<Stmt, Val, Weight> normalRule : normalRules) {
								boolean skipIt = false;
								if (skipTheseValsAtStmt.get(normalRule.getL2()) != null) {
									Val forbiddenVal = skipTheseValsAtStmt.get(normalRule.getL2());
									if (!normalRule.getS1().equals(forbiddenVal)) {
										skipIt = true;
									}
								}
								if (!skipIt) {
									wpds.addRule(normalRule);
								}
							}
						}
						/* "DeclarationStatements" result in a normal rule, assigning rhs to lhs. */
					}

					if (v.label().equals(DeclarationStatement.class.getSimpleName())) {
						log.debug("Found variable declaration " + v.property("code").value());

						Vertex decl = v.edges(Direction.OUT, "DECLARATIONS").next().inVertex();
						Val declVal = new Val((String) decl.property("name").value(), currentFunctionName);

						Optional<Vertex> rhs = Optional.empty();
						if (decl.edges(Direction.OUT, "INITIALIZER").hasNext()) {
							// TODO Do not simply assume that the target of an INITIALIZER edge is a variable
							rhs = Optional.of(decl.edges(Direction.OUT, "INITIALIZER").next().inVertex());
						}

						if (rhs.isPresent()) {
							Vertex rhsVar = rhs.get();
//							if (isCallExpression(rhsVar)) {
//								// TODO Handle as push/pop rule
//							} else
								if (rhsVar.property("name").isPresent()) {
								log.debug("  Has name on right hand side " + rhsVar.property("name").value());
								Val rhsVal = new Val((String) rhsVar.property("name").value(), currentFunctionName);
								// We add all transitions of the typestate NFA that may be triggered by the current op

								//							Set<NFATransition> relevantNFATransitions = nfa.getTransitions().stream().filter(
								//									tran -> tran.getTarget()etOp().equals(rhsVal.getVariable())).collect(Collectors.toSet());
								//							Weight weight = relevantNFATransitions.isEmpty() ? Weight.one() : new Weight(relevantNFATransitions);

								// Add declVal to set of currently tracked variables
								valsInScope.add(declVal);

								Rule<Stmt, Val, Weight> normalRuleSelf = new NormalRule<>(rhsVal, previousStmt, rhsVal, currentStmt, Weight.one());
								log.debug("Adding normal rule " + normalRuleSelf.toString());
								wpds.addRule(normalRuleSelf);

								Rule<Stmt, Val, Weight> normalRuleCopy = new NormalRule<>(rhsVal, previousStmt, declVal, currentStmt, Weight.one());
								System.out.println("Adding normal rule " + normalRuleCopy.toString());
								wpds.addRule(normalRuleCopy);

							} else {
								// handle new instantiations of objects
								System.out.println("  Has no name on right hand side: " + v.property("code").value().toString());

								// Normal copy of all values in scope
								for (Val valInScope : valsInScope) {
									Rule<Stmt, Val, Weight> normalRule = new NormalRule<>(valInScope, previousStmt, valInScope, currentStmt,
											Weight.one());
									if (skipTheseValsAtStmt.get(normalRule.getL2()) != null) {
										Val forbiddenVal = skipTheseValsAtStmt.get(normalRule.getL2());
										if (!normalRule.getS1().equals(forbiddenVal)) {
											System.out.println("Adding normal rule " + normalRule.toString());
											wpds.addRule(normalRule);
										}
									}
								}

								// Add declVal to set of currently tracked variables
								valsInScope.add(declVal);

								// Create normal rule
								//Weight w = new Weight(nfa.getInitialTransitions());
								//NFATransition startCycle = new NFATransition(nfa.getStart(), nfa.getStart(), nfa.getStart().getName());
								Rule<Stmt, Val, Weight> normalRule = new NormalRule<>(new Val("EPSILON", currentFunctionName), previousStmt, declVal, currentStmt,
										Weight.one());
								System.out.println("Adding normal rule " + normalRule.toString());
								wpds.addRule(normalRule);

							}
						}
						} else if (v.label().equals(ReturnStatement.class.getSimpleName())) {
							/* Return statements result in pop rules */
							// TODO Proper handling of variables in scope
							ReturnStatement returnV = (ReturnStatement) odb.vertexToNode(v);
							if (returnV != null && !returnV.isDummy()) {
								returnV.getReturnValue().toString();
								Set<Val> returnedVals = findReturnedVals(crymlinTraversal, v);

								for (Val returnedVal : returnedVals) {
									Set<NFATransition> relevantNFATransitions = nfa.getTransitions().stream().filter(
											tran -> tran.getTarget().getOp().equals(returnedVal.getVariable())).collect(Collectors.toSet());
									Weight weight = relevantNFATransitions.isEmpty() ? Weight.one() : new Weight(relevantNFATransitions);

									// Pop Rule for actually returned value
									PopRule<Stmt, Val, Weight> returnPopRule = new PopRule<>(new Val(returnV.getReturnValue().getName().toString(), currentFunctionName),
											currentStmt, returnedVal, weight);
									wpds.addRule(returnPopRule);
									System.out.println("Created pop rule " + returnPopRule.toString());
								}

								// Pop Rules for side effects on parameters
								Map<String, Set<Pair<Val, Val>>> paramToValueMap = findParamToValues(functionDeclaration, v, odb, crymlinTraversal);
								for (Pair<Val, Val> pToA : paramToValueMap.get(currentFunctionName)) {
									PopRule<Stmt, Val, Weight> popRule = new PopRule<>(pToA.getValue0(), currentStmt, pToA.getValue1(), Weight.one());
									wpds.addRule(popRule);
									System.out.println("Created pop rule " + popRule.toString());
								}

							}

							// Create normal rule. Flow remains where it is.  // TODO should be outside of dummy, but should avoid cyclic rules
							for (Val valInScope : valsInScope) {
								Set<NFATransition> relevantNFATransitions = nfa.getTransitions().stream()
										.filter(tran -> tran.getTarget().getOp().equals(currentStmt))
										.collect(Collectors.toSet());
								Rule<Stmt, Val, Weight> normalRule = new NormalRule<>(valInScope, previousStmt, valInScope, currentStmt, Weight.one());
								boolean skipIt = false;
								if (skipTheseValsAtStmt.get(normalRule.getL2()) != null) {
									Val forbiddenVal = skipTheseValsAtStmt.get(normalRule.getL2());
									if (!normalRule.getS1().equals(forbiddenVal)) {
										skipIt = true;
									}
								}
								if (!skipIt) {
									System.out.println("Adding normal rule " + normalRule.toString());
									wpds.addRule(normalRule);
								}
							}

						}
						previousStmt = currentStmt;
						//skipTheseArgs.clear();
					}

				// Add successors to work list
				Iterator<Edge> successors = v.edges(Direction.OUT, "EOG");
				while (successors.hasNext()) {
					worklist.add(successors.next().inVertex());
				}
			}
		}

		/*
		 * Typestate analysis is finished. The results are as follows: 1) Transitions in WNFA with *empty weights* or weights into an ERROR type state indicate an error.
		 * Type state requirements are violated at this point. 2) If there is a path through the automaton leading to the END state, the type state specification is
		 * completely covered by this path 3) If all transitions have proper type state weights but none of them leads to END, the type state is correct but incomplete.
		 */

		return wpds;
	}

	private Set<NormalRule<Stmt, Val, Weight>> createNormalRules(final NFA nfa, final CallExpression mce, final Stmt previousStmt, final Stmt currentStmt,
			final Set<Val> valsInScope) {
		Set<NormalRule<Stmt, Val, Weight>> result = new HashSet<>();
		Set<NFATransition> relevantNFATransitions = nfa.getTransitions().stream().filter(
				tran -> tran.getTarget().getOp().equals(mce.getName())).collect(Collectors.toSet());
		Weight weight = relevantNFATransitions.isEmpty() ? Weight.one() : new Weight(relevantNFATransitions);

		// Create normal rule. Flow remains where it is.
		for (Val valInScope : valsInScope) {
			NormalRule<Stmt, Val, Weight> normalRule = new NormalRule<>(valInScope, previousStmt, valInScope, currentStmt, weight);
			result.add(normalRule);
		}

		return result;
	}

	/**
	 * Returns true if the given CallExpression refers to a function whose Body is not available.
	 *
	 * @param mce
	 * @return
	 */
	private boolean isPhantom(CallExpression mce) {
		return mce.getInvokes().isEmpty();
	}

	/** Finds the mapping from function parameters to arguments of calls to this method. This is needed for later construction of pop rules.
	 *
	 * @param functionDeclaration
	 * @param returnV
	 * @param odb
	 * @param crymlinTraversalSource
	 * @return
	 */
	private Map<String, Set<Pair<Val, Val>>> findParamToValues(Vertex functionDeclaration, Vertex returnV,
			OverflowDatabase<de.fraunhofer.aisec.cpg.graph.Node> odb, CrymlinTraversalSource crymlinTraversalSource) {
		Map<String, Set<Pair<Val, Val>>> result = new HashMap<>();
		try {
			FunctionDeclaration calleeFD = (FunctionDeclaration) odb.vertexToNode(functionDeclaration);
			if (calleeFD == null) {
				log.error("Unexpected: FunctionDeclaraion of callee is null.");
				return result;
			}
			String calleeName = calleeFD.getName();

			List<Vertex> calls = crymlinTraversalSource
					.byID((long) returnV.id())
					.repeat(__().out("DFG"))
					.until(__().hasLabel(CallExpression.class.getSimpleName()))
					.limit(5).toList();

			for (Vertex call : calls) {
				CallExpression ce = (CallExpression) odb.vertexToNode(call);
				if (ce == null) {
					continue;
				}

				/* Find name of calling function ("caller") TODO This is not an optimal way to find out the calling function, as we need to traverse potentially many EOG edges. */
				List<Vertex> callers = crymlinTraversalSource
						.byID((long) call.id())
						.repeat(__().in("EOG"))
						.until(__().hasLabel(FunctionDeclaration.class.getSimpleName()))
						.limit(50).toList();

				for (Vertex callerV : callers) {
					FunctionDeclaration caller = (FunctionDeclaration) odb.vertexToNode(callerV);
					if (caller == null) {
						log.error("Unexpected: Null Node object for FunctionDeclaration vertex {}", callerV.id());
						continue;
					}
					List<Expression> args = ce.getArguments();
					FunctionDeclaration callee = ce.getInvokes().get(
							0);  // TODO we assume there is exactly one (=our) called function ("callee"). In case of fuzzy resolution, there might be more.
					List<ParamVariableDeclaration> params = callee.getParameters();

					Set<Pair<Val, Val>> pToA = new HashSet<>();
					for (int i = 0; i < Math.min(params.size(), args.size()); i++) {
						pToA.add(new Pair(new Val(params.get(i).getName(), calleeName), new Val(args.get(i).getName(), caller.getName())));
					}
					result.put(calleeName, pToA);
				}
			}
		} catch (FastNoSuchElementException e) {
			e.printStackTrace();
			System.out.println("ERROR FNSE");
		}

		return result;
	}

	/**
	 * Given a return statement, this method finds all variables at the caller site that might be assigned the returned value.
	 * <p>
	 * In the following example, given the return statement in bla(), this method will return a Val for "x".
	 * <p>
	 * void blubb() { int x = bla(); }
	 * <p>
	 * int bla() { return 42; }
	 *
	 * @param v
	 * @return
	 */
	private Set<Val> findReturnedVals(CrymlinTraversalSource crymlinTraversalSource, Vertex v) {
		/*
		 * Follow along "DFG" edges from the return statement to the CallExpression that initiated the call. Then check if there is a "DFG" edge from that CallExpression
		 * to a VariableDeclaration.
		 */
		Set<Val> returnedVals = new HashSet<>();
		List<Vertex> calls = crymlinTraversalSource
				.byID((long) v.id())
				.repeat(__().out("DFG"))
				.until(__().hasLabel(CallExpression.class.getSimpleName()))
				.limit(5).toList();

		for (Vertex call : calls) {
			// We found the call site into our method. Now see if the return value is used.
			Optional<Vertex> nextDfgAftercall = crymlinTraversalSource.byID((long) call.id()).out("DFG").tryNext();
			String returnVar = "";
			if (nextDfgAftercall.isPresent()) {
				if (nextDfgAftercall.get().label().equals(VariableDeclaration.class.getSimpleName()) || nextDfgAftercall.get().label().equals(DeclaredReferenceExpression.class.getSimpleName())) {
					// return value is used. Remember variable name.
					returnVar = nextDfgAftercall.get().property("name").value().toString();
				}
				// Finally we need to find out in which function the call site actually is
				String callerFunctionName = null;
				Vertex callerFunction = crymlinTraversalSource.byID((long) call.id()).repeat(__().out("EOG")).until(__().in("STATEMENTS"))
						//					.limit(10)
						.in("STATEMENTS").in("BODY")
						//					.outV()
						.next();
				if (callerFunction != null) {
					callerFunctionName = callerFunction.property("name").value().toString();
				}

				if (callerFunctionName != null) {
					returnedVals.add(new Val(returnVar, callerFunctionName));
				}
			}
		}

		return returnedVals;
	}

	/**
	 * Creates push rules for a given call expression.
	 * <p>
	 * Typically, there will be only a single push rule per call expression. Only in case of multiple return sites, such as when considering exception handling, the
	 * resulting set may contain more than one rule.
	 *
	 * @param mce
	 * @param crymlinTraversal
	 * @param currentFunctionName
	 * @param nfa
	 * @param previousStmt
	 * @param currentStmt
	 * @param v
	 * @return
	 */
	private Set<PushRule<Stmt, Val, Weight>> createPushRules(CallExpression mce, CrymlinTraversalSource crymlinTraversal, String currentFunctionName,
			NFA nfa, Stmt previousStmt, Stmt currentStmt, Vertex v) {
		Set<NFATransition> relevantNFATransitions = nfa.getTransitions().stream().filter(
				tran -> tran.getTarget().getOp().equals(mce.getName())).collect(Collectors.toSet());
		Weight weight = relevantNFATransitions.isEmpty() ? Weight.one() : new Weight(relevantNFATransitions);

		// Return site(s). Actually, multiple return sites will only occur in case of exception handling.
		List<Vertex> returnSites = CrymlinQueryWrapper.getNextStatements(crymlinTraversal, (long) v.id());

		// Arguments of function call
		List<Val> argVals = argumentsToVals(mce, currentFunctionName);

		Set<PushRule<Stmt, Val, Weight>> pushRules = new HashSet<>();
		for (FunctionDeclaration potentialCallee : mce.getInvokes()) {
			// Parameters of function
			if (potentialCallee.getParameters().size() != argVals.size()) {
				log.warn("Skipping call from {} to {} due different argument/parameter counts.", currentFunctionName,
						potentialCallee.getSignature());
				continue;
			}
			List<Val> parmVals = parametersToVals(potentialCallee);

			// Get first statement of callee. This is the jump target of our Push Rule.
			Statement firstStmt = getFirstStmtOfMethod(crymlinTraversal, potentialCallee);
			if (firstStmt != null && firstStmt.getCode() != null) {
				String firstStmtCode = firstStmt.getCode();
				for (int i = 0; i < argVals.size(); i++) {
					for (Vertex returnSiteVertex : returnSites) {
						Stmt returnSite = new Stmt(returnSiteVertex.property("startLine").value() + " : " + returnSiteVertex.property("code").value().toString());
						PushRule<Stmt, Val, Weight> pushRule = new PushRule<Stmt, Val, Weight>(
								argVals.get(i),
								currentStmt,
								parmVals.get(i),
								new Stmt(potentialCallee.getRegion().getStartLine() + " : " + potentialCallee.getName()),
								returnSite,
								weight);
						pushRules.add(pushRule);
					}
				}
			} else {
				log.error("Unexpected: Found a method with body, but no first statement relevant for WPDS: {}", potentialCallee.getName());
			}
		}
		return pushRules;

	}

	@Nullable
	private Statement getFirstStmtOfMethod(@NonNull CrymlinTraversalSource crymlinTraversalSource, @NonNull FunctionDeclaration potentialCallee) {
		// Traverse along EOG edge until we find a "relevant" node (=Statement or CallExpression)
		// TODO The following line would throw NPE, as getID() returns null. See https://***REMOVED***/sas/dev/cpg/issues/125
		//Vertex v = crymlinTraversalSource.byID(potentialCallee.getId()).next();
		if (potentialCallee.getBody() != null) {

			// Actually we want to do a crymlin query instead of this error-prone and manual iteration here. This requires a fix of the issue above.
			Statement firstStmt = potentialCallee.getBody();
			while (firstStmt instanceof CompoundStatement) {
				firstStmt = ((CompoundStatement) firstStmt).getStatements().get(0);
			}
			return firstStmt;
		}

		return null;
	}

	private List<Val> parametersToVals(FunctionDeclaration potentialCallee) {
		List<Val> parmVals = new ArrayList<>();
		for (ParamVariableDeclaration p : potentialCallee.getParameters()) {
			parmVals.add(new Val(p.getName(), potentialCallee.getName()));
		}
		return parmVals;
	}

	private List<Val> argumentsToVals(CallExpression mce, String currentFunctionName) {
		List<Val> argVals = new ArrayList<>();
		List<Expression> args = mce.getArguments();
		for (Expression arg : args) {
			argVals.add(new Val(arg.getName(), currentFunctionName));
		}
		return argVals;
	}

	private HashMap<MOp, Vertex> getVerticesOfRule(MRule rule) {
		HashMap<MOp, Vertex> opToVertex = new HashMap<>();
		for (Map.Entry<String, Pair<String, MEntity>> entry : rule.getEntityReferences().entrySet()) {
			MEntity ent = entry.getValue().getValue1();
			if (ent == null) {
				continue;
			}
			for (MOp op : ent.getOps()) {
				op.getAllVertices().forEach(v -> opToVertex.put(op, v));
			}
		}
		return opToVertex;
	}

	/**
	 * Creates an initial configuration of a WPDS from where post* runs.
	 * <p>
	 * The initial configuration comprises the set of states (i.e. statements and variables on the stack) which are relevant for following typestate analysis and is given
	 * in form of a weighted automaton P.
	 * <p>
	 * Typically, the initial configuration will refer to a single "trigger" statement from where typestate analysis should start. This statement i
	 *
	 * @param variable
	 * @param method
	 * @return
	 */
	private WeightedAutomaton createInitialConfiguration(String stmt, String variable, String method, NFA nfa) {
		Val initialState = new Val(variable, method);
		WeightedAutomaton<Stmt, Val, Weight> wnfa = new WeightedAutomaton<Stmt, Val, Weight>(initialState) {
			@Override
			public Val createState(Val val, Stmt stmt) {
				return val;
			}

			@Override
			public boolean isGeneratedState(Val val) {
				return false;
			}

			@Override
			public Stmt epsilon() {
				return new Stmt("EPSILON");
			}

			@Override
			public Weight getZero() {
				return Weight.zero();
			}

			@Override
			public Weight getOne() {
				return Weight.one();
			}
		};
		Val ACCEPTING = new Val("ACCEPT", "ACCEPT");
		// Create an automaton for the initial configuration from where post* will start.
		wnfa.addTransition(new Transition<>(initialState, new Stmt(stmt), ACCEPTING), Weight.one());
		// Add final ("accepting") states to NFA.
		wnfa.addFinalState(ACCEPTING);

		return wnfa;
	}
}
