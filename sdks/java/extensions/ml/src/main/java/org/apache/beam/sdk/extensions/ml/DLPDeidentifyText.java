/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.ml;

import com.google.auto.value.AutoValue;
import com.google.cloud.dlp.v2.DlpServiceClient;
import com.google.privacy.dlp.v2.ContentItem;
import com.google.privacy.dlp.v2.DeidentifyConfig;
import com.google.privacy.dlp.v2.DeidentifyContentRequest;
import com.google.privacy.dlp.v2.DeidentifyContentResponse;
import com.google.privacy.dlp.v2.FieldId;
import com.google.privacy.dlp.v2.InspectConfig;
import com.google.privacy.dlp.v2.ProjectName;
import com.google.privacy.dlp.v2.Table;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;

/**
 * A {@link PTransform} connecting to Cloud DLP (https://cloud.google.com/dlp/docs/libraries) and
 * deidentifying text according to provided settings. The transform supports both CSV formatted
 * input data and unstructured input.
 *
 * <p>If the csvHeader property is set and a sideinput with CSV headers is added to the PTransform,
 * csvDelimiter also should be set, else the results will be incorrect. If csvHeader is neither set
 * nor passed as sideinput, input is assumed to be unstructured.
 *
 * <p>Either deidentifyTemplateName (String) or deidentifyConfig {@link DeidentifyConfig} need to be
 * set. inspectTemplateName and inspectConfig ({@link InspectConfig} are optional.
 *
 * <p>Batch size defines how big are batches sent to DLP at once in bytes.
 *
 * <p>The transform consumes {@link KV} of {@link String}s (assumed to be filename as key and
 * contents as value) and outputs {@link KV} of {@link String} (eg. filename) and {@link
 * DeidentifyContentResponse}, which will contain {@link Table} of results for the user to consume.
 */
