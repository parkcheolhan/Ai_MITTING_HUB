package kr.meetinghub;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SecretCipherTest {
    @TempDir Path directory;
    @Test void encryptionIsRandomizedAndReadableAfterRecreatingCipher() {
        Path file=directory.resolve("jira.key");var cipher=new SecretCipher(file.toString(),"");
        String one=cipher.encrypt("fake-unit-secret"),two=cipher.encrypt("fake-unit-secret");
        assertThat(one).isNotEqualTo(two).doesNotContain("fake-unit-secret");
        assertThat(new SecretCipher(file.toString(),"").decrypt(one)).isEqualTo("fake-unit-secret");
    }
    @Test void tamperingOrLosingKeyFailsWithoutExposingSecret() throws Exception {
        Path file=directory.resolve("jira.key");var cipher=new SecretCipher(file.toString(),"");
        String encrypted=cipher.encrypt("fake-unit-secret");
        assertThatThrownBy(()->cipher.decrypt(encrypted+"corrupt")).hasMessageContaining("복호화").hasMessageNotContaining("fake-unit-secret");
        Files.delete(file);assertThatThrownBy(()->cipher.decrypt(encrypted)).hasMessageContaining("복호화");
        assertThat(file).doesNotExist();
    }
    @Test void jiraAndAiCiphertextsCannotBeInterchanged() {
        var cipher=new SecretCipher(directory.resolve("context.key").toString(),"");
        String ai=cipher.encrypt("fake-ai-secret","ai-api-key");
        assertThat(cipher.decrypt(ai,"ai-api-key")).isEqualTo("fake-ai-secret");
        assertThatThrownBy(()->cipher.decrypt(ai)).hasMessageContaining("복호화");
    }
}
