package com.devinrsmith;

import com.devinrsmith.grpchandlers.Request;
import com.devinrsmith.grpchandlers.Response;
import com.devinrsmith.grpchandlers.TheServiceGrpc;
import com.devinrsmith.grpchandlers.TheServiceGrpc.TheServiceBlockingStub;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class GrpcHandlersCheckTest {

  @Rule public GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  @Rule public Timeout globalTimeout = new Timeout(10L, TimeUnit.SECONDS);

  GrpcHandlersCheck impl;

  ManagedChannel channel;

  @Before
  public void setUp() throws Exception {
    impl = new GrpcHandlersCheck();
    final String name = InProcessServerBuilder.generateName();
    // note: using default (not direct) executor since our implementation *does* block.
    final Server server = InProcessServerBuilder.forName(name).addService(impl).build();
    grpcCleanup.register(server.start());
    channel = grpcCleanup.register(InProcessChannelBuilder.forName(name).build());
  }

  @Test
  public void demonstrate() {
    final TheServiceBlockingStub stub = TheServiceGrpc.newBlockingStub(channel);
    final int count = 2;
    final Iterator<Response> it = stub.demonstrate(Request.newBuilder().setCount(count).build());
    for (int i = 0; i < count; ++i) {
      if (!it.hasNext()) {
        throw new AssertionError();
      }
      if (it.next().getIndex() != i) {
        throw new AssertionError();
      }
    }
  }
}
