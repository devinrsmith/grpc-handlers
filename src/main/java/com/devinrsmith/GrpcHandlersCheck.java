package com.devinrsmith;

import com.devinrsmith.grpchandlers.Request;
import com.devinrsmith.grpchandlers.Response;
import com.devinrsmith.grpchandlers.TheServiceGrpc.TheServiceImplBase;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Objects;

class GrpcHandlersCheck extends TheServiceImplBase {

  /** change this to > 0 to "fix" the issue */
  private static final int TIMEOUT = 0;

  /** or, we can fix the issue by doing our work on a new thread */
  private static final boolean NEW_THREAD = false;

  @Override
  public void demonstrate(Request request, StreamObserver<Response> responseObserver) {
    final Demonstrate demonstrate =
        new Demonstrate(request, (ServerCallStreamObserver<Response>) responseObserver);
    demonstrate.init();
    if (NEW_THREAD) {
      final Thread thread = new Thread(() -> execute(demonstrate, responseObserver));
      thread.start();
    } else {
      execute(demonstrate, responseObserver);
    }
  }

  private void execute(Demonstrate demonstrate, StreamObserver<Response> responseObserver) {
    try {
      demonstrate.execute();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      responseObserver.onError(e);
    }
  }

  private static class Demonstrate {

    private final Request request;
    private final ServerCallStreamObserver<Response> observer;

    public Demonstrate(Request request, ServerCallStreamObserver<Response> observer) {
      this.request = Objects.requireNonNull(request);
      this.observer = Objects.requireNonNull(observer);
    }

    /** must be done on the GRPC thread before returning */
    void init() {
      observer.setOnCancelHandler(this::onCancelled);
      observer.setOnReadyHandler(this::onReady);
    }

    void execute() throws InterruptedException {
      for (int i = 0; i < request.getCount(); ++i) {
        if (!waitForActionable()) {
          return; // observer was cancelled
        }
        // observer is ready
        observer.onNext(Response.newBuilder().setIndex(i).build());
      }
      observer.onCompleted();
    }

    synchronized void onCancelled() {
      notify();
    }

    synchronized void onReady() {
      notify();
    }

    synchronized boolean waitForActionable() throws InterruptedException {
      while (true) {
        if (observer.isCancelled()) {
          return false;
        }
        if (observer.isReady()) {
          return true;
        }
        if (TIMEOUT == 0) {
          wait();
        } else {
          // If we don't trust our callbacks, this is a hacky "fix"
          wait(TIMEOUT);
        }
      }
    }
  }
}
