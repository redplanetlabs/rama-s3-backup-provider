package com.rpl.rama.backup.s3;

import com.rpl.rama.backup.BackupProvider.ProgressListener;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.reactivestreams.*;
import org.slf4j.*;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.SdkPublisher;

class InputStreamBody
    implements org.reactivestreams.Publisher<ByteBuffer>,
        AsyncRequestBody,
        SdkPublisher<ByteBuffer> {
  private static final Logger LOGGER = LoggerFactory.getLogger(InputStreamBody.class);

  AsyncRequestBody delegate;
  ProgressListener progressListener;

  InputStreamBody(
      ProgressListener listener, InputStream is, Long contentLength, ExecutorService execServ) {
    if(listener == null) throw new RuntimeException("Progress listener must not be null");
    progressListener = listener;
    delegate = AsyncRequestBody.fromInputStream(is, contentLength, execServ);
    delegate.subscribe(new Subscriber() {
      @Override
      public void onComplete() {
        progressListener.reportProgress();
      }

      @Override
      public void onNext(Object v) {
        progressListener.reportProgress();
      }

      @Override
      public void onError(Throwable t) {
        LOGGER.error("Error during upload to S3", t);
      }

      @Override
      public void onSubscribe(Subscription s) {
        progressListener.reportProgress();
      }
    });
  }

  @Override
  public Optional<Long> contentLength() {
    return delegate.contentLength();
  }

  @Override
  public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
    delegate.subscribe(subscriber);
  }
}
