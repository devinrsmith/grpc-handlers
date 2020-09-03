package com.devinrsmith;

import com.devinrsmith.grpcchunks.Chunk;
import com.devinrsmith.grpcchunks.Request;
import com.devinrsmith.grpcchunks.TheServiceGrpc;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Iterator;

public class ClientMain {

  public static final String DEFAULT_TARGET = "127.0.0.1:5555";
  public static final int DEFAULT_FLOW_CONTROL_WINDOW = 65_536;

  private static int intProperty(String key, int orElse) {
    final String value = System.getProperty(key);
    return value == null ? orElse : Integer.parseInt(value);
  }

  private static long longProperty(String key, long orElse) {
    final String value = System.getProperty(key);
    return value == null ? orElse : Long.parseLong(value);
  }

  public static void main(String[] args) {
    final String target = System.getProperty("target", DEFAULT_TARGET);
    final int flowControlWindow = intProperty("flow_control_window", DEFAULT_FLOW_CONTROL_WINDOW);
    final int chunkSize = intProperty("chunk_size", 2048);
    final long numChunks = longProperty("num_chunks", 524288);
    final ManagedChannel channel =
        NettyChannelBuilder.forTarget(target)
            .usePlaintext()
            .initialFlowControlWindow(flowControlWindow)
            .build();
    final Request request =
        Request.newBuilder().setChunkSize(chunkSize).setNumChunks(numChunks).build();
    final Hasher hasher = Hashing.murmur3_32().newHasher();

    final long startNanos = System.nanoTime();
    final Iterator<Chunk> it = TheServiceGrpc.newBlockingStub(channel).serverStreaming(request);
    Chunk chunk = null;
    while (it.hasNext()) {
      chunk = it.next();
      for (ByteBuffer byteBuffer : chunk.getPart().asReadOnlyByteBufferList()) {
        hasher.putBytes(byteBuffer);
      }
    }
    final long endNanos = System.nanoTime();
    final long totalBytes = chunk.getSerializedSize() * numChunks;

    System.out.println(hasher.hash());

    //final double seconds = (endNanos - startNanos) / 1_000_000_000.0;
    //final double gigabits = totalBytes * 8.0 / 1_000_000_000;
    //final double gbps = gigabits / seconds;
    final double gbps = (totalBytes * 8.0) / (endNanos - startNanos);
    System.out.printf("%.2f Gbps%n", gbps);

  }
}
