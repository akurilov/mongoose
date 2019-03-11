package com.emc.mongoose.storage.driver.coop.netty.http.swift;

import static com.emc.mongoose.storage.driver.coop.netty.http.swift.SwiftApi.VALUE_MULTIPART_BYTERANGES;

import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.Item;
import com.emc.mongoose.base.item.op.OpType;
import com.emc.mongoose.base.item.op.Operation;
import com.emc.mongoose.base.item.op.data.DataOperation;
import com.emc.mongoose.storage.driver.coop.netty.http.HttpResponseHandlerBase;
import com.emc.mongoose.storage.driver.coop.netty.http.HttpStorageDriverBase;
import com.github.akurilov.commons.collection.Range;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AttributeKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Created by andrey on 26.11.16. */
public final class SwiftResponseHandler<I extends Item, O extends Operation<I>>
    extends HttpResponseHandlerBase<I, O> {

  private static final Pattern BOUNDARY_VALUE_PATTERN =
      Pattern.compile(
          VALUE_MULTIPART_BYTERANGES + ";" + HttpHeaderValues.BOUNDARY + "=([0-9a-f]+)");
  private static final String HEADER_PATTERN = "(Content-Type:).*[\\s]*(Content-Range:).*";

  private static final String HEADER_WITH_BOUNDARY_PATTERN =
      "[\\s]{2}((%1$s)[\\s]*(" + HEADER_PATTERN + ")|(%1$s--))[\\s]{4}";
  private static final AttributeKey<String> ATTR_KEY_BOUNDARY_MARKER =
      AttributeKey.valueOf("boundary_marker");

  private byte[] cutChunck = new byte[] {};

  public SwiftResponseHandler(final HttpStorageDriverBase<I, O> driver, final boolean verifyFlag) {
    super(driver, verifyFlag);
  }

  @Override
  protected final void handleResponseHeaders(
      final Channel channel, final O op, final HttpHeaders respHeaders) {
    final String contentType = respHeaders.get(HttpHeaderNames.CONTENT_TYPE);
    if (contentType != null) {
      final Matcher boundaryMatcher = BOUNDARY_VALUE_PATTERN.matcher(contentType);
      if (boundaryMatcher.find()) {
        final String boundaryMarker = "--" + boundaryMatcher.group(1);
        channel.attr(ATTR_KEY_BOUNDARY_MARKER).set(boundaryMarker);
      }
    }
  }

  /*
  	Boundary marker: ac9c12f841fa093d82ba80a402f6b62e
  	Content:
  --ac9c12f841fa093d82ba80a402f6b62e
  Content-Type: application/octet-stream
  Content-Range: bytes 0-0/10240

  ?
  --ac9c12f841fa093d82ba80a402f6b62e
  Content-Type: application/octet-stream
  Content-Range: bytes 3-6/10240

  ????
  --ac9c12f841fa093d82ba80a402f6b62e--
  	 */
  protected final void handleResponseContentChunk(
      final Channel channel, final O op, final ByteBuf contentChunk) throws IOException {
    if (OpType.READ.equals(op.type())) {
      if (op instanceof DataOperation) {
        final DataOperation<? extends DataItem> dataOp = (DataOperation<? extends DataItem>) op;
        final BitSet[] markedRangesMaskPair = dataOp.markedRangesMaskPair();
        // if the count of marked byte ranges > 1
        if (1 < markedRangesMaskPair[0].cardinality() + markedRangesMaskPair[1].cardinality()) {
          final ByteBuf newContentChunk = removeHeaders(channel, op, contentChunk);
          super.handleResponseContentChunk(channel, op, newContentChunk);
        } else {
          final List<Range> fixedRanges = dataOp.fixedRanges();
          if (fixedRanges != null && 1 < fixedRanges.size()) {
            final ByteBuf newContentChunk = removeHeaders(channel, op, contentChunk);
            super.handleResponseContentChunk(channel, op, newContentChunk);
          } else {
            super.handleResponseContentChunk(channel, op, contentChunk);
          }
        }
      } else {
        super.handleResponseContentChunk(channel, op, contentChunk);
      }
    } else {
      super.handleResponseContentChunk(channel, op, contentChunk);
    }
  }

  ByteBuf removeHeaders(final Channel channel, final O op, final ByteBuf contentChunk) {
    final String boundaryMarker = channel.attr(ATTR_KEY_BOUNDARY_MARKER).get();
    final int chunckSize = contentChunk.readableBytes();
    final int cutChunkSize = cutChunck.length;
    final byte[] bytesChunk = new byte[cutChunkSize + chunckSize];
    System.arraycopy(cutChunck, 0, bytesChunk, 0, cutChunkSize);
    while (contentChunk.readerIndex() < chunckSize) {
      bytesChunk[cutChunkSize - 1 + contentChunk.readerIndex()] = contentChunk.readByte();
    }
    byte[] newBytesChunk = new byte[cutChunkSize + chunckSize];
    String s = new String(bytesChunk);
    final Pattern p = Pattern.compile(String.format(HEADER_WITH_BOUNDARY_PATTERN, boundaryMarker));
    final Matcher m = p.matcher(s);
    final List<int[]> contentRangeIdxs = new ArrayList();
    if (m.find()) {
      int startIndex = 0;
      int endIndex;
      final int count = m.groupCount();
      for (int i = 0; i < count; ++i) {
        endIndex = m.start(i);
        contentRangeIdxs.add(new int[] {startIndex, endIndex});
        startIndex = m.end(i);
      }
      endIndex = newBytesChunk.length - 1;
      contentRangeIdxs.add(new int[] {startIndex, endIndex});
    }
    // TODO: get sum of all ranges
    // TODO: create new bytesArray [sum of all ranges]
    // TODO: copy all ranges to new bytesArray

    for (final int[] range : contentRangeIdxs) {}

    s =
        s.replaceAll(
            String.format(HEADER_WITH_BOUNDARY_PATTERN, boundaryMarker, boundaryMarker), "");
    s = cuteEnd(s);
    return Unpooled.copiedBuffer(s.getBytes());
  }

  private String cuteEnd(final String content) {
    if (content.substring(content.length() - 1) == "-") {
      cutChunck = "-";
    }
    if (content.substring(content.length() - 2) == "--") {
      cutChunck = "--";
    }
    final Pattern pattern = Pattern.compile("[\\s]{2}--(.|\\s)*");
    final Matcher matcher = pattern.matcher(content);
    if (matcher.find()) {
      // TODO kochuv: may be without count?
      final int count = matcher.groupCount();
      cutChunck = matcher.group(count - 1);
      return content.replaceAll(cutChunck, "");
    }
    cutChunck = "";
    return content;
  }
}