@Experimental
@AutoValue
public abstract class DLPDeidentifyText
    extends PTransform<
        PCollection<KV<String, String>>, PCollection<KV<String, DeidentifyContentResponse>>> {

  public static final Integer DLP_PAYLOAD_LIMIT_BYTES = 524000;

  /** @return Template name for data inspection. */
  @Nullable
  public abstract String inspectTemplateName();

  /** @return Template name for data deidentification. */
  @Nullable
  public abstract String deidentifyTemplateName();

  /**
   * @return Configuration object for data inspection. If present, supersedes the template settings.
   */
  @Nullable
  public abstract InspectConfig inspectConfig();

  /** @return Configuration object for deidentification. If present, supersedes the template. */
  @Nullable
  public abstract DeidentifyConfig deidentifyConfig();

  /** @return List of column names if the input KV value is a CSV formatted row. */
  @Nullable
  public abstract PCollectionView<List<String>> csvHeader();

  /** @return Delimiter to be used when splitting values from input strings into columns. */
  @Nullable
  public abstract String csvColumnDelimiter();

  /** @return Size of input elements batch to be sent to Cloud DLP service in one request. */
  public abstract Integer batchSizeBytes();

  /** @return ID of Google Cloud project to be used when deidentifying data. */
  public abstract String projectId();

  @AutoValue.Builder
  public abstract static class Builder {
    /** @param inspectTemplateName Template name for data inspection. */
    public abstract Builder setInspectTemplateName(String inspectTemplateName);

    /** @param csvHeader List of column names if the input KV value is a CSV formatted row. */
    public abstract Builder setCsvHeader(PCollectionView<List<String>> csvHeader);

    /**
     * @param delimiter Delimiter to be used when splitting values from input strings into columns.
     */
    public abstract Builder setCsvColumnDelimiter(String delimiter);

    /**
     * @param batchSize Size of input elements batch to be sent to Cloud DLP service in one request.
     */
    public abstract Builder setBatchSizeBytes(Integer batchSize);

    /** @param projectId ID of Google Cloud project to be used when deidentifying data. */
    public abstract Builder setProjectId(String projectId);

    /** @param deidentifyTemplateName Template name for data deidentification. */
    public abstract Builder setDeidentifyTemplateName(String deidentifyTemplateName);

    /**
     * @param inspectConfig Configuration object for data inspection. If present, supersedes the
     *     template settings.
     */
    public abstract Builder setInspectConfig(InspectConfig inspectConfig);

    /**
     * @param deidentifyConfig Configuration object for data deidentification. If present,
     *     supersedes the template settings.
     */
    public abstract Builder setDeidentifyConfig(DeidentifyConfig deidentifyConfig);

    abstract DLPDeidentifyText autoBuild();

    public DLPDeidentifyText build() {
      DLPDeidentifyText dlpDeidentifyText = autoBuild();
      if (dlpDeidentifyText.deidentifyConfig() == null
          && dlpDeidentifyText.deidentifyTemplateName() == null) {
        throw new IllegalArgumentException(
            "Either deidentifyConfig or deidentifyTemplateName need to be set!");
      }
      if (dlpDeidentifyText.batchSizeBytes() > DLP_PAYLOAD_LIMIT_BYTES) {
        throw new IllegalArgumentException(
            String.format(
                "Batch size is too large! It should be smaller or equal than %d.",
                DLP_PAYLOAD_LIMIT_BYTES));
      }
      return dlpDeidentifyText;
    }
  }

  public static DLPDeidentifyText.Builder newBuilder() {
    return new AutoValue_DLPDeidentifyText.Builder();
  }

  /**
   * The transform converts the contents of input PCollection into {@link Table.Row}s and then calls
   * Cloud DLP service to perform the deidentification according to provided settings.
   *
   * @param input input PCollection
   * @return PCollection after transformations
   */
  @Override
  public PCollection<KV<String, DeidentifyContentResponse>> expand(
      PCollection<KV<String, String>> input) {
    return input
        .apply(ParDo.of(new MapStringToDlpRow(csvColumnDelimiter())))
        .apply("Batch Contents", ParDo.of(new BatchRequestForDLP(batchSizeBytes())))
        .apply(
            "DLPDeidentify",
            ParDo.of(
                new DeidentifyText(
                    projectId(),
                    inspectTemplateName(),
                    deidentifyTemplateName(),
                    inspectConfig(),
                    deidentifyConfig(),
                    csvHeader())));
  }

  /** DoFn performing calls to Cloud DLP service on GCP. */
  static class DeidentifyText
      extends DoFn<KV<String, Iterable<Table.Row>>, KV<String, DeidentifyContentResponse>> {
    private final String projectId;
    private final String inspectTemplateName;
    private final String deidentifyTemplateName;
    private final InspectConfig inspectConfig;
    private final DeidentifyConfig deidentifyConfig;
    private final PCollectionView<List<String>> csvHeaders;
    private transient DeidentifyContentRequest.Builder requestBuilder;

    @Setup
    public void setup() throws IOException {
      requestBuilder =
          DeidentifyContentRequest.newBuilder().setParent(ProjectName.of(projectId).toString());
      if (inspectTemplateName != null) {
        requestBuilder.setInspectTemplateName(inspectTemplateName);
      }
      if (inspectConfig != null) {
        requestBuilder.setInspectConfig(inspectConfig);
      }
      if (deidentifyConfig != null) {
        requestBuilder.setDeidentifyConfig(deidentifyConfig);
      }
      if (deidentifyTemplateName != null) {
        requestBuilder.setDeidentifyTemplateName(deidentifyTemplateName);
      }
    }

    /**
     * @param projectId ID of GCP project that should be used for deidentification.
     * @param inspectTemplateName Template name for inspection. Optional.
     * @param deidentifyTemplateName Template name for deidentification. Either this or
     *     deidentifyConfig is required.
     * @param inspectConfig Configuration object for inspection. Optional.
     * @param deidentifyConfig Deidentification config containing data transformations. Either this
     *     or deidentifyTemplateName is required.
     * @param csvHeaders Header row of CSV table if applicable.
     */
    public DeidentifyText(
        String projectId,
        String inspectTemplateName,
        String deidentifyTemplateName,
        InspectConfig inspectConfig,
        DeidentifyConfig deidentifyConfig,
        PCollectionView<List<String>> csvHeaders) {
      this.projectId = projectId;
      this.inspectTemplateName = inspectTemplateName;
      this.deidentifyTemplateName = deidentifyTemplateName;
      this.inspectConfig = inspectConfig;
      this.deidentifyConfig = deidentifyConfig;
      this.csvHeaders = csvHeaders;
    }

    @ProcessElement
    public void processElement(ProcessContext c) throws IOException {
      try (DlpServiceClient dlpServiceClient = DlpServiceClient.create()) {
        String fileName = c.element().getKey();
        List<FieldId> dlpTableHeaders;
        if (csvHeaders != null) {
          dlpTableHeaders =
              c.sideInput(csvHeaders).stream()
                  .map(header -> FieldId.newBuilder().setName(header).build())
                  .collect(Collectors.toList());
        } else {
          // handle unstructured input
          dlpTableHeaders = new ArrayList<>();
          dlpTableHeaders.add(FieldId.newBuilder().setName("value").build());
        }
        Table table =
            Table.newBuilder()
                .addAllHeaders(dlpTableHeaders)
                .addAllRows(c.element().getValue())
                .build();
        ContentItem contentItem = ContentItem.newBuilder().setTable(table).build();
        this.requestBuilder.setItem(contentItem);
        DeidentifyContentResponse response =
            dlpServiceClient.deidentifyContent(this.requestBuilder.build());
        c.output(KV.of(fileName, response));
      }
    }
  }
}
