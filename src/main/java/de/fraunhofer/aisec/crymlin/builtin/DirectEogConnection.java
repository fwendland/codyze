
package de.fraunhofer.aisec.crymlin.builtin;

import de.fraunhofer.aisec.analysis.markevaluation.ExpressionEvaluator;
import de.fraunhofer.aisec.analysis.structures.ConstantValue;
import de.fraunhofer.aisec.analysis.structures.ErrorValue;
import de.fraunhofer.aisec.analysis.structures.ListValue;
import de.fraunhofer.aisec.analysis.structures.MarkContextHolder;
import de.fraunhofer.aisec.crymlin.CrymlinQueryWrapper;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * this Builtin checks if there is a _direct_ EOG-connection (i.e., without branches) between the two given vertices (responsible for the markvars given as parameters)
 */

public class DirectEogConnection implements Builtin {
	private static final Logger log = LoggerFactory.getLogger(DirectEogConnection.class);

	@Override
	public @NonNull String getName() {
		return "_direct_eog_connection";
	}

	@Override
	public ConstantValue execute(
			ListValue argResultList,
			Integer contextID,
			MarkContextHolder markContextHolder,
			ExpressionEvaluator expressionEvaluator) {

		try {
			List<Vertex> vertices = BuiltinHelper.extractResponsibleVertices(argResultList, 2);
			// now we have one vertex each for arg0 and arg1, both not null

			ConstantValue ret = ConstantValue.of(CrymlinQueryWrapper.eogConnection(vertices.get(0), vertices.get(1), false));
			ret.addResponsibleVertices(vertices.get(0), vertices.get(1));
			return ret;

		}
		catch (InvalidArgumentException e) {
			log.warn(e.getMessage());
			return ErrorValue.newErrorValue(e.getMessage(), argResultList.getAll());
		}
	}
}
