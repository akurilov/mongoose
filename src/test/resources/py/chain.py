from __future__ import print_function, absolute_import, with_statement
from sys import exit
from loadbuilder import INSTANCE as LOAD_BUILDER
from timeout import INSTANCE as RUN_TIME
#
from org.apache.logging.log4j import Level, LogManager
#
from com.emc.mongoose.base.api import Request
from com.emc.mongoose.run import Main
from com.emc.mongoose.util.logging import ExceptionHandler, Markers
from com.emc.mongoose.base.data.persist import TempFileConsumerProducer
#
from java.lang import Throwable, IllegalArgumentException
from java.util import NoSuchElementException
#
LOG = LogManager.getLogger()
#
LOAD_CHAIN = None
try:
	LOAD_CHAIN = Main.RUN_TIME_CONFIG.getStringArray("scenario.chain.load")
	LOG.info(Markers.MSG, "Load chain: {}", LOAD_CHAIN)
except NoSuchElementException:
	LOG.error(Markers.ERR, "No load type specified, try arg -Dscenario.chain.load=<VALUE> to override")
#
FLAG_SIMULTANEOUS = False
try:
	FLAG_SIMULTANEOUS = Main.RUN_TIME_CONFIG.getBoolean("scenario.chain.simultaneous")
	LOG.info(Markers.MSG, "Simultaneous chain load: {}", LOAD_CHAIN)
except NoSuchElementException:
	LOG.error(Markers.ERR, "No chain simultaneous flag specified, try arg -Dscenario.chain.simultaneous=<VALUE> to override")
#
def build(flagSimultaneous=True, dataItemSizeMin=0, dataItemSizeMax=0, threadsPerNode=0):
	chain = list()
	prevLoad = None
	for loadTypeStr in LOAD_CHAIN:
		LOG.debug(Markers.MSG, "Next load type is \"{}\"", loadTypeStr)
		try:
			LOAD_BUILDER.setLoadType(Request.Type.valueOf(loadTypeStr.upper()))
			if dataItemSizeMin > 0:
				LOAD_BUILDER.setMinObjSize(dataItemSizeMin)
			if dataItemSizeMax > 0:
				LOAD_BUILDER.setMaxObjSize(dataItemSizeMax)
			if threadsPerNode > 0:
				LOAD_BUILDER.setThreadsPerNodeDefault(threadsPerNode)
			load = LOAD_BUILDER.build()
			#
			if load is not None:
				if flagSimultaneous:
					if prevLoad is not None:
						prevLoad.setConsumer(load)
					chain.append(load)
				else:
					if prevLoad is not None:
						mediatorBuff = TempFileConsumerProducer(
							Main.RUN_TIME_CONFIG,
							'-'.join((Main.RUN_TIME_CONFIG.getRunName(), Main.RUN_TIME_CONFIG.getString(Main.KEY_RUN_ID))),
							'x'.join((Main.RUN_TIME_CONFIG.formatSize(dataItemSizeMin), str(threadsPerNode))),
							1, 0
						)
						if mediatorBuff is not None:
							prevLoad.setConsumer(mediatorBuff)
							chain.append(mediatorBuff)
							mediatorBuff.setConsumer(load)
							chain.append(load)
						else:
							LOG.error(Markers.ERR, "No mediator buffer instanced")
			else:
				LOG.error(Markers.ERR, "No load executor instanced")
			if prevLoad is None:
				LOAD_BUILDER.setInputFile(None) # prevent the file list producer creation for next loads
			prevLoad = load
		except IllegalArgumentException:
			LOG.error(Markers.ERR, "Wrong load type \"{}\", skipping", loadTypeStr)
		except Throwable as e:
			ExceptionHandler.trace(LOG, Level.FATAL, e, "Unexpected failure")
	return chain
	#
def execute(chain=(), flagSimultaneous=True):
	if flagSimultaneous:
		for load in chain:
			load.start()
		try:
			chain[0].join(RUN_TIME[1].toMillis(RUN_TIME[0]))
		except:
			LOG.error(Markers.ERR, "No 1st load executor in the chain")
	else:
		loadExecutor, prevMediatorBuff, mediatorBuff = None, None, None
		for load in chain:
			if isinstance(load, TempFileConsumerProducer):
				mediatorBuff = load
				if loadExecutor is not None:
					try:
						loadExecutor.start()
						if prevMediatorBuff is not None:
							prevMediatorBuff.start()
						loadExecutor.join(RUN_TIME[1].toMillis(RUN_TIME[0]))
						prevMediatorBuff.interrupt()
						loadExecutor.close()
						mediatorBuff.close()
						prevMediatorBuff = mediatorBuff
					except Throwable as e:
						ExceptionHandler.trace(LOG, Level.ERROR, e, "Chain execution failure")
			else:
				loadExecutor = load
	for load in chain:
		try:
			load.close()
		except Throwable as e:
			ExceptionHandler.trace(LOG, Level.WARN, e, "Chain element closing failure")
#
if __name__=="__builtin__":
	#
	dataItemSize, dataItemSizeMin, dataItemSizeMax, threadsPerNode = 0, 0, 0, 0
	#
	try:
		dataItemSize = Main.RUN_TIME_CONFIG.getSizeBytes("data.size")
	except:
		LOG.debug(Markers.MSG, "No \"data.size\" specified")
	try:
		dataItemSizeMin = Main.RUN_TIME_CONFIG.getSizeBytes("data.size.min")
	except:
		LOG.debug(Markers.MSG, "No \"data.size\" specified")
	try:
		dataItemSizeMax = Main.RUN_TIME_CONFIG.getSizeBytes("data.size.max")
	except:
		LOG.debug(Markers.MSG, "No \"data.size\" specified")
	try:
		threadsPerNode = Main.RUN_TIME_CONFIG.getShort("load.threads")
	except:
		LOG.debug(Markers.MSG, "No \"load.threads\" specified")
	#
	chain = build(
		FLAG_SIMULTANEOUS,
		dataItemSizeMin if dataItemSize == 0 else dataItemSize,
		dataItemSizeMax if dataItemSize == 0 else dataItemSize,
		threadsPerNode
	)
	execute(chain, FLAG_SIMULTANEOUS)
	#
	LOG.info(Markers.MSG, "Scenario end")
