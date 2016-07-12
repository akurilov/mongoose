import com.emc.mongoose.storage.mock.config.StorageMockConfig;
import com.emc.mongoose.storage.mock.config.StorageMockDecoder;
import com.emc.mongoose.common.config.reader.ConfigReader;
import org.junit.Test;

import javax.json.JsonObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 Created on 11.07.16.
 */
public class StorageMockDecoderTest {

	private static String parameterErrorMessage(final String content) {
		return "Wrong " + content + " parameter";
	}

	@Test
	public void shouldCreateConfig() throws Exception{
		final StorageMockDecoder storageMockDecoder = new StorageMockDecoder();
		final JsonObject defaults = ConfigReader.readJson("defaults.json");
		assertNotNull("The configuration file was read wrong", defaults);
		final StorageMockConfig storageMockConfig =
			storageMockDecoder.decode(defaults);
		assertEquals(parameterErrorMessage("headCount"), storageMockConfig.getHeadCount(), 1);
		assertEquals(parameterErrorMessage("capacity"), storageMockConfig.getCapacity(), 1_000_000);
		final StorageMockConfig.Container container = storageMockConfig.container();
		assertEquals(parameterErrorMessage("container.capacity"), container.getCapacity(), 1_000_000);
		assertEquals(parameterErrorMessage("container.countLimit"), container.getCountLimit(), 1_000_000);
	}

}
