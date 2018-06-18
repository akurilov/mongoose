package com.emc.mongoose.load.step.client;

import com.emc.mongoose.config.TimeUtil;
import com.emc.mongoose.env.Extension;
import com.emc.mongoose.exception.OmgShootMyFootException;
import com.emc.mongoose.item.io.IoType;
import com.emc.mongoose.item.io.task.IoTask;
import com.emc.mongoose.load.step.StepFileManager;
import com.emc.mongoose.load.step.StepFileManagerImpl;
import com.emc.mongoose.load.step.LoadStepBase;
import com.emc.mongoose.metrics.AggregatingMetricsContext;
import com.emc.mongoose.metrics.MetricsSnapshot;
import com.emc.mongoose.logging.LogContextThreadFactory;
import com.emc.mongoose.concurrent.ServiceTaskExecutor;
import com.emc.mongoose.data.DataInput;
import com.emc.mongoose.item.CsvFileItemInput;
import com.emc.mongoose.item.Item;
import com.emc.mongoose.item.ItemFactory;
import com.emc.mongoose.item.ItemType;
import com.emc.mongoose.storage.driver.StorageDriver;
import com.emc.mongoose.svc.Service;
import com.emc.mongoose.svc.ServiceUtil;
import com.emc.mongoose.item.StorageItemInput;
import com.emc.mongoose.load.step.StepFileManagerService;
import com.emc.mongoose.load.step.StepFileService;
import com.emc.mongoose.load.step.LoadStep;
import com.emc.mongoose.load.step.LoadStepManagerService;
import com.emc.mongoose.load.step.service.LoadStepService;
import com.emc.mongoose.load.step.metrics.MetricsSnapshotsSupplierTask;
import com.emc.mongoose.load.step.metrics.MetricsSnapshotsSupplierTaskImpl;
import com.emc.mongoose.logging.LogUtil;
import com.emc.mongoose.logging.Loggers;
import static com.emc.mongoose.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.Constants.KEY_STEP_ID;

import com.github.akurilov.commons.func.Function2;
import com.github.akurilov.commons.func.Function3;
import com.github.akurilov.commons.io.Input;
import com.github.akurilov.commons.io.file.BinFileInput;
import com.github.akurilov.commons.net.NetUtil;
import com.github.akurilov.commons.system.SizeInBytes;

import com.github.akurilov.confuse.Config;

