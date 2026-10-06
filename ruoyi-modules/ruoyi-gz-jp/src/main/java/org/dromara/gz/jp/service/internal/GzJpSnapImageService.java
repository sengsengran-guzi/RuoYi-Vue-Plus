package org.dromara.gz.jp.service.internal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.gz.common.service.IGzFileService;
import org.dromara.gz.jp.mapper.GzJpOrderItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
@Slf4j @Service @RequiredArgsConstructor
public class GzJpSnapImageService {
    private final GzJpSnapReadService reads;
    private final IGzFileService files;
    private final GzJpOrderItemMapper rows;
    public void afterCommit(Long rowId,String original) {
        String proxy=original==null?"":reads.image(original);
        if(proxy.isBlank()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                CompletableFuture.runAsync(()->TenantHelper.dynamic("1001",()->store(rowId,proxy)));
            }
        });
    }
    private void store(Long rowId,String proxy) {
        try {
            // The original mercdn URL never reaches HttpClient: only our configured owned image proxy does.
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
            var response=client.send(HttpRequest.newBuilder(URI.create(proxy)).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()) {
                if(response.statusCode()!=200) throw new IOException("image proxy HTTP "+response.statusCode());
                byte[] bytes=body.readNBytes(10*1024*1024+1);
                if(bytes.length>10*1024*1024) throw new IOException("image exceeds 10MB");
                String mime=response.headers().firstValue("content-type").orElse("image/jpeg").split(";")[0];
                var uploaded=files.upload(new Download(bytes,mime),"jp_product_image");
                rows.setSnapImage(rowId,String.valueOf(uploaded.getFileId()));
            }
        } catch(Exception ex) { if(ex instanceof InterruptedException) Thread.currentThread().interrupt(); log.warn("Snap image transfer failed rowId={}; retaining proxy fallback",rowId,ex); }
    }
    private record Download(byte[] content,String mime) implements MultipartFile {
        public String getName(){return "file";} public String getOriginalFilename(){return "snap."+("image/png".equals(mime)?"png":"image/webp".equals(mime)?"webp":"jpg");}
        public String getContentType(){return mime;} public boolean isEmpty(){return content.length==0;} public long getSize(){return content.length;}
        public byte[] getBytes(){return content;} public InputStream getInputStream(){return new ByteArrayInputStream(content);}
        public void transferTo(File dest)throws IOException {Files.write(dest.toPath(),content);}
    }
}
