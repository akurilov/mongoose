#!/usr/bin/env python
from __future__ import print_function, absolute_import, with_statement
from sys import exit
#
from org.apache.logging.log4j import LogManager
LOG = LogManager.getLogger()
#
from com.emc.mongoose.run import Main
from com.emc.mongoose.util.logging import Markers
#
from java.util import NoSuchElementException
def loadbuilder_init():
    #
    mode = None
    try:
        mode = Main.RUN_TIME_CONFIG.get().getRunMode()
    except NoSuchElementException:
        LOG.fatal(Markers.ERR, "Launch mode is not specified, use -Drun.mode=<VALUE> argument")
        exit()
    #
    INSTANCE = None
    #
    from org.apache.commons.configuration import ConversionException
    if mode == Main.RUN_MODE_CLIENT or mode == Main.RUN_MODE_COMPAT_CLIENT:
        from com.emc.mongoose.web.load.client.impl import BasicLoadBuilderClient
        from java.rmi import RemoteException
        try:
            try:
                INSTANCE = BasicLoadBuilderClient()
            except ConversionException:
                LOG.fatal(Markers.ERR, "Servers address list should be comma delimited")
                exit()
            except NoSuchElementException:  # no one server addr not specified, try 127.0.0.1
                LOG.fatal(Markers.ERR, "Servers address list not specified, try  arg -Dremote.servers=<LIST> to override")
                exit()
        except RemoteException as e:
            LOG.fatal(Markers.ERR, "Failed to create load builder client: {}", e)
            exit()
    else: # standalone
        from com.emc.mongoose.web.load.impl import BasicLoadBuilder
        #
        INSTANCE = BasicLoadBuilder()
    #
    if INSTANCE is None:
        LOG.fatal(Markers.ERR, "No load builder instanced")
        exit()
    INSTANCE.setProperties(Main.RUN_TIME_CONFIG.get())
    return INSTANCE
