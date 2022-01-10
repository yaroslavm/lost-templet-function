package functions;

import com.google.api.gax.paging.Page;
import com.google.cloud.functions.BackgroundFunction;
import com.google.cloud.functions.Context;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.common.base.MoreObjects;
import com.google.gson.Gson;
import functions.TemplateProcessor.PubSubMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.docx4j.model.datastorage.migration.VariablePrepare;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;

@Slf4j
public class TemplateProcessor implements BackgroundFunction<PubSubMessage> {
  private final static Gson gson = new Gson();

  @Override
  public void accept(final PubSubMessage message, final Context context) {
    if (message.data == null) {
      log.warn("field `data` is null, exiting.");
      return;
    }

    final var data = new String(Base64.getDecoder().decode(message.data), StandardCharsets.UTF_8);
    final var request = gson.fromJson(data, DocumentRequest.class);

    final var attributes = request.attributes;
    if (attributes == null) {
      log.warn("`attributes` are null, exiting.");
      return;
    }

    final var templateFile =
        MoreObjects.firstNonNull(request.templateFile, attributes.get("templateFile"));

    final var templateFolder = System.getenv("TEMPLATE_FOLDER");
    if (templateFolder == null) {
      throw new IllegalArgumentException("TEMPLATE_FOLDER is null");
    }
    final var resultFolder =
        MoreObjects.firstNonNull(System.getenv("RESULT_FOLDER"), templateFolder);
    final var targetFile =
        resultFolder
            + MoreObjects.firstNonNull(
                MoreObjects.firstNonNull(request.targetFile, attributes.get("templateFile")),
                "new_file_" + System.currentTimeMillis() + ".docx");

    final var storage = StorageOptions.getDefaultInstance().getService();
    final String bucketName = System.getenv("BUCKET");
    if (bucketName == null) {
      throw new IllegalArgumentException("BUCKET is null");
    }
    final Page<Blob> blobs =
        storage.list(
            bucketName,
            Storage.BlobListOption.prefix(templateFolder),
            Storage.BlobListOption.delimiter("/"));
    for (final Blob blob : blobs.iterateAll()) {
      if (templateFile != null
          && templateFile.equalsIgnoreCase(blob.getName())
          && blob.getName().endsWith(".docx")) {
        final var content = new ByteArrayInputStream(blob.getContent());
        final WordprocessingMLPackage file;
        try {
          file = WordprocessingMLPackage.load(content);
          final var main = file.getMainDocumentPart();
          VariablePrepare.prepare(file);
          main.variableReplace(attributes);
          final var output = new ByteArrayOutputStream();
          file.save(output);
          storage.create(BlobInfo.newBuilder(bucketName, targetFile).build(), output.toByteArray());
        } catch (final Exception e) {
          throw new RuntimeException(e);
        }
        break; // exit after first match
      }
    }
  }

  public static class PubSubMessage {
    String data;
  }

  public static class DocumentRequest {
    String templateFile;
    String targetFile;
    Map<String, String> attributes;
  }
}
