package kr.meetinghub;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionalTranscriptUploadTest {
    @TempDir Path directory;
    @Test void acceptsAccAndAacRegardlessOfBrowserMimeType()throws Exception{
        var service=mock(MeetingService.class);var api=new Api(service,null,null,null,directory.toString());
        for(String name:new String[]{"recording.acc","recording.AAC"}){
            var asset=api.upload("meeting",new MockMultipartFile("file",name,"application/octet-stream",new byte[]{1,2,3}));
            assertThat(asset.originalName()).isEqualTo(name);assertThat(asset.mediaType()).isEqualTo("audio/aac");
            assertThat(Files.readAllBytes(directory.resolve(asset.storageKey()))).containsExactly(1,2,3);
        }
        verify(service,times(2)).addAsset(eq("meeting"),any());
    }
}
