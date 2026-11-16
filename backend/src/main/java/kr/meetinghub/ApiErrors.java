package kr.meetinghub;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.dao.OptimisticLockingFailureException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<?> status(org.springframework.web.server.ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"요청을 처리할 수 없습니다.":e.getReason()));}
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()==null?"잘못된 입력입니다.":e.getMessage())); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("message",e.getBindingResult().getFieldErrors().stream().map(f->f.getField()+": "+f.getDefaultMessage()).reduce("",(a,b)->a+b+" ")));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge() { return ResponseEntity.status(413).body(Map.of("message","파일 크기는 100MB 이하여야 합니다.")); }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(Map.of("message","다른 요청에서 변경했습니다. 새로고침 후 다시 시도하세요.")); }
}
