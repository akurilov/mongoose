package com.emc.mongoose.model.impl.io;

import com.emc.mongoose.common.math.Random;
import com.emc.mongoose.model.api.io.Input;

import java.io.File;
import java.util.List;

import static java.lang.Integer.parseInt;

public class FilePathInput
implements Input<String> {

	private static final int RADIX = Character.MAX_RADIX;
	private static final Random RANDOM = new Random();

	private final int width;
	private final int depth;

	public FilePathInput(final String paramsString) {
		this(areParamsValid(paramsString) ? paramsString.split(DELIMITER) : new String[]{});
	}

	private FilePathInput(final String[] params) {
		this(
			(params.length > 0 ? parseInt(params[0].replaceAll(" ", "")) : 0),
			(params.length > 1 ? parseInt(params[1].replaceAll(" ", "")) : 0)
		);
	}

	private static boolean areParamsValid(final String paramsString) {
		final int delPos;
		if(paramsString == null) {
			delPos = 0;
		} else {
			delPos = paramsString.indexOf(DELIMITER);
		}
		return delPos > 0 && delPos < paramsString.length() - 1;
	}

	public FilePathInput(int width, int depth) {
		this.width = width;
		this.depth = depth;
		if(width <= 0 || depth <= 0) {
			throw new IllegalArgumentException();
		}
	}

	private String nextDirName(final int width) {
		return Integer.toString(Math.abs(RANDOM.nextInt(width)), RADIX);
	}

	private final static ThreadLocal<StringBuilder>
		THREAD_LOCAL_PATH_BUILDER = new ThreadLocal<StringBuilder>() {
			@Override
			protected StringBuilder initialValue() {
				return new StringBuilder();
			}
		};

	@Override
	public String get() {
		final StringBuilder pathBuilder = THREAD_LOCAL_PATH_BUILDER.get();
		pathBuilder.setLength(0);
		final int newDepth = RANDOM.nextInt(depth) + 1;
		for(int i = 0; i < newDepth; i++) {
			pathBuilder.append(nextDirName(width));
			pathBuilder.append(File.separatorChar);
		}
		return pathBuilder.toString();
	}
	//
	@Override
	public int get(final List<String> buffer, final int limit) {
		int count = 0, newDepth;
		final StringBuilder pathBuilder = THREAD_LOCAL_PATH_BUILDER.get();
		for(; count < limit; count ++) {
			pathBuilder.setLength(0);
			newDepth = RANDOM.nextInt(depth) + 1;
			for(int i = 0; i < newDepth; i++) {
				pathBuilder.append(nextDirName(width));
				pathBuilder.append(File.separatorChar);
			}
			buffer.add(pathBuilder.toString());
		}
		return count;
	}
	//
	@Override
	public void skip(final long count) {
		for(long i = 0; i < count; i ++) {
			RANDOM.nextInt(depth);
		}
	}
	//
	@Override
	public void reset() {
		RANDOM.reset();
	}
	//
	@Override
	public void close() {
	}
}
