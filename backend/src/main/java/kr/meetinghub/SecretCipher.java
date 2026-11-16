package kr.meetinghub;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretCipher {
    private final Path keyFile;
    private final String configuredKey;
    private final SecureRandom random=new SecureRandom();
    public SecretCipher(@Value("${app.secret-key-file:./data/secrets/jira.key}") String keyFile,
                        @Value("${app.encryption-key:}") String configuredKey) {
        this.keyFile=Path.of(keyFile).toAbsolutePath().normalize();this.configuredKey=configuredKey;
    }
    private synchronized byte[] key(boolean create) throws Exception {
        byte[] key;
        if(!configuredKey.isBlank())key=Base64.getDecoder().decode(configuredKey);
        else {
            if(!Files.exists(keyFile)) {
                if(!create)throw new IllegalStateException("missing key");
                Files.createDirectories(keyFile.getParent());
                var generated=new byte[32];random.nextBytes(generated);
                try {
                    var permissions=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
                    try {Files.createFile(keyFile,permissions);}catch(UnsupportedOperationException e){Files.createFile(keyFile);}
                    // On Windows restrict access to the file owner before writing key material.
                    var acl=Files.getFileAttributeView(keyFile,AclFileAttributeView.class);
                    if(acl!=null) {
                        var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(keyFile))
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                        acl.setAcl(List.of(entry));
                    }
                    Files.write(keyFile,generated,StandardOpenOption.WRITE);
                } catch(FileAlreadyExistsException e) { /* another process created the key */ }
                finally {Arrays.fill(generated,(byte)0);}
            }
            key=Files.readAllBytes(keyFile);
        }
        if(key.length!=32)throw new IllegalStateException("invalid key length");return key;
    }
    public String encrypt(String plaintext) {
        return encrypt(plaintext,"jira-token");
    }
    public String encrypt(String plaintext,String context) {
        try {
            var nonce=new byte[12];random.nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(true),"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(("meetinghub:"+context+":v1").getBytes(StandardCharsets.UTF_8));
            return "v1."+Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) {throw new IllegalArgumentException("토큰 암호화 저장에 실패했습니다. 서버 암호화 키 설정과 파일 권한을 확인하세요.");}
    }
    public String decrypt(String encrypted) {
        return decrypt(encrypted,"jira-token");
    }
    public String decrypt(String encrypted,String context) {
        try {
            var parts=encrypted.split("\\.");if(parts.length!=3||!parts[0].equals("v1"))throw new IllegalStateException("invalid ciphertext");
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key(false),"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[1])));
            cipher.updateAAD(("meetinghub:"+context+":v1").getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])),StandardCharsets.UTF_8);
        } catch(Exception e) {throw new IllegalArgumentException("저장된 토큰을 복호화할 수 없습니다. 원래 암호화 키를 복원하거나 설정 화면에서 토큰을 다시 입력하세요.");}
    }
}
