package com.emc.mongoose.storage.driver.coop.net;

import com.emc.mongoose.item.op.Operation;
import com.emc.mongoose.item.Item;
import com.emc.mongoose.logging.LogUtil;
import static com.emc.mongoose.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.item.op.Operation.Status.INTERRUPTED;
import static com.emc.mongoose.item.op.Operation.Status.FAIL_IO;
import static com.emc.mongoose.item.op.Operation.Status.FAIL_UNKNOWN;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.timeout.IdleStateEvent;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 Created by kurila on 04.10.16.
 Contains the content validation functionality
 */
public abstract class ResponseHandlerBase<M, I extends Item, O extends Operation<I>>
extends SimpleChannelInboundHandler<M> {

	private final static String CLS_NAME = ResponseHandlerBase.class.getSimpleName();
	
	protected final NetStorageDriverBase<I, O> driver;
	protected final boolean verifyFlag;
	
	protected ResponseHandlerBase(final NetStorageDriverBase<I, O> driver, boolean verifyFlag) {
		this.driver = driver;
		this.verifyFlag = verifyFlag;
	}
	
	@Override @SuppressWarnings("unchecked")
	protected final void channelRead0(final ChannelHandlerContext ctx, final M msg)
	throws Exception {

		ThreadContext.put(KEY_CLASS_NAME, CLS_NAME);

		final Channel channel = ctx.channel();
		final O op = (O) channel.attr(NetStorageDriver.ATTR_KEY_OPERATION).get();
		handle(channel, op, msg);
	}
	
	protected abstract void handle(final Channel channel, final O op, final M msg)
	throws IOException;

	@Override @SuppressWarnings("unchecked")
	public final void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause)
	throws IOException {
		final Channel channel = ctx.channel();
		final O op = (O) channel.attr(NetStorageDriver.ATTR_KEY_OPERATION).get();
		if(op != null) {
			if(driver.isStopped() || driver.isClosed()) {
				op.status(INTERRUPTED);
			} else if(cause instanceof PrematureChannelClosureException) {
				LogUtil.exception(Level.WARN, cause, "Premature channel closure");
				op.status(FAIL_IO);
			} else {
				LogUtil.exception(Level.WARN, cause, "Client handler failure");
				op.status(FAIL_UNKNOWN);
			}
			if(!driver.isStopped()) {
				try {
					driver.complete(channel, op);
				} catch(final Exception e) {
					LogUtil.exception(Level.DEBUG, e, "Failed to complete the load operation");
				}
			}
		}
	}

	@Override
	public final void userEventTriggered(final ChannelHandlerContext ctx, final Object evt)
	throws Exception {
		if(evt instanceof IdleStateEvent) {
			throw new SocketTimeoutException();
		}
	}
}
