package com.ctrip.gs.recommendation.svd;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.mahout.common.HadoopUtil;
import org.apache.mahout.common.Pair;
import org.apache.mahout.common.iterator.sequencefile.PathFilters;
import org.apache.mahout.common.iterator.sequencefile.PathType;
import org.apache.mahout.common.iterator.sequencefile.SequenceFileDirIterable;
import org.apache.mahout.common.iterator.sequencefile.SequenceFileDirValueIterator;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;
import org.apache.mahout.math.als.AlternatingLeastSquaresSolver;
import org.apache.mahout.math.map.OpenIntObjectHashMap;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.io.Closeables;

final class ALS {

	private ALS() {
	}

	static Vector readFirstRow(Path dir, Configuration conf) throws IOException {
		Iterator<VectorWritable> iterator = new SequenceFileDirValueIterator<VectorWritable>(dir, PathType.LIST,
				PathFilters.partFilter(), null, true, conf);
		return iterator.hasNext() ? iterator.next().get() : null;
	}

	public static OpenIntObjectHashMap<Vector> readMatrixByRowsFromDistributedCache(int numEntities, Configuration conf)
			throws IOException {

		IntWritable rowIndex = new IntWritable();
		VectorWritable row = new VectorWritable();

		OpenIntObjectHashMap<Vector> featureMatrix = numEntities > 0 ? new OpenIntObjectHashMap<Vector>(numEntities)
				: new OpenIntObjectHashMap<Vector>();

		Path[] cachedFiles = HadoopUtil.getCachedFiles(conf);
		LocalFileSystem localFs = FileSystem.getLocal(conf);

		for (Path cachedFile : cachedFiles) {

			SequenceFile.Reader reader = null;
			try {
				reader = new SequenceFile.Reader(localFs, cachedFile, conf);
				while (reader.next(rowIndex, row)) {
					featureMatrix.put(rowIndex.get(), row.get());
				}
			} finally {
				Closeables.close(reader, true);
			}
		}

		Preconditions.checkState(!featureMatrix.isEmpty(), "Feature matrix is empty");
		return featureMatrix;
	}

	public static OpenIntObjectHashMap<Vector> readMatrixByRows(Path dir, Configuration conf) {
		OpenIntObjectHashMap<Vector> matrix = new OpenIntObjectHashMap<Vector>();
		for (Pair<IntWritable, VectorWritable> pair : new SequenceFileDirIterable<IntWritable, VectorWritable>(dir,
				PathType.LIST, PathFilters.partFilter(), conf)) {
			int rowIndex = pair.getFirst().get();
			Vector row = pair.getSecond().get();
			matrix.put(rowIndex, row);
		}
		return matrix;
	}

	public static Vector solveExplicit(VectorWritable ratingsWritable, OpenIntObjectHashMap<Vector> uOrM, double lambda,
			int numFeatures) {
		Vector ratings = ratingsWritable.get();

		List<Vector> featureVectors = Lists.newArrayListWithCapacity(ratings.getNumNondefaultElements());
		for (Vector.Element e : ratings.nonZeroes()) {
			int index = e.index();
			featureVectors.add(uOrM.get(index));
		}

		return AlternatingLeastSquaresSolver.solve(featureVectors, ratings, lambda, numFeatures);
	}
}
