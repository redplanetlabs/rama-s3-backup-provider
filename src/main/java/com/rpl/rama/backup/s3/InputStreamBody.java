package com.rpl.rama.backup.s3;

import com.rpl.rama.backup.BackupProvider.ProgressListener;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.reactivestreams.Subscriber;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.SdkPublisher;

class InputStreamBody
    implements org.reactivestreams.Publisher<ByteBuffer>,
        AsyncRequestBody,
        SdkPublisher<ByteBuffer> {

  AsyncRequestBody delegate;
  ProgressListener progressListener;

  InputStreamBody(
      ProgressListener listener, InputStream is, Long contentLength, ExecutorService execServ) {
    progressListener = listener;
    delegate = AsyncRequestBody.fromInputStream(is, contentLength, execServ);
  }

  void reportProgress() {
    if (progressListener != null) {
      progressListener.reportProgress();
    }
  }

  @Override
  public Optional<Long> contentLength() {
    return delegate.contentLength();
  }

  @Override
  public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
    delegate.subscribe(subscriber);
    reportProgress();
  }
}
