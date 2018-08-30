new java.lang.ProcessBuilder()
	.command("/bin/sh", "-c", "rm -f " + ITEM_LIST_FILE)
	.start()
	.waitFor();

var itemNamingConfig = {
	"item": {
		"naming": {
			"length": 16,
			"radix": 16
		}
	}
}

PreconditionLoad
	.config(itemNamingConfig)
	.config(
		{
			"item": {
				"data": {
					"size": ITEM_DATA_SIZE
				},
				"output": {
					"file": ITEM_LIST_FILE,
					"path": ITEM_OUTPUT_PATH + "/%p{16;2}"
				}
			},
			"load": {
				"step": {
					"limit": {
						"count": STEP_LIMIT_COUNT
					}
				}
			}
		}
	)
	.run();

ReadVerifyLoad
	.config(itemNamingConfig)
	.config(
		{
			"item": {
				"input": {
					"file": ITEM_LIST_FILE
				}
			}
		}
	)
	.run();
