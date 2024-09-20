package com.rpl.rama.backup.s3;

import com.rpl.rama.backup.BackupProvider.ProgressListener;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.core.internal.async.InputStreamResponseTransformer;

class InputStreamTransformer<ResponseT extends SdkResponse>
    implements AsyncResponseTransformer<ResponseT, ResponseInputStream<ResponseT>> {
  InputStreamResponseTransformer<ResponseT> delegate;
  ProgressListener progressListener;

  InputStreamTransformer(ProgressListener listener) {
    progressListener = listener;
    delegate = new InputStreamResponseTransformer<>();
  }

  void reportProgress() {
    if (progressListener != null) {
      progressListener.reportProgress();
    }
  }

  @Override
  public void exceptionOccurred(Throwable ex) {
    delegate.exceptionOccurred(ex);
  }

  @Override
  public void onResponse(ResponseT response) {
    delegate.onResponse(response);
    reportProgress();
  }

  @Override
  public void onStream(SdkPublisher<ByteBuffer> publisher) {
    delegate.onStream(publisher);
    reportProgress();
  }

  @Override
  public CompletableFuture<ResponseInputStream<ResponseT>> prepare() {
    return delegate.prepare();
  }
}
