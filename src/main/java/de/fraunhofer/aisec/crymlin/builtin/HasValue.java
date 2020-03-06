
package de.fraunhofer.aisec.crymlin.builtin;

import de.fraunhofer.aisec.analysis.markevaluation.ExpressionEvaluator;
import de.fraunhofer.aisec.analysis.structures.ConstantValue;
import de.fraunhofer.aisec.analysis.structures.ErrorValue;
import de.fraunhofer.aisec.analysis.structures.ListValue;
import de.fraunhofer.aisec.analysis.structures.MarkContextHolder;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This builtin checks if the markvar has a cpg-node
 */
public class HasValue implements Builtin {
	private static final Logger log = LoggerFactory.getLogger(HasValue.class);

	@Override
	public @NonNull String getName() {
		return "_has_value";
	}

	@Override
	public ConstantValue execute(
			ListValue argResultList,
			Integer contextID,
			MarkContextHolder markContextHolder,
			ExpressionEvaluator expressionEvaluator) {

		if (argResultList.size() != 1) {
			log.warn("Invalid number of arguments: {}", argResultList.size());
			return ErrorValue.newErrorValue("Invalid number of arguments: {}" + argResultList.size(), argResultList.getAll());
		}

		if (!(argResultList.get(0) instanceof ConstantValue)) {
			log.warn("Argument %s is not a ConstantValue");
			return ErrorValue.newErrorValue("Argument %s is not a ConstantValue", argResultList.getAll());
		}

		Set<Vertex> collect = ((ConstantValue) argResultList.get(0)).getResponsibleVertices().stream().filter(Objects::nonNull).collect(Collectors.toSet());

		ConstantValue ret = ConstantValue.of(collect.size() > 0);
		ret.addResponsibleVertices(collect);
		return ret;

	}
}