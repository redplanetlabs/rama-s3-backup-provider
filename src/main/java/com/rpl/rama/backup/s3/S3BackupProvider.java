package com.rpl.rama.backup.s3;

import com.rpl.rama.backup.BackupProvider;
import com.rpl.rama.backup.BackupProvider.KeysPage;
import com.rpl.rama.backup.BackupProvider.ProgressListener;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.utils.ThreadFactoryBuilder;

/***
 * An implementation of a Rama BackupProvider for AWS S3.
 */
public class S3BackupProvider implements BackupProvider {

  S3AsyncClient client;
  String bucketName = null;
  ProgressListener progressListener;

  ExecutorService execServ =
      Executors.newCachedThreadPool(
          new ThreadFactoryBuilder()
              .daemonThreads(true)
              .threadNamePrefix("S3BackupProvider")
              .build());

  public S3BackupProvider(final String bucketNameOrUrl)
      throws URISyntaxException, java.util.concurrent.ExecutionException, InterruptedException {
    URI uri = null;

    uri = new URI(bucketNameOrUrl);

    if (uri.getScheme() == null) {
      // just the bucket name specified
      this.bucketName = bucketNameOrUrl;
      this.client =
          S3AsyncClient.builder()
              .asyncConfiguration(
                  b ->
                      b.advancedOption(
                          SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, execServ))
              .build();
    } else {
      // full url for endpoint and bucket name
      this.bucketName = Paths.get(uri.getPath()).getFileName().toString();
      S3AsyncClientBuilder builder =
          S3AsyncClient.builder()
              .asyncConfiguration(
                  b ->
                      b.advancedOption(
                          SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, execServ));

      if (uri.getScheme().equals("http")) {
        URI overrideURI =
            new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null);
        builder.endpointOverride(overrideURI).forcePathStyle(true);
      }
      this.client = builder.build();
    }

    // Create the bucket if it doesn't exist
    HeadBucketRequest headRequest = HeadBucketRequest.builder().bucket(bucketName).build();
    HeadBucketResponse head =
        this.client
            .headBucket(headRequest)
            .exceptionally(
                (ex) -> {
                  System.err.println(ex.toString());
                  return null;
                })
            .join();

    if (head == null) {
      System.err.println("  creating bucket " + bucketName);
      this.client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build()).get();
    }
    client.waiter().waitUntilBucketExists(headRequest).get();
  }

  @Override
  public void setProgressListener(final ProgressListener listener) {
    progressListener = listener;
  }

  void reportProgress() {
    if (progressListener != null) {
      progressListener.reportProgress();
    }
  }

  // This is in JDK 9+
  static <T> CompletableFuture<T> failedFuture(Throwable ex) {
    CompletableFuture<T> f = new CompletableFuture<>();
    f.completeExceptionally(ex);
    return f;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends InputStream> CompletableFuture<T> getObject(final String key) {
    try {
      final GetObjectRequest request =
          GetObjectRequest.builder().key(key).bucket(bucketName).build();
      final CompletableFuture<ResponseInputStream<GetObjectResponse>> resultCf =
          client.getObject(request, new InputStreamTransformer<>(progressListener));
      return (CompletableFuture<T>)
          resultCf.handle(
              (res, ex) -> {
                if (ex == null) {
                  return res;
                } else if (ex instanceof NoSuchKeyException) {
                  return null;
                } else if (ex instanceof CompletionException
                    && ex.getCause() instanceof NoSuchKeyException) {
                  return null;
                }
                throw new CompletionException(ex);
              });
    } catch (final S3Exception e) {
      return failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<Void> putObject(
      final String key, final InputStream inputStream, final Long contentLength) {
    try {
      final PutObjectRequest request =
          PutObjectRequest.builder().key(key).bucket(bucketName).build();
      final AsyncRequestBody body =
          new InputStreamBody(progressListener, inputStream, contentLength, execServ);
      final CompletableFuture<Void> result =
          client
              .putObject(request, body)
              .thenApply(
                  (res) -> {
                    reportProgress();
                    return null;
                  });
      return result;
    } catch (final S3Exception e) {
      return failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<Void> deleteObject(final String key) {
    try {
      final DeleteObjectRequest request =
          DeleteObjectRequest.builder().key(key).bucket(bucketName).build();
      final CompletableFuture<Void> result =
          client
              .deleteObject(request)
              .thenApply(
                  (res) -> {
                    return null;
                  });
      return result;
    } catch (final S3Exception e) {
      return failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<Boolean> hasKey(final String key) {
    try {
      final HeadObjectRequest request =
          HeadObjectRequest.builder().bucket(bucketName).key(key).bucket(bucketName).build();
      final CompletableFuture<Boolean> result = new CompletableFuture<>();

      client
          .headObject(request)
          .whenComplete(
              (res, ex) -> {
                if (ex == null) {
                  result.complete(true);
                } else {
                  result.complete(false);
                }
              })
          .exceptionally(ex -> null);

      return result;
    } catch (final S3Exception e) {
      return failedFuture(e);
    }
  }

  private static String s3ObjectFilename(S3Object s3Object) {
    return Paths.get(s3Object.key()).getFileName().toString();
  }

  private static String commonPrefixPath(CommonPrefix commonPrefix) {
    String cp = commonPrefix.prefix();
    Path p = Paths.get(cp);
    return p.toString();
  }

  private static String commonPrefixFilename(CommonPrefix commonPrefix) {
    String cp = commonPrefix.prefix();
    Path p = Paths.get(cp);
    return p.getFileName().toString();
  }

  @Override
  public CompletableFuture<KeysPage> listKeysRecursive(final String prefix, final String paginationKey) {
    try {
      ListObjectsV2Request.Builder builder =
          ListObjectsV2Request.builder()
              .bucket(bucketName)
              .continuationToken(paginationKey);
      ListObjectsV2Request request = builder.build();

      return client
              .listObjectsV2(request)
              .thenApply(
                  (res) -> {
                    List<String> keys =
                        res.contents()
                           .stream()
                           .map(S3Object::key)
                           .collect(Collectors.toList());
                    return new KeysPage(keys, res.continuationToken());
                  });
    } catch (final S3Exception e) {
      return failedFuture(e);
    }
  }

  @Override
  public CompletableFuture<KeysPage> listKeysNonRecursive(
      final String prefix, final String paginationKey, final int pageSize) {
    try {
      ListObjectsV2Request.Builder builder =
          ListObjectsV2Request.builder()
              .bucket(bucketName)
              .startAfter(paginationKey)
              .maxKeys(pageSize)
              .prefix(prefix)
              .delimiter("/");
      ListObjectsV2Request request = builder.build();

      return client
              .listObjectsV2(request)
              .thenApply(
                  (res) -> {
                    List<String> keys =
                          Stream.concat(
                                  res.contents()
                                     .stream()
                                     .map(S3BackupProvider::s3ObjectFilename),
                                  res.commonPrefixes()
                                     .stream()
                                      .map(prefix.endsWith("/")
                                            ? S3BackupProvider::commonPrefixFilename
                                            : S3BackupProvider::commonPrefixPath))
                              .collect(Collectors.toList());
                      String newPaginationKey = null;
                      if(keys.size() == pageSize) {
                        if(!prefix.endsWith("/")) throw new RuntimeException("Invalid prefix for pagination " + prefix);
                        newPaginationKey = prefix + keys.get(keys.size()-1);
                      }
                      return new BackupProvider.KeysPage(keys, newPaginationKey);
                  });
    } catch (final S3Exception e) {
      return failedFuture(e);
    }
  }
}
