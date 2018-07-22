package com.emc.mongoose.item.io;

import com.emc.mongoose.item.DataItemFactory;
import com.emc.mongoose.item.Item;
import com.emc.mongoose.item.ItemFactory;
import com.github.akurilov.commons.io.file.FileInput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 Created by kurila on 30.06.15.
 */
public class CsvFileItemInput<I extends Item>
extends CsvItemInput<I>
implements FileInput<I> {
	//
	protected final Path itemsFilePath;
	/**
	 @param itemsFilePath the input stream to get the data item records from
	 @param itemFactory the concrete item factory
	 @throws IOException
	 @throws NoSuchMethodException */
	public CsvFileItemInput(final Path itemsFilePath, final ItemFactory<I> itemFactory)
	throws IOException, NoSuchMethodException {
		super(Files.newBufferedReader(itemsFilePath, StandardCharsets.UTF_8), itemFactory);
		this.itemsFilePath = itemsFilePath;
	}
	//
	@Override
	public String toString() {
		return (itemFactory instanceof DataItemFactory ? "Data" : "") +
			"ItemsFromFile(" + itemsFilePath + ")";
	}
	//
	@Override
	public final Path getFilePath() {
		return itemsFilePath;
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
