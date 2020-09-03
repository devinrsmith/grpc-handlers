package com.devinrsmith;

import com.devinrsmith.grpcchunks.Chunk;
import com.devinrsmith.grpcchunks.Request;
import com.devinrsmith.grpcchunks.TheServiceGrpc.TheServiceImplBase;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ServerMain {

  public static final int DEFAULT_PORT = 5555;
  public static final int DEFAULT_FLOW_CONTROL_WINDOW = 65_536;
  public static final int DEFAULT_APP_POOL_SIZE = Runtime.getRuntime().availableProcessors();
  public static final int DEFAULT_GRPC_POOL_SIZE =
      Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
  public static final long DEFAULT_AWAIT_SHUTDOWN_MILLIS = 5_000;
  public static final long DEFAULT_AWAIT_SHUTDOWN_NOW_MILLIS = 5_000;

  private static int intProperty(String key, int orElse) {
    final String value = System.getProperty(key);
    return value == null ? orElse : Integer.parseInt(value);
  }

  private static long longProperty(String key, long orElse) {
    final String value = System.getProperty(key);
    return value == null ? orElse : Long.parseLong(value);
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    final String ip = System.getProperty("ip", "127.0.0.1");
    final int port = intProperty("port", DEFAULT_PORT);
    final int flowControlWindow = intProperty("flow_control_window", DEFAULT_FLOW_CONTROL_WINDOW);
    final int appPoolSize = intProperty("app_pool_size", DEFAULT_APP_POOL_SIZE);
    final int grpcPoolSize = intProperty("grpc_pool_size", DEFAULT_GRPC_POOL_SIZE);
    final long awaitShutdownMillis =
        longProperty("await_shutdown_millis", DEFAULT_AWAIT_SHUTDOWN_MILLIS);
    final long awaitShutdownNowMillis =
        longProperty("await_shutdown_now_millis", DEFAULT_AWAIT_SHUTDOWN_NOW_MILLIS);

    final ExecutorService grpcPool = Executors.newFixedThreadPool(appPoolSize);
    final ExecutorService appPool = Executors.newFixedThreadPool(grpcPoolSize);
    final TheServiceImpl impl = new TheServiceImpl(appPool);
    final Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress(ip, port))
            .addService(impl)
            .executor(grpcPool)
            .initialFlowControlWindow(flowControlWindow)
            .build()
            .start();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> onShutdownHook(server, awaitShutdownMillis, awaitShutdownNowMillis)));
    server.awaitTermination();
  }

  private static void onShutdownHook(
      Server server, long awaitShutdownMillis, long awaitShutdownNowMillis) {
    System.err.println("*** shutting down gRPC server since JVM is shutting down");
    boolean isTerminated = false;
    try {
      isTerminated = server.shutdown().awaitTermination(awaitShutdownMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace(System.err);
    }
    if (!isTerminated) {
      try {
        isTerminated =
            server.shutdownNow().awaitTermination(awaitShutdownNowMillis, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        e.printStackTrace(System.err);
      }
    }
    if (isTerminated) {
      System.err.println("*** server shut down cleanly");
    } else {
      System.err.println("*** server did not shutdown, exiting JVM");
    }
  }

  private static class TheServiceImpl extends TheServiceImplBase {

    private final ExecutorService executorService;

    public TheServiceImpl(ExecutorService executorService) {
      this.executorService = Objects.requireNonNull(executorService);
    }

    @Override
    public void serverStreaming(Request request, StreamObserver<Chunk> responseObserver) {
      final int chunkSize = request.getChunkSize();
      final ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
      for (int i = 0; buffer.remaining() >= 4; ++i) {
        buffer.putInt(i);
      }
      while (buffer.remaining() > 0) {
        buffer.put((byte) 0);
      }
      buffer.flip();
      final Chunk chunk = Chunk.newBuilder().setPart(ByteString.copyFrom(buffer)).build();
      final ServerStreamingTask task =
          new ServerStreamingTask(
              request, (ServerCallStreamObserver<Chunk>) responseObserver, chunk);
      task.init();
      executorService.submit(task);
    }
  }

  /**
   * Note: this task is *not* well suited for servers that expect to have a large number of
   * concurrent requests. The logic necessary to create a well-functioning concurrent server that
   * respects observer status is much more difficult; this implementation is used to demonstrate the
   * desired behavior and gather performance characteristics in this simplified model.
   */
  private static class ServerStreamingTask implements Callable<Void> {
    private final Request request;
    private final ServerCallStreamObserver<Chunk> observer;
    private final Chunk chunk;

    public ServerStreamingTask(
        Request request, ServerCallStreamObserver<Chunk> observer, Chunk chunk) {
      this.request = Objects.requireNonNull(request);
      this.observer = Objects.requireNonNull(observer);
      this.chunk = Objects.requireNonNull(chunk);
    }

    public void init() {
      observer.setOnReadyHandler(this::onReady);
      observer.setOnCancelHandler(this::onCancelled);
    }

    private synchronized void onReady() {
      notify();
    }

    private synchronized void onCancelled() {
      notify();
    }

    @Override
    public Void call() throws Exception {
      final long startNanos = System.nanoTime();
      final long numChunks = request.getNumChunks();
      for (long i = 0; i < numChunks; i++) {
        // fast path
        if (observer.isReady()) {
          observer.onNext(chunk);
          continue;
        }
        if (observer.isCancelled()) {
          return null;
        }
        // wait path
        SYNC:
        try {
          synchronized (this) {
            while (true) {
              if (observer.isReady()) {
                break SYNC;
              }
              if (observer.isCancelled()) {
                return null;
              }
              wait();
            }
          }
        } catch (InterruptedException e) {
          observer.onError(e);
          throw e;
        }
        observer.onNext(chunk);
      }
      observer.onCompleted();
      final long endNanos = System.nanoTime();
      final long totalBytes = chunk.getSerializedSize() * numChunks;
      final double gbps = (totalBytes * 8.0) / (endNanos - startNanos);
      System.out.printf("%.2f Gbps%n", gbps);
      return null;
    }
  }
}
