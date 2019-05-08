package com.google.cloud.hadoop.fs.gcs;

import static com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystemConfiguration.GCS_COOPERATIVE_LOCKING_ENABLE;
import static com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystemConfiguration.GCS_REPAIR_IMPLICIT_DIRECTORIES_ENABLE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.time.temporal.ChronoUnit.SECONDS;

import com.google.cloud.hadoop.gcsio.GcsAtomicOperations;
import com.google.cloud.hadoop.gcsio.GcsAtomicOperations.Operation;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorage;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageFileSystem;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageFileSystem.DeleteOperation;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageFileSystem.RenameOperation;
import com.google.cloud.hadoop.gcsio.StorageResourceId;
import com.google.common.flogger.GoogleLogger;
import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * FSCK tool to recover failed directory mutations guarded by GCS Connector Cooperative Locking
 * feature.
 *
 * <p>Usage: <code>
 *   hadoop jar /usr/lib/hadoop/lib/gcs-connector.jar
 *       com.google.cloud.hadoop.fs.gcs.AtomicGcsFsck gs://my-bucket
 * </code>
 */
public class AtomicGcsFsck {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final int LOCK_EXPIRATION_SECONDS = 120;

  private static final Gson GSON = new Gson();

  public static void main(String[] args) throws Exception {
    String bucket = args[0];
    checkArgument(bucket.startsWith("gs://"), "bucket parameter should have 'gs://' scheme");

    Configuration conf = new Configuration();
    // Disable cooperative locking to prevent blocking
    conf.set(GCS_COOPERATIVE_LOCKING_ENABLE.getKey(), "false");
    conf.set(GCS_REPAIR_IMPLICIT_DIRECTORIES_ENABLE.getKey(), "false");

    URI bucketUri = URI.create(bucket);
    GoogleHadoopFileSystem ghFs = (GoogleHadoopFileSystem) FileSystem.get(bucketUri, conf);
    GoogleCloudStorageFileSystem gcsFs = ghFs.getGcsFs();
    GoogleCloudStorage gcs = gcsFs.getGcs();
    GcsAtomicOperations gcsAtomic = gcsFs.getGcsAtomic();

    Instant operationExpirationTime = Instant.now();

    Set<Operation> lockedOperations = gcsAtomic.getLockedOperations(bucketUri.getAuthority());
    if (lockedOperations.isEmpty()) {
      logger.atInfo().log("No expired operation locks");
      return;
    }

    Map<FileStatus, String> expiredOperations = new HashMap<>();
    for (Operation lockedOperation : lockedOperations) {
      String operationId = lockedOperation.getOperationId();
      URI operationPattern =
          bucketUri.resolve(
              "/" + GcsAtomicOperations.LOCK_DIRECTORY + "*" + operationId + "*.lock");
      FileStatus[] operationStatuses = ghFs.globStatus(new Path(operationPattern));
      checkState(
          operationStatuses.length < 2,
          "operation %s should not have more than one lock file",
          operationId);

      // Lock file not created - nothing to repair
      if (operationStatuses.length == 0) {
        logger.atInfo().log(
            "Operation %s for %s resources doesn't have lock file, unlocking",
            lockedOperation.getOperationId(), lockedOperation.getResources());
        StorageResourceId[] lockedResources =
            lockedOperation.getResources().stream()
                .map(r -> StorageResourceId.fromObjectName(bucketUri.resolve("/" + r).toString()))
                .toArray(StorageResourceId[]::new);
        gcsAtomic.unlockPaths(lockedOperation.getOperationId(), lockedResources);
        continue;
      }

      FileStatus operation = operationStatuses[0];
      String operationContent;
      try (FSDataInputStream in = ghFs.open(operation.getPath())) {
        operationContent = IOUtils.toString(in);
      }

      Instant operationLockEpoch = getOperationLockEpoch(operation, operationContent);
      if (operationLockEpoch
          .plus(LOCK_EXPIRATION_SECONDS, SECONDS)
          .isBefore(operationExpirationTime)) {
        expiredOperations.put(operation, operationContent);
        logger.atInfo().log("Operation %s expired.", operation.getPath());
      } else {
        logger.atInfo().log("Operation %s not expired.", operation.getPath());
      }
    }

    Function<Map.Entry<FileStatus, String>, Boolean> operationRecovery =
        expiredOperation -> {
          FileStatus operation = expiredOperation.getKey();
          String operationContent = expiredOperation.getValue();

          String operationId = getOperationId(operation);
          try {
            if (operation.getPath().toString().contains("_delete_")) {
              logger.atInfo().log("Repairing FS after %s delete operation.", operation.getPath());
              DeleteOperation operationObject =
                  GSON.fromJson(operationContent, DeleteOperation.class);
              ghFs.delete(new Path(operationObject.getResource()), /* recursive= */ true);
              gcsAtomic.unlockPaths(
                  operationId, StorageResourceId.fromObjectName(operationObject.getResource()));
            } else if (operation.getPath().toString().contains("_rename_")) {
              RenameOperation operationObject =
                  GSON.fromJson(operationContent, RenameOperation.class);
              if (operationObject.getCopySucceeded()) {
                logger.atInfo().log(
                    "Repairing FS after %s rename operation (deleting source (%s)).",
                    operation.getPath(), operationObject.getSrcResource());
                ghFs.delete(new Path(operationObject.getSrcResource()), /* recursive= */ true);
              } else {
                logger.atInfo().log(
                    "Repairing FS after %s rename operation"
                        + " (deleting destination (%s) and renaming (%s -> %s)).",
                    operation.getPath(),
                    operationObject.getDstResource(),
                    operationObject.getSrcResource(),
                    operationObject.getDstResource());
                ghFs.delete(new Path(operationObject.getDstResource()), /* recursive= */ true);
                ghFs.rename(
                    new Path(operationObject.getSrcResource()),
                    new Path(operationObject.getDstResource()));
              }
              gcsAtomic.unlockPaths(
                  operationId,
                  StorageResourceId.fromObjectName(operationObject.getSrcResource()),
                  StorageResourceId.fromObjectName(operationObject.getDstResource()));
            } else {
              throw new IllegalStateException("Unknown operation type: " + operation.getPath());
            }
          } catch (IOException e) {
            throw new RuntimeException("Failed to recover operation: ", e);
          }
          return true;
        };

    for (Map.Entry<FileStatus, String> expiredOperation : expiredOperations.entrySet()) {
      long start = System.currentTimeMillis();
      try {
        boolean succeeded = operationRecovery.apply(expiredOperation);
        long finish = System.currentTimeMillis();
        if (succeeded) {
          logger.atInfo().log(
              "Operation %s successfully rolled forward in %dms", expiredOperation, finish - start);
        } else {
          logger.atSevere().log(
              "Operation %s failed to roll forward in %dms", expiredOperation, finish - start);
        }
      } catch (Exception e) {
        long finish = System.currentTimeMillis();
        logger.atSevere().withCause(e).log(
            "Operation %s failed to roll forward in %dms", expiredOperation, finish - start);
      }
    }
  }

  private static Instant getOperationLockEpoch(FileStatus operation, String operationContent) {
    if (operation.getPath().toString().contains("_delete_")) {
      return Instant.ofEpochSecond(
          GSON.fromJson(operationContent, DeleteOperation.class).getLockEpochSeconds());
    } else if (operation.getPath().toString().contains("_rename_")) {
      return Instant.ofEpochSecond(
          GSON.fromJson(operationContent, RenameOperation.class).getLockEpochSeconds());
    }
    throw new IllegalStateException("Unknown operation type: " + operation.getPath());
  }

  private static String getOperationId(FileStatus operation) {
    String[] fileParts = operation.getPath().toString().split("_");
    return fileParts[fileParts.length - 1].split("\\.")[0];
  }
}