package org.dromara.gz.jp.service.internal;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.gz.common.service.IGzFileService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
@Slf4j @Service @RequiredArgsConstructor
public class GzJpSnapEvidenceService {
 private final IGzFileService files;
 public String store(JsonNode response){
  if(response==null)return null;var ids=new ArrayList<String>();
  for(var item:response.path("evidence")){try{byte[] bytes=Base64.getDecoder().decode(item.path("base64").asText());if(bytes.length>10*1024*1024)throw new IOException("Evidence exceeds 10MB");ids.add(String.valueOf(files.upload(new Evidence(bytes,item.path("name").asText(),item.path("content_type").asText()),"snap_evidence").getFileId()));}catch(Exception e){log.error("Snap evidence upload failed; result remains authoritative",e);}}
  return ids.isEmpty()?null:String.join(",",ids);
 }
 private record Evidence(byte[] bytes,String filename,String mime) implements MultipartFile{
  public String getName(){return "file";}public String getOriginalFilename(){return filename;}public String getContentType(){return mime;}public boolean isEmpty(){return bytes.length==0;}public long getSize(){return bytes.length;}public byte[] getBytes(){return bytes;}public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}public void transferTo(File f)throws IOException{Files.write(f.toPath(),bytes);}
 }
}
