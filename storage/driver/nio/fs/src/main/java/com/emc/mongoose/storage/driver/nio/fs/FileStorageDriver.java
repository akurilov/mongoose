package com.emc.mongoose.storage.driver.nio.fs;

import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.api.model.item.Item;
import com.emc.mongoose.api.model.item.ItemFactory;
import com.emc.mongoose.storage.driver.nio.base.NioStorageDriver;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import static java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 Created by andrey on 01.12.16.
 */
public interface FileStorageDriver<I extends Item, O extends IoTask<I>>
extends NioStorageDriver<I, O> {

	FileSystem FS = FileSystems.getDefault();
	FileSystemProvider FS_PROVIDER = FS.provider();
	
	Set<OpenOption> CREATE_OPEN_OPT = new HashSet<OpenOption>() {
		{
			add(StandardOpenOption.CREATE);
			add(StandardOpenOption.TRUNCATE_EXISTING);
			add(StandardOpenOption.WRITE);
		}
	};
	Set<OpenOption> READ_OPEN_OPT = new HashSet<OpenOption>() {
		{
			add(StandardOpenOption.READ);
		}
	};
	Set<OpenOption> WRITE_OPEN_OPT = new HashSet<OpenOption>() {
		{
			add(StandardOpenOption.WRITE);
		}
	};

	DirectoryStream.Filter<Path> ACCEPT_ALL_PATHS_FILTER = entry -> true;

	final class PrefixDirectoryStreamFilter
	implements Filter<Path> {

		private final PathMatcher pathPrefixMatcher;

		public PrefixDirectoryStreamFilter(final String prefix) {
			pathPrefixMatcher = FS.getPathMatcher("glob:" + prefix + "*");
		}

		@Override
		public final boolean accept(final Path entry)
		throws IOException {
			return pathPrefixMatcher.matches(entry.getFileName());
		}
	}

	@Override
	default List<I> list(
		final ItemFactory<I> itemFactory, final String path, final String prefix, final int idRadix,
		final I lastPrevItem, final int count
	) throws IOException {
		return _list(itemFactory, path, prefix, idRadix, lastPrevItem, count);
	}

	static <I extends Item> List<I> _list(
		final ItemFactory<I> itemFactory, final String path, final String prefix, final int idRadix,
		final I lastPrevItem, final int count
	) throws IOException {

		final Filter<Path> filter = (prefix == null || prefix.isEmpty()) ?
			ACCEPT_ALL_PATHS_FILTER : new PrefixDirectoryStreamFilter(prefix);
		final List<I> buff = new ArrayList<>(count);

		try(
			final DirectoryStream<Path> dirStream = FS_PROVIDER.newDirectoryStream(
				Paths.get(path), filter
			)
		) {
			final int prefixLength = (prefix == null || prefix.isEmpty()) ?
				0 : prefix.length();

			File nextFile;
			String nextFileName;
			I nextItem;

			final String lastPrevItemName;
			boolean lastPrevItemNameFound;
			if(lastPrevItem == null) {
				lastPrevItemName = null;
				lastPrevItemNameFound = true;
			} else {
				lastPrevItemName = lastPrevItem.getName();
				lastPrevItemNameFound = false;
			}

			for(final Path nextPath : dirStream) {
				nextFile = new File(nextPath.toString());
				nextFileName = nextFile.getName();
				if(lastPrevItemNameFound) {
					try {
						final long offset;
						if(prefixLength > 0) {
							// only items with the prefix are passed so it's safe
							offset = Long.parseLong(nextFileName.substring(prefixLength), idRadix);
						} else {
							offset = Long.parseLong(nextFileName, idRadix);
						}
						nextItem = itemFactory.getItem(
							nextFile.getAbsolutePath(), offset, nextFile.length()
						);
					} catch(final NumberFormatException e) {
						// try to not use the offset (read verification should be disabled)
						nextItem = itemFactory.getItem(
							nextFile.getAbsolutePath(), 0, nextFile.length()
						);
					}
					buff.add(nextItem);
					if(count == buff.size()) {
						break;
					}
				} else {
					lastPrevItemNameFound = nextFileName.equals(lastPrevItemName);
				}
			}
		} catch(final DirectoryIteratorException e) {
			throw e.getCause(); // according the JDK documentation
		}

		return buff;
	}
}
