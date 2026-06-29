package com.acme.core.util;

import com.acme.core.exception.ThrowException;
import com.acme.core.payload.file.FileItemDTO;
import com.acme.core.payload.file.FileListResponseDTO;
import com.acme.core.payload.file.StorageStatsDTO;
import io.minio.*;
import io.minio.messages.DeleteRequest;
import io.minio.messages.DeleteResult;
import io.minio.messages.Item;
import jakarta.annotation.Nonnull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLConnection;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;


@Slf4j
@RequiredArgsConstructor
public class FileUtil {

  private final MinioClient minioClient;

  private static final ZoneId   BANGKOK              = ZoneId.of("Asia/Bangkok");
  private static final int      DEFAULT_EXPIRY_MINUTES = 60;
  private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
      "image/jpeg", "image/png", "image/webp"
  );

  // ─── Bucket enum ─────────────────────────────────────────────────────────

  public enum Bucket {
    PUBLIC("public", true),
    CRM("crm", false),
    ACCOUNT("account", false),
    CHECKLIST("checklist", false);

    @Getter
    private final String  name;
    private final boolean isPublic;

    Bucket(String name, boolean isPublic) {
      this.name     = name;
      this.isPublic = isPublic;
    }

    public boolean isPublic()  { return isPublic; }
  }

  // ─── uploadImage (UUID filename) ──────────────────────────────────────────

  public Mono<String> uploadImage(Bucket bucket, String prefix, FilePart image) {
    if (image == null) return Mono.just("");
    String contentType = detectContentType(image.filename());
    if (!ALLOWED_IMAGE_TYPES.contains(contentType))
      return Mono.error(new ThrowException("image.invalid.type"));

    String   newFilename = UUID.randomUUID() + "." + getExtension(image.filename());
    FilePart renamed     = renameFilePart(image, newFilename);
    return uploadFile(bucket, prefix, renamed).map(FileItemDTO::getKey);
  }

  // ─── uploadImage (date + refId filename) ─────────────────────────────────

  public Mono<String> uploadImage(Bucket bucket, String refId, String prefix, FilePart filePart) {
    String contentType = detectContentType(filePart.filename());
    if (!ALLOWED_IMAGE_TYPES.contains(contentType))
      return Mono.error(new ThrowException("image.invalid.type"));

    String datePrefix  = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    String newFilename = datePrefix + "-" + refId + "-" + UUID.randomUUID() + "." + getExtension(filePart.filename());
    FilePart renamed   = renameFilePart(filePart, newFilename);
    return uploadFile(bucket, prefix, renamed).map(FileItemDTO::getKey);
  }

  // ─── uploadAvatar (resize → 80×80, JPEG 75%) ─────────────────────────────

  public Mono<String> uploadAvatar(Bucket bucket, String prefix, FilePart image) {
    if (image == null) return Mono.just("");
    String contentType = detectContentType(image.filename());
    if (!ALLOWED_IMAGE_TYPES.contains(contentType))
      return Mono.error(new ThrowException("image.invalid.type"));

    return readBytes(image)
        .flatMap(raw -> Mono.fromCallable(() -> resizeToAvatar(raw))
            .subscribeOn(Schedulers.boundedElastic()))
        .flatMap(bytes -> {
          String key = normalizePrefix(prefix) + UUID.randomUUID() + ".jpg";
          return putBytes(bucket.getName(), key, bytes, "image/jpeg").thenReturn(key);
        });
  }

  // ─── uploadImages (multiple) ──────────────────────────────────────────────

  public Mono<List<String>> uploadImages(Bucket bucket, String refId,
                                         String prefix, Flux<FilePart> fileParts) {
    String datePrefix = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    return fileParts
        .flatMap(fp -> {
          String ct = detectContentType(fp.filename());
          if (!ALLOWED_IMAGE_TYPES.contains(ct))
            return Mono.<String>error(new ThrowException("image.invalid.type"));
          String   newFilename = datePrefix + "-" + refId + "-" + UUID.randomUUID() + "." + getExtension(fp.filename());
          FilePart renamed     = renameFilePart(fp, newFilename);
          return uploadFile(bucket, prefix, renamed).map(FileItemDTO::getKey);
        })
        .collectList();
  }

  // ─── replaceImage ─────────────────────────────────────────────────────────

  public Mono<String> replaceImage(Bucket bucket, String prefix,
                                   FilePart image, String existingKey) {
    if (image == null) return Mono.just(existingKey != null ? existingKey : "");
    String contentType = detectContentType(image.filename());
    if (!ALLOWED_IMAGE_TYPES.contains(contentType))
      return Mono.error(new ThrowException("image.invalid.type"));

    String   newFilename = UUID.randomUUID() + "." + getExtension(image.filename());
    FilePart renamed     = renameFilePart(image, newFilename);

    Mono<Void> deleteOld = (existingKey != null && !existingKey.isBlank())
        ? delete(bucket, List.of(existingKey)).onErrorResume(e -> {
      log.warn("Failed to delete old image: {}", e.getMessage());
      return Mono.empty();
    })
        : Mono.empty();

    return deleteOld.then(uploadFile(bucket, prefix, renamed)).map(FileItemDTO::getKey);
  }

  // ─── uploadFile (single) ──────────────────────────────────────────────────

  public Mono<FileItemDTO> uploadFile(Bucket bucket, String prefix, FilePart filePart) {
    String normalizedPrefix = normalizePrefix(prefix);
    String key              = normalizedPrefix + filePart.filename();
    String contentType      = detectContentType(filePart.filename());

    return readBytes(filePart)
        .flatMap(bytes -> putBytes(bucket.getName(), key, bytes, contentType)
            .thenReturn(FileItemDTO.builder()
                .key(key)
                .name(filePart.filename())
                .type("file")
                .size((long) bytes.length)
                .lastModified(OffsetDateTime.now(BANGKOK))
                .contentType(contentType)
                .path(normalizedPrefix)
                .build()));
  }

  // ─── uploadFiles (multiple) ───────────────────────────────────────────────

  public Flux<FileItemDTO> uploadFiles(Bucket bucket, String prefix, Flux<FilePart> fileParts) {
    return fileParts.flatMap(fp -> uploadFile(bucket, prefix, fp));
  }

  // ─── createFolder ─────────────────────────────────────────────────────────

  public Mono<FileItemDTO> createFolder(Bucket bucket, String prefix, String folderName) {
    String normalizedPrefix = normalizePrefix(prefix);
    String folderKey        = normalizedPrefix + sanitizeName(folderName) + "/";

    return Mono.fromCallable(() -> {
          minioClient.putObject(PutObjectArgs.builder()
              .bucket(bucket.getName())
              .object(folderKey)
              .stream(new ByteArrayInputStream(new byte[0]), 0L, -1L)
              .build());
          return FileItemDTO.builder()
              .key(folderKey).name(folderName).type("folder")
              .size(0L).lastModified(OffsetDateTime.now(BANGKOK)).path(normalizedPrefix)
              .build();
        })
        .subscribeOn(Schedulers.boundedElastic());
  }

  // ─── rename ───────────────────────────────────────────────────────────────

  public Mono<FileItemDTO> rename(Bucket bucket, String oldKey, String newName) {
    boolean isFolder  = oldKey.endsWith("/");
    String  parentPath = getParentPath(oldKey);
    String  newKey    = parentPath + sanitizeName(newName) + (isFolder ? "/" : "");

    Mono<Void> copyOp   = isFolder ? copyFolder(bucket, oldKey, newKey)   : copyObject(bucket, oldKey, newKey);
    Mono<Void> deleteOp = isFolder ? deleteFolder(bucket, oldKey)         : deleteObject(bucket, oldKey);

    return copyOp.then(deleteOp)
        .thenReturn(FileItemDTO.builder()
            .key(newKey).name(newName)
            .type(isFolder ? "folder" : "file")
            .path(parentPath).lastModified(OffsetDateTime.now(BANGKOK))
            .build());
  }

  // ─── copy ─────────────────────────────────────────────────────────────────

  public Mono<List<FileItemDTO>> copy(Bucket bucket, List<String> sourceKeys,
                                      String destinationPrefix) {
    String destPrefix = normalizePrefix(destinationPrefix);
    return Flux.fromIterable(sourceKeys)
        .flatMap(sourceKey -> {
          boolean    isFolder = sourceKey.endsWith("/");
          String     name     = getItemName(sourceKey);
          String     destKey  = destPrefix + name + (isFolder ? "/" : "");
          Mono<Void> op       = isFolder
              ? copyFolder(bucket, sourceKey, destKey)
              : copyObject(bucket, sourceKey, destKey);
          return op.thenReturn(FileItemDTO.builder()
              .key(destKey).name(name)
              .type(isFolder ? "folder" : "file")
              .path(destPrefix).lastModified(OffsetDateTime.now(BANGKOK))
              .build());
        })
        .collectList();
  }

  // ─── move ─────────────────────────────────────────────────────────────────

  public Mono<List<FileItemDTO>> move(Bucket bucket, List<String> sourceKeys,
                                      String destinationPrefix) {
    return copy(bucket, sourceKeys, destinationPrefix)
        .flatMap(copied ->
            Flux.fromIterable(sourceKeys)
                .flatMap(key -> key.endsWith("/") ? deleteFolder(bucket, key) : deleteObject(bucket, key))
                .then()
                .thenReturn(copied));
  }

  // ─── delete ───────────────────────────────────────────────────────────────

  public Mono<Void> delete(Bucket bucket, List<String> keys) {
    if (keys == null || keys.isEmpty()) return Mono.empty();
    return Flux.fromIterable(keys)
        .flatMap(key -> key.endsWith("/") ? deleteFolder(bucket, key) : deleteObject(bucket, key))
        .then();
  }

  // ─── listDirectory ────────────────────────────────────────────────────────

  public Mono<FileListResponseDTO> listDirectory(Bucket bucket, String prefix,
                                                 int page, int size,
                                                 String sortBy, String sortDir) {
    String normalizedPrefix = normalizePrefix(prefix);
    return Mono.fromCallable(() -> {
          List<FileItemDTO> all = new ArrayList<>();
          for (Result<Item> r : minioClient.listObjects(
              ListObjectsArgs.builder()
                  .bucket(bucket.getName())
                  .prefix(normalizedPrefix)
                  .delimiter("/")
                  .build())) {
            Item item = r.get();
            if (item.isDir()) {
              all.add(FileItemDTO.folder(item.objectName(), normalizedPrefix));
            } else if (!item.objectName().equals(normalizedPrefix)) {
              all.add(FileItemDTO.file(item, normalizedPrefix));
            }
          }

          Comparator<FileItemDTO> cmp = buildComparator(sortBy);
          if ("desc".equalsIgnoreCase(sortDir)) cmp = cmp.reversed();
          all.sort(cmp);

          int total     = all.size();
          int fromIndex = page * size;
          int toIndex   = Math.min(fromIndex + size, total);
          return new FileListResponseDTO(
              fromIndex >= total ? List.of() : all.subList(fromIndex, toIndex),
              total, page, size,
              (int) Math.ceil((double) total / size),
              normalizedPrefix);
        })
        .subscribeOn(Schedulers.boundedElastic());
  }

  // ─── search ───────────────────────────────────────────────────────────────

  public Mono<FileListResponseDTO> search(Bucket bucket, String prefix,
                                          String query, int page, int size) {
    String normalizedPrefix = normalizePrefix(prefix);
    String lowerQuery       = query.toLowerCase();

    return listAllObjects(bucket, normalizedPrefix)
        .filter(item -> getItemName(item.objectName()).toLowerCase().contains(lowerQuery))
        .map(item -> FileItemDTO.file(item, normalizedPrefix))
        .collectList()
        .map(allItems -> {
          int total     = allItems.size();
          int fromIndex = page * size;
          int toIndex   = Math.min(fromIndex + size, total);
          return new FileListResponseDTO(
              fromIndex >= total ? List.of() : allItems.subList(fromIndex, toIndex),
              total, page, size,
              (int) Math.ceil((double) total / size),
              normalizedPrefix);
        });
  }

  // ─── getFileDetails ───────────────────────────────────────────────────────

  public Mono<FileItemDTO> getFileDetails(Bucket bucket, String key) {
    return Mono.fromCallable(() -> {
          StatObjectResponse stat = minioClient.statObject(
              StatObjectArgs.builder()
                  .bucket(bucket.getName())
                  .object(key)
                  .build());
          return FileItemDTO.builder()
              .key(key)
              .name(getItemName(key))
              .type("file")
              .size(stat.size())
              .lastModified(stat.lastModified().withZoneSameInstant(BANGKOK).toOffsetDateTime())
              .contentType(stat.contentType())
              .path(getParentPath(key))
              .etag(stat.etag())
              .build();
        })
        .subscribeOn(Schedulers.boundedElastic());
  }

  // ─── getPreSignedUrl ──────────────────────────────────────────────────────
  public Mono<String> getPreSignedUrl(Bucket bucket, String key, int expiryMinutes) {
    return Mono.fromCallable(() ->
            minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.GET)
                    .bucket(bucket.getName())
                    .object(key)
                    .expiry(expiryMinutes, TimeUnit.MINUTES)
                    .build()))
        .subscribeOn(Schedulers.boundedElastic());
  }

  public Mono<String> getPreSignedUrl(Bucket bucket, String key) {
    return getPreSignedUrl(bucket, key, DEFAULT_EXPIRY_MINUTES);
  }

  // ─── getStorageStats ──────────────────────────────────────────────────────
  public Mono<StorageStatsDTO> getStorageStats(Bucket bucket, String prefix) {
    String normalizedPrefix = normalizePrefix(prefix);
    return listAllObjects(bucket, normalizedPrefix)
        .collectList()
        .map(items -> {
          long              totalSize    = 0;
          long              totalFiles   = 0;
          long              totalFolders = 0;
          Map<String, Long> breakdown    = new HashMap<>();
          for (Item item : items) {
            if (item.isDir()) {
              totalFolders++;
            } else if (!item.objectName().equals(normalizedPrefix)) {
              totalSize += item.size();
              totalFiles++;
              breakdown.merge(getExtension(item.objectName()), 1L, Long::sum);
            }
          }
          return new StorageStatsDTO(totalSize, totalFiles, totalFolders, breakdown, normalizedPrefix);
        });
  }

  // ─── renameFilePart ───────────────────────────────────────────────────────
  public FilePart renameFilePart(FilePart original, String newFilename) {
    return new FilePart() {
      @Override @Nonnull public String      name()                            { return original.name(); }
      @Override @Nonnull public String      filename()                        { return newFilename; }
      @Override @Nonnull public HttpHeaders headers()                         { return original.headers(); }
      @Override @Nonnull public Flux<DataBuffer> content()                   { return original.content(); }
      @Override @Nonnull public Mono<Void>  transferTo(@Nonnull Path dest)   { return original.transferTo(dest); }
    };
  }

  // ─── private: core S3 ops ────────────────────────────────────────────────
  private Mono<Void> putBytes(String bucketName, String key, byte[] bytes, String contentType) {
    return Mono.fromCallable(() -> {
          minioClient.putObject(PutObjectArgs.builder()
              .bucket(bucketName)
              .object(key)
              .stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L)
              .contentType(contentType)
              .build());
          return null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  private Mono<Void> copyObject(Bucket bucket, String src, String dst) {
    return Mono.fromCallable(() -> {
          minioClient.copyObject(CopyObjectArgs.builder()
              .bucket(bucket.getName())
              .object(dst)
              .source(SourceObject.builder()
                  .bucket(bucket.getName())
                  .object(src)
                  .build())
              .build());
          return null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  private Mono<Void> copyFolder(Bucket bucket, String srcPrefix, String dstPrefix) {
    return listAllObjects(bucket, srcPrefix)
        .flatMap(item -> {
          String rel = item.objectName().substring(srcPrefix.length());
          return copyObject(bucket, item.objectName(), dstPrefix + rel);
        })
        .then();
  }

  private Mono<Void> deleteObject(Bucket bucket, String key) {
    return Mono.fromCallable(() -> {
          minioClient.removeObject(RemoveObjectArgs.builder()
              .bucket(bucket.getName())
              .object(key)
              .build());
          return null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  private Mono<Void> deleteFolder(Bucket bucket, String prefix) {
    return listAllObjects(bucket, prefix)
        .buffer(1000)
        .flatMap(batch -> Mono.fromCallable(() -> {
              List<DeleteRequest.Object> objects = batch.stream()
                  .map(item -> new DeleteRequest.Object(item.objectName()))
                  .toList();
              Iterable<Result<DeleteResult.Error>> results =
                  minioClient.removeObjects(RemoveObjectsArgs.builder()
                      .bucket(bucket.getName())
                      .objects(objects)
                      .build());
              for (Result<DeleteResult.Error> r : results) {
                r.get(); // throws on per-object error
              }
              return null;
            })
            .subscribeOn(Schedulers.boundedElastic()))
        .then();
  }

  private Flux<Item> listAllObjects(Bucket bucket, String prefix) {
    return Flux.<Item>create(sink -> {
          try {
            for (Result<Item> r : minioClient.listObjects(
                ListObjectsArgs.builder()
                    .bucket(bucket.getName())
                    .prefix(prefix)
                    .recursive(true)
                    .build())) {
              sink.next(r.get());
            }
            sink.complete();
          } catch (Exception e) {
            sink.error(e);
          }
        })
        .subscribeOn(Schedulers.boundedElastic());
  }

  // ─── private: image helpers ───────────────────────────────────────────────

  private Mono<byte[]> readBytes(FilePart filePart) {
    return filePart.content()
        .reduce(new ByteArrayOutputStream(), (baos, buf) -> {
          byte[] bytes = new byte[buf.readableByteCount()];
          buf.read(bytes);
          baos.write(bytes, 0, bytes.length);
          return baos;
        })
        .map(ByteArrayOutputStream::toByteArray);
  }

  private byte[] resizeToAvatar(byte[] raw) throws Exception {
    BufferedImage original = ImageIO.read(new ByteArrayInputStream(raw));
    if (original == null) throw new ThrowException("image.invalid.format");

    double scale  = Math.min(80.0 / original.getWidth(), 80.0 / original.getHeight());
    int    newW   = (int) (original.getWidth()  * scale);
    int    newH   = (int) (original.getHeight() * scale);

    BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
    Graphics2D    g       = resized.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);
    g.drawImage(original, 0, 0, newW, newH, null);
    g.dispose();

    ByteArrayOutputStream out    = new ByteArrayOutputStream();
    ImageWriter           writer = ImageIO.getImageWritersByFormatName("jpg").next();
    ImageWriteParam       param  = writer.getDefaultWriteParam();
    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    param.setCompressionQuality(0.75f);
    try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(ios);
      writer.write(null, new IIOImage(resized, null, null), param);
    }
    writer.dispose();
    return out.toByteArray();
  }

  // ─── private: utility ────────────────────────────────────────────────────

  private String normalizePrefix(String prefix) {
    if (prefix == null || prefix.isBlank()) return "";
    String p = prefix.trim();
    if (!p.endsWith("/")) p += "/";
    if (p.startsWith("/")) p = p.substring(1);
    return p;
  }

  private String getParentPath(String key) {
    String k   = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
    int    idx = k.lastIndexOf('/');
    return idx <= 0 ? "" : k.substring(0, idx + 1);
  }

  private String getItemName(String key) {
    String k   = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
    int    idx = k.lastIndexOf('/');
    return idx < 0 ? k : k.substring(idx + 1);
  }

  private String getExtension(String key) {
    int dot   = key.lastIndexOf('.');
    int slash = key.lastIndexOf('/');
    return (dot > slash && dot < key.length() - 1)
        ? key.substring(dot + 1).toLowerCase() : "other";
  }

  private String sanitizeName(String name) {
    return name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
  }

  private String detectContentType(String filename) {
    String ct = URLConnection.guessContentTypeFromName(filename);
    return ct != null ? ct : "application/octet-stream";
  }

  private Comparator<FileItemDTO> buildComparator(String sortBy) {
    return switch (sortBy == null ? "name" : sortBy.toLowerCase()) {
      case "size"                 -> Comparator.comparingLong(f -> f.getSize() == null ? 0 : f.getSize());
      case "lastmodified", "date" -> Comparator.comparing(
          f -> f.getLastModified() == null ? OffsetDateTime.MIN : f.getLastModified());
      case "type"                 -> Comparator.comparing(FileItemDTO::getType);
      default                     -> Comparator.comparing(f -> f.getName().toLowerCase());
    };
  }

  public FilePart buildFilePart(String filename, byte[] bytes) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentLength(bytes.length);
    return new FilePart() {
      @Override @Nonnull public String      name()                          { return filename; }
      @Override @Nonnull public String      filename()                      { return filename; }
      @Override @Nonnull public HttpHeaders headers()                       { return headers; }
      @Override @Nonnull public Flux<DataBuffer> content() {
        return Flux.just(new DefaultDataBufferFactory().wrap(bytes));
      }
      @Override @Nonnull public Mono<Void> transferTo(@Nonnull Path dest) {
        return DataBufferUtils.write(content(), dest);
      }
    };
  }

  public Mono<byte[]> getRawBytes(Bucket bucket, String key) {
    return Mono.fromCallable(() -> {
          GetObjectResponse res = minioClient.getObject(
              GetObjectArgs.builder()
                  .bucket(bucket.getName())
                  .object(key)
                  .build());
          byte[] bytes = res.readAllBytes();
          res.close();
          return bytes;
        })
        .subscribeOn(Schedulers.boundedElastic());
  }

  public Mono<byte[]> downloadUrl(String url) {
    return Mono.fromCallable(() -> {
          try (java.io.InputStream in = new java.net.URI(url).toURL().openStream()) {
            return in.readAllBytes();
          }
        })
        .subscribeOn(Schedulers.boundedElastic());
  }
}