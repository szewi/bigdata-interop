/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.testing.http.HttpTesting;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.hadoop.util.ApiErrorExtractor;
import com.google.cloud.hadoop.util.ClientRequestHelper;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Utility class with helper methods for GCS IO tests. */
public final class GoogleCloudStorageTestUtils {

  public static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
  public static final JacksonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

  static final String BUCKET_NAME = "foo-bucket";
  static final String OBJECT_NAME = "bar-object";

  private static final ApiErrorExtractor ERROR_EXTRACTOR = ApiErrorExtractor.INSTANCE;
  private static final ClientRequestHelper<StorageObject> REQUEST_HELPER =
      new ClientRequestHelper<>();

  private GoogleCloudStorageTestUtils() {}

  public static GoogleCloudStorageReadChannel createReadChannel(
      Storage storage, GoogleCloudStorageReadOptions options) throws IOException {
    return new GoogleCloudStorageReadChannel(
        storage, BUCKET_NAME, OBJECT_NAME, ERROR_EXTRACTOR, REQUEST_HELPER, options);
  }

  public static HttpResponse fakeResponse(String header, Object headerValue, InputStream content)
      throws IOException {
    return fakeResponse(ImmutableMap.of(header, headerValue), content);
  }

  public static HttpResponse fakeResponse(Map<String, Object> headers, InputStream content)
      throws IOException {
    HttpTransport transport =
        new MockHttpTransport() {
          @Override
          public LowLevelHttpRequest buildRequest(String method, String url) {
            return new MockLowLevelHttpRequest() {
              @Override
              public LowLevelHttpResponse execute() {
                MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                headers.forEach((h, hv) -> response.addHeader(h, String.valueOf(hv)));
                return response.setContent(content);
              }
            };
          }
        };
    HttpRequest request =
        transport.createRequestFactory().buildGetRequest(HttpTesting.SIMPLE_GENERIC_URL);
    return request.execute();
  }

  public static MockHttpTransport mockTransport(LowLevelHttpResponse... responsesIn) {
    return new MockHttpTransport() {
      int responsesIndex = 0;
      final LowLevelHttpResponse[] responses = responsesIn;

      @Override
      public LowLevelHttpRequest buildRequest(String method, String url) {
        return new MockLowLevelHttpRequest() {
          @Override
          public LowLevelHttpResponse execute() {
            return responses[responsesIndex++];
          }
        };
      }
    };
  }

  public static MockLowLevelHttpResponse metadataResponse(StorageObject metadataObject)
      throws IOException {
    return dataResponse(JSON_FACTORY.toByteArray(metadataObject));
  }

  public static MockLowLevelHttpResponse dataRangeResponse(
      byte[] content, long rangeStart, long totalSize) {
    long rangeEnd = rangeStart + content.length - 1;
    return dataResponse(content)
        .addHeader("Content-Range", rangeStart + "-" + rangeEnd + "/" + totalSize);
  }

  public static MockLowLevelHttpResponse dataResponse(byte[] content) {
    return new MockLowLevelHttpResponse()
        .addHeader("Content-Length", String.valueOf(content.length))
        .setContent(content);
  }
}
