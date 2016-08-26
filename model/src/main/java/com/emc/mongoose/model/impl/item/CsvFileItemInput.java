package com.emc.mongoose.model.impl.item;

import com.emc.mongoose.model.api.data.ContentSource;
import com.emc.mongoose.model.api.item.FileItemInput;
import com.emc.mongoose.model.api.item.Item;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
/**
 Created by kurila on 30.06.15.
 */
public class CsvFileItemInput<T extends Item>
extends CsvItemInput<T>
implements FileItemInput<T> {
	//
	protected final Path itemsFilePath;
	/**
	 @param itemsFilePath the input stream to get the data item records from
	 @param itemCls the particular data item implementation class used to parse the records
	 @throws IOException
	 @throws NoSuchMethodException */
	public CsvFileItemInput(
		final Path itemsFilePath, final Class<? extends T> itemCls, final ContentSource contentSrc
	) throws IOException, NoSuchMethodException {
		super(Files.newInputStream(itemsFilePath, StandardOpenOption.READ), itemCls, contentSrc);
		this.itemsFilePath = itemsFilePath;
	}
	//
	@Override
	public String toString() {
		return "csvFileItemInput<" + itemsFilePath.getFileName() + ">";
	}
	//
	@Override
	public final Path getFilePath() {
		return itemsFilePath;
	}
	//
	@Override
	public final void delete()
	throws IOException {
		Files.delete(itemsFilePath);
	}
	//
	@Override
	public void reset()
	throws IOException {
		if(itemsSrc != null) {
			itemsSrc.close();
		}
		setItemsSrc(Files.newBufferedReader(itemsFilePath, StandardCharsets.UTF_8));
	}
}
