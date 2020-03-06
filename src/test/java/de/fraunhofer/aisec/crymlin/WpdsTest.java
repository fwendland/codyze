
package de.fraunhofer.aisec.crymlin;

import de.fraunhofer.aisec.analysis.structures.Finding;
import de.fraunhofer.aisec.analysis.structures.TypestateMode;
import de.fraunhofer.aisec.analysis.wpds.NFA;
import de.fraunhofer.aisec.mark.XtextParser;
import de.fraunhofer.aisec.mark.markDsl.OrderExpression;
import de.fraunhofer.aisec.markmodel.fsm.FSM;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WPDS-based evaluation of order expressions.
 */
class WpdsTest extends AbstractMarkTest {

	WpdsTest() {
		// Using WPDS instead of NFA
		tsMode = TypestateMode.WPDS;
	}

	@Test
	void testRegexToNFA() throws Exception {
		XtextParser parser = new XtextParser();
		parser.addMarkFile(new File("src/test/resources/unittests/order2.mark"));
		OrderExpression expr = (OrderExpression) parser
				.parse()
				.values()
				.iterator()
				.next()
				.getRule()
				.get(0)
				.getStmt()
				.getEnsure()
				.getExp();
		expr = expr.getExp();

		// Use this implementation ...
		FSM fsm = new FSM();
		fsm.sequenceToFSM(expr);

		// ... and that implementation
		NFA nfa = NFA.of(expr);

		// TODO Compare both. Requires exposing list of transitions for FSM.
	}

	@Test
	void testCppInterprocOk1() throws Exception {
		@NonNull
		Set<Finding> findings = performTest("unittests/orderInterprocOk1.cpp", "unittests/order2.mark");

		// Note that line numbers of the "range" are the actual line numbers -1. This is required for proper LSP->editor mapping
		assertEquals(0, findings.stream().filter(Finding::isProblem).count());
	}

	@Test
	void testWpdsVector() throws Exception {

		@NonNull
		Set<Finding> findings = performTest("unittests/wpds-vector-example.java", "unittests/vector.mark");

		// Extract <line nr, isProblem> from findings
		Map<Integer, Boolean> startLineNumbers = findings.stream()
				.collect(Collectors.toMap(
					f -> f.getRanges().get(0).getStart().getLine(),
					f -> f.isProblem(),
					(isProblemA, isProblemB) -> {
						System.out.println("Several findings : " + isProblemA + "/" + isProblemB);
						return isProblemA && isProblemB;
					}));
	}

	@Test
	void testWpdsOK1() throws Exception {
		@NonNull
		Set<Finding> findings = performTest("unittests/wpds-ok1.cpp", "unittests/order2.mark");

		// Extract <line nr, isProblem> from findings
		Map<Integer, Boolean> startLineNumbers = findings.stream()
				.collect(Collectors.toMap(
					f -> f.getRanges().get(0).getStart().getLine(),
					f -> f.isProblem(),
					(isProblemA, isProblemB) -> {
						System.out.println("Several findings : " + isProblemA + "/" + isProblemB);
						return isProblemA && isProblemB;
					}));

		// Note that line numbers of the "range" are the actual line numbers -1. This is required for proper LSP->editor mapping
		assertTrue(startLineNumbers.containsKey(10)); // create
		assertFalse(startLineNumbers.get(10));
		assertTrue(startLineNumbers.containsKey(13)); // init
		assertFalse(startLineNumbers.get(13));
		assertTrue(startLineNumbers.containsKey(29)); // start
		assertFalse(startLineNumbers.get(29));
		assertTrue(startLineNumbers.containsKey(31)); // process
		assertFalse(startLineNumbers.get(31));
		assertTrue(startLineNumbers.containsKey(24)); // process
		assertFalse(startLineNumbers.get(24));
		assertTrue(startLineNumbers.containsKey(26)); // finish
		assertFalse(startLineNumbers.get(26));
	}

	@Test
	void testWpdsOK2() throws Exception {
		@NonNull
		Set<Finding> findings = performTest("unittests/wpds-ok2.cpp", "unittests/order2.mark");

		// Extract <line nr, isProblem> from findings
		Map<Integer, Boolean> startLineNumbers = findings.stream()
				.collect(Collectors.toMap(
					f -> f.getRanges().get(0).getStart().getLine(),
					f -> f.isProblem(),
					(isProblemA, isProblemB) -> {
						System.out.println("Several findings : " + isProblemA + "/" + isProblemB);
						return isProblemA && isProblemB;
					}));

		// Note that line numbers of the "range" are the actual line numbers -1. This is required for proper LSP->editor mapping
		assertTrue(startLineNumbers.containsKey(10)); // create
		assertFalse(startLineNumbers.get(10));
		assertTrue(startLineNumbers.containsKey(15)); // init
		assertFalse(startLineNumbers.get(15));
		assertTrue(startLineNumbers.containsKey(17)); // start
		assertFalse(startLineNumbers.get(17));
		assertTrue(startLineNumbers.containsKey(20)); // process
		assertFalse(startLineNumbers.get(20));
		assertTrue(startLineNumbers.containsKey(21)); // process
		assertFalse(startLineNumbers.get(21));
		assertTrue(startLineNumbers.containsKey(23)); // process
		assertFalse(startLineNumbers.get(23));
		assertTrue(startLineNumbers.containsKey(25)); // finish
		assertFalse(startLineNumbers.get(25));
	}