import com.github.akurilov.confuse.impl.BasicConfig;
import org.apache.logging.log4j.CloseableThreadContext;
import static org.apache.logging.log4j.CloseableThreadContext.Instance;
import org.apache.logging.log4j.Level;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class LoadStepClientBase
extends LoadStepBase
implements LoadStepClient {

	private final Map<LoadStep, MetricsSnapshotsSupplierTask> metricsSnapshotsSuppliers;

	public LoadStepClientBase(
		final Config baseConfig, final List<Extension> extensions, final List<Map<String, Object>> stepConfigs
	) {
		super(baseConfig, extensions, stepConfigs);
		this.metricsSnapshotsSuppliers = new HashMap<>();
	}

	private List<LoadStep> stepSlices = new ArrayList<>();
	private Map<String, Optional<StepFileManager>> fileMgrs = null;
	private Map<String, Optional<StepFileService>> itemInputFileSvcs = null;
	private Map<String, Optional<StepFileService>> itemOutputFileSvcs = null;
	private Map<String, Optional<StepFileService>> ioTraceLogFileSvcs = null;

	@Override
	protected final void doStartWrapped()
	throws IllegalArgumentException {

		try(
			final Instance logCtx = CloseableThreadContext
				.put(KEY_STEP_ID, id())
				.put(KEY_CLASS_NAME, LoadStepClientBase.class.getSimpleName())
		) {
			// need to set the once generated step id
			final Config config = new BasicConfig(baseConfig);
			config.val("load-step-id", id());

			final Config nodeConfig = baseConfig.configVal("load-step-node");
			final int nodePort = nodeConfig.intVal("port");
			final Function<String, String> addPortIfMissingPartialFunc = Function2
				.partial2(NetUtil::addPortIfMissing, nodePort);
			final List<String> nodeAddrs = nodeConfig
				.<String>listVal("addrs")
				.stream()
				.map(addPortIfMissingPartialFunc)
				.collect(Collectors.toList());
			if(nodeAddrs.size() < 1) {
				throw new IllegalArgumentException(
					"There should be at least 1 node address to be configured if the distributed mode is enabled"
				);
			}

			fileMgrs = initFileManagers(id(), nodeAddrs);
			Loggers.MSG.debug("{}: file manager services resolved", id());

			if(baseConfig.boolVal("output-metrics-trace-persist")) {
				initIoTraceLogFileServices(nodeAddrs);
			}
			final Map<String, Config> configSlices = sliceConfigs(baseConfig, nodeAddrs);

			nodeAddrs
				.parallelStream()
				.map(
					nodeAddrWithPort -> {
						final LoadStepService stepSvc = resolveStepSvc(configSlices, nodeAddrWithPort);
						if(stepSvc != null) {
							try {
								stepSvc.start();
								final MetricsSnapshotsSupplierTask
									snapshotsSupplier = new MetricsSnapshotsSupplierTaskImpl(
									ServiceTaskExecutor.INSTANCE, stepSvc
								);
								snapshotsSupplier.start();
								metricsSnapshotsSuppliers.put(stepSvc, snapshotsSupplier);
							} catch(final RemoteException | IllegalStateException e) {
								try {
									LogUtil.exception(
										Level.ERROR, e, "Failed to start the step service {}", stepSvc.name()
									);
								} catch(final RemoteException ignored) {
								}
							}
						}
						return stepSvc;
					}
				)
				.forEach(stepSlices::add);

			Loggers.MSG.info(
				"Load step client \"{}\" started @ {}", id(), Arrays.toString(nodeAddrs.toArray())
			);
		} catch(final RemoteException ignored) {
		}
	}

	protected final void initMetrics(
		final int originIndex, final IoType ioType, final int concurrency, final int nodeCount,
		final Config metricsConfig, final SizeInBytes itemDataSize, final boolean outputColorFlag
	) {
		metricsContexts.add(
			new AggregatingMetricsContext(
				id(), ioType, nodeCount, concurrency * nodeCount,
				(int) (concurrency * nodeCount * metricsConfig.doubleVal("threshold")),
				itemDataSize,
				(int) TimeUtil.getTimeInSeconds(metricsConfig.stringVal("average-period")),
				outputColorFlag, metricsConfig.boolVal("average-persist"),
				metricsConfig.boolVal("summary-persist"),
				metricsConfig.boolVal("summary-perfDbResultsFile"),
				() -> stepClient.remoteMetricsSnapshots(originIndex)
			)
		);
	}

	@Override
	protected final void doShutdown() {
		stepSlices
			.parallelStream()
			.forEach(
				stepSlice -> {
					try(
						final Instance logCtx = CloseableThreadContext
							.put(KEY_STEP_ID, id())
							.put(KEY_CLASS_NAME, LoadStepClientBase.class.getSimpleName())
					) {
						stepSlice.shutdown();
					} catch(final RemoteException e) {
						LogUtil.exception(Level.WARN, e, "Failed to shutdown the step service {}", stepSlice);
					}
				}
			);
	}

	private Map<String, Config> sliceConfigs(final Config config, final List<String> nodeAddrs) {

		// TODO - issue #1193 - modify to include local

		final Map<String, Config> configSlices = nodeAddrs
			.stream()
			.collect(Collectors.toMap(Function.identity(), Function2.partial1(LoadStep::initConfigSlice, config)));

		// slice the count limit (if any)
		final int nodeCount = nodeAddrs.size();
		final long countLimit = config.longVal("load-step-limit-count");
		if(nodeCount > 1 && countLimit > 0) {
			final long countLimitPerNode = (long) Math.ceil(((double) countLimit) / nodeCount);
			long remainingCountLimit = countLimit;
			for(final Map.Entry<String, Config> configEntry : configSlices.entrySet()) {
				final Config limitConfigSlice = configEntry.getValue().configVal("load-step-limit");
				if(remainingCountLimit > countLimitPerNode) {
					Loggers.MSG.info("Node \"{}\": count limit = {}", configEntry.getKey(), countLimitPerNode);
					limitConfigSlice.val("count", countLimitPerNode);
					remainingCountLimit -= countLimitPerNode;
				} else {
					Loggers.MSG.info("Node \"{}\": count limit = {}", configEntry.getKey(), remainingCountLimit);
					limitConfigSlice.val("count", remainingCountLimit);
					remainingCountLimit = 0;
				}
			}
		}

		// slice an item input (if any)
		final int batchSize = config.intVal("load-batch-size");
		try(final Input<Item> itemInput = createItemInput(config, extensions, batchSize)) {
			if(itemInput != null) {
				Loggers.MSG.info("{}: slice the item input \"{}\"...", id(), itemInput);
				sliceItemInput(itemInput, nodeAddrs, configSlices, batchSize);
			}
		} catch(final IOException e) {
			LogUtil.exception(Level.WARN, e, "Failed to use the item input");
		} catch(final Throwable cause) {
			LogUtil.exception(Level.ERROR, cause, "Unexpected failure");
		}

		// item output file (if any)
		final String itemOutputFile = config.stringVal("item-output-file");
		if(itemOutputFile != null && !itemOutputFile.isEmpty()) {
			itemOutputFileSvcs = nodeAddrs
				.parallelStream()
				.collect(
					Collectors.toMap(
						Function.identity(),
						nodeAddrWithPort -> ServiceUtil.isLocalAddress(nodeAddrWithPort) ?
							Optional.empty() :
							fileMgrs
								.get(nodeAddrWithPort)
								.map(Function3.partial13(LoadStepClientBase::createFileService, nodeAddrWithPort, null))
								.map(
									Function2
										.partial1(LoadStepClientBase::resolveService, nodeAddrWithPort)
										.andThen(svc -> (StepFileService) svc)
								)
								.map(Function2.partial1(LoadStepClientBase::createRemoteFile, nodeAddrWithPort))
					)
				);
			// change the item output file value for each slice
			nodeAddrs.forEach(
				nodeAddrWithPort -> itemOutputFileSvcs
					.get(nodeAddrWithPort)
					.ifPresent(
						fileSvc -> {
							try {
								final String remoteItemOutputFile = fileSvc.filePath();
								final Config outputConfigSlice = configSlices
									.get(nodeAddrWithPort)
									.configVal("item-output");
								outputConfigSlice.val("file", remoteItemOutputFile);
								Loggers.MSG.info(
									"{}: temporary item output file is \"{}\" @ {}", id(), remoteItemOutputFile,
									nodeAddrWithPort
								);
							} catch(final Exception e) {
								LogUtil.exception(
									Level.WARN, e, "Failed to get the remote item output file path @ {}",
									nodeAddrWithPort
								);
							}
						}
					)
			);
		}

		return configSlices;
	}

	private static String createFileService(
		final String nodeAddrWithPort, final StepFileManagerService fileMgrSvc, final String fileSvcName
	) {
		try {
			return fileMgrSvc.createFile(fileSvcName);
		} catch(final Exception e) {
			LogUtil.exception(
				Level.WARN, e, "Failed to create the file service @{}", nodeAddrWithPort
			);
		}
		return null;
	}

	private static Service resolveService(final String nodeAddrWithPort, final String svcName) {
		try {
			return ServiceUtil.resolve(nodeAddrWithPort, svcName);
		} catch(final Exception e) {
			LogUtil.exception(Level.WARN, e, "Failed to resolve the service @ {}", nodeAddrWithPort);
		}
		return null;
	}

	private static StepFileService createRemoteFile(
		final String nodeAddrWithPort, final StepFileService fileSvc
	) {
		try {
			fileSvc.open(StepFileService.WRITE_OPEN_OPTIONS);
			fileSvc.closeFile();
			final String filePath = fileSvc.filePath();
			Loggers.MSG.info("Use temporary remote item output file \"{}\"", filePath);
		} catch(final IOException e) {
			LogUtil.exception(Level.WARN, e, "Failed to create the remote file @ {}", nodeAddrWithPort);
		}
		return fileSvc;
	}

	private static <I extends Item> Input<I> createItemInput(
		final Config config, final List<Extension> extensions, final int batchSize
	) {

		final Config itemConfig = config.configVal("item");
		final ItemType itemType = ItemType.valueOf(itemConfig.stringVal("type").toUpperCase());
		final ItemFactory<I> itemFactory = ItemType.getItemFactory(itemType);
		final Config itemInputConfig = itemConfig.configVal("input");
		final String itemInputFile = itemInputConfig.stringVal("file");

		if(itemInputFile != null && !itemInputFile.isEmpty()) {
			final Path itemInputFilePath = Paths.get(itemInputFile);
			try {
				if(itemInputFile.endsWith(".csv")) {
					try {
						return new CsvFileItemInput<>(itemInputFilePath, itemFactory);
					} catch(final NoSuchMethodException e) {
						throw new RuntimeException(e);
					}
				} else {
					return new BinFileInput<>(itemInputFilePath);
				}
			} catch(final IOException e) {
				LogUtil.exception(Level.WARN, e, "Failed to open the item input file \"{}\"", itemInputFile);
			}
		} else {
			final String itemInputPath = itemInputConfig.stringVal("path");
			if(itemInputPath != null && !itemInputPath.isEmpty()) {
				final Config dataConfig = itemConfig.configVal("data");
				final Config dataInputConfig = dataConfig.configVal("input");
				final Config dataLayerConfig = dataInputConfig.configVal("layer");
				try {
					final DataInput dataInput = DataInput.instance(
						dataInputConfig.stringVal("file"), dataInputConfig.stringVal("seed"),
						new SizeInBytes(dataLayerConfig.stringVal("size")), dataLayerConfig.intVal("cache")
					);
					final StorageDriver<I, IoTask<I>> storageDriver = StorageDriver.instance(
						extensions, config.configVal("load"), config.configVal("storage"),
						dataInput, dataConfig.boolVal("verify"), config.stringVal("load-step-id")
					);
					final Config namingConfig = itemConfig.configVal("naming");
					final String namingPrefix = namingConfig.stringVal("prefix");
					final int namingRadix = namingConfig.intVal("radix");
					return new StorageItemInput<>(
						storageDriver, batchSize, itemFactory, itemInputPath, namingPrefix, namingRadix
					);
				} catch(final IOException | IllegalStateException | IllegalArgumentException e) {
					LogUtil.exception(Level.WARN, e, "Failed to initialize the data input");
				} catch(final OmgShootMyFootException e) {
					LogUtil.exception(Level.ERROR, e, "Failed to initialize the storage driver");
				} catch(final InterruptedException e) {
					throw new CancellationException();
				}
			}
		}

		return null;
	}

	private void sliceItemInput(
		final Input<Item> itemInput, final List<String> nodeAddrs,
		final Map<String, Config> configSlices, final int batchSize
	) throws IOException {

		// TODO - issue #1193 - modify to include local

		itemInputFileSvcs = createOpenItemInputFileServices(nodeAddrs);

		final Map<String, ByteArrayOutputStream> itemsDataByNode = nodeAddrs
			.stream()
			.collect(
				Collectors.toMap(
					Function.identity(), n -> new ByteArrayOutputStream(batchSize * 0x40)
				)
			);
		final Map<String, ObjectOutputStream> itemsOutByNode = itemsDataByNode
			.keySet()
			.stream()
			.collect(
				Collectors.toMap(
					Function.identity(),
					n -> {
						try {
							return new ObjectOutputStream(itemsDataByNode.get(n));
						} catch(final IOException ignored) {
						}
						return null;
					}
				)
			);

		transferItemsInputData(nodeAddrs, itemInput, batchSize, itemsDataByNode, itemsOutByNode);
		Loggers.MSG.info("{}: items input data is distributed to the nodes: {}", Arrays.toString(nodeAddrs.toArray()));

		nodeAddrs
			.parallelStream()
			.map(itemsOutByNode::get)
			.filter(Objects::nonNull)
			.forEach(
				o -> {
					try {
						o.close();
					} catch(final IOException ignored) {
					}
				}
			);

		itemInputFileSvcs
			.values()
			.parallelStream()
			.filter(Optional::isPresent)
			.map(Optional::get)
			.forEach(
				fileSvc -> {
					try{
						fileSvc.closeFile();
					} catch(final IOException e) {
						LogUtil.exception(Level.DEBUG, e, "Failed to close the remote file");
					}
				}
			);

		nodeAddrs
			.parallelStream()
			.forEach(
				nodeAddrWithPort -> itemInputFileSvcs
					.get(nodeAddrWithPort)
					.map(
						fileSvc -> {
							try {
								return fileSvc.filePath();
							} catch(final Exception e) {
								try {
									LogUtil.exception(
										Level.WARN, e, "Failed to invoke the file service \"{}\" @ {}",
										fileSvc.name(), nodeAddrWithPort
									);
								} catch(final RemoteException ignored) {
								}
							}
							return null;
						}
					)
					.ifPresent(
						itemInputFile -> configSlices
							.get(nodeAddrWithPort)
							.val("item-input-file", itemInputFile)
					)
			);
	}

	private Map<String, Optional<StepFileService>> createOpenItemInputFileServices(
		final Collection<String> nodeAddrs
	) {
		return nodeAddrs
			.parallelStream()
			.collect(
				Collectors.toMap(
					Function.identity(),
					nodeAddrWithPort -> fileMgrs
						.get(nodeAddrWithPort)
						.map(
							fileMgrSvc -> {
								try(
									final Instance logCtx = CloseableThreadContext
										.put(KEY_STEP_ID, id())
										.put(
											KEY_CLASS_NAME,
											LoadStepClientBase.class.getSimpleName()
										)
								) {
									return fileMgrSvc.createFile(null);
								} catch(final Exception e) {
									LogUtil.exception(
										Level.WARN, e, "Failed to create the remote file service @ {}",
										nodeAddrWithPort
									);
								}
								return null;
							}
						)
						.map(
							Function2
								.partial1(LoadStepClientBase::resolveService, nodeAddrWithPort)
								.andThen(svc -> (StepFileService) svc)
						)
						.map(
							fileSvc -> {
								try(
									final Instance logCtx = CloseableThreadContext
										.put(KEY_STEP_ID, id())
										.put(
											KEY_CLASS_NAME,
											LoadStepClientBase.class.getSimpleName()
										)
								) {
									fileSvc.open(StepFileService.WRITE_OPEN_OPTIONS);
								} catch(final IOException e) {
									LogUtil.exception(
										Level.WARN, e, "Failed to open the remote file for writing @ {}",
										nodeAddrWithPort
									);
								}
								return fileSvc;
							}
						)
				)
			);
	}

	private void transferItemsInputData(
		final List<String> nodeAddrs, final Input<? extends Item> itemInput, final int batchSize,
		final Map<String, ByteArrayOutputStream> itemsDataByNode,
		final Map<String, ObjectOutputStream> itemsOutByNode
	) throws IOException {

		final int nodeCount = nodeAddrs.size();
		final List<? extends Item> itemsBuff = new ArrayList<>(batchSize);

		int n;
		final ObjectOutputStream out = itemsOutByNode.get(nodeAddrs.get(0));

		while(true) {

			// get the next batch of items
			try {
				n = itemInput.get((List) itemsBuff, batchSize);
			} catch(final EOFException e) {
				break;
			}

			if(n > 0) {

				// convert the items to the text representation
				if(nodeCount > 1) {
					// distribute the items using round robin
					for(int i = 0; i < n; i ++) {
						itemsOutByNode
							.get(nodeAddrs.get(i % nodeCount))
							.writeUnshared(itemsBuff.get(i));
					}
				} else {
					for(int i = 0; i < n; i ++) {
						out.writeUnshared(itemsBuff.get(i));
					}
				}

				itemsBuff.clear();

				// write the text items data to the remote input files
				nodeAddrs
					.parallelStream()
					.forEach(
						nodeAddrWithPort -> {
							final ByteArrayOutputStream buff = itemsDataByNode.get(nodeAddrWithPort);
							itemInputFileSvcs
								.get(nodeAddrWithPort)
								.ifPresent(
									itemInputFileSvc -> {
										try {
											final byte[] data = buff.toByteArray();
											itemInputFileSvc.write(data);
											buff.reset();
										} catch(final IOException e) {
											LogUtil.exception(
												Level.WARN, e,
												"Failed to write the items input data to the " +
													"remote file @ {}",
												nodeAddrWithPort
											);
										}
									}
								);
						}
					);
			} else {
				break;
			}
		}
	}

	private LoadStepService resolveStepSvc(
		final Map<String, Config> configSlices, final String nodeAddrWithPort
	) {

		final LoadStepManagerService stepMgrSvc;
		try {
			stepMgrSvc = ServiceUtil.resolve(nodeAddrWithPort, LoadStepManagerService.SVC_NAME);
		} catch(final Exception e) {
			LogUtil.exception(
				Level.ERROR, e, "Failed to resolve the service \"{}\" @ {}", LoadStepManagerService.SVC_NAME,
				nodeAddrWithPort
			);
			return null;
		}

		final String stepSvcName;
		try {
			stepSvcName = stepMgrSvc.getStepService(getTypeName(), configSlices.get(nodeAddrWithPort), stepConfigs);
		} catch(final Exception e) {
			LogUtil.exception(
				Level.ERROR, e, "Failed to start the new scenario step service @ {}",
				nodeAddrWithPort
			);
			return null;
		}

		final LoadStepService stepSvc;
		try {
			stepSvc = ServiceUtil.resolve(nodeAddrWithPort, stepSvcName);
		} catch(final Exception e) {
			LogUtil.exception(
				Level.ERROR, e, "Failed to resolve the service \"{}\" @ {}", LoadStepManagerService.SVC_NAME,
				nodeAddrWithPort
			);
			return null;
		}

		try {
			Loggers.MSG.info("{}: load step service \"{}\" is resolved @ {}", id(), stepSvc.name(), nodeAddrWithPort);
		} catch(final RemoteException ignored) {
		}

		return stepSvc;
	}

	@Override
	public final boolean await(final long timeout, final TimeUnit timeUnit)
	throws IllegalStateException, InterruptedException {
		if(stepSlices == null || stepSlices.size() == 0) {
			throw new IllegalStateException("No step slices are available");
		}
		final ExecutorService awaitExecutor = Executors.newFixedThreadPool(
			stepSlices.size(), new LogContextThreadFactory("stepSliceAwaitWorker", true)
		);
		stepSlices
			.stream()
			.map(
				stepSlice ->
					(Runnable) () ->
						Function3
							.partial1(LoadStepClientBase::awaitStepSlice, stepSlice)
							.apply(timeout, timeUnit)
			)
			.forEach(awaitExecutor::submit);
		awaitExecutor.shutdown();
		return awaitExecutor.awaitTermination(timeout, TimeUnit.SECONDS);
	}

	private static boolean awaitStepSlice(final LoadStep stepSlice, final long timeout, final TimeUnit timeUnit) {
		try(
			final Instance logCtx = CloseableThreadContext
				.put(KEY_STEP_ID, stepSlice.id())
				.put(KEY_CLASS_NAME, LoadStepClientBase.class.getSimpleName())
		) {
			long commFailCount = 0;
			while(true) {
				try {
					if(stepSlice.await(timeout, timeUnit)) {
						return true;
					}
				} catch(final RemoteException e) {
					LogUtil.exception(
						Level.DEBUG, e, "Failed to invoke the step slice \"{}\" await method {} times",
						stepSlice, commFailCount
					);
					commFailCount ++;
					Thread.sleep(commFailCount);
				}
			}
		} catch(final InterruptedException e) {
			throw new CancellationException();
		} catch(final RemoteException ignored) {
			return false;
		}
	}

	private static Map<String, Optional<StepFileManager>> initFileManagers(
		final String stepId, final List<String> nodeAddrs
	) {
		return nodeAddrs
			.parallelStream()
			.collect(
				Collectors.toMap(
					Function.identity(),
					nodeAddrWithPort -> resolveFileManager(stepId, nodeAddrWithPort)
				)
			);
	}

	private static Optional<StepFileManager> resolveFileManager(final String stepId, final String nodeAddrWithPort) {

		StepFileManager fileMgr = null;

		if(ADDR_LOCAL_NONE.equals(nodeAddrWithPort)) {
			fileMgr = StepFileManagerImpl.INSTANCE;
		} else {
			try(
				final Instance logCtx = CloseableThreadContext
					.put(KEY_STEP_ID, stepId)
					.put(KEY_CLASS_NAME, LoadStepClientBase.class.getSimpleName())
			) {
				fileMgr = ServiceUtil.resolve(nodeAddrWithPort, StepFileManagerService.SVC_NAME);
			} catch(final Exception e) {
				LogUtil.exception(
					Level.ERROR, e, "Failed to resolve the remote file manager service @ {}",
					nodeAddrWithPort
				);
			}
		}

		return fileMgr == null ? Optional.empty() : Optional.of(fileMgr);
	}

	private void initIoTraceLogFileServices(final List<String> nodeAddrs) {
		ioTraceLogFileSvcs = nodeAddrs
			.stream()
			.collect(
				Collectors.toMap(
					Function.identity(),
					nodeAddrWithPort -> {
						if(ServiceUtil.isLocalAddress(nodeAddrWithPort)) {
							return Optional.empty();
						} else {
							return fileMgrs
								.get(nodeAddrWithPort)
								.map(
									fileMgrSvc -> {
										try {
											return fileMgrSvc.createLogFile(Loggers.IO_TRACE.getName(), id());
										} catch(final Exception e) {
											LogUtil.exception(
												Level.WARN, e, "Failed to create the log file service @ {}",
												nodeAddrWithPort
											);
										}
										return null;
									}
								)
								.map(
									ioTraceLogFileSvcName -> {
										try {
											return ServiceUtil.resolve(
												nodeAddrWithPort, ioTraceLogFileSvcName
											);
										} catch(final Exception e) {
											LogUtil.exception(
												Level.WARN, e,
												"Failed to resolve the log file service \"{}\" @ {}",
												ioTraceLogFileSvcName, nodeAddrWithPort
											);
										}
										return null;
									}
								);
						}
					}
				)
			);
	}

	@Override
	protected final void doStopWrapped() {
		stepSlices
			.parallelStream()
			.forEach(LoadStepClientBase::stopStepSlice);
	}

	private static LoadStep stopStepSlice(final LoadStep stepSlice) {
		try(
			final Instance logCtx = CloseableThreadContext
				.put(KEY_STEP_ID, stepSlice.id())
				.put(KEY_CLASS_NAME, LoadStepClientBase.class.getSimpleName())
		) {
			stepSlice.stop();
		} catch(final Exception e) {
			try {
				LogUtil.exception(Level.WARN, e, "Failed to stop the step slice \"{}\"", stepSlice);
			} catch(final Exception ignored) {
			}
		}
		return stepSlice;
	}

	@Override
	protected final void doCloseWrapped() {

		metricsSnapshotsSuppliers
			.values()
			.parallelStream()
			.forEach(
				snapshotsFetcher -> {
					try(
						final Instance logCtx = CloseableThreadContext
							.put(KEY_STEP_ID, id())
							.put(KEY_CLASS_NAME, LoadStepClientBase.class.getSimpleName())
					) {
						snapshotsFetcher.stop();
					} catch(final IOException e) {
						LogUtil.exception(Level.WARN, e, "Failed to stop the remote metrics snapshot fetcher");
					}
				}
			);

		stepSlices.parallelStream().forEach(LoadStepClientBase::closeStepSlice);
		stepSlices.clear();
		stepSlices = null;

		metricsSnapshotsSuppliers
			.values()
			.parallelStream()
			.forEach(
				snapshotsFetcher -> {
					try(
						final Instance logCtx = CloseableThreadContext
							.put(KEY_STEP_ID, id())
							.put(KEY_CLASS_NAME, LoadStepClientBase.class.getSimpleName())
					) {
						snapshotsFetcher.close();
					} catch(final IOException e) {
						LogUtil.exception(Level.WARN, e, "Failed to close the remote metrics snapshot fetcher");
					}
				}
			);
		metricsSnapshotsSuppliers.clear();

		if(null != itemInputFileSvcs) {
			itemInputFileSvcs
				.entrySet()
				.parallelStream()
				.filter(entry -> entry.getValue().isPresent())
				.forEach(entry -> closeFileSvc(entry.getValue().get(), entry.getKey()));
			itemInputFileSvcs.clear();
			itemInputFileSvcs = null;
		}

		if(null != itemOutputFileSvcs) {
			final String itemOutputFile = baseConfig.stringVal("item-output-file");
			transferItemOutputData(itemOutputFileSvcs, itemOutputFile);
			itemOutputFileSvcs
				.entrySet()
				.parallelStream()
				.filter(entry -> entry.getValue().isPresent())
				.forEach(entry -> closeFileSvc(entry.getValue().get(), entry.getKey()));
			itemOutputFileSvcs.clear();
			itemOutputFileSvcs = null;
		}

		if(null != ioTraceLogFileSvcs) {
			try {
				Loggers.MSG.info("{}: transfer the I/O traces data from the nodes", id());
			} catch(final RemoteException ignored) {
			}
			ioTraceLogFileSvcs
				.values()
				.parallelStream()
				.filter(Optional::isPresent)
				.map(Optional::get)
				.forEach(LoadStepClientBase::transferIoTraceData);
			ioTraceLogFileSvcs.clear();
			ioTraceLogFileSvcs = null;
		}
	}

	private static void transferItemOutputData(
		final Map<String, Optional<StepFileService>> itemOutputFileSvcs, final String itemOutputFile
	) {
		final Path itemOutputFilePath = Paths.get(itemOutputFile);
		if(Files.exists(itemOutputFilePath)) {
			Loggers.MSG.info("Item output file \"{}\" already exists - will be appended", itemOutputFile);
		} else {
			Loggers.MSG.info(
				"Transfer the items output data from the remote nodes to the local file \"{}\"...",  itemOutputFile
			);
		}
		try(
			final OutputStream out = Files.newOutputStream(
				Paths.get(itemOutputFile), StepFileService.APPEND_OPEN_OPTIONS
			)
		) {
			itemOutputFileSvcs
				.values()
				.parallelStream()
				.filter(Optional::isPresent)
				.map(Optional::get)
				.forEach(
					fileSvc -> {
						long transferredByteCount = 0;
						try(
							final Instance logCtx = CloseableThreadContext
								.put(KEY_CLASS_NAME, LoadStepClientBase.class.getSimpleName())
						) {
							fileSvc.open(StepFileService.READ_OPTIONS);
							byte buff[];
							while(true) {
								buff = fileSvc.read();
								synchronized(out) {
									out.write(buff);
								}
								transferredByteCount += buff.length;
							}
						} catch(final EOFException ok) {
						} catch(final IOException e) {
							LogUtil.exception(Level.WARN, e, "Remote items output file transfer failure");
						} catch(final Throwable cause) {
							LogUtil.exception(Level.ERROR, cause, "Unexpected failure");
						} finally {
							try {
								Loggers.MSG.info(
									"{} of items output data transferred from \"{}\" to \"{}\"",
									SizeInBytes.formatFixedSize(transferredByteCount),
									fileSvc.name(), itemOutputFile
								);
							} catch(final RemoteException ignored) {
							}
						}
					}
				);
		} catch(final IOException e) {
			LogUtil.exception(
				Level.ERROR, e, "Failed to open the local file \"{}\" for the items output", itemOutputFile
			);
		}
	}

	private static LoadStep closeStepSlice(final LoadStep stepSlice) {
		if(null != stepSlice) {
			try(
				final Instance logCtx = CloseableThreadContext
					.put(KEY_CLASS_NAME, LoadStepClientBase.class.getSimpleName())
			) {
				stepSlice.close();
			} catch(final Exception e) {
				try {
					LogUtil.exception(Level.WARN, e, "Failed to close the step service \"{}\"",  stepSlice);
				} catch(final Exception ignored) {
				}
			}
		}
		return stepSlice;
	}

	private static StepFileService closeFileSvc(
		final StepFileService fileSvc, final String nodeAddrWithPort
	) {
		if(null != fileSvc) {
			try(
				final Instance logCtx = CloseableThreadContext
					.put(KEY_CLASS_NAME, LoadStepClientBase.class.getSimpleName())
			) {
				fileSvc.close();
			} catch(final IOException e) {
				try {
					LogUtil.exception(
						Level.WARN, e, "Failed to close the file service \"{}\" @ {}",  fileSvc.name(), nodeAddrWithPort
					);
				} catch(final RemoteException ignored) {
				}
			}
		}
		return fileSvc;
	}

	private static void transferIoTraceData(final StepFileService ioTraceLogFileSvc) {
		long transferredByteCount = 0;
		try(
			final Instance logCtx = CloseableThreadContext
				.put(KEY_CLASS_NAME, LoadStepClientBase.class.getSimpleName())
		) {
			ioTraceLogFileSvc.open(StepFileService.READ_OPTIONS);
			Loggers.MSG.debug("Opened the remote I/O traces file \"{}\"", ioTraceLogFileSvc.name());
			byte[] data;
			while(true) {
				data = ioTraceLogFileSvc.read();
				Loggers.IO_TRACE.info(new String(data));
				transferredByteCount += data.length;
			}
		} catch(final EOFException ok) {
		} catch(final RemoteException e) {
			LogUtil.exception(Level.WARN, e, "Failed to read the data from the remote file");
		} catch(final IOException e) {
			LogUtil.exception(Level.ERROR, e, "Unexpected I/O exception");
		} finally {
			try {
				Loggers.MSG.info(
					"Transferred {} of the remote I/O traces data from the remote file \"{}\"",
					SizeInBytes.formatFixedSize(transferredByteCount), ioTraceLogFileSvc.name()
				);
				ioTraceLogFileSvc.close();
			} catch(final IOException e) {
				try {
					LogUtil.exception(
						Level.DEBUG, e, "Failed to close the remote file {}",  ioTraceLogFileSvc.filePath()
					);
				} catch(final Exception ignored) {
				}
			}
		}
	}

	@Override
	public final LoadStepClientBase config(final Map<String, Object> config) {
		return this;
	}

	@Override
	public final List<MetricsSnapshot> remoteMetricsSnapshots(final int originIndex) {
		return metricsSnapshotsSuppliers
			.values()
			.stream()
			.map(Supplier::get)
			.filter(Objects::nonNull)
			.map(metricsSnapshots -> metricsSnapshots.get(originIndex))
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
	}
}