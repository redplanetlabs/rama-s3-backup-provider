package com.rpl.integration;

import com.rpl.rama.backup.BackupProvider;
import com.rpl.rama.backup.BackupProviderTester;
import com.rpl.rama.backup.s3.S3BackupProvider;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.awssdk.regions.Region;

public class S3BackupProviderIT {

  private static void assertEquals(Object expected, Object val) {
    if (expected == null && val != null || expected != null && !expected.equals(val)) {
      throw new RuntimeException("assertEquals failure: expected " + expected + ", actual " + val);
    }
  }

  private class ProgressListener implements BackupProvider.ProgressListener {
    AtomicLong callCount;

    public ProgressListener(AtomicLong callCount) {
      this.callCount = callCount;
    }

    @Override
    public void reportProgress() {
      callCount.incrementAndGet();
    }
  }

  private static void testing(String s) {
    System.err.println(s);
  }

  public void testS3Provider() throws Exception {
    final String k = "a/b/c";
    final java.nio.file.Path dir = Files.createTempDirectory("testS3Provider");

    try {
      testing("An S3 provider");
      BackupProvider provider;

      if (System.getenv("AWS_ACCESS_KEY_ID") != null) {
        provider = new S3BackupProvider("rama-s3-provider-testbucket");
      } else {
        // Any credentials found by the client's default credential chain will do,
        // as s3Mock doesn't check them.
        System.setProperty("aws.region", Region.US_WEST_1.id());
        System.setProperty("aws.accessKeyId", "something");
        System.setProperty("aws.secretAccessKey", "else");

        // An S3BackupProvider instance,
        int port = Integer.getInteger("it.s3mock.port_http", 9090);
        String host = System.getProperty("it.s3mock.host", "localhost");
        URI uri = new URI("http", null, host, port, "/testbucket", null, null);
        provider = new S3BackupProvider(uri.toString());
      }

      testing("  when empty");

      testing("    lists no keys,");
      {
        BackupProvider.KeysPage page = provider.listKeys("", false, null).get();
        assertEquals(Collections.emptyList(), page.keys);
        assert page.nextPageMarker == null;
      }
      {
        BackupProvider.KeysPage page = provider.listKeys("", true, null).get();
        assertEquals(Collections.emptyList(), page.keys);
        assert page.nextPageMarker == null;
      }

      testing("    returns false for hasKey on a non-existing key");
      assert !provider.hasKey(k).get();

      testing("  when a key is added,");
      provider.putObject(k, new ByteArrayInputStream("abc".getBytes()), 3L).get();

      testing("    returns true for hasKey on the added key");
      assert provider.hasKey(k).get();

      testing("    getObject returns an InputStream for the contents");
      {
        InputStream inputStream = provider.getObject(k).get();
        String text =
            new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));
        assertEquals("abc", text);
      }
      testing("    lists the added key");
      {
        BackupProvider.KeysPage page = provider.listKeys("a/b/", false, null).get();
        assertEquals(Arrays.asList("c"), page.keys);
        assert page.nextPageMarker == null;
      }
      {
        BackupProvider.KeysPage page = provider.listKeys("", true, null).get();
        assertEquals(Arrays.asList("a/b/c"), page.keys);
        assert page.nextPageMarker == null;
      }

      testing("    lists the root directory elements");
      {
        BackupProvider.KeysPage page = provider.listKeys("a/", false, null).get();
        assertEquals(Arrays.asList("b"), page.keys);
        assert page.nextPageMarker == null;
      }
      {
        BackupProvider.KeysPage page = provider.listKeys("a/", true, null).get();
        assertEquals(Arrays.asList("a/b/c"), page.keys);
        assert page.nextPageMarker == null;
      }

      testing("  with a progress listener");
      {
        AtomicLong callCount = new AtomicLong(0);
        ProgressListener listener = new ProgressListener(callCount);
        provider.setProgressListener(listener);

        String key = "a/b/with-listener";
        testing("    putObject calls the listener");
        {
          byte[] content = "abc".getBytes();
          provider.putObject(key, new ByteArrayInputStream(content), 3L).get();
          assert callCount.get() > 1;
        }
        testing("    getObject calls the listener");
        {
          callCount.set(0);
          InputStream inputStream = provider.getObject(key).get();
          String text =
              new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                  .lines()
                  .collect(Collectors.joining("\n"));
          assert callCount.get() > 1;
        }
      }
      System.err.println("done");
    } finally {
      try (Stream<java.nio.file.Path> pathStream = Files.walk(dir)) {
        pathStream
            .sorted(Comparator.reverseOrder())
            .map(java.nio.file.Path::toFile)
            .forEach(File::delete);
      }
    }
  }

  public void testS3ProviderTester() throws Exception {
    final java.nio.file.Path dir = Files.createTempDirectory("testS3Provider");

    try {
      testing("An S3 provider");
      BackupProvider provider;

      if (System.getenv("AWS_ACCESS_KEY_ID") != null) {
        provider = new S3BackupProvider("rama-s3-provider-testbucket");
      } else {
        // Any credentials found by the client's default credential chain will do,
        // as s3Mock doesn't check them.
        System.setProperty("aws.region", Region.US_WEST_1.id());
        System.setProperty("aws.accessKeyId", "something");
        System.setProperty("aws.secretAccessKey", "else");

        // An S3BackpProvider instance,
        int port = Integer.getInteger("it.s3mock.port_http", 9090);
        String host = System.getProperty("it.s3mock.host", "localhost");
        URI uri = new URI("http", null, host, port, "/testbucket2", null, null);
        provider = new S3BackupProvider(uri.toString());
      }

      BackupProviderTester.testProvider(provider);

    } finally {
      try (Stream<java.nio.file.Path> pathStream = Files.walk(dir)) {
        pathStream
            .sorted(Comparator.reverseOrder())
            .map(java.nio.file.Path::toFile)
            .forEach(File::delete);
      }
    }
  }
}