	@Test
	@Disabled // Disabled as if-branches are not yet correctly translated into WPDS rules
	void testWpdsOk3() throws Exception {
		@NonNull
		Set<Finding> findings = performTest("unittests/wpds-ok3.cpp", "unittests/wpds-3.mark");

		// Extract <line nr, isProblem> from findings
		Map<Integer, Boolean> startLineNumbers = findings.stream()
														 .collect(Collectors.toMap(
																 f -> f.getRanges().get(0).getStart().getLine(),
																 f -> f.isProblem(),
																 (isProblemA, isProblemB) -> {
																	 System.out.println("Several findings : " + isProblemA + "/" + isProblemB);
																	 return isProblemA && isProblemB;
																 }));

		// Note that line numbers of the "range" are the actual line numbers -1. This is required for proper LSP->editor mapping
		assertTrue(startLineNumbers.containsKey(10)); // create
		assertFalse(startLineNumbers.get(10));
		assertTrue(startLineNumbers.containsKey(15)); // init
		assertFalse(startLineNumbers.get(15));
		assertTrue(startLineNumbers.containsKey(17)); // start
		assertFalse(startLineNumbers.get(17));
		assertTrue(startLineNumbers.containsKey(20)); // process
		assertFalse(startLineNumbers.get(20));
		assertTrue(startLineNumbers.containsKey(21)); // process
		assertFalse(startLineNumbers.get(21));
		assertTrue(startLineNumbers.containsKey(23)); // process
		assertFalse(startLineNumbers.get(23));
		assertTrue(startLineNumbers.containsKey(25)); // finish
		assertFalse(startLineNumbers.get(25));
	}

	@Test
	void testWpdsNOK1() throws Exception {
		@NonNull
		Set<Finding> findings = performTest("unittests/wpds-nok1.cpp", "unittests/order2.mark");

		// Extract <line nr, isProblem> from findings
		Map<Integer, Boolean> startLineNumbers = findings.stream()
				.collect(Collectors.toMap(
					f -> f.getRanges().get(0).getStart().getLine(),
					f -> f.isProblem(),
					(isProblemA, isProblemB) -> {
						System.out.println("Several findings : " + isProblemA + "/" + isProblemB);
						return isProblemA && isProblemB;
					}));

		// Note that line numbers of the "range" are the actual line numbers -1. This is required for proper LSP->editor mapping
		assertTrue(startLineNumbers.containsKey(22)); // start
		assertTrue(startLineNumbers.get(22));
		assertTrue(startLineNumbers.containsKey(24)); // start
		assertTrue(startLineNumbers.get(24));
		//		assertTrue(startLineNumbers.containsKey(29)); // start
		//		assertTrue(startLineNumbers.get(29));
	}

	@Test
	void testCppInterprocNOk1() throws Exception {
		@NonNull
		Set<Finding> findings = performTest("unittests/orderInterprocNOk1.cpp", "unittests/order2.mark");

		// Extract <line nr, isProblem> from findings
		Map<Integer, Boolean> startLineNumbers = findings.stream()
				.collect(Collectors.toMap(
					f -> f.getRanges().get(0).getStart().getLine(),
					f -> f.isProblem(),
					(isProblemA, isProblemB) -> {
						System.out.println("Several findings : " + isProblemA + "/" + isProblemB);
						return isProblemA && isProblemB;
					}));

		// Note that line numbers of the "range" are the actual line numbers -1. This is required for proper LSP->editor mapping
		assertTrue(startLineNumbers.containsKey(28));
		assertTrue(startLineNumbers.get(28)); // isProblem
		assertTrue(startLineNumbers.containsKey(30));
		assertTrue(startLineNumbers.get(30)); // isProblem
		assertTrue(startLineNumbers.containsKey(32));
		assertTrue(startLineNumbers.get(32)); // isProblem
	}

	@Test
	void testCppInterprocNOk2() throws Exception {
		@NonNull
		Set<Finding> findings = performTest("unittests/orderInterprocNOk2.cpp", "unittests/order2.mark");

		Map<Integer, Boolean> startLineNumbers = findings.stream()
				.collect(Collectors.toMap(
					f -> f.getRanges().get(0).getStart().getLine(),
					f -> f.isProblem(),
					(isProblemA, isProblemB) -> {
						System.out.println("Several findings : " + isProblemA + "/" + isProblemB);
						return isProblemA && isProblemB;
					}));

		// Note that line numbers of the "range" are the actual line numbers -1. This is required for proper LSP->editor mapping
		assertTrue(startLineNumbers.containsKey(30));
		assertTrue(startLineNumbers.get(30)); // isProblem
	}
}