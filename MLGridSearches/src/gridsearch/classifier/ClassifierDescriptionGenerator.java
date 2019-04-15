package gridsearch.classifier;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import hasco.core.HASCO;
import hasco.core.HASCOSolutionCandidate;
import hasco.core.RefinementConfiguredSoftwareConfigurationProblem;
import hasco.events.HASCOSolutionEvent;
import hasco.model.ComponentInstance;
import hasco.serialization.CompositionSerializer;
import hasco.variants.forwarddecomposition.HASCOViaFD;
import jaicore.basic.FileUtil;
import jaicore.basic.algorithm.events.AlgorithmEvent;
import jaicore.basic.algorithm.reduction.AlgorithmicProblemReduction;
import jaicore.basic.algorithm.reduction.IdentityReduction;
import jaicore.basic.sets.SetUtil;
import jaicore.experiments.IExperimentJSONKeyGenerator;
import jaicore.planning.hierarchical.algorithms.forwarddecomposition.graphgenerators.tfd.TFDNode;
import jaicore.search.algorithms.standard.auxilliary.iteratingoptimizer.IteratingGraphSearchOptimizer;
import jaicore.search.algorithms.standard.auxilliary.iteratingoptimizer.IteratingGraphSearchOptimizerFactory;
import jaicore.search.algorithms.standard.dfs.DepthFirstSearch;
import jaicore.search.algorithms.standard.dfs.DepthFirstSearchFactory;
import jaicore.search.core.interfaces.IOptimalPathInORGraphSearchFactory;
import jaicore.search.model.other.EvaluatedSearchGraphPath;
import jaicore.search.probleminputs.GraphSearchWithPathEvaluationsInput;

public class ClassifierDescriptionGenerator implements IExperimentJSONKeyGenerator {

	private final String configFile = "resources/searchmodels/weka/weka-classifiers-smo-poly.json";
	private final HASCO<?, ?, ?, ?> hasco;
	private List<ObjectNode> configurations;
	private final RefinementConfiguredSoftwareConfigurationProblem<Double> problem;
	private final int maxSolutionsPerRun = 50000;

	public ClassifierDescriptionGenerator() throws IOException {
		super();

		/* create a HASCO obejct */
		problem = new RefinementConfiguredSoftwareConfigurationProblem<>(new File(configFile), "AbstractClassifier", n -> 0.0);
		IOptimalPathInORGraphSearchFactory<GraphSearchWithPathEvaluationsInput<TFDNode, String, Double>, TFDNode, String, Double> searchFactory = new IteratingGraphSearchOptimizerFactory<>(new DepthFirstSearchFactory<>());
		AlgorithmicProblemReduction<GraphSearchWithPathEvaluationsInput<TFDNode, String, Double>, EvaluatedSearchGraphPath<TFDNode, String, Double>, GraphSearchWithPathEvaluationsInput<TFDNode, String, Double>, EvaluatedSearchGraphPath<TFDNode, String, Double>> searchProblemTransformer = new IdentityReduction<>();
		HASCOViaFD<GraphSearchWithPathEvaluationsInput<TFDNode, String, Double>, Double> hasco = new HASCOViaFD<>(problem, searchFactory, searchProblemTransformer);
		this.hasco = hasco;
	}

	@Override
	public int getNumberOfValues() {

		try {

			/* if the number of values has not been computed before, run a full DFS */
			if (configurations == null) {
				readConfigs();
			}
			return configurations.size();
		}
		catch (Exception e) {
			e.printStackTrace();
			return -1;
		}
	}

	@SuppressWarnings("rawtypes")
	private void readConfigs() throws IOException {
		if (configurations != null) {
			throw new UnsupportedOperationException("Cannot reload configurations");
		}
		Set<ComponentInstance> solutions = new HashSet<>();
		File cachedFile = new File(problem.hashCode() + ".configs");
		File currentSearchFile = new File("dfs.state");
		StringBuilder sb = new StringBuilder();
		int numSolutions = 0;
		configurations = new ArrayList<>();
		if (!cachedFile.exists() || currentSearchFile.exists()) {
			
			/* get HASCO's depth first search in order to retrieve or set the current index  */
			DepthFirstSearch<?, ?> dfs = (DepthFirstSearch)((IteratingGraphSearchOptimizer)hasco.getSearch()).getBaseAlgorithm();
			
			/* if a search state file exists, set the current state of the DFS according to the sotred decision array */
			if (currentSearchFile.exists()) {
				List<Integer> decisions = SetUtil.unserializeList(FileUtil.readFileAsString(currentSearchFile).trim()).stream().map(Integer::valueOf).collect(Collectors.toList());
				int[] decisionsAsArray = new int[decisions.size()];
				for (int i = 0; i < decisionsAsArray.length; i++) {
					decisionsAsArray[i] = decisions.get(i);
				}
				System.out.println(Arrays.toString(decisionsAsArray));
				dfs.setCurrentPath(decisionsAsArray);
				Files.delete(currentSearchFile.toPath());
			}
			
			/* identify new solutions with HASCO */
			for (AlgorithmEvent e : hasco) {
				if (e instanceof HASCOSolutionEvent){
					
					/* get the solution object */
					ComponentInstance solution = ((HASCOSolutionCandidate<?>)((HASCOSolutionEvent) e).getSolutionCandidate()).getComponentInstance();
					assert !solutions.contains(solution) : "Found solution " + solution + "twice!";
					ObjectNode on = CompositionSerializer.serializeComponentInstance(solution);
					configurations.add(on);
					sb.append(on.toString() + "\n");
					numSolutions ++;
					if (numSolutions % 100 == 0) {
						System.out.println("Found " + numSolutions + " so far.");
					}
					
					/* if we have the limit of solutions reached, write the candidates into the output file and quit */
					if (numSolutions == maxSolutionsPerRun) {
						System.out.print("Serializing to " + currentSearchFile.getAbsolutePath());
						try (FileWriter fw = new FileWriter(currentSearchFile)) {
							fw.write(Arrays.toString(dfs.getDecisionIndicesForCurrentPath()));
						}
						System.out.println(" [done]");
						break;
					}
				}
			}
			try (FileWriter fw = new FileWriter(cachedFile, true)) {
				fw.write(sb.toString());
			}
		}
		else {
			ObjectMapper om = new ObjectMapper();
			for (String line : FileUtil.readFileAsList(cachedFile)) {
				configurations.add((ObjectNode)om.readTree(line));
			}
		}
	}

	@Override
	public ObjectNode getValue(final int i) {
		try {
			readConfigs();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return configurations.get(i);
	}

	@Override
	public boolean isValueValid(final String value) {
		try {
			return configurations.contains(new ObjectMapper().readTree(value));
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}

	public static void main(final String[] args) throws IOException {
		ClassifierDescriptionGenerator gen = new ClassifierDescriptionGenerator();
		gen.readConfigs();
		System.out.println(gen.getNumberOfValues());
		//		for (ObjectNode configuration : gen.configurations) {
		//			System.out.println(configuration);
		//		}
	}
}
