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
import java.util.*;
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

  private static void testing(String s) {
    System.err.println(s);
  }

  public static String zeroPad(int num, int length) {
    return String.format("%0" + length + "d", num);
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
        BackupProvider.KeysPage page = provider.listKeysNonRecursive("", null, 1000).get();
        assertEquals(Collections.emptyList(), page.keys);
        assert page.nextPageMarker == null;
      }
      {
        BackupProvider.KeysPage page = provider.listKeysRecursive("", null).get();
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
        BackupProvider.KeysPage page = provider.listKeysNonRecursive("a/b/", null, 1000).get();
        assertEquals(Arrays.asList("c"), page.keys);
        assert page.nextPageMarker == null;
      }
      {
        BackupProvider.KeysPage page = provider.listKeysRecursive("", null).get();
        assertEquals(Arrays.asList("a/b/c"), page.keys);
        assert page.nextPageMarker == null;
      }
      testing("    lists the root directory elements");
      {
        BackupProvider.KeysPage page = provider.listKeysNonRecursive("a/", null, 1000).get();
        assertEquals(Arrays.asList("b"), page.keys);
        assert page.nextPageMarker == null;
      }
      {
        BackupProvider.KeysPage page = provider.listKeysRecursive("a/", null).get();
        assertEquals(Arrays.asList("a/b/c"), page.keys);
        assert page.nextPageMarker == null;
      }
      {
        BackupProvider.KeysPage page = provider.listKeysRecursive("a", null).get();
        assertEquals(Arrays.asList("a/b/c"), page.keys);
        assert page.nextPageMarker == null;
      }
      {
        BackupProvider.KeysPage page = provider.listKeysRecursive("a/b", null).get();
        assertEquals(Arrays.asList("a/b/c"), page.keys);
        assert page.nextPageMarker == null;
      }
      {
        BackupProvider.KeysPage page = provider.listKeysRecursive("a/b/", null).get();
        assertEquals(Arrays.asList("a/b/c"), page.keys);
        assert page.nextPageMarker == null;
      }
      testing("  paginated list keys");
      {
            byte[] content = "abc".getBytes();
            provider.putObject("x/k2", new ByteArrayInputStream(content), 3L).get();
            provider.putObject("x/k1", new ByteArrayInputStream(content), 3L).get();
            provider.putObject("x/k5", new ByteArrayInputStream(content), 3L).get();
            provider.putObject("x/k3", new ByteArrayInputStream(content), 3L).get();
            provider.putObject("x/k4", new ByteArrayInputStream(content), 3L).get();
            BackupProvider.KeysPage page = provider.listKeysNonRecursive("x/", null, 2).get();
            assertEquals(Arrays.asList("k1", "k2"), page.keys);
            page = provider.listKeysNonRecursive("x/", page.nextPageMarker, 2).get();
            assertEquals(Arrays.asList("k3", "k4"), page.keys);
            page = provider.listKeysNonRecursive("x/", page.nextPageMarker, 2).get();
            assertEquals(Arrays.asList("k5"), page.keys);
            assertEquals(null, page.nextPageMarker);
      }
      testing("  large number of keys");
      {
            byte[] content = "abc".getBytes();
            for(int i=0; i<1100; i++) {
              provider.putObject("z/" + zeroPad(i, 4), new ByteArrayInputStream(content), 3L).get();
            }
            BackupProvider.KeysPage page = provider.listKeysNonRecursive("z/", null, -1).get();
            List expected = new ArrayList();
            for(int i=0; i<1000; i++) expected.add(zeroPad(i, 4));
            assertEquals(expected, page.keys);
            page = provider.listKeysNonRecursive("z/", page.nextPageMarker, -1).get();
            expected = new ArrayList();
            for(int i=1000; i<1100; i++) expected.add(zeroPad(i, 4));
            assertEquals(expected, page.keys);
            assertEquals(null, page.nextPageMarker);

            page = provider.listKeysRecursive("z/", null).get();
            expected = new ArrayList();
            for(int i=0; i<1000; i++) expected.add("z/" + zeroPad(i, 4));
            assertEquals(expected, page.keys);
            page = provider.listKeysRecursive("z/", page.nextPageMarker).get();
            expected = new ArrayList();
            for(int i=1000; i<1100; i++) expected.add("z/" + zeroPad(i, 4));
            assertEquals(expected, page.keys);
            assertEquals(null, page.nextPageMarker);
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
